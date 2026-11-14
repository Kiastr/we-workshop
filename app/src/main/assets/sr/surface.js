/* =============================================================================
 * surface.js — FixedSurfacePolicy（真超分硬规则 1 与 2 的落地点）
 * -----------------------------------------------------------------------------
 * 来自 android-true-super-resolution skill 的三条硬规则，这里负责前两条：
 *   1. 渲染目标必须大于源尺寸约 1.2×。否则 Anime4K/FSRCNNX 的 //!WHEN
 *      （OUTPUT.w MAIN.w / 1.200 > ...）求值为 0，整条放大链会被跳过 ——
 *      shader 编译成功、注入成功，画面却还是原画，即「假超分」。
 *   2. 渲染目标尺寸只能在管线创建时一次性锁定，之后永不 resize。
 *      本模块只返回尺寸；canvas.width/height 由 player.js 在 create 时写入一次，
 *      CSS 用 width:100% 自适应显示，drawing buffer 尺寸绝不在运行中改动。
 *      （切换视频 = 销毁旧管线、新建一条，不属于「运行中 resize」。）
 *
 * 上限夹紧（skill 参考实现给定的护栏）
 *   maxScale    = 2.0        理想倍率，匹配所有 x2 模型的输出
 *   maxPixels   = 3686400    ≈2560×1440，控制填充率/显存
 *   maxDimension= 2560       单维上限
 *   minScale    = 1.2        真超分判据下限
 * 由 maxScale 起算，先按像素上限、再按单维上限等比收缩；偶数对齐（CNN 的
 * Depth-to-Space 要求偶数尺寸）。
 *
 * 导入方法：SRRenderer / player.js 调用 SRSurface.compute(srcW, srcH) 拿尺寸，
 * 不需要改本文件；调上限时改 DEFAULTS 或给 compute 传 opts 覆盖即可。
 * ============================================================================= */
(function (global) {
  'use strict';

  var DEFAULTS = {
    maxScale: 2.0,
    maxPixels: 3686400,
    maxDimension: 2560,
    minScale: 1.2
  };

  /** 向下取偶数（>=2）。CNN 的 x2 Depth-to-Space 依赖偶数尺寸。 */
  function even(v) {
    v = Math.max(2, Math.floor(v));
    return (v % 2 === 0) ? v : v - 1;
  }

  /** 计算锁定的渲染表面。
   *  返回 {outW,outH,srcW,srcH,scale,trueSR,reason}；非法源尺寸返回 null。
   *  trueSR=false 表示受设备上限所限达不到 1.2×，此时放大链不会生效，
   *  EnhancementController 应直接提示用户而不是白跑一遍 shader。 */
  function compute(srcW, srcH, opts) {
    srcW = Math.floor(srcW || 0);
    srcH = Math.floor(srcH || 0);
    if (srcW < 2 || srcH < 2) return null;
    var o = Object.assign({}, DEFAULTS, opts || {});

    var w = even(srcW * o.maxScale);
    var h = even(srcH * o.maxScale);

    // 像素总量上限：等比收缩到刚好 <= maxPixels
    if (w * h > o.maxPixels) {
      var k = Math.sqrt(o.maxPixels / (w * h));
      w = even(w * k);
      h = even(h * k);
    }
    // 单维上限（保持宽高比）
    var biggest = Math.max(w, h);
    if (biggest > o.maxDimension) {
      var k2 = o.maxDimension / biggest;
      w = even(w * k2);
      h = even(h * k2);
    }
    // 不小于源尺寸
    w = Math.max(w, even(srcW));
    h = Math.max(h, even(srcH));

    var sw = w / srcW, sh = h / srcH;
    var trueSR = (sw >= o.minScale) && (sh >= o.minScale);
    return {
      outW: w, outH: h, srcW: srcW, srcH: srcH,
      scale: Math.min(sw, sh),
      trueSR: trueSR,
      reason: trueSR ? '' : '源分辨率过高，受设备上限约束无法达到 1.2× 放大，超分链不会生效'
    };
  }

  global.SRSurface = { compute: compute, even: even, DEFAULTS: DEFAULTS };
})(window);
