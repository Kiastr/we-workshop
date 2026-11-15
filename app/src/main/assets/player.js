/* =============================================================================
 * player.js — 本地视频播放 + 真超分 UI
 * -----------------------------------------------------------------------------
 * 数据来源：Bridge.localVideos()（LocalVideoServer 提供的 127.0.0.1 视频流）。
 * 渲染链：sr/surface.js（锁尺寸）→ sr/renderer.js（FBO ping-pong）
 *        → sr/controller.js（生效确认 / 看门狗 / 阶梯降档 / 熔断 / A-B）
 *
 * 为什么 <video> 是隐藏的
 *   视频只作为 WebGL 纹理源，真正显示的是 <canvas>。canvas 的 drawing buffer
 *   尺寸由 FixedSurfacePolicy 在创建时一次性锁定，之后永不 resize（硬规则 2）。
 *   <video crossorigin="anonymous"> 必须在设置 src 之前就带该属性，否则本地
 *   HTTP 流的 CORS 不生效，WebGL 取帧会污染 canvas，超分直接不可用。
 *
 * app 内引导（与 README 双重交付，勿只做其一）
 *   - 列表页横幅：说明 1.2× 判据与自动降档
 *   - 控件 tooltip（title 属性）+ 播放页状态条
 *   - 首次打开的一次性提示（localStorage 标记）
 *   - 列表页底部「模型与许可」清单（来自 manifest.licenses）
 * ============================================================================= */
(function (global) {
  'use strict';

  var P = {
    open: false, stage: false,
    videos: [], current: null,
    renderer: null, controller: null, surface: null,
    raf: 0, lastTs: 0, rate: 1, buffering: false,
    needRedraw: true, seeking: false
  };

  var $ = function (id) { return document.getElementById(id); };
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function fmtSize(n) {
    n = Number(n) || 0;
    if (n > 1048576) return (n / 1048576).toFixed(1) + ' MB';
    if (n > 1024) return (n / 1024).toFixed(0) + ' KB';
    return n + ' B';
  }
  function fmtTime(s) {
    s = Math.max(0, Math.floor(Number(s) || 0));
    var m = Math.floor(s / 60), r = s % 60;
    return (m < 10 ? '0' : '') + m + ':' + (r < 10 ? '0' : '') + r;
  }

  // ---------------- 列表页 ----------------

  function openPlayer() {
    P.open = true;
    $('player').classList.remove('hidden');
    $('playerList').classList.remove('hidden');
    $('playerStage').classList.add('hidden');
    P.stage = false;
    renderVideoList();
    // 一次性引导提示
    try {
      if (!localStorage.getItem('sr_guide_v1')) {
        $('srGuideOnce').classList.remove('hidden');
        localStorage.setItem('sr_guide_v1', '1');
      }
    } catch (e) { }
  }

  function closePlayer() {
    stopPlayback();
    P.open = false;
    $('player').classList.add('hidden');
  }

  /** 边下边看：从详情页「边看边下」按钮直接进入播放页（跳过列表选择）。
   *  item 由 app.js 用 Bridge.streamingVideo() 的状态组装，含 url/name/title/mime/size。
   *  复用既有 playVideo → initPipeline → WebGL2 超分链路，无需为流式另写一套渲染。 */
  global.__playStream = function (item) {
    if (!item || !item.url) return;
    P.open = true;
    $('player').classList.remove('hidden');
    playVideo(item);
  };

  /** 返回键：播放页先回列表，列表页才关闭。供 app.js 的 __onBack 调用。 */
  global.__playerBack = function () {
    if (!P.open) return false;
    if (P.stage) { backToList(); return true; }
    closePlayer();
    return true;
  };

  function backToList() {
    stopPlayback();
    P.stage = false;
    $('playerStage').classList.add('hidden');
    $('playerList').classList.remove('hidden');
    renderVideoList();
  }

  function renderVideoList() {
    var box = $('playerItems');
    var data = { ok: false, items: [], error: '未读取' };
    try {
      data = JSON.parse(window.Bridge.localVideos() || '{}');
    } catch (e) {
      data = { ok: false, items: [], error: String(e) };
    }
    P.videos = (data && data.items) || [];
    if (!data.ok) {
      box.innerHTML = '<p class="setting-hint">读取本地视频失败：' + esc(data.error || '') + '</p>';
    } else if (!P.videos.length) {
      box.innerHTML = '<p class="setting-hint">还没有已下载的视频。先在工坊里下载，'
        + '文件会保存到 Download/Wallpaper WE/。</p>';
    } else {
      box.innerHTML = P.videos.map(function (v, i) {
        var thumb = v.img
          ? '<img src="' + esc(v.img) + '" alt="" loading="lazy">'
          : '<div class="pv-ph">▶</div>';
        var streaming = !!v.streaming;
        var locked = streaming && !v.ready;   // moov 在尾部 / 水位不足：不可点
        var badge = streaming
          ? '<span class="pv-badge' + (v.ready ? '' : ' pv-badge-off') + '">'
            + (v.ready ? '边下边看' : '下载中') + '</span>'
          : '';
        var sub = streaming
          ? esc(v.reason || '正在下载') + ' · ' + fmtSize(v.waterline) + ' / ' + fmtSize(v.total)
          : (esc(v.name) + ' · ' + fmtSize(v.size) + ' · ' + esc(v.mime));
        return '<div class="pv-item' + (locked ? ' pv-locked' : '') + '" data-i="' + i + '">'
          + '<div class="pv-thumb">' + thumb + '</div>'
          + '<div class="pv-meta">'
          + '<div class="pv-title">' + badge + esc(v.title || v.name) + '</div>'
          + '<div class="pv-sub">' + sub + '</div>'
          + '</div><div class="pv-go">' + (locked ? '稍候' : '播放') + '</div></div>';
      }).join('');
      box.querySelectorAll('.pv-item').forEach(function (el) {
        el.onclick = function () {
          var v = P.videos[Number(el.dataset.i)];
          if (v && v.streaming && !v.ready) {   // 未就绪：给出原因，不进播放页
            try { if (window.Bridge.toast) window.Bridge.toast(v.reason || '还在下载，稍候再试'); } catch (e) { }
            return;
          }
          playVideo(v);
        };
      });
    }
    // 模型与许可清单（app 内展示，与 README 一致）
    var m = global.__SR_MANIFEST;
    if (m && m.licenses) {
      $('srLicenses').innerHTML = '<h3 class="sr-lic-title">模型与许可</h3>'
        + m.licenses.map(function (l) {
          return '<div class="sr-lic-row"><span>' + esc(l.name) + '</span>'
            + '<span class="sr-lic-tag">' + esc(l.license) + '</span></div>';
        }).join('');
    }
  }

  // ---------------- 播放页 ----------------

  function playVideo(item) {
    if (!item || !item.url) return;
    stopPlayback();
    P.current = item;
    P.stage = true;
    $('playerList').classList.add('hidden');
    $('playerStage').classList.remove('hidden');
    $('srTitle').textContent = item.title || item.name;
    $('srInfo').textContent = '正在加载视频…';

    var v = $('srVideo');
    // crossorigin 是 HTML 属性，必须在 src 之前（见文件头）
    v.loop = true;
    v.playbackRate = P.rate;
    v.src = item.url;
    v.load();

    v.onloadedmetadata = function () { initPipeline(v); };
    v.onerror = function () {
      $('srInfo').textContent = '视频加载失败：' + (v.error && v.error.message || '格式可能不受 WebView 支持');
    };
    v.onwaiting = function () { P.buffering = true; };
    v.onplaying = function () { P.buffering = false; };
    v.oncanplay = function () { P.buffering = false; };
    try { v.play().catch(function () { }); } catch (e) { }
  }

  function initPipeline(v) {
    var sw = v.videoWidth, sh = v.videoHeight;
    if (!sw || !sh) { $('srInfo').textContent = '无法读取视频尺寸'; return; }

    var surface = SRSurface.compute(sw, sh);
    if (!surface) { $('srInfo').textContent = '视频尺寸非法'; return; }
    P.surface = surface;

    try {
      P.renderer = SRRenderer.create($('srCanvas'), v, {
        surface: surface,
        shaders: global.__SR_SHADERS,
        manifest: global.__SR_MANIFEST,
        ajn: global.__SR_AJN
      });
    } catch (e) {
      $('srInfo').textContent = 'WebGL2 初始化失败：' + (e.message || e);
      return;
    }

    P.controller = SRController.create({
      renderer: P.renderer,
      manifest: global.__SR_MANIFEST,
      targetFps: 30,
      onStatus: onStatus,
      onLog: function (msg) { $('srLog').textContent = msg; }
    });
    buildTierOptions();

    // 真超分硬规则 1：目标未达 1.2× 时明确告知，避免「假超分」
    if (!surface.trueSR) {
      $('srLog').textContent = surface.reason;
    } else {
      $('srLog').textContent = '渲染目标 ' + surface.outW + '×' + surface.outH
        + '（' + surface.scale.toFixed(2) + '×，创建期锁定）';
    }

    // 默认档位：优先 Anime4K Fast；若被能力/尺寸护栏禁用，退到第一个可用档，
    // 都不行则原画。避免直接撞 renderer 的尺寸兜底再降档（会闪一下）。
    var avail = SRController.availableTiers(P.controller);
    var startTier = 'off', fastOk = false, fallback = null;
    for (var ai = 0; ai < avail.length; ai++) {
      var a = avail[ai];
      if (a.disabled || a.id === 'off') continue;
      if (a.id === 'a4k_fast') fastOk = true;
      if (!fallback) fallback = a.id;
    }
    startTier = fastOk ? 'a4k_fast' : (fallback || 'off');
    SRController.setTier(P.controller, startTier, true);
    P.needRedraw = true;
    P.lastTs = 0;
    $('srTier').value = startTier;
    P.raf = requestAnimationFrame(loop);
  }

  function buildTierOptions() {
    var list = SRController.availableTiers(P.controller);
    $('srTier').innerHTML = list.map(function (t) {
      return '<option value="' + esc(t.id) + '"' + (t.disabled ? ' disabled' : '')
        + ' title="' + esc(t.why || t.hint) + '">' + esc(t.label)
        + (t.disabled ? '（不可用）' : '') + '</option>';
    }).join('');
  }

  function stopPlayback() {
    if (P.raf) cancelAnimationFrame(P.raf);
    P.raf = 0; P.lastTs = 0;
    var v = $('srVideo');
    if (v) {
      try { v.pause(); } catch (e) { }
      v.onloadedmetadata = v.onerror = v.onwaiting = v.onplaying = v.oncanplay = null;
      v.removeAttribute('src');
      try { v.load(); } catch (e) { }
    }
    if (P.controller) { try { SRController.dispose(P.controller); } catch (e) { } P.controller = null; }
    if (P.renderer) { try { SRRenderer.dispose(P.renderer); } catch (e) { } P.renderer = null; }
    $('player').classList.remove('player-fs');
  }

  function loop(ts) {
    if (!P.stage || !P.renderer) return;
    var dt = P.lastTs ? (ts - P.lastTs) : 16.7;
    P.lastTs = ts;
    var frame = { ok: false, passes: 0, skipped: 0, descs: [] };
    var v = $('srVideo');
    var paused = v && v.paused;
    if (!paused || P.needRedraw) {
      try { frame = SRRenderer.renderFrame(P.renderer); }
      catch (e) { frame = { ok: false, reason: String(e && e.message || e) }; }
      P.needRedraw = false;
    }
    SRController.onFrame(P.controller, frame, dt, {
      buffering: P.buffering || (v && v.readyState < 2), rate: P.rate
    });
    updateProgress(v);
    P.raf = requestAnimationFrame(loop);
  }

  function updateProgress(v) {
    if (!v || !isFinite(v.duration) || v.duration <= 0) return;
    if (!P.seeking) $('srSeek').value = String(Math.round(v.currentTime / v.duration * 1000));
    $('srTime').textContent = fmtTime(v.currentTime) + ' / ' + fmtTime(v.duration);
  }

  var STATE_TEXT = {
    active: '已生效', degraded: '已自动降档', tripped: '已熔断',
    comparing: 'A/B 对比中', fake: '未生效（假超分）', unsupported: '不可用'
  };

  function onStatus(s) {
    var txt = '档位：' + s.label + ' · ' + (STATE_TEXT[s.state] || s.state)
      + ' · ' + s.passes + ' pass · ' + s.fps + ' fps';
    if (s.reason) txt += ' · ' + s.reason;
    if (s.descs && s.descs.length) txt += ' · 链：' + s.descs[0];
    $('srInfo').textContent = txt;
    $('srInfo').className = 'sr-info sr-' + s.state;
    if (P.controller && s.tierId !== $('srTier').value && !s.comparing) {
      $('srTier').value = s.tierId;
    }
  }

  // ---------------- 控件绑定 ----------------
  function bind() {
    var pb = $('playerBtn');
    if (pb) pb.onclick = openPlayer;
    $('playerClose').onclick = closePlayer;
    $('playerBackdrop').onclick = closePlayer;
    $('srBack').onclick = backToList;

    $('srPlay').onclick = function () {
      var v = $('srVideo');
      if (v.paused) { v.play().catch(function () { }); this.textContent = '⏸'; }
      else { v.pause(); this.textContent = '▶'; }
    };

    var seek = $('srSeek');
    seek.onpointerdown = function () { P.seeking = true; };
    seek.onpointerup = seek.onpointercancel = function () { P.seeking = false; };
    seek.oninput = function () {
      var v = $('srVideo');
      if (v && isFinite(v.duration) && v.duration > 0) {
        v.currentTime = Number(seek.value) / 1000 * v.duration;
        P.needRedraw = true;
      }
    };

    $('srTier').onchange = function () {
      if (!P.controller) return;
      var r = SRController.setTier(P.controller, this.value, true);
      if (!r.ok) { this.value = P.controller.tierId; }
      P.needRedraw = true;
    };

    $('srRate').onchange = function () {
      P.rate = Number(this.value) || 1;
      var v = $('srVideo');
      if (v) v.playbackRate = P.rate;
    };

    // A/B：按住显示原画，松开恢复（看门狗在对比期间不采样）
    var ab = $('srAB');
    function abOn(e) { e.preventDefault(); if (P.controller) SRController.setComparing(P.controller, true); }
    function abOff(e) { e.preventDefault(); if (P.controller) SRController.setComparing(P.controller, false); P.needRedraw = true; }
    ab.addEventListener('pointerdown', abOn);
    ab.addEventListener('pointerup', abOff);
    ab.addEventListener('pointercancel', abOff);
    ab.addEventListener('pointerleave', abOff);

    $('srFull').onclick = function () {
      $('player').classList.toggle('player-fs');
      P.needRedraw = true;
    };
    $('srGuideOnceBtn').onclick = function () { $('srGuideOnce').classList.add('hidden'); };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind);
  } else {
    bind();
  }
})(window);
