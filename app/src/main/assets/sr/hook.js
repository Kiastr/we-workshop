/* =============================================================================
 * hook.js — mpv GLSL hook → WebGL2 (GLSL ES 3.00) 翻译运行时
 * -----------------------------------------------------------------------------
 * 为什么需要它
 *   Anime4K / FSR / FSRCNNX 官方发布的是 mpv 的 "//!HOOK" 语法，不是标准 GLSL：
 *   每个 .glsl 文件里串了多个 pass，靠 //! 指令声明绑定纹理、输出尺寸、
 *   执行条件（WHEN）与中间纹理名（SAVE/BIND）。mpv 在运行时把它们翻译成
 *   一组 FBO ping-pong。WebView 里没有 mpv，只能自己实现这套语义。
 *
 * 导入方法（未来自主维护必读）
 *   1. shader 原始文件来自各上游仓库，清单与 License 在 manifest.js。
 *   2. 由仓库根目录 tools/gen_assets.py 把 GLSL 文本烘进 shaders.js，
 *      把 AnimeJaNai ONNX 权重烘进 weights.js。不要手改这两个文件。
 *   3. 新增 shader 只需：把 .glsl 放进 tools/sources/ 对应子目录 → 在
 *      gen_assets.py 的 FILES 里登记 → 运行脚本 → manifest.js 的 tier.chain
 *      里引用文件名。运行时会自动按 //! 指令解析，无需改本文件。
 *
 * 语义映射（与 mpv 手册对齐，逐条经过真实 shader 反查）
 *   //!DESC         -> pass.desc，作为「链真的跑了」的生效指纹
 *   //!HOOK X       -> pass.hook；HOOKED 别名指向该 hook 点纹理
 *                     MAIN / PREKERNEL / NATIVE -> MAIN 槽；LUMA -> LUMA 槽
 *   //!BIND X       -> 绑定纹理名；HOOKED 是别名；其余名字查纹理池
 *   //!SAVE X       -> 输出写入纹理池 X（SAVE MAIN 会替换主纹理，尺寸可变）
 *                     无 SAVE 的 pass 输出写回主纹理 / 或 LUMA_OUT
 *   //!WIDTH/HEIGHT -> RPN 表达式，求值为输出 FBO 尺寸；缺省 = HOOKED 尺寸
 *   //!COMPONENTS   -> mpv 用来选纹理格式；这里统一 RGBA16F，shader 用 .x 取单通道
 *   //!WHEN         -> RPN 表达式，求值为 0 则整段跳过（必须保留，否则是假超分）
 *   //!PARAM/!TYPE  -> mpv 用户参数扩展，float 注入 const，DEFINE 注入 #define
 *
 * 注入的宏（每个被引用的纹理 NAME 各一套）
 *   NAME_tex(p)     -> texture(s_NAME, p)
 *   NAME_texOff(o)  -> texture(s_NAME, v_uv + o / u_size_NAME)   // o 是像素偏移
 *   NAME_pos        -> v_uv = gl_FragCoord.xy / u_outSize        // 归一化输出坐标
 *   NAME_pt         -> 1 / u_size_NAME
 *   NAME_size       -> u_size_NAME
 *   HOOKED_gather   -> 故意不定义！FSR 自带 #if defined(HOOKED_gather) 降级分支，
 *                     WebGL2 无 textureGather，不定义即自动走 texture() 回退路径。
 *   target_size     -> u_targetSize（FSR EASU 用它判断 6.25x 阈值）
 *
 * 坐标系说明（很重要）
 *   视频纹理以 UNPACK_FLIP_Y_WEBGL=true 上传，t=0 是图像底部，与 OpenGL 一致。
 *   全链路统一用 gl_FragCoord（y 自下而上）除以输出尺寸得到 v_uv，中间 FBO
 *   与最终 canvas 方向自洽，不需要二次翻转。AnimeJaNai 的 ONNX 链路在
 *   animejanai.js 里单独做了 y 翻转以对齐 ONNX 的「行 0 = 图像顶部」约定。
 * ============================================================================= */
(function (global) {
  'use strict';

  /** RPN（逆波兰）求值器。
   *  mpv 的 //!WHEN / WIDTH / HEIGHT 都是 RPN。运算符：
   *    算术 + - * /、比较 < > <= >= = !=、逻辑非 !（一元）、min/max（二元）
   *  注意 `*` 同时承担「逻辑与」（两个 0/1 相乘），`+` 同时承担「逻辑或」
   *  （0/1 相加，最终按 >0 判定），所以统一用数值运算即可，不需要布尔类型。
   *  变量形如 OUTPUT.w、MAIN.h、conv2d_tf.w、SHARP（//!PARAM 名）。
   */
  function evalRPN(expr, vars) {
    if (!expr) return 0;
    var toks = String(expr).trim().split(/\s+/);
    var st = [];
    var num = /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/;
    for (var i = 0; i < toks.length; i++) {
      var t = toks[i];
      if (num.test(t)) { st.push(parseFloat(t)); continue; }
      switch (t) {
        case '+': { var b = st.pop() || 0, a = st.pop() || 0; st.push(a + b); break; }
        case '-': { var b = st.pop() || 0, a = st.pop() || 0; st.push(a - b); break; }
        case '*': { var b = st.pop() || 0, a = st.pop() || 0; st.push(a * b); break; }
        case '/': { var b = st.pop() || 0, a = st.pop() || 0; st.push(b === 0 ? 0 : a / b); break; }
        case '<':  { var b = st.pop() || 0, a = st.pop() || 0; st.push(a < b ? 1 : 0); break; }
        case '>':  { var b = st.pop() || 0, a = st.pop() || 0; st.push(a > b ? 1 : 0); break; }
        case '<=': { var b = st.pop() || 0, a = st.pop() || 0; st.push(a <= b ? 1 : 0); break; }
        case '>=': { var b = st.pop() || 0, a = st.pop() || 0; st.push(a >= b ? 1 : 0); break; }
        case '=':  { var b = st.pop() || 0, a = st.pop() || 0; st.push(Math.abs(a - b) < 1e-6 ? 1 : 0); break; }
        case '!=': { var b = st.pop() || 0, a = st.pop() || 0; st.push(Math.abs(a - b) >= 1e-6 ? 1 : 0); break; }
        case '!':  { var a = st.pop() || 0; st.push(a ? 0 : 1); break; }
        case 'min': { var b = st.pop() || 0, a = st.pop() || 0; st.push(Math.min(a, b)); break; }
        case 'max': { var b = st.pop() || 0, a = st.pop() || 0; st.push(Math.max(a, b)); break; }
        default: {
          // 变量：优先精确匹配，再按 NAME.w / NAME.h 拆分
          var v;
          if (Object.prototype.hasOwnProperty.call(vars, t)) v = vars[t];
          else {
            var m = /^(.*)\.(w|h|width|height)$/.exec(t);
            if (m) {
              var base = m[1], dim = m[2];
              var keyW = base + '.w', keyH = base + '.h';
              v = (dim === 'w' || dim === 'width')
                ? (vars[keyW] !== undefined ? vars[keyW] : 0)
                : (vars[keyH] !== undefined ? vars[keyH] : 0);
            } else v = 0;
          }
          st.push(Number(v) || 0);
        }
      }
    }
    return st.length ? (st[st.length - 1] || 0) : 0;
  }

  /** 解析 //!PARAM 块（FSR 用）。
   *  形态：//!PARAM NAME / //!TYPE float|DEFINE / //!MINIMUM / //!MAXIMUM / 裸值行
   *  返回 [{name,type,value}]，并把整块从源码里剥离，避免干扰 pass 切分。
   */
  function extractParams(text) {
    var params = [];
    var lines = text.split('\n');
    var out = [];
    for (var i = 0; i < lines.length; i++) {
      var ln = lines[i];
      var pm = /^!\/\/PARAM\s+(\S+)/.exec(ln.trim());
      // 兼容两种写法：源码里是 "//!PARAM"，trim 后仍是 "//!PARAM"
      pm = /^\/\/!PARAM\s+(\S+)/.exec(ln.trim());
      if (!pm) { out.push(ln); continue; }
      var name = pm[1], type = 'float', value = null;
      var j = i + 1;
      while (j < lines.length) {
        var s = lines[j].trim();
        var tm = /^\/\/!TYPE\s+(\S+)/.exec(s);
        if (tm) { type = tm[1]; j++; continue; }
        if (/^\/\/!(MINIMUM|MAXIMUM|DEFAULT)/.test(s)) { j++; continue; }
        if (s === '') { j++; continue; }
        if (/^-?\d+(\.\d+)?$/.test(s)) { value = parseFloat(s); j++; break; }
        break; // 不是值行，PARAM 块结束
      }
      params.push({ name: name, type: type, value: value === null ? 0 : value });
      // 跳过已消费的行，但保留未消费部分
      i = j - 1;
      out.push(''); // 占位，保持行结构
    }
    return { params: params, rest: out.join('\n') };
  }

  /** 把一个 .glsl 文件切成多个 pass。
   *  关键难点：上游两种指令顺序都存在——
   *    Anime4K/FSR: //!DESC 在 //!HOOK 之前，源码块在最后
   *    FSRCNNX:     //!HOOK 在 //!DESC 之前，源码块仍在最后
   *  共同点是「每个 pass = 一个连续 //! 指令块 + 紧跟的一个连续源码块」。
   *  因此按「指令块 / 源码块」交替切分，而不是按单一指令行切分。
   */
  function parseShaderFile(name, text) {
    var p = extractParams(text);
    var params = p.params;
    var lines = p.rest.split('\n');

    // 丢弃第一个 //! 指令之前的全部内容（license 注释等 preamble）
    var first = -1;
    for (var k = 0; k < lines.length; k++) {
      if (/^\s*\/\/!/.test(lines[k])) { first = k; break; }
    }
    if (first < 0) return { params: params, passes: [] };
    lines = lines.slice(first);

    // 交替分组：type 'd' = 指令块，'c' = 源码块
    var groups = [];
    var cur = null;
    for (var i = 0; i < lines.length; i++) {
      var isDir = /^\s*\/\/!/.test(lines[i]);
      var type = isDir ? 'd' : 'c';
      if (!cur || cur.type !== type) {
        cur = { type: type, lines: [] };
        groups.push(cur);
      }
      cur.lines.push(lines[i]);
    }

    var passes = [];
    var pendingDirs = [];
    for (var g = 0; g < groups.length; g++) {
      var grp = groups[g];
      if (grp.type === 'd') {
        // 连续的指令块合并（有的文件把 WHEN 单独隔了一行空行）
        for (var q = 0; q < grp.lines.length; q++) {
          var s = grp.lines[q].trim();
          if (/^\/\/!/.test(s)) pendingDirs.push(s);
        }
        continue;
      }
      // 源码块：若只有空行/纯注释，不算一个 pass
      var code = grp.lines.join('\n');
      var meaningful = code.split('\n').filter(function (L) {
        var t = L.trim();
        return t && !/^\/\//.test(t) && !/^\/\*/.test(t) && !/^\*/.test(t) && t !== '*/';
      }).join('\n').trim();
      if (!meaningful) continue;
      if (!pendingDirs.length) continue;
      var pass = buildPass(name, pendingDirs, code, passes.length);
      if (pass) passes.push(pass);
      pendingDirs = [];
    }
    return { params: params, passes: passes };
  }

  function buildPass(file, dirs, code, index) {
    var pass = {
      file: file, index: index,
      desc: '', hook: 'MAIN', binds: [], save: null,
      width: null, height: null, when: null, components: 4,
      code: code
    };
    for (var i = 0; i < dirs.length; i++) {
      var s = dirs[i];
      var m = /^\/\/!(\w+)\s*(.*)$/.exec(s);
      if (!m) continue;
      var key = m[1].toUpperCase(), val = m[2].trim();
      switch (key) {
        case 'DESC':  pass.desc = val; break;
        case 'HOOK':  pass.hook = val.toUpperCase(); break;
        case 'BIND':  pass.binds.push(val); break;
        case 'SAVE':  pass.save = val; break;
        case 'WIDTH': pass.width = val; break;
        case 'HEIGHT':pass.height = val; break;
        case 'WHEN':  pass.when = val; break;
        case 'COMPONENTS': pass.components = parseInt(val, 10) || 4; break;
        // PARAM/TYPE/MINIMUM/MAXIMUM 已在 extractParams 里处理
      }
    }
    if (!pass.code || !/\bhook\s*\(/.test(pass.code)) return null;
    // HOOKED 是别名，不需要显式出现在 binds 里也能被引用；
    // 收集源码中所有 <NAME>_<suffix> 标识符，作为需要注入宏的纹理名集合
    var refs = {};
    var re = /\b([A-Za-z_][A-Za-z0-9_]*)_(texOff|tex|pos|pt|size|gather|raw|mul|map)\b/g;
    var mm;
    while ((mm = re.exec(pass.code))) refs[mm[1]] = true;
    pass.binds.forEach(function (b) { refs[b] = true; });
    // 移除 gather 引用：不注入 HOOKED_gather 宏，让 FSR 走 #else 回退分支
    Object.keys(refs).forEach(function (r) { /* 保留集合，宏生成时跳过 gather */ });
    pass.refs = Object.keys(refs);
    return pass;
  }

  /** 拼接一条 tier 链的所有 pass（保持文件顺序，并合并各自 PARAM）。 */
  function parseChain(fileNames, shaderDB) {
    var passes = [], params = [], files = {};
    for (var i = 0; i < fileNames.length; i++) {
      var fn = fileNames[i];
      var src = shaderDB[fn];
      if (!src) { console.warn('[SR] 缺少 shader:', fn); continue; }
      var r = parseShaderFile(fn, src);
      files[fn] = r.passes.length;
      r.params.forEach(function (p) { params.push(p); });
      r.passes.forEach(function (p) { passes.push(p); });
    }
    return { passes: passes, params: params, files: files };
  }

  /** 生成 WebGL2 (GLSL ES 3.00) 顶点着色器：一个覆盖全屏的三角形。 */
  var VERT = [
    '#version 300 es',
    'void main() {',
    '  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);',
    '  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);',
    '}'
  ].join('\n');

  /** 为一个 pass 生成 fragment shader 源码。
   *  samplerNames: 该纹理名集合（refs），用于声明 uniform sampler2D。
   *  注意：所有纹理尺寸用 uniform 传入，因此程序只编译一次，与运行时尺寸无关。
   */
  function buildFragment(pass, params) {
    var head = [];
    head.push('#version 300 es');
    head.push('precision highp float;');
    head.push('precision highp int;');
    head.push('precision highp sampler2D;');
    head.push('uniform vec2 u_outSize;');
    head.push('uniform vec2 u_targetSize;');

    // //!PARAM 注入：float -> const，DEFINE -> #define（供 #if 使用）
    (params || []).forEach(function (p) {
      if (p.type === 'DEFINE') head.push('#define ' + p.name + ' ' + p.value);
      else head.push('const float ' + p.name + ' = ' + p.value + ';');
    });

    var names = [];
    pass.refs.forEach(function (r) {
      // HOOKED 是别名，渲染器绑定时会把它解析成真实纹理；
      // 但 shader 里用的是 HOOKED_xxx，所以仍要声明 HOOKED 这套宏。
      //
      // `target` 必须排除：refs 正则会把 mpv 内置变量 `target_size`（输出尺寸）
      // 里的 "target" 误收成纹理名。若保留，会注入
      // "#define target_size u_size_target"，与下面映射 u_targetSize 的全局定义
      // 重复（mesa 实测报 Redefinition of macro target_size），且语义也不对。
      // FSR EASU 用它判断 6.25x 阈值：const bool deea = (target_size.x *
      // target_size.y > HOOKED_size.x * HOOKED_size.y * 6.25)。
      if (r === 'target') return;
      if (names.indexOf(r) < 0) names.push(r);
    });
    names.forEach(function (n) {
      head.push('uniform sampler2D s_' + n + ';');
      head.push('uniform vec2 u_size_' + n + ';');
      head.push('#define ' + n + '_tex(p) texture(s_' + n + ', (p))');
      // 与 mpv 的 "HOOKED_pos + HOOKED_pt * offset" 对齐：用乘法而非除法。
      // 除法写法 v_uv + (o)/size 在实参是标量（FSRCNNX 的 MODEL1_texOff(0)）
      // 时变成 int / vec2，GLSL 无法编译；vec2(o) 把标量/vec2 实参统一成 vec2，
      // 再乘 1/size，语义仍是「o 个像素的归一化偏移」。
      head.push('#define ' + n + '_texOff(o) texture(s_' + n + ', v_uv + vec2(o) * (vec2(1.0) / u_size_' + n + '))');
      head.push('#define ' + n + '_pos v_uv');
      head.push('#define ' + n + '_pt (vec2(1.0) / u_size_' + n + ')');
      head.push('#define ' + n + '_size u_size_' + n);
    });

    head.push('#define v_uv (gl_FragCoord.xy / u_outSize)');
    head.push('#undef target_size');
    head.push('#define target_size u_targetSize');
    // 故意不定义任何 *_gather：WebGL2 (GLSL ES 3.00) 无 textureGather，
    // FSR 的 #if defined(HOOKED_gather) && (__VERSION__>=400||...) 会为假，
    // 自动落到用 HOOKED_tex 的兼容性回退分支，无需改动官方 shader。
    head.push('out vec4 outColor;');

    // 用户源码里可能带 precision 声明（FSR 在 #if FP16 分支里写 mediump），
    // 统一删掉以保证 CNN 权重精度；其余代码原样保留。
    var code = pass.code.split('\n').filter(function (L) {
      return !/^\s*precision\s+(lowp|mediump|highp)\s+\w+\s*;/.test(L);
    }).join('\n');
    // mpv 在编译期把 target_size / HOOKED_size 当常量，所以官方 shader 里会出现
    // "const bool deea = (target_size.x * ... > ...)" 这类声明。WebGL2 里这些尺寸
    // 是 uniform（运行时值），const 初始化不是常量表达式（mesa 实测报
    // "initializer of const variable must be a constant expression"）。
    // 统一去掉用户源码里的 const 关键字，降级为普通局部变量，语义不变。
    // 已核实全部 17 个 shader 只有这一处 const，且没有数组声明依赖 const 变量做长度。
    code = code.replace(/\bconst\b/g, '');

    head.push(code);
    head.push('void main() { outColor = hook(); }');
    return { source: head.join('\n'), names: names };
  }

  /** 编译并链接一个 program，返回带 uniform location 缓存的对象。 */
  function compileProgram(gl, pass, params) {
    var built = buildFragment(pass, params);
    function sh(type, src) {
      var s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        var log = gl.getShaderInfoLog(s);
        gl.deleteShader(s);
        throw new Error('shader 编译失败 [' + pass.file + ' #' + pass.index + ' '
          + pass.desc + ']: ' + log + '\n--- source head ---\n'
          + src.split('\n').slice(0, 40).join('\n'));
      }
      return s;
    }
    var vs = sh(gl.VERTEX_SHADER, VERT);
    var fs = sh(gl.FRAGMENT_SHADER, built.source);
    var prog = gl.createProgram();
    gl.attachShader(prog, vs); gl.attachShader(prog, fs);
    gl.linkProgram(prog);
    gl.deleteShader(vs); gl.deleteShader(fs);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      var log = gl.getProgramInfoLog(prog);
      gl.deleteProgram(prog);
      throw new Error('program link 失败 [' + pass.file + ']: ' + log);
    }
    var u = {
      outSize: gl.getUniformLocation(prog, 'u_outSize'),
      targetSize: gl.getUniformLocation(prog, 'u_targetSize'),
      samplers: {}, sizes: {}
    };
    built.names.forEach(function (n) {
      u.samplers[n] = gl.getUniformLocation(prog, 's_' + n);
      u.sizes[n] = gl.getUniformLocation(prog, 'u_size_' + n);
    });
    return { prog: prog, u: u, names: built.names, source: built.source };
  }

  global.SRHook = {
    evalRPN: evalRPN,
    parseShaderFile: parseShaderFile,
    parseChain: parseChain,
    compileProgram: compileProgram,
    buildFragment: buildFragment,
    VERT: VERT
  };
})(window);
