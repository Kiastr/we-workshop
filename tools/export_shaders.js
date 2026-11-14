/* =============================================================================
 * export_shaders.js — 导出所有超分 shader 源码（stdout JSON）
 * -----------------------------------------------------------------------------
 * 用途：给 tools/verify_shaders.py 提供输入，用真实 GLSL ES 编译器逐个编译。
 * 运行：node tools/export_shaders.js > shaders.json
 *      （verify_shaders.py 内部会自动调用本脚本并读取 stdout，无需手动导出）
 *
 * 覆盖内容
 *   1. hook.js 的顶点着色器
 *   2. manifest.js 里每条 tier.chain 的所有 pass，经 SRHook.buildFragment()
 *      生成真正喂给 WebGL2 的 fragment 源码（含 //!PARAM 注入、宏注入）
 *   3. animejanai.js 的 vert/prep/conv/assemble/blit，conv 额外覆盖
 *      outN = 6 / 4 / 1 三种 MRT 分批变体（不同 MAX_DRAW_BUFFERS 的设备）
 *
 * 注意：Node 里没有 window，这里用 global.window = global 模拟 WebView 的
 * 全局对象；两个运行时都是 (function(global){...})(window) 的 IIFE，
 * 因此能被直接 require 并挂到 global 上。
 * ============================================================================= */
global.window = global;
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SR = path.join(ROOT, 'app', 'src', 'main', 'assets', 'sr');
require(path.join(SR, 'hook.js'));
require(path.join(SR, 'animejanai.js'));
require(path.join(SR, 'surface.js'));
require(path.join(SR, 'renderer.js'));   // 取它的 3 个内置 shader 源码

function loadJS(file, varName) {
  const s = fs.readFileSync(path.join(SR, file), 'utf8');
  return JSON.parse(s.slice(s.indexOf('=', s.indexOf(varName)) + 1, s.lastIndexOf(';')));
}
const shaders = loadJS('shaders.js', '__SR_SHADERS');
const manifest = loadJS('manifest.js', '__SR_MANIFEST');

const out = [];
const seen = new Map();
function add(tag, src, type) {
  if (seen.has(src)) return;   // 不同 tier 复用同一 shader，去重避免重复编译
  seen.set(src, tag);
  out.push({ tag, type, src });
}

add('HOOK/vert', SRHook.VERT, 'vert');
for (const tier of manifest.tiers) {
  if (!tier.chain.length) continue;
  const r = SRHook.parseChain(tier.chain, shaders);
  for (const p of r.passes) {
    const b = SRHook.buildFragment(p, r.params);
    add('HOOK/' + tier.id + '/' + p.file + '#' + p.index + ' ' + p.desc, b.source, 'frag');
  }
}

const ajn = SRAnimeJaNai.buildSources(6);
add('AJN/vert', ajn.vert, 'vert');
add('AJN/prep', ajn.prep, 'frag');
add('AJN/conv(outN=6)', ajn.conv, 'frag');
add('AJN/assemble', ajn.assemble, 'frag');
add('AJN/blit', ajn.blit, 'frag');
add('AJN/conv(outN=4)', SRAnimeJaNai.buildSources(4).conv, 'frag');
add('AJN/conv(outN=1)', SRAnimeJaNai.buildSources(1).conv, 'frag');

// renderer.js 内置的 luma / combine / blit 也必须过真实编译器
add('RENDERER/luma', SRRenderer.SOURCES.luma, 'frag');
add('RENDERER/combine', SRRenderer.SOURCES.combine, 'frag');
add('RENDERER/blit', SRRenderer.SOURCES.blit, 'frag');

process.stdout.write(JSON.stringify(out));
