#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 mesa EGL/GLES3 真实编译 + link 所有超分 shader（GLSL ES 3.00）。

为什么需要它
  hook.js 把 mpv //!HOOK shader 翻译成 WebGL2 GLSL，翻译器光靠 Node 解析回归
  看不出宏冲突、类型错误、const 常量表达式等问题。真实编译器能抓出来：
    - FSR 的 target_size 宏重复定义（mpv 内置变量被误判成纹理名）
    - FSRCNNX 的 texOff(0) 标量实参导致 int / vec2
    - FSR 的 const bool deea 用 uniform 初始化（mpv 里是编译期常量）
  这些在 Node 侧全部「看起来正常」，只有真编译才暴露。

依赖
  Linux + libEGL.so.1 / libGLESv2.so.2（mesa，通常随系统自带）+ node
  Windows 用户可在 WSL / Linux CI 里跑；Android 真机仍需在 WebView 里验证。
运行（仓库根目录）
  python3 tools/verify_shaders.py
通过标准
  compile FAIL 0 且 link FAIL 0
"""
import os, sys, json, subprocess, ctypes

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE', '1')
os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
os.environ.setdefault('MESA_LOADER_DRIVER_OVERRIDE', 'llvmpipe')

# ---------- 收集 shader 源码（Node 导出，stdout JSON；不产生临时文件） ----------
raw = subprocess.check_output(['node', os.path.join(ROOT, 'tools', 'export_shaders.js')], cwd=ROOT)
items = json.loads(raw)

# ---------- 建立 surfaceless GLES3 上下文 ----------
egl = ctypes.CDLL('libEGL.so.1')
g = ctypes.CDLL('libGLESv2.so.2')
CV, CI, CU = ctypes.c_void_p, ctypes.c_int, ctypes.c_uint
egl.eglGetDisplay.argtypes = [CV]; egl.eglGetDisplay.restype = CV
egl.eglInitialize.argtypes = [CV, ctypes.POINTER(CI), ctypes.POINTER(CI)]; egl.eglInitialize.restype = CI
egl.eglBindAPI.argtypes = [CU]; egl.eglBindAPI.restype = CI
egl.eglChooseConfig.argtypes = [CV, ctypes.POINTER(CI), ctypes.POINTER(CV), CI, ctypes.POINTER(CI)]
egl.eglChooseConfig.restype = CI
egl.eglCreatePbufferSurface.argtypes = [CV, CV, ctypes.POINTER(CI)]; egl.eglCreatePbufferSurface.restype = CV
egl.eglCreateContext.argtypes = [CV, CV, CV, ctypes.POINTER(CI)]; egl.eglCreateContext.restype = CV
egl.eglMakeCurrent.argtypes = [CV, CV, CV, CV]; egl.eglMakeCurrent.restype = CI
g.glGetString.argtypes = [CU]; g.glGetString.restype = ctypes.c_char_p
g.glCreateShader.argtypes = [CU]; g.glCreateShader.restype = CU
g.glDeleteShader.argtypes = [CU]
g.glShaderSource.argtypes = [CU, CI, ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(CI)]
g.glCompileShader.argtypes = [CU]
g.glGetShaderiv.argtypes = [CU, CU, ctypes.POINTER(CI)]
g.glGetShaderInfoLog.argtypes = [CU, CI, ctypes.POINTER(CI), ctypes.c_char_p]
g.glCreateProgram.restype = CU
g.glDeleteProgram.argtypes = [CU]
g.glAttachShader.argtypes = [CU, CU]
g.glLinkProgram.argtypes = [CU]
g.glGetProgramiv.argtypes = [CU, CU, ctypes.POINTER(CI)]
g.glGetProgramInfoLog.argtypes = [CU, CI, ctypes.POINTER(CI), ctypes.c_char_p]
GL_FRAG, GL_VERT, GL_COMPILE, GL_LINK = 0x8B30, 0x8B31, 0x8B81, 0x8B82

dpy = egl.eglGetDisplay(CV(0))
maj, minr = CI(), CI()
assert egl.eglInitialize(dpy, ctypes.byref(maj), ctypes.byref(minr)), 'eglInitialize 失败'
assert egl.eglBindAPI(0x30A0), 'eglBindAPI(OPENGL_ES_API) 失败'
attrs = (CI * 9)(0x3033, 0x0001, 0x3040, 0x0040, 0x3038)  # PBUFFER | OPENGL_ES3_BIT
cfg, num = CV(), CI()
assert egl.eglChooseConfig(dpy, attrs, ctypes.byref(cfg), 1, ctypes.byref(num)) and num.value
sa = (CI * 5)(0x3057, 16, 0x3056, 16, 0x3038)
surf = egl.eglCreatePbufferSurface(dpy, cfg, sa)
ca = (CI * 3)(0x3098, 3, 0x3038)  # EGL_CONTEXT_CLIENT_VERSION = 3
ctx = egl.eglCreateContext(dpy, cfg, CV(0), ca)
assert egl.eglMakeCurrent(dpy, surf, surf, ctx), 'eglMakeCurrent 失败'
print('GL   :', g.glGetString(0x1F02).decode())
print('GLSL :', g.glGetString(0x8B8C).decode())

def compile_shader(src, typ):
    s = g.glCreateShader(typ)
    b = src.encode('utf-8')
    arr = (ctypes.c_char_p * 1)(b)
    ln = (CI * 1)(len(b))
    g.glShaderSource(s, 1, arr, ln)
    g.glCompileShader(s)
    ok = CI()
    g.glGetShaderiv(s, GL_COMPILE, ctypes.byref(ok))
    buf = ctypes.create_string_buffer(16384)
    n = CI()
    g.glGetShaderInfoLog(s, 16384, ctypes.byref(n), buf)
    return s, ok.value, buf.value.decode(errors='replace').strip()

vertsrc = [x for x in items if x['type'] == 'vert'][0]['src']
vs, vok, vlog = compile_shader(vertsrc, GL_VERT)
print('VERT :', vlog or 'OK')
assert vok, '顶点着色器编译失败'

fails, linkfails, okc = [], [], 0
for it in items:
    s, ok, log = compile_shader(it['src'], GL_FRAG if it['type'] == 'frag' else GL_VERT)
    if not ok:
        fails.append((it['tag'], log))
        g.glDeleteShader(s)
        continue
    okc += 1
    if it['type'] == 'frag':
        p = g.glCreateProgram()
        g.glAttachShader(p, vs); g.glAttachShader(p, s)
        g.glLinkProgram(p)
        lok = CI()
        g.glGetProgramiv(p, GL_LINK, ctypes.byref(lok))
        if not lok.value:
            buf = ctypes.create_string_buffer(16384)
            n = CI()
            g.glGetProgramInfoLog(p, 16384, ctypes.byref(n), buf)
            linkfails.append((it['tag'], buf.value.decode(errors='replace').strip()))
        g.glDeleteProgram(p)
    g.glDeleteShader(s)

print('compiled OK %d / %d' % (okc, len(items)))
print('compile FAIL %d   link FAIL %d' % (len(fails), len(linkfails)))
for tag, log in fails[:40]:
    print('\n--- COMPILE FAIL: %s ---\n%s' % (tag, log[:1500]))
for tag, log in linkfails[:20]:
    print('\n--- LINK FAIL: %s ---\n%s' % (tag, log[:800]))
if fails or linkfails:
    sys.exit(1)
print('[PASS] 全部 shader 真实编译 + link 通过')
