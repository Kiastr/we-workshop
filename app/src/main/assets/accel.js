/*
 * 网络加速（App 内 hosts 映射）UI 控制器。
 *
 * 依赖：app.js 的 callBridge（异步 Bridge 回调机制），因此本文件必须在 app.js 之后加载。
 * 数据来自 Java 层 HostsAccelerator.configJson()；测速走 Bridge.accelBenchmark（后台线程）。
 *
 * 设计约束：
 * - 所有动态内容都经 esc() 转义。虽然 Java 层已对自定义域名/IP 做了字面量校验，
 *   这里仍按「前端不信任任何输入」处理，避免 innerHTML 注入。
 * - 总开关默认关闭；测速结果、服务开关、自定义条目都由 Java 层持久化到 SharedPreferences，
 *   前端只负责展示与发指令，不保存状态。
 * - 页面内的 #accelGuide 是 app 内 UI 引导，与 README「网络加速」章节成对交付，
 *   首次打开显示，点「知道了」后写入 localStorage 不再打扰。
 */
(function () {
  'use strict';

  // v2：文案新增「卡片三态」说明（测速失败 ≠ 未测速）。升级 key 让老用户也看到一次。
  var GUIDE_KEY = 'accel_guide_v2';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function el(id) { return document.getElementById(id); }

  function toast(msg) {
    try { window.Bridge.toast(msg); } catch (e) { /* bridge 不可用时忽略 */ }
  }

  function config() {
    try {
      return JSON.parse(window.Bridge.accelConfig() || '{}');
    } catch (e) {
      return { ok: false, error: String(e) };
    }
  }

  // 服务卡片底部三态：
  //   ① 已优选            → 绿色，显示 IP 与延迟
  //   ② 测过速但无可用 IP  → 告警色，明确提示补自定义条目（旧版误显示为「未测速」）
  //   ③ 从未测速          → 灰色；无内置候选时额外说明必须靠测速/自定义
  // 判据 benched 由 Java 层 HostsAccelerator 持久化，重启 App 后仍准确。
  function foot(s, benched) {
    if (s.picked) {
      return '<span class="accel-picked">● 优选 ' + esc(s.picked) +
          ' · ' + Number(s.ms) + ' ms</span>';
    }
    if (benched) {
      return '<span class="accel-failed">⚠ 测速无可用 IP · 请在下方补一条自定义 hosts</span>';
    }
    return s.hasCandidates
      ? '<span class="accel-pending">未测速 · 使用内置候选</span>'
      : '<span class="accel-pending">未测速 · 无内置候选，需测速或自定义条目</span>';
  }

  function render(cfg) {
    if (!cfg || !cfg.ok) { toast('读取加速配置失败'); return; }

    el('accelEnabled').checked = !!cfg.enabled;
    el('accelHit').textContent = String(cfg.hits || 0);

    var services = cfg.services || [];
    var on = 0, picked = 0;
    el('accelServices').innerHTML = services.map(function (s) {
      if (s.on) on++;
      if (s.picked) picked++;
      return '<div class="accel-card' + (s.on ? ' on' : '') + '">' +
          '<div class="accel-card-top">' +
            '<div class="accel-card-name">' + esc(s.name) + '</div>' +
            '<label class="switch">' +
              '<input type="checkbox" data-id="' + esc(s.id) + '"' + (s.on ? ' checked' : '') + '>' +
              '<span class="switch-track"></span>' +
            '</label>' +
          '</div>' +
          '<div class="accel-card-desc">' + esc(s.desc) + '</div>' +
          '<div class="accel-card-domains">' + esc(s.domains) + '</div>' +
          '<div class="accel-card-foot">' + foot(s, cfg.benched) + '</div>' +
        '</div>';
    }).join('');
    el('accelOn').textContent = on + ' / ' + services.length;
    el('accelPicked').textContent = String(picked);

    var custom = cfg.custom || [];
    el('accelCustom').innerHTML = custom.length
      ? custom.map(function (c) {
          return '<div class="accel-rule">' +
              '<span>' + esc(c.domain) + ' <i>→</i> ' + esc(c.ip) + '</span>' +
              '<button class="accel-del" data-domain="' + esc(c.domain) + '" title="删除">✕</button>' +
            '</div>';
        }).join('')
      : '<div class="accel-empty">暂无自定义条目</div>';
  }

  function refresh() { render(config()); }

  function openAccel() {
    refresh();
    var seen = false;
    try { seen = !!localStorage.getItem(GUIDE_KEY); } catch (e) { /* 隐私模式下默认显示 */ }
    el('accelGuide').classList.toggle('hidden', seen);
    el('accel').classList.remove('hidden');
  }

  function closeAccel() { el('accel').classList.add('hidden'); }

  // 供 HTML 的 onclick 与 app.js 的 __onBack 调用
  window.openAccel = openAccel;
  window.closeAccel = closeAccel;

  el('accelBtn').onclick = openAccel;
  el('accelClose').onclick = closeAccel;

  el('accelGuideBtn').onclick = function () {
    try { localStorage.setItem(GUIDE_KEY, '1'); } catch (e) { /* ignore */ }
    el('accelGuide').classList.add('hidden');
  };

  el('accelEnabled').onchange = function (e) {
    window.Bridge.accelSetEnabled(e.target.checked);
    toast(e.target.checked ? '网络加速已开启' : '网络加速已关闭');
    refresh();
  };

  // 服务卡片开关：事件委托，重渲染后依然有效
  el('accelServices').addEventListener('change', function (e) {
    var inp = e.target.closest ? e.target.closest('input[data-id]') : null;
    if (!inp) return;
    window.Bridge.accelSetService(inp.getAttribute('data-id'), inp.checked);
    refresh();
  });

  el('accelBenchBtn').onclick = async function () {
    var btn = el('accelBenchBtn');
    var out = el('accelBenchOut');
    var label = btn.textContent;
    btn.disabled = true;
    btn.textContent = '测速中…';
    out.classList.remove('hidden');
    out.textContent = '正在并发做 TCP + TLS(SNI) 握手探测，约需几秒…';
    var r = await window.callBridge('accelBenchmark');
    btn.disabled = false;
    btn.textContent = label;
    if (!r || !r.ok) {
      out.textContent = '测速失败：' + ((r && r.error) || '未知错误');
      return;
    }
    var lines = ['耗时 ' + r.elapsed + ' ms · 探测 ' + r.total + ' 个候选 · 选中 ' + r.picked + ' 个', ''];
    var rs = (r.results || []).slice().sort(function (a, b) {
      if (a.domain !== b.domain) return a.domain < b.domain ? -1 : 1;
      return (a.ok ? a.ms : 1e9) - (b.ok ? b.ms : 1e9);
    });
    rs.forEach(function (p) {
      lines.push((p.ok ? '✓ ' : '✗ ') + p.domain + '  ' + p.ip + '  ' + (p.ok ? (p.ms + ' ms') : p.why));
    });
    if (!rs.length) lines.push('（没有可探测的候选，请检查服务开关）');
    // 把「哪些域名一个可用 IP 都没选出」显式列出来，避免用户只看到「已优选 4」
    // 却不知道失败的那组才是列表加载失败的原因。
    var okDomains = {}, failedDomains = [];
    rs.forEach(function (p) { if (p.ok) okDomains[p.domain] = true; });
    rs.forEach(function (p) {
      if (!p.ok && !okDomains[p.domain] && failedDomains.indexOf(p.domain) < 0) {
        failedDomains.push(p.domain);
      }
    });
    if (failedDomains.length) {
      lines.push('', '⚠ 这些域名测速无可用 IP：' + failedDomains.join('、'));
      lines.push('  已自动复用其它组验证过的 IP 参与本轮探测；仍失败时请在下方填本地区可用 IP 后重测。');
    }
    out.textContent = lines.join('\n');
    refresh();
    toast('测速完成，已选 ' + r.picked + ' 个优选 IP');
  };

  el('accelClearBtn').onclick = function () {
    window.Bridge.accelClearPicks();
    refresh();
    toast('已清除优选结果，回到内置候选');
  };

  el('accelAddBtn').onclick = function () {
    var d = el('accelDomain').value.trim();
    var ip = el('accelIp').value.trim();
    var r = window.Bridge.accelAddRule(d, ip);
    if (r === 'ok') {
      el('accelDomain').value = '';
      el('accelIp').value = '';
      refresh();
      toast('已添加自定义条目');
    } else {
      toast(String(r || '添加失败').replace(/^err:?/, ''));
    }
  };

  el('accelCustom').addEventListener('click', function (e) {
    var b = e.target.closest ? e.target.closest('button[data-domain]') : null;
    if (!b) return;
    window.Bridge.accelRemoveRule(b.getAttribute('data-domain'));
    refresh();
  });
})();
