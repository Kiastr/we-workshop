/* =============================================================================
 * controller.js — EnhancementController（注入 → 确认 → 看门狗 → 阶梯降档 → 熔断）
 * -----------------------------------------------------------------------------
 * 对应 android-true-super-resolution skill 的四个必要组件之三、四：
 *   EnhancementController、看门狗 / 阶梯降档 / 熔断 / A-B 对比。
 *   （FixedSurfacePolicy 在 surface.js，渲染链确认在 renderer.js 的 pass 指纹。）
 *
 * 状态机
 *   active      当前档位已注入、链真的跑了（passes>0）
 *   degraded    看门狗判定卡顿，已自动降一档
 *   tripped     已降到 off 仍卡，熔断：保持原画并停止继续降档
 *   comparing   用户正按住 A/B，临时切原画（不改用户选择）
 *   fake        非 off 档但 passes===0（//!WHEN 全被跳过/链没跑）—— 假超分
 *   unsupported 设备能力不足或 shader 编译失败，该档位不可用
 *
 * 看门狗判据（skill 参考实现）
 *   - 只认真实卡顿信号：p95 帧间隔 > 1.5×目标帧间隔，或 frame-drop 比例
 *     每秒增速 > 1%
 *   - 需「持续 10 秒」才降档，避免瞬时抖动误伤
 *   - 排除窗口：buffering（视频未就绪/等待缓冲）、A/B 对比中、倍速≠1、
 *     档位刚变更后 3 秒内
 *   - 降档按 manifest.tiers 声明顺序（=算力升序）逐档往 cost 小的走，
 *     跳过编译失败/设备不支持的档位；到 off 仍卡则熔断
 *
 * 生效确认（skill 第三条硬规则）
 *   不把「shader 编译成功」当生效。用 renderer 吐出的 pass 描述列表确认：
 *   非 off 档若连续 2 秒 passes===0，判定 fake，提示并触发降档。
 *
 * 导入方法
 *   SRController.create({renderer, manifest, onStatus, onLog}) -> c
 *   每帧 SRController.onFrame(c, frameInfo, dtMs, {buffering, rate})
 *   用户选档 SRController.setTier(c, id, true)
 *   A/B 按住 SRController.setComparing(c, true/false)
 * ============================================================================= */
(function (global) {
  'use strict';

  var SAMPLE_WINDOW = 180;      // 最近 180 帧
  var STALL_MS = 10000;         // 持续 10 秒才降档
  var IGNORE_MS = 3000;         // 档位变更后忽略窗口
  var FAKE_MS = 2000;           // 连续 2 秒 0 pass 判假超分

  function percentile(arr, p) {
    if (!arr.length) return 0;
    var a = arr.slice().sort(function (x, y) { return x - y; });
    return a[Math.min(a.length - 1, Math.floor(p * a.length))];
  }

  function create(opts) {
    var c = {
      renderer: opts.renderer,
      manifest: opts.manifest,
      onStatus: opts.onStatus || function () { },
      onLog: opts.onLog || function () { },
      targetFps: opts.targetFps || 30,
      tierId: 'off', userTier: 'off',
      disabled: {},              // id -> 不可用原因
      state: 'active', reason: '',
      samples: [], prevDrop: 0, lastEval: 0,
      stallSince: 0, fakeSince: 0, ignoreUntil: 0,
      comparing: false, tripped: false,
      passes: 0, skipped: 0, descs: [], fps: 0,
      scale: 0
    };
    // manifest.tiers 顺序即算力升序，也是降档顺序（skill 硬约束）
    c.tiers = c.manifest.tiers;
    return c;
  }

  function emit(c) {
    c.onStatus({
      tierId: c.tierId,
      label: labelOf(c, c.tierId),
      state: c.comparing ? 'comparing' : c.state,
      reason: c.reason,
      passes: c.passes, skipped: c.skipped,
      descs: c.descs.slice(0, 3),
      fps: Math.round(c.fps),
      scale: c.scale,
      tripped: c.tripped,
      disabled: Object.assign({}, c.disabled)
    });
  }

  function labelOf(c, id) {
    for (var i = 0; i < c.tiers.length; i++) if (c.tiers[i].id === id) return c.tiers[i].label;
    return id;
  }

  function indexOf(c, id) {
    for (var i = 0; i < c.tiers.length; i++) if (c.tiers[i].id === id) return i;
    return -1;
  }

  /** 注入一个档位。manual=true 表示用户主动选择（重置熔断/看门狗）。 */
  function setTier(c, id, manual) {
    var now = performance.now();
    try {
      var info = SRRenderer.setTier(c.renderer, id);
      c.tierId = id;
      c.state = id === 'off' ? 'active' : 'active';
      c.reason = '';
      c.ignoreUntil = now + IGNORE_MS;
      c.stallSince = 0; c.fakeSince = 0;
      c.samples.length = 0; c.prevDrop = 0; c.lastEval = now;
      delete c.disabled[id];
      c.sizeBlocked = false;
      if (manual) {
        c.userTier = id;
        c.tripped = false;
        c.onLog('已切换到 ' + labelOf(c, id) + '（' + info.passes + ' pass）');
      }
      emit(c);
      return { ok: true, passes: info.passes };
    } catch (e) {
      var msg = String(e && e.message || e);
      c.disabled[id] = msg;
      c.onLog('档位 ' + labelOf(c, id) + ' 不可用：' + msg);
      emit(c);
      return { ok: false, reason: msg };
    }
  }

  /** 阶梯降档：从当前档往 cost 小的方向找第一个可用档。 */
  function stepDown(c, why) {
    var idx = indexOf(c, c.tierId);
    for (var i = idx - 1; i >= 0; i--) {
      var t = c.tiers[i];
      if (c.disabled[t.id]) continue;
      var r = setTier(c, t.id, false);
      if (r.ok) {
        c.state = 'degraded';
        c.reason = why;
        c.onLog('性能不足，自动降档到 ' + labelOf(c, t.id) + '（' + why + '）');
        emit(c);
        return true;
      }
    }
    // 已经在最低档（off）或全部不可用：熔断，保持原画
    c.tripped = true;
    c.state = 'tripped';
    c.reason = '设备性能不足，已回退原画并停止继续降档';
    if (c.tierId !== 'off') setTier(c, 'off', false);
    c.state = 'tripped'; c.reason = '设备性能不足，已回退原画并停止继续降档';
    c.onLog(c.reason);
    emit(c);
    return false;
  }

  /** 每帧喂入渲染结果与帧间隔，驱动确认与看门狗。 */
  function onFrame(c, frame, dtMs, env) {
    var now = performance.now();
    env = env || {};
    c.passes = frame.passes || 0;
    c.skipped = frame.skipped || 0;
    c.descs = frame.descs || [];

    // 硬兜底：渲染期发现中间纹理尺寸超限时标记该档不可用并降档一次，
    // 避免每帧重复抛错。正常情况由 availableTiers 在 UI 层已提前禁用。
    if (!frame.ok && frame.reason && frame.reason.indexOf('超限') >= 0) {
      if (!c.sizeBlocked) {
        c.sizeBlocked = true;
        c.disabled[c.tierId] = frame.reason;
        c.onLog('渲染尺寸超限，自动降档：' + frame.reason);
        stepDown(c, frame.reason);
      }
      return;
    }

    // 排除窗口：缓冲中 / A/B 中 / 倍速 / 刚换档 / 已熔断 / 无效帧
    var excluded = c.comparing || env.buffering || (env.rate && env.rate !== 1)
      || now < c.ignoreUntil || c.tripped || !frame.ok;
    if (excluded) {
      c.samples.length = 0;
      c.stallSince = 0; c.fakeSince = 0; c.prevDrop = 0;
      c.lastEval = now;
      return;
    }

    // ---- 生效确认：非 off 档但一个 pass 都没跑 = 假超分 ----
    if (c.tierId !== 'off' && c.passes === 0) {
      if (!c.fakeSince) c.fakeSince = now;
      if (now - c.fakeSince >= FAKE_MS) {
        c.state = 'fake';
        c.reason = '渲染目标未达 1.2× 或 WHEN 条件全被跳过，超分链未生效';
        c.onLog('检测到假超分：' + c.reason);
        stepDown(c, c.reason);
        c.fakeSince = 0;
        return;
      }
    } else {
      c.fakeSince = 0;
      if (c.state === 'fake') { c.state = 'active'; c.reason = ''; }
    }

    // ---- 采样帧间隔 ----
    if (dtMs > 0 && dtMs < 1000) {
      c.samples.push(dtMs);
      if (c.samples.length > SAMPLE_WINDOW) c.samples.shift();
    }
    if (!c.samples.length) return;
    c.fps = 1000 / (c.samples.reduce(function (a, b) { return a + b; }, 0) / c.samples.length);

    // ---- 每秒评估一次 ----
    if (now - c.lastEval < 1000) return;
    c.lastEval = now;
    var target = 1000 / c.targetFps;
    var p95 = percentile(c.samples, 0.95);
    var drops = 0;
    for (var i = 0; i < c.samples.length; i++) if (c.samples[i] > target * 2) drops++;
    var dropRate = drops / c.samples.length;
    var growth = dropRate - c.prevDrop;      // 每秒增速
    c.prevDrop = dropRate;

    var slow = p95 > target * 1.5;
    var worsening = growth > 0.01;
    if (slow || worsening) {
      if (!c.stallSince) c.stallSince = now;
      if (now - c.stallSince >= STALL_MS) {
        stepDown(c, slow ? ('p95 ' + Math.round(p95) + 'ms > ' + Math.round(target * 1.5) + 'ms')
          : ('掉帧增速 ' + (growth * 100).toFixed(1) + '%/s'));
        c.stallSince = 0;
      }
    } else {
      c.stallSince = 0;
    }
  }

  /** A/B 对比：按住时临时切原画，松开恢复用户选择的档位。 */
  function setComparing(c, on) {
    if (c.comparing === !!on) return;
    c.comparing = !!on;
    if (on) {
      // 不修改 userTier；只临时注入 off
      try { SRRenderer.setTier(c.renderer, 'off'); } catch (e) { }
      c.reason = 'A/B 对比中：当前显示原画';
    } else {
      var restore = c.userTier;
      try {
        SRRenderer.setTier(c.renderer, restore);
        c.tierId = restore;
      } catch (e) {
        c.tierId = 'off';
      }
      c.reason = '';
      c.ignoreUntil = performance.now() + IGNORE_MS;
      c.samples.length = 0; c.stallSince = 0;
    }
    emit(c);
  }

  /** 可用档位清单（供 UI 渲染下拉框，禁用项带原因）。
   *  额外护栏：x2 类 CNN / AJN 链会生成 2× 源尺寸的中间纹理，源分辨率过高时
   *  单张 RGBA16F 可达数十 MB，有 OOM 风险。这里按 FixedSurfacePolicy 的上限
   *  （放宽 2 倍给中间纹理）提前禁用，并给出可操作的提示。 */
  function availableTiers(c) {
    var r = c.renderer;
    var maxMidW = r.srcW * 2, maxMidH = r.srcH * 2;
    var heavyBlocked = (maxMidW > SRSurface.DEFAULTS.maxDimension)
      || (maxMidW * maxMidH > SRSurface.DEFAULTS.maxPixels * 2);
    return c.tiers.map(function (t) {
      var why = c.disabled[t.id] || '';
      var heavy = (t.type === 'cnn' || t.type === 'ajn');
      if (!why && heavy && heavyBlocked) {
        why = '源 ' + r.srcW + '×' + r.srcH + ' 过高：x2 中间纹理将超出设备上限，'
          + '建议用 ≤720p 源或改用 FSR';
      }
      if (!why && t.type !== 'off' && t.type !== 'spatial' && !r.floatOK) {
        why = '设备不支持浮点渲染目标';
      }
      if (!why && t.type === 'ajn') {
        var sup = SRAnimeJaNai.isSupported(r.gl);
        if (!sup.ok) why = sup.reason;
      }
      return { id: t.id, label: t.label, hint: t.hint, cost: t.cost, disabled: !!why, why: why };
    });
  }

  function dispose(c) {
    c.samples.length = 0;
    c.onStatus = function () { };
    c.onLog = function () { };
  }

  global.SRController = {
    create: create, setTier: setTier, onFrame: onFrame,
    setComparing: setComparing, availableTiers: availableTiers,
    emit: emit, dispose: dispose
  };
})(window);
