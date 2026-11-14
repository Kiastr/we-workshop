/* =============================================================================
 * renderer.js — WebGL2 渲染调度器（把 mpv hook 链真正跑成 FBO ping-pong）
 * -----------------------------------------------------------------------------
 * 为什么需要它
 *   hook.js 只负责「把 //!HOOK 语法翻译成 GLSL」并编译 program，它不知道
 *   //!SAVE / //!BIND / //!WIDTH / //!WHEN 在运行时该怎么调度。skill 的第三条
 *   硬规则是「必须验证渲染链真的跑了，而不是只验证 shader 注入成功」，
 *   本文件就是那条链的执行者，并吐出 pass 描述作为生效指纹。
 *
 * -----------------------------------------------------------------------------
 * 纹理池语义（按真实 shader 反查确定，勿凭印象改）
 *   slots: name -> {tex,fbo,w,h}
 *     NATIVE  原始视频帧，尺寸恒为 srcW×srcH，整条链只读
 *     MAIN    当前主纹理；//!SAVE MAIN 会替换它，尺寸可能变化（Depth-to-Space x2）
 *     LUMA    亮度平面，仅 FSRCNNX 档使用（//!HOOK LUMA），最终也会变成 2×
 *     HOOKED  别名，每个 pass 指向该 pass 的 hook 点纹理（MAIN / PREKERNEL / LUMA）
 *     OUTPUT  不是纹理，只是 RPN 变量 = canvas 尺寸（真超分判据用）
 *   中间名（STATSMAX / conv2d_tf / FEATURE1 / MODEL1 / ...）由 //!SAVE 自动登记。
 *
 *   //!WIDTH / //!HEIGHT 经实测总是成对出现（各 100 条），是 RPN 表达式，
 *   求值为输出 FBO 尺寸；缺省时 = HOOKED 尺寸。RPN 里会引用中间纹理的
 *   .w/.h（如 "conv2d_last_tf.w 2 *"），因此变量表必须动态包含所有 slots。
 *   //!WHEN 求值为 0 则整段跳过 —— 必须保留，否则就是假超分。
 *
 *   ★ 每个 pass 渲染到一个「专用 target」（targets[passIndex]），执行完再把
 *     slots[outName] 指向它。绝不 in-place 覆盖输入纹理，避免 WebGL 同时读写
 *     同一纹理的 feedback loop。这也是 FBO ping-pong 的落地方式。
 *   ★ 无 //!SAVE 的 pass 输出写回 hook 点纹理（mpv 语义），即 PREKERNEL->MAIN、
 *     LUMA->LUMA、MAIN->MAIN。
 *
 * -----------------------------------------------------------------------------
 * 三个内置 pass
 *   luma    FSRCNNX 专用：RGB -> 亮度 Y，存进 vec4(Y,Y,Y,1) 供 //!HOOK LUMA 用
 *   combine FSRCNNX 专用：官方模型只增强亮度、色度保持双线性。RGB 管线里没有
 *           YUV 平面，这里用 Y'/Y 缩放原 RGB 做等价近似（见 fsrcnnx 档 hint）
 *   blit    把最终纹理缩放到 FixedSurfacePolicy 锁定的 canvas 尺寸
 *   off 档直接用 blit，AJN 档整体交给 animejanai.js（它自带 assemble+blit）。
 *
 * 导入方法（未来自主维护）
 *   SRRenderer.create(canvas, video, {surface, shaders, manifest, ajn})
 *   -> setTier(id) -> 每帧 renderFrame() -> 退出 dispose()
 *   新增档位只需在 tools/gen_assets.py 的 TIERS 登记，本文件无需改动。
 * ============================================================================= */
(function (global) {
  'use strict';

  var BLIT_FS = [
    '#version 300 es', 'precision highp float;',
    'uniform sampler2D u_tex;', 'uniform vec2 u_outSize;',
    'out vec4 o0;',
    'void main(){ o0 = texture(u_tex, gl_FragCoord.xy / u_outSize); }'
  ].join('\n');

  var LUMA_FS = [
    '#version 300 es', 'precision highp float;',
    'uniform sampler2D u_src;', 'uniform vec2 u_outSize;',
    'out vec4 o0;',
    'void main(){',
    '  vec3 c = texture(u_src, gl_FragCoord.xy / u_outSize).rgb;',
    '  float y = dot(c, vec3(0.299, 0.587, 0.114));',
    '  o0 = vec4(vec3(y), 1.0);',
    '}'
  ].join('\n');

  var COMBINE_FS = [
    '#version 300 es', 'precision highp float;',
    'uniform sampler2D u_orig;', 'uniform sampler2D u_luma;',
    'uniform vec2 u_outSize;', 'out vec4 o0;',
    'void main(){',
    '  vec2 uv = gl_FragCoord.xy / u_outSize;',
    '  vec3 rgb = texture(u_orig, uv).rgb;',
    '  float yp = texture(u_luma, uv).r;',
    '  float y = dot(rgb, vec3(0.299, 0.587, 0.114));',
    // FSRCNNX 只增强亮度：按 Y'/Y 缩放原 RGB，保留色度（RGB 管线的等价近似）
    '  o0 = vec4(clamp(rgb * (yp / max(y, 0.02)), 0.0, 1.0), 1.0);',
    '}'
  ].join('\n');

  function makeProgram(gl, fragSrc, uniforms) {
    function sh(type, src) {
      var s = gl.createShader(type);
      gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        var log = gl.getShaderInfoLog(s); gl.deleteShader(s);
        throw new Error('[SR/Renderer] 内置 shader 编译失败: ' + log);
      }
      return s;
    }
    var vs = sh(gl.VERTEX_SHADER, SRHook.VERT);
    var fs = sh(gl.FRAGMENT_SHADER, fragSrc);
    var p = gl.createProgram();
    gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p);
    gl.deleteShader(vs); gl.deleteShader(fs);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      var lg = gl.getProgramInfoLog(p); gl.deleteProgram(p);
      throw new Error('[SR/Renderer] 内置 program link 失败: ' + lg);
    }
    var u = {};
    (uniforms || []).forEach(function (n) { u[n] = gl.getUniformLocation(p, n); });
    return { prog: p, u: u };
  }

  function makeTarget(gl, w, h, ifmt, type, linear) {
    var tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    var f = linear ? gl.LINEAR : gl.NEAREST;
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, f);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, f);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, ifmt, w, h, 0, gl.RGBA, type, null);
    var fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    var okFbo = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    if (!okFbo) {
      gl.deleteTexture(tex); gl.deleteFramebuffer(fbo);
      throw new Error('[SR/Renderer] FBO 不完整 (' + w + 'x' + h + ', ifmt=' + ifmt + ')');
    }
    return { tex: tex, fbo: fbo, w: w, h: h };
  }

  function delTarget(gl, t) {
    if (!t) return;
    if (t.tex) gl.deleteTexture(t.tex);
    if (t.fbo) gl.deleteFramebuffer(t.fbo);
  }

  /** 创建渲染器。surface 来自 SRSurface.compute()，尺寸在这里一次性锁定。 */
  function create(canvas, video, opts) {
    opts = opts || {};
    var surface = opts.surface;
    if (!surface) throw new Error('[SR/Renderer] 缺少 surface');
    var gl = canvas.getContext('webgl2', {
      alpha: false, antialias: false, depth: false, stencil: false,
      premultipliedAlpha: false, preserveDrawingBuffer: false
    });
    if (!gl) throw new Error('[SR/Renderer] 设备不支持 WebGL2');

    // 硬规则 2：drawing buffer 尺寸只在创建时写一次，之后永不 resize。
    // CSS 负责把它缩放显示到屏幕，canvas.width/height 不再改动。
    canvas.width = surface.outW;
    canvas.height = surface.outH;

    var floatExt = gl.getExtension('EXT_color_buffer_float')
      || gl.getExtension('EXT_color_buffer_half_float');
    var linearFloat = !!gl.getExtension('OES_texture_float_linear');
    // CNN 中间特征会超出 [0,1] 且可能为负，必须浮点纹理；RGBA8 会 clamp 成错画面。
    var ifmt = floatExt ? gl.RGBA16F : gl.RGBA8;
    var type = floatExt ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE;
    var midLinear = floatExt ? linearFloat : true;  // RGBA8 天然支持 LINEAR

    var r = {
      gl: gl, canvas: canvas, video: video,
      outW: surface.outW, outH: surface.outH,
      srcW: surface.srcW, srcH: surface.srcH,
      floatOK: !!floatExt,
      ifmt: ifmt, type: type, midLinear: midLinear,
      shaders: opts.shaders || global.__SR_SHADERS,
      manifest: opts.manifest || global.__SR_MANIFEST,
      ajnMeta: opts.ajn || global.__SR_AJN,
      vao: gl.createVertexArray(),
      targets: [], slots: {},
      programs: [], chain: null, tier: null, ajn: null,
      native: null, lumaTex: null,
      blit: null, luma: null, combine: null,
      lastFrameOk: false
    };

    // 源纹理：视频帧 8bit，LINEAR 供 AutoDownscalePre / blit 采样
    r.native = makeTarget(gl, r.srcW, r.srcH, gl.RGBA8, gl.UNSIGNED_BYTE, true);
    r.lumaTex = makeTarget(gl, r.srcW, r.srcH, ifmt, type, midLinear);
    r.blit = makeProgram(gl, BLIT_FS, ['u_tex', 'u_outSize']);
    r.luma = makeProgram(gl, LUMA_FS, ['u_src', 'u_outSize']);
    r.combine = makeProgram(gl, COMBINE_FS, ['u_orig', 'u_luma', 'u_outSize']);
    return r;
  }

  /** hook 点 -> slot 名。PREKERNEL 是 MAIN 的预处理阶段，仍写回 MAIN。 */
  function hookSlotOf(hook) {
    if (hook === 'LUMA') return 'LUMA';
    return 'MAIN';   // MAIN / PREKERNEL / NATIVE / 其它
  }

  function resetSlots(r) {
    // HOOKED 不进 slots：它是每 pass 动态解析的别名，放进去会污染变量表
    r.slots = {
      NATIVE: r.native,
      MAIN: r.native,
      LUMA: r.lumaTex
    };
  }

  /** 构造 //!WHEN / WIDTH / HEIGHT 的 RPN 变量表。 */
  function buildVars(r, hooked, params) {
    var v = {
      'OUTPUT.w': r.outW, 'OUTPUT.h': r.outH
    };
    Object.keys(r.slots).forEach(function (k) {
      var e = r.slots[k];
      if (!e) return;
      v[k + '.w'] = e.w;
      v[k + '.h'] = e.h;
    });
    (params || []).forEach(function (p) { v[p.name] = p.value; });
    // HOOKED 必须最后写入：它是当前 pass 的 hook 点纹理，尺寸可能与
    // NATIVE/MAIN 不同（MAIN 会被 SAVE 改成 2×）。FSR 的 WHEN 依赖它。
    v['HOOKED.w'] = hooked.w;
    v['HOOKED.h'] = hooked.h;
    return v;
  }

  function uploadVideo(r) {
    var v = r.video, gl = r.gl;
    if (!v || v.readyState < 2 || !v.videoWidth) return false;
    gl.bindTexture(gl.TEXTURE_2D, r.native.tex);
    // 与 hook.js 坐标系约定一致：t=0 是图像底部（OpenGL y 向上）
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGBA, gl.UNSIGNED_BYTE, v);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    return true;
  }

  function buildLuma(r) {
    var gl = r.gl, p = r.luma;
    gl.bindFramebuffer(gl.FRAMEBUFFER, r.lumaTex.fbo);
    gl.viewport(0, 0, r.srcW, r.srcH);
    gl.useProgram(p.prog);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, r.native.tex);
    gl.uniform1i(p.u.u_src, 0);
    gl.uniform2f(p.u.u_outSize, r.srcW, r.srcH);
    gl.bindVertexArray(r.vao);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  function getTarget(r, index, w, h) {
    var t = r.targets[index];
    if (!t || t.w !== w || t.h !== h) {
      delTarget(r.gl, t);
      t = makeTarget(r.gl, w, h, r.ifmt, r.type, r.midLinear);
      r.targets[index] = t;
    }
    return t;
  }

  /** 切换档位：编译整条链（一次性），AJN 档额外创建卷积渲染器。 */
  function setTier(r, tierId) {
    var gl = r.gl;
    // 释放上一档的 program / AJN
    r.programs.forEach(function (p) { gl.deleteProgram(p.prog); });
    r.programs = [];
    if (r.ajn) { try { SRAnimeJaNai.dispose(r.ajn); } catch (e) { } r.ajn = null; }

    var tier = null;
    for (var i = 0; i < r.manifest.tiers.length; i++) {
      if (r.manifest.tiers[i].id === tierId) { tier = r.manifest.tiers[i]; break; }
    }
    if (!tier) throw new Error('[SR/Renderer] 未知档位 ' + tierId);
    if (tier.type !== 'off' && !r.floatOK && tier.type !== 'spatial') {
      throw new Error('[SR/Renderer] 设备不支持浮点渲染目标，无法运行神经超分档');
    }
    r.tier = tier;
    r.chain = null;

    if (tier.type === 'ajn') {
      var sup = SRAnimeJaNai.isSupported(gl);
      if (!sup.ok) throw new Error('[SR/Renderer] AnimeJaNai 不可用: ' + sup.reason);
      r.ajn = SRAnimeJaNai.create(gl, r.ajnMeta);
      return { tier: tier, passes: 13, desc: 'AJN' };
    }
    if (!tier.chain.length) return { tier: tier, passes: 0, desc: 'off' };

    r.chain = SRHook.parseChain(tier.chain, r.shaders);
    r.programs = r.chain.passes.map(function (p) {
      // 编译失败会抛出 pass.file#index + desc，交给 controller 决定降档
      return SRHook.compileProgram(gl, p, r.chain.params);
    });
    return { tier: tier, passes: r.chain.passes.length, desc: r.chain.passes.map(function (p) { return p.desc; }) };
  }

  function blit(r, entry) {
    var gl = r.gl, p = r.blit;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, r.outW, r.outH);
    gl.useProgram(p.prog);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, entry.tex);
    gl.uniform1i(p.u.u_tex, 0);
    gl.uniform2f(p.u.u_outSize, r.outW, r.outH);
    gl.bindVertexArray(r.vao);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  function combineLuma(r, lumaEntry) {
    var gl = r.gl, p = r.combine;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, r.outW, r.outH);
    gl.useProgram(p.prog);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, r.native.tex);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, lumaEntry.tex);
    gl.uniform1i(p.u.u_orig, 0);
    gl.uniform1i(p.u.u_luma, 1);
    gl.uniform2f(p.u.u_outSize, r.outW, r.outH);
    gl.bindVertexArray(r.vao);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  /** 渲染一帧；返回生效指纹供 EnhancementController 确认「链真的跑了」。 */
  function renderFrame(r) {
    var gl = r.gl;
    if (!r.tier) return { ok: false, reason: '未设置档位' };
    if (!uploadVideo(r)) return { ok: false, reason: '视频帧未就绪' };

    gl.disable(gl.BLEND); gl.disable(gl.DEPTH_TEST); gl.disable(gl.SCISSOR_TEST);
    r.lastFrameOk = true;

    if (r.tier.type === 'off') {
      blit(r, r.native);
      return { ok: true, passes: 0, skipped: 0, descs: [], tier: 'off' };
    }
    if (r.tier.type === 'ajn') {
      var res = SRAnimeJaNai.render(r.ajn, {
        inputTex: r.native.tex, srcW: r.srcW, srcH: r.srcH,
        outFbo: null, outW: r.outW, outH: r.outH
      });
      return { ok: res.ok, passes: res.passes || 0, skipped: 0,
        descs: [res.desc || 'AJN'], reason: res.reason, tier: r.tier.id };
    }

    resetSlots(r);
    if (r.tier.luma) buildLuma(r);

    var descs = [], skipped = 0;
    for (var i = 0; i < r.chain.passes.length; i++) {
      var pass = r.chain.passes[i], prog = r.programs[i];
      var hookName = hookSlotOf(pass.hook);
      var hooked = r.slots[hookName] || r.native;
      var vars = buildVars(r, hooked, r.chain.params);

      // //!WHEN 为 0 必须整段跳过；这是「真超分」判据，不能省略
      if (pass.when && SRHook.evalRPN(pass.when, vars) <= 0) { skipped++; continue; }

      var ow = pass.width ? Math.round(SRHook.evalRPN(pass.width, vars)) : hooked.w;
      var oh = pass.height ? Math.round(SRHook.evalRPN(pass.height, vars)) : hooked.h;
      ow = SRSurface.even(ow); oh = SRSurface.even(oh);
      // 硬兜底：x2 Depth-to-Space 类 pass 的输出尺寸由官方 shader 固定（必须
      // 恰好 2× 输入，不能擅自缩小否则子像素重排会错）。当源分辨率过高时只能
      // 拒绝运行该档，交由 controller 降档，防止分配超大 RGBA16F 纹理导致 OOM。
      if (ow * oh > 7372800) {   // 2 × SRSurface.DEFAULTS.maxPixels
        throw new Error('渲染尺寸超限 ' + ow + '×' + oh);
      }
      var target = getTarget(r, i, ow, oh);

      gl.bindFramebuffer(gl.FRAMEBUFFER, target.fbo);
      gl.viewport(0, 0, ow, oh);
      gl.useProgram(prog.prog);
      gl.uniform2f(prog.u.outSize, ow, oh);
      gl.uniform2f(prog.u.targetSize, r.outW, r.outH);

      // 按 compileProgram 返回的 names 绑定（已排除 mpv 内置变量 target，
      // 不要改用 pass.refs，否则会多绑一个不存在的 sampler）
      for (var k = 0; k < prog.names.length; k++) {
        var n = prog.names[k];
        var e = (n === 'HOOKED') ? hooked : (r.slots[n] || r.native);
        gl.activeTexture(gl.TEXTURE0 + k);
        gl.bindTexture(gl.TEXTURE_2D, e.tex);
        gl.uniform1i(prog.u.samplers[n], k);
        gl.uniform2f(prog.u.sizes[n], e.w, e.h);
      }
      gl.bindVertexArray(r.vao);
      gl.drawArrays(gl.TRIANGLES, 0, 3);

      // 无 //!SAVE 时写回 hook 点纹理（mpv 语义）
      var outName = pass.save || hookName;
      r.slots[outName] = target;
      descs.push(pass.desc);
    }

    var finalEntry = r.slots[r.tier.luma ? 'LUMA' : 'MAIN'] || r.native;
    if (r.tier.luma) combineLuma(r, finalEntry);
    else blit(r, finalEntry);

    return {
      ok: true, passes: descs.length, skipped: skipped,
      descs: descs, tier: r.tier.id
    };
  }

  function dispose(r) {
    if (!r) return;
    var gl = r.gl;
    r.programs.forEach(function (p) { gl.deleteProgram(p.prog); });
    ['blit', 'luma', 'combine'].forEach(function (k) {
      if (r[k]) gl.deleteProgram(r[k].prog);
    });
    r.targets.forEach(function (t) { delTarget(gl, t); });
    delTarget(gl, r.native); delTarget(gl, r.lumaTex);
    if (r.vao) gl.deleteVertexArray(r.vao);
    if (r.ajn) { try { SRAnimeJaNai.dispose(r.ajn); } catch (e) { } }
  }

  global.SRRenderer = {
    create: create, setTier: setTier,
    renderFrame: renderFrame, dispose: dispose,
    // 内置 shader 源码对外暴露，供 tools/verify_shaders.py 做真实编译验证
    SOURCES: { blit: BLIT_FS, luma: LUMA_FS, combine: COMBINE_FS }
  };
})(window);
