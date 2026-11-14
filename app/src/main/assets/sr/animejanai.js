/* =============================================================================
 * animejanai.js — AnimeJaNai SuperUltraCompact (ONNX) → WebGL2 GLSL 卷积链
 * -----------------------------------------------------------------------------
 * 为什么单独一个模块
 *   其余 4 类模型是 mpv GLSL hook（由 hook.js 翻译），AnimeJaNai 是 ONNX 权重。
 *   按用户拍板「转写成 GLSL 统一管线」，这里把 2x_AnimeJaNai_Strong_V1_
 *   SuperUltraCompact_net_g_100000.onnx 的推理图手写成 WebGL2 pass 链，
 *   对外暴露与 hook.js 管线一致的「输入视频纹理 → 输出 FBO」接口。
 *
 * 导入方法（未来自主维护必读）
 *   1. 权重由仓库根目录 tools/gen_assets.py 从 ONNX 烘焙进 weights.js
 *      （window.__SR_AJN），层元信息在 manifest.js。不要手改 weights.js。
 *   2. 本文件只读 window.__SR_AJN，不依赖 Node / 网络 / 原生代码。
 *   3. 重新训练或换模型时，只要满足「Conv3x3 + PReLU + DepthToSpace(2) +
 *      nearest residual」结构，改 gen_assets.py 的层解析即可，本文件通常不用动。
 *
 * -----------------------------------------------------------------------------
 * ONNX 计算图（用 onnxruntime 真实解析，非猜测）
 *   input [1,3,H,W]
 *     └─ 10× Conv(3x3,pad=1,stride=1)：
 *          body.0  3→24   + PReLU(onnx::PRelu_74)
 *          body.2  24→24  + PReLU(75) ... body.16 24→24 + PReLU(82)
 *          body.18 24→12  （无激活）
 *     └─ DepthToSpace(blocksize=2, mode='CRD')  [1,12,H,W] → [1,3,2H,2W]
 *     └─ Resize(input, nearest, floor, scales=[1,1,2,2]) → [1,3,2H,2W]
 *     └─ Add → output（无 Clip/Sigmoid，输出可为负，显示阶段 clamp）
 *
 * -----------------------------------------------------------------------------
 * 三个「实测反推」的关键事实（写错任意一条就是假超分：能跑但输出全错）
 *
 *  (1) 权重 texel 一取四通道
 *      ONNX Conv 权重是 [outC,inC,3,3]。gen_assets.py 转置成 [inC,3,3,outC]
 *      再扁平化，索引 = weightOffset + (ic*9 + ky*3 + kx)*outC + oc。
 *      因为 outC 恒为 4 的倍数（24 / 12），固定 (ic,ky,kx) 时连续 4 个 oc
 *      正好落进同一个 RGBA32F texel。因此一次 texelFetch 就能更新一个
 *      「RGBA slot」的 4 个输出通道 —— 这是整条链能一次 MRT draw 完成的根基。
 *
 *  (2) y 轴方向：dy = 1 - ky
 *      视频纹理用 UNPACK_FLIP_Y_WEBGL=true 上传，OpenGL 的 y 向上；
 *      ONNX 的 row 0 是图像顶部（y 向下）。两者关系 ONNX_row = H-1-OGL_y。
 *      ONNX Conv 的 ky=0 取 oy-1（更靠上）→ OGL 里是 y+1。
 *      故采样偏移 dy = 1-ky，kx 不变（dx = kx-1）。边界越界取 0（pad=1 补零）。
 *
 *  (3) DepthToSpace 的 mode='CRD' 实测映射不是我最初以为的 c + C*(by*B+bx)
 *      用 ORT 中间张量 onnx::Add_67 反推（12 通道编码输入），真实映射是：
 *          in_ch = c*B*B + by*B + bx = c*4 + by*2 + bx
 *          （c=输出通道 0..2；by=Y%2；bx=X%2；Y 为 ONNX 行，Y=2H-1-OGL_y）
 *      即 c=0 → {0,1,2,3}，c=1 → {4,5,6,7}，c=2 → {8,9,10,11}。
 *      Resize 残差是标准 repeat 2×：src=(Y/2, X/2)，asymmetric+floor。
 *
 *  验证：用 onnxruntime 作 ground truth，逐层比对 10 层 Conv（maxdiff ≤1e-6），
 *  再用同样公式跑完整链路，最终 max abs diff = 5.07e-7（float32 舍入），
 *  allclose(2e-4)=True。改动本文件公式后请重跑 tools/verify_ajn.py。
 *
 * -----------------------------------------------------------------------------
 * 渲染资源与纹理单元占用（上层 renderer 分配 unit 时请避开 0/1/2）
 *   unit 0 : TEXTURE_2D      视频源（prep / assemble 残差）、upTex（blit）
 *   unit 1 : TEXTURE_2D      权重纹理 wtex（RGBA32F, 1024×12）
 *   unit 2 : TEXTURE_2D_ARRAY featA / featB 特征图（RGBA32F, W×H×6）
 *
 * 特征图布局
 *   featA / featB 是 TEXTURE_2D_ARRAY，depth=6（= 24 通道 / 4 的最大 slot 数）。
 *   L0 输入 inC=3，只用 layer0（RGBA 的 .rgb，.a 恒 0）。
 *   每层 Conv 用 MRT 一次写 outSlots 个 layer 到「另一个」array（ping-pong，
 *   避免同一纹理同时被采样和渲染的 feedback loop）。
 *   MAX_DRAW_BUFFERS 不足 6 时按 outN 自动分批（u_slotBase 偏移）。
 *
 * 接口
 *   SRAnimeJaNai.isSupported(gl)      -> {ok, reason, maxDrawBuffers, ...}
 *   SRAnimeJaNai.buildSources(mdb)    -> {vert, prep, conv, assemble, blit, outN}
 *                                       供离线 GLSL 编译验证用，不接触 GL
 *   SRAnimeJaNai.create(gl, meta)     -> renderer（上传权重、编译 4 个 program）
 *   SRAnimeJaNai.render(r, opts)      -> {ok, passes, desc}
 *       opts: {inputTex, srcW, srcH, outFbo|null, outW, outH}
 *   SRAnimeJaNai.dispose(r)           -> 释放 GL 资源
 * ============================================================================= */
(function (global) {
  'use strict';

  /* 全屏三角形，无顶点缓冲；与 hook.js 的 SRHook.VERT 完全相同。 */
  var VERT = [
    '#version 300 es',
    'void main() {',
    '  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);',
    '  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);',
    '}'
  ].join('\n');

  var PREC = [
    '#version 300 es',
    'precision highp float;',
    'precision highp int;',
    'precision highp sampler2D;',
    'precision highp sampler2DArray;'
  ].join('\n');

  /* ---- pass 1：视频源 → featA layer0（RGBA，.a=0，对应 inC=3） ----
   * 视频纹理 y 向上 = 图像在下，与 feat 的 OGL 坐标一致，无需翻转。 */
  function buildPrepFrag() {
    return PREC + '\n' + [
      'uniform sampler2D u_video;',
      'uniform vec2 u_srcSize;',
      'layout(location=0) out vec4 o0;',
      'void main(){',
      '  vec3 c = texture(u_video, gl_FragCoord.xy / u_srcSize).rgb;',
      '  o0 = vec4(c, 0.0);',
      '}'
    ].join('\n');
  }

  /* ---- pass 2..11：通用 Conv3x3 + PReLU，全部层参数走 uniform ----
   * outN 是编译期常量（= min(6, MAX_DRAW_BUFFERS)），决定 MRT 的 out 数量。
   * 内层循环用常量上界 24 / 3 / 3 + break，兼容对动态循环敏感的移动端驱动。 */
  function buildConvFrag(outN) {
    var decl = [], assign = [];
    for (var i = 0; i < outN; i++) {
      decl.push('layout(location=' + i + ') out vec4 o' + i + ';');
      assign.push('  o' + i + ' = acc[' + i + '];');
    }
    return PREC + '\n' + [
      'uniform sampler2DArray u_in;',
      'uniform sampler2D u_wtex;',
      'uniform int u_wtW;',
      'uniform int u_W, u_H;',
      'uniform int u_inC, u_outC;',
      'uniform int u_wOff, u_biasOff, u_slopeOff;',
      'uniform int u_outSlots, u_slotBase;',
      'uniform float u_prelu;',
      decl.join('\n'),
      // fi 必为 4 的倍数：取 (fi/2) 号 texel，一次拿到 4 个输出通道的权重
      'vec4 wfetch4(int fi){',
      '  int t = fi >> 2;',
      '  int y = t / u_wtW;',
      '  int x = t - y * u_wtW;',
      '  return texelFetch(u_wtex, ivec2(x, y), 0);',
      '}',
      'void main(){',
      '  ivec2 p = ivec2(gl_FragCoord.xy);',
      '  vec4 acc[6];',
      '  for(int s=0;s<6;s++) acc[s] = vec4(0.0);',
      '  for(int s=0;s<' + outN + ';s++){',
      '    int slot = u_slotBase + s;',
      '    if(slot < u_outSlots) acc[s] = wfetch4(u_biasOff + slot*4);',
      '  }',
      '  for(int ic=0;ic<24;ic++){',
      '    if(ic >= u_inC) break;',
      '    int isl = ic >> 2;',
      '    int icp = ic & 3;',
      '    for(int ky=0;ky<3;ky++){',
      '      int yy = p.y + (1 - ky);',              // dy = 1-ky（见文件头事实 2）
      '      if(yy < 0 || yy >= u_H) continue;',     // pad=1 补零
      '      for(int kx=0;kx<3;kx++){',
      '        int xx = p.x + (kx - 1);',
      '        if(xx < 0 || xx >= u_W) continue;',
      '        vec4 inv = texelFetch(u_in, ivec3(xx, yy, isl), 0);',
      '        float v = icp==0 ? inv.r : (icp==1 ? inv.g : (icp==2 ? inv.b : inv.a));',
      '        int wbase = u_wOff + (ic*9 + ky*3 + kx) * u_outC;',
      '        for(int s=0;s<' + outN + ';s++){',
      '          int slot = u_slotBase + s;',
      '          if(slot >= u_outSlots) break;',
      '          acc[s] += wfetch4(wbase + slot*4) * v;',
      '        }',
      '      }',
      '    }',
      '  }',
      '  if(u_prelu > 0.5){',
      '    for(int s=0;s<' + outN + ';s++){',
      '      int slot = u_slotBase + s;',
      '      if(slot >= u_outSlots) break;',
      '      vec4 sl = wfetch4(u_slopeOff + slot*4);',
      // step(0,acc)=1 表示 acc>=0 → 乘 1；否则乘 slope
      '      acc[s] = acc[s] * mix(sl, vec4(1.0), step(vec4(0.0), acc[s]));',
      '    }',
      '  }',
      assign.join('\n'),
      '}'
    ].join('\n');
  }

  /* ---- pass 12：DepthToSpace(CRD) + nearest 残差 → upTex [2W,2H] ----
   * 见文件头事实 3：in_ch = c*4 + by*2 + bx，且 Y = 2H-1-OGL_y。 */
  function buildAssembleFrag() {
    return PREC + '\n' + [
      'uniform sampler2DArray u_feat;',
      'uniform sampler2D u_video;',
      'uniform vec2 u_srcSize;',
      'uniform int u_W, u_H;',
      'layout(location=0) out vec4 o0;',
      'float fetchCh(int ich, ivec2 p){',
      '  int sl = ich >> 2;',
      '  int cp = ich & 3;',
      '  vec4 v = texelFetch(u_feat, ivec3(p.x, p.y, sl), 0);',
      '  return cp==0 ? v.r : (cp==1 ? v.g : (cp==2 ? v.b : v.a));',
      '}',
      'void main(){',
      '  ivec2 p = ivec2(gl_FragCoord.xy);',          // 输出尺寸 2W×2H
      '  int X = p.x;',
      '  int Y = 2*u_H - 1 - p.y;',                   // OGL y → ONNX 行
      '  int by = Y - (Y/2)*2;',                      // Y % 2
      '  int bx = X - (X/2)*2;',                      // X % 2
      '  int sRow = Y/2;',
      '  int sCol = X/2;',
      '  int sy = u_H - 1 - sRow;',                   // 回到 OGL 源行
      '  ivec2 sp = ivec2(sCol, sy);',
      '  vec3 base = texture(u_video, (vec2(sp) + 0.5) / u_srcSize).rgb;',
      '  float r = fetchCh(0*4 + by*2 + bx, sp);',
      '  float g = fetchCh(1*4 + by*2 + bx, sp);',
      '  float b = fetchCh(2*4 + by*2 + bx, sp);',
      '  vec3 v = vec3(r, g, b) + base;',             // Add( DepthToSpace, Resize )
      '  o0 = vec4(clamp(v, 0.0, 1.0), 1.0);',       // 显示阶段 clamp（图无 Clip 算子）
      '}'
    ].join('\n');
  }

  /* ---- pass 13：upTex → 目标 FBO（FixedSurfacePolicy 锁定的渲染尺寸） ---- */
  function buildBlitFrag() {
    return PREC + '\n' + [
      'uniform sampler2D u_tex;',
      'uniform vec2 u_outSize;',
      'layout(location=0) out vec4 o0;',
      'void main(){',
      '  o0 = texture(u_tex, gl_FragCoord.xy / u_outSize);',
      '}'
    ].join('\n');
  }

  /** 只生成源码、不接触 GL；离线 GLSL 编译验证工具直接调用它。 */
  function buildSources(maxDrawBuffers) {
    var n = Math.max(1, Math.min(6, (maxDrawBuffers | 0) || 1));
    return {
      vert: VERT,
      prep: buildPrepFrag(),
      conv: buildConvFrag(n),
      assemble: buildAssembleFrag(),
      blit: buildBlitFrag(),
      outN: n
    };
  }

  /** 能力检测：不满足时上层 EnhancementController 应跳过该档并降档。 */
  function isSupported(gl) {
    if (!gl) return { ok: false, reason: '无 WebGL2 上下文' };
    if (!gl.getExtension('EXT_color_buffer_float')) {
      return { ok: false, reason: '缺少 EXT_color_buffer_float，无法渲染到 RGBA32F 特征纹理' };
    }
    var layers = gl.getParameter(gl.MAX_ARRAY_TEXTURE_LAYERS);
    if (layers < 6) return { ok: false, reason: 'MAX_ARRAY_TEXTURE_LAYERS < 6（实测 ' + layers + '）' };
    var mdb = gl.getParameter(gl.MAX_DRAW_BUFFERS);
    if (mdb < 1) return { ok: false, reason: 'MAX_DRAW_BUFFERS < 1' };
    var mts = gl.getParameter(gl.MAX_TEXTURE_SIZE);
    return { ok: true, maxDrawBuffers: mdb, maxArrayLayers: layers, maxTextureSize: mts };
  }

  function compile(gl, type, src, tag) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      var log = gl.getShaderInfoLog(s);
      gl.deleteShader(s);
      throw new Error('[SR/AJN] ' + tag + ' 编译失败: ' + log
        + '\n--- source ---\n' + src.split('\n').slice(0, 80).join('\n'));
    }
    return s;
  }

  function link(gl, fs, uniforms, tag) {
    var vs = compile(gl, gl.VERTEX_SHADER, VERT, tag + '/vert');
    var f = compile(gl, gl.FRAGMENT_SHADER, fs, tag + '/frag');
    var p = gl.createProgram();
    gl.attachShader(p, vs); gl.attachShader(p, f);
    gl.linkProgram(p);
    gl.deleteShader(vs); gl.deleteShader(f);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      var log = gl.getProgramInfoLog(p);
      gl.deleteProgram(p);
      throw new Error('[SR/AJN] ' + tag + ' link 失败: ' + log);
    }
    var u = {};
    for (var i = 0; i < uniforms.length; i++) u[uniforms[i]] = gl.getUniformLocation(p, uniforms[i]);
    return { prog: p, u: u };
  }

  /** base64 → Float32Array → RGBA32F 纹理。
   *  Android 全系小端，ONNX 权重由 gen_assets.py 以本机 float32 字节序写出，
   *  故 new Float32Array(u8.buffer) 直接可用；若将来跑到 exotic 大端设备需做字节交换。 */
  function uploadWeights(gl, meta) {
    var bin = atob(meta.weightsB64);
    var u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    var f32 = new Float32Array(u8.buffer, 0, bin.length / 4);
    var t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F,
      meta.weightTexWidth, meta.weightTexHeight, 0, gl.RGBA, gl.FLOAT, f32);
    gl.bindTexture(gl.TEXTURE_2D, null);
    return t;
  }

  function makeArray(gl, w, h, depth) {
    var t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D_ARRAY, t);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage3D(gl.TEXTURE_2D_ARRAY, 0, gl.RGBA32F, w, h, depth, 0, gl.RGBA, gl.FLOAT, null);
    gl.bindTexture(gl.TEXTURE_2D_ARRAY, null);
    return t;
  }

  function make2D(gl, w, h) {
    var t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.bindTexture(gl.TEXTURE_2D, null);
    return t;
  }

  /** 源尺寸变化时重建中间纹理。FixedSurfacePolicy 保证播放期间尺寸锁定，
   *  所以正常播放只会建一次；切集/换分辨率才走重建。 */
  function ensureTargets(r, w, h) {
    var gl = r.gl;
    if (r.curW === w && r.curH === h && r.featA) return;
    ['featA', 'featB', 'upTex'].forEach(function (k) { if (r[k]) gl.deleteTexture(r[k]); });
    r.featA = makeArray(gl, w, h, 6);
    r.featB = makeArray(gl, w, h, 6);
    r.upTex = make2D(gl, 2 * w, 2 * h);
    r.curW = w; r.curH = h;
  }

  /** 把 FBO 的所有 color attachment 全部 detach，避免上一 pass 的 array layer
   *  与下一 pass 不同尺寸的 2D 纹理混挂导致 FRAMEBUFFER_INCOMPLETE。 */
  function resetAttachments(gl, n) {
    for (var i = 0; i < n; i++) {
      // 用 framebufferTexture2D 传 null 清除 attachment point 上的任意纹理
      // （前一 pass 可能挂的是 2D upTex，也可能是 array layer，detach 语义统一）
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, null, 0);
    }
  }

  function create(gl, meta) {
    var sup = isSupported(gl);
    if (!sup.ok) throw new Error('[SR/AJN] ' + sup.reason);
    if (!meta || !meta.layers || !meta.weightsB64) throw new Error('[SR/AJN] 缺少 window.__SR_AJN');
    var src = buildSources(sup.maxDrawBuffers);
    var r = {
      gl: gl, meta: meta, src: src, outN: src.outN,
      wtW: meta.weightTexWidth, layers: meta.layers,
      maxTex: sup.maxTextureSize,
      curW: 0, curH: 0,
      progs: {
        prep: link(gl, src.prep, ['u_video', 'u_srcSize'], 'prep'),
        conv: link(gl, src.conv, ['u_in', 'u_wtex', 'u_wtW', 'u_W', 'u_H',
          'u_inC', 'u_outC', 'u_wOff', 'u_biasOff', 'u_slopeOff',
          'u_outSlots', 'u_slotBase', 'u_prelu'], 'conv'),
        assemble: link(gl, src.assemble, ['u_feat', 'u_video', 'u_srcSize', 'u_W', 'u_H'], 'assemble'),
        blit: link(gl, src.blit, ['u_tex', 'u_outSize'], 'blit')
      },
      wtex: null, featA: null, featB: null, upTex: null,
      fbo: gl.createFramebuffer(), vao: gl.createVertexArray()
    };
    r.wtex = uploadWeights(gl, meta);   // 权重只上传一次，与源尺寸无关
    return r;
  }

  function draw(gl) { gl.drawArrays(gl.TRIANGLES, 0, 3); }

  /** 跑完整链路，结果写入 opts.outFbo（null = 默认 framebuffer）。 */
  function render(r, opts) {
    var gl = r.gl;
    var W = opts.srcW | 0, H = opts.srcH | 0;
    if (W < 1 || H < 1 || !opts.inputTex) return { ok: false, reason: '非法输入尺寸/纹理' };
    if (2 * W > r.maxTex || 2 * H > r.maxTex) {
      return { ok: false, reason: '源尺寸超出 MAX_TEXTURE_SIZE：' + W + 'x' + H };
    }
    ensureTargets(r, W, H);

    var P = r.progs, u, i, li, L, base;
    gl.bindVertexArray(r.vao);
    gl.bindFramebuffer(gl.FRAMEBUFFER, r.fbo);
    gl.disable(gl.BLEND); gl.disable(gl.DEPTH_TEST); gl.disable(gl.SCISSOR_TEST);
    gl.viewport(0, 0, W, H);
    resetAttachments(gl, r.outN);

    // ---- pass 1: prep ----
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, opts.inputTex);
    gl.framebufferTextureLayer(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, r.featA, 0, 0);
    gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
    u = P.prep.u; gl.useProgram(P.prep.prog);
    gl.uniform1i(u.u_video, 0); gl.uniform2f(u.u_srcSize, W, H);
    draw(gl);

    // ---- pass 2..11: 10 层 Conv，ping-pong featA/featB ----
    var srcArr = r.featA, dstArr = r.featB;
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, r.wtex);
    u = P.conv.u; gl.useProgram(P.conv.prog);
    gl.uniform1i(u.u_in, 2);          // sampler2DArray 在 unit 2
    gl.uniform1i(u.u_wtex, 1);
    gl.uniform1i(u.u_wtW, r.wtW);
    gl.uniform1i(u.u_W, W); gl.uniform1i(u.u_H, H);
    for (li = 0; li < r.layers.length; li++) {
      L = r.layers[li];
      var outSlots = (L.outC + 3) >> 2;
      gl.uniform1i(u.u_inC, L.inC);
      gl.uniform1i(u.u_outC, L.outC);
      gl.uniform1i(u.u_wOff, L.weightOffset);
      gl.uniform1i(u.u_biasOff, L.biasOffset);
      gl.uniform1i(u.u_slopeOff, L.slopeOffset);
      gl.uniform1f(u.u_prelu, L.prelu ? 1.0 : 0.0);
      gl.uniform1i(u.u_outSlots, outSlots);
      for (base = 0; base < outSlots; base += r.outN) {
        var bufs = [];
        for (i = 0; i < r.outN; i++) {
          var layer = base + i;
          if (layer < outSlots) {
            gl.framebufferTextureLayer(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, dstArr, 0, layer);
            bufs.push(gl.COLOR_ATTACHMENT0 + i);
          } else {
            gl.framebufferTextureLayer(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, null, 0, 0);
            bufs.push(gl.NONE);
          }
        }
        gl.drawBuffers(bufs);
        gl.uniform1i(u.u_slotBase, base);
        gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D_ARRAY, srcArr);
        draw(gl);
      }
      var tmp = srcArr; srcArr = dstArr; dstArr = tmp;   // ping-pong
    }

    // ---- pass 12: assemble → upTex (2W×2H) ----
    resetAttachments(gl, r.outN);
    gl.viewport(0, 0, 2 * W, 2 * H);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, r.upTex, 0);
    var one = [gl.COLOR_ATTACHMENT0];
    for (i = 1; i < r.outN; i++) one.push(gl.NONE);
    gl.drawBuffers(one);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, opts.inputTex);
    gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D_ARRAY, srcArr); // 最后一层
    u = P.assemble.u; gl.useProgram(P.assemble.prog);
    gl.uniform1i(u.u_feat, 2); gl.uniform1i(u.u_video, 0);
    gl.uniform2f(u.u_srcSize, W, H);
    gl.uniform1i(u.u_W, W); gl.uniform1i(u.u_H, H);
    draw(gl);

    // ---- pass 13: blit → 目标 FBO（尺寸由 FixedSurfacePolicy 锁定） ----
    gl.bindFramebuffer(gl.FRAMEBUFFER, opts.outFbo || null);
    gl.viewport(0, 0, opts.outW | 0, opts.outH | 0);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, r.upTex);
    u = P.blit.u; gl.useProgram(P.blit.prog);
    gl.uniform1i(u.u_tex, 0);
    gl.uniform2f(u.u_outSize, opts.outW | 0, opts.outH | 0);
    draw(gl);

    // 生效指纹：上层用它确认「链真的跑了」，而不是只确认 shader 编译成功
    return {
      ok: true,
      passes: 2 + r.layers.length + 1,   // prep + conv×10 + assemble + blit
      desc: 'AnimeJaNai SuperUltraCompact (10 Conv3x3 + CRD + residual)'
    };
  }

  function dispose(r) {
    if (!r) return;
    var gl = r.gl;
    ['prep', 'conv', 'assemble', 'blit'].forEach(function (k) {
      if (r.progs && r.progs[k]) gl.deleteProgram(r.progs[k].prog);
    });
    ['wtex', 'featA', 'featB', 'upTex'].forEach(function (k) { if (r[k]) gl.deleteTexture(r[k]); });
    if (r.fbo) gl.deleteFramebuffer(r.fbo);
    if (r.vao) gl.deleteVertexArray(r.vao);
  }

  global.SRAnimeJaNai = {
    isSupported: isSupported,
    buildSources: buildSources,
    create: create,
    render: render,
    dispose: dispose
  };
})(window);
