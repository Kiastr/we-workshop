#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 we-workshop 超分资源包（纯离线，无网络依赖）。

------------------------------------------------------------------------------
目录结构
  tools/
    gen_assets.py          本脚本：素材 -> app 资源
    verify_ajn.py          ONNX Runtime 数值验证（证明 GLSL 公式没写反）
    export_shaders.js      导出所有 shader 源码（stdout JSON）供编译验证
    verify_shaders.py      mesa EGL/GLES3 真实编译 + link 验证
    sources/               上游原始素材（只读，勿手改）
      anime4k/*.glsl       Anime4K 官方 GLSL + Low/High-end 模板 conf
      fsr/AMD_FSR1_RT.glsl
      fsrcnnx/FSRCNNX_x2_8_0_4_1.glsl
      animejanai/*.onnx    AnimeJaNai SuperUltraCompact 权重

输出（覆盖写，勿手改）
  app/src/main/assets/sr/shaders.js    window.__SR_SHADERS  { 文件名: GLSL 文本 }
  app/src/main/assets/sr/weights.js    window.__SR_AJN      { 权重 base64 + 每层元信息 }
  app/src/main/assets/sr/manifest.js   window.__SR_MANIFEST { 档位链 + sha256/bytes/license/source }

------------------------------------------------------------------------------
再生成步骤（在仓库根目录执行）
  python3 -m pip install --break-system-packages numpy onnx
  python3 tools/gen_assets.py

新增 / 更换 shader 的步骤
  1. 把上游 .glsl 放进 tools/sources/ 对应子目录（保留原始注释，脚本会自行清理）
  2. 在下面 FILES 里登记 (子目录, 文件名, license, source)
  3. 在 TIERS 的 chain 里引用文件名，或新增一个档位
  4. 重跑本脚本
  5. 运行时 app/src/main/assets/sr/hook.js 会自动按 //! 指令解析，通常无需改 JS
  6. 强烈建议接着跑 verify_ajn.py 与 verify_shaders.py 做回归

新增档位的硬约束（来自 android-true-super-resolution skill）
  TIERS 的「枚举声明顺序 = 算力升序 = 看门狗降档顺序」，不可随意调序。
  新档位必须给 cost / type / label / hint / chain 五个字段。

------------------------------------------------------------------------------
设计要点（供未来自主维护）
  1. shader 是 mpv 的 //!HOOK 语法，不是标准 GLSL；运行时由 hook.js 翻译成
     WebGL2 GLSL ES 3.00。//! 指令行必须保留，普通整行 // 注释才压缩掉。
     License 不在 GLSL 里保留，统一登记在 manifest.js，运行时在设置页展示。
  2. AnimeJaNai ONNX 权重按 ((ic*9 + ky*3 + kx)*outC + oc) 主序扁平化，
     使「固定 (ic,ky,kx) 的连续 4 个输出通道」正好落在一个 RGBA32F texel 里，
     卷积 shader 一次 texelFetch 就能服务 4 个输出通道（见 animejanai.js）。
     该布局已由 verify_ajn.py 用 onnxruntime 逐层验证（maxdiff <= 1e-6）。
  3. DepthToSpace(mode='CRD') 的通道映射是 c*4 + by*2 + bx（实测反推，
     不是想当然的 c + C*(by*B+bx)），见 animejanai.js 文件头。
------------------------------------------------------------------------------
"""
import os, json, hashlib, base64
import numpy as np
import onnx
from onnx import numpy_helper

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # tools/ -> 仓库根
SRC = os.path.join(ROOT, 'tools', 'sources')
DST = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'sr')
os.makedirs(DST, exist_ok=True)

# ---------------- 资源清单 ----------------
# (子目录, 文件名, license, source)
FILES = [
    ('anime4k', 'Anime4K_Clamp_Highlights.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Restore_CNN_S.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Restore_CNN_M.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Restore_CNN_VL.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Restore_CNN_Soft_M.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Restore_CNN_Soft_S.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Upscale_CNN_x2_S.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Upscale_CNN_x2_M.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Upscale_CNN_x2_VL.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Upscale_Denoise_CNN_x2_S.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_Upscale_Denoise_CNN_x2_M.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_AutoDownscalePre_x2.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_AutoDownscalePre_x4.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_3DGraphics_Upscale_x2_US.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('anime4k', 'Anime4K_3DGraphics_AA_Upscale_x2_US.glsl', 'MIT', 'https://github.com/bloc97/Anime4K'),
    ('fsr', 'AMD_FSR1_RT.glsl', 'MIT (AMD FidelityFX SDK)', 'https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK'),
    ('fsrcnnx', 'FSRCNNX_x2_8_0_4_1.glsl', 'LGPL-3.0', 'https://github.com/hooke007/mpv_PlayKit'),
]

def clean_glsl(text):
    """删整行 // 注释（保留 //! 指令），压缩体积；不动行尾注释与代码。"""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('//') and not s.startswith('//!'):
            continue
        out.append(line)
    return '\n'.join(out).strip() + '\n'

shaders = {}
files_meta = []
for sub, name, lic, source in FILES:
    path = os.path.join(SRC, sub, name)
    raw = open(path, 'rb').read()
    cleaned = clean_glsl(raw.decode('utf-8'))
    shaders[name] = cleaned
    files_meta.append({
        'name': name,
        'bytes': len(cleaned.encode('utf-8')),
        'sha256': hashlib.sha256(cleaned.encode('utf-8')).hexdigest(),
        'license': lic,
        'source': source,
    })
    print('shader', name, len(cleaned), 'passes', cleaned.count('//!DESC'))

# ---------------- 档位（顺序 = 算力升序 = 看门狗降档顺序，skill 硬约束） ----------------
# type: spatial=空间放大 / cnn=GLSL CNN 链 / ajn=ONNX 转写卷积链
TIERS = [
    {'id': 'off', 'label': '原画（关闭超分）', 'cost': 0, 'type': 'off', 'chain': [],
     'hint': '不做任何后处理，功耗最低。'},
    {'id': 'fsr', 'label': 'FSR EASU + RCAS', 'cost': 1, 'type': 'spatial',
     'chain': ['AMD_FSR1_RT.glsl'],
     'hint': 'AMD FSR1：边缘自适应空间放大 + 对比度自适应锐化。2 个 pass，最省电，非神经超分。'},
    {'id': 'a4k_fast', 'label': 'Anime4K 官方 · Fast', 'cost': 2, 'type': 'cnn',
     'chain': ['Anime4K_Clamp_Highlights.glsl', 'Anime4K_Restore_CNN_M.glsl',
               'Anime4K_Upscale_CNN_x2_M.glsl', 'Anime4K_AutoDownscalePre_x2.glsl',
               'Anime4K_AutoDownscalePre_x4.glsl', 'Anime4K_Upscale_CNN_x2_S.glsl'],
     'hint': '官方 Low-end 模板 Mode A（FAST）：M 级重建 + S 级放大，弱 GPU 首选。'},
    {'id': 'a4k_3d', 'label': 'Anime4K 3DGraphics', 'cost': 3, 'type': 'cnn',
     'chain': ['Anime4K_Clamp_Highlights.glsl', 'Anime4K_3DGraphics_Upscale_x2_US.glsl'],
     'hint': '官方 3D 专用链，针对 3D 渲染内容的硬边/纹理/锯齿，开销低于通用 CNN x2。'},
    {'id': 'a4k_3d_aa', 'label': 'Anime4K 3DGraphics AA', 'cost': 3, 'type': 'cnn',
     'chain': ['Anime4K_Clamp_Highlights.glsl', 'Anime4K_3DGraphics_AA_Upscale_x2_US.glsl'],
     'hint': '3D 专用链 + 抗锯齿训练权重，锯齿明显的 3D 源更合适。'},
    {'id': 'fsrcnnx', 'label': 'FSRCNNX x2 8-0-4-1', 'cost': 4, 'type': 'cnn', 'luma': True,
     'chain': ['FSRCNNX_x2_8_0_4_1.glsl'],
     'hint': '轻量且效果好的 FSRCNNX 版本（14 pass，8 特征通道）；只增强亮度，色度走双线性。'},
    {'id': 'a4k_standard', 'label': 'Anime4K 官方 · 标准 (HQ)', 'cost': 5, 'type': 'cnn',
     'chain': ['Anime4K_Clamp_Highlights.glsl', 'Anime4K_Restore_CNN_VL.glsl',
               'Anime4K_Upscale_CNN_x2_VL.glsl', 'Anime4K_AutoDownscalePre_x2.glsl',
               'Anime4K_AutoDownscalePre_x4.glsl', 'Anime4K_Upscale_CNN_x2_M.glsl'],
     'hint': '官方 High-end 模板 Mode A (HQ)：VL 级重建，画质最好、开销最大，建议 ≤720p。'},
    {'id': 'animejanai', 'label': 'AnimeJaNai SuperUltraCompact', 'cost': 6, 'type': 'ajn',
     'chain': [],
     'hint': 'ONNX 权重转写的 GLSL 卷积链（10 层 3×3 Conv + PReLU + DepthToSpace + 残差）。实验重档，仅建议 ≤720p、高性能 GPU。'},
]

# ---------------- AnimeJaNai ONNX -> 权重纹理 ----------------
AJN_PATH = os.path.join(SRC, 'animejanai',
                        '2x_AnimeJaNai_Strong_V1_SuperUltraCompact_net_g_100000.onnx')
model = onnx.load(AJN_PATH)
init = {w.name: numpy_helper.to_array(w).astype(np.float32) for w in model.graph.initializer}

# 按 ONNX 节点顺序取出 Conv 与紧随的 PReLU slope
conv_names, slopes = [], []
for n in model.graph.node:
    if n.op_type == 'Conv':
        conv_names.append(n.input[1].replace('.weight', ''))
# 9 个 PReLU（最后一层 Conv 无激活）；initializer 名 onnx::PRelu_74..82 的
# 数字顺序与节点顺序一致，已用 verify_ajn.py 逐层比对确认。
slope_tensors = [k for k in init if k.startswith('onnx::PRelu_')]
slope_tensors.sort(key=lambda k: int(k.split('_')[-1]))
slopes = [init[k].reshape(-1).astype(np.float32) for k in slope_tensors]
assert len(conv_names) == 10 and len(slopes) == 9, (len(conv_names), len(slopes))

# 权重布局：每层连续存 ((ic*9 + ky*3 + kx)*outC + oc) 的 float32，
# 再追加该层 bias(outC) 与 slope(outC)。
all_weights = []
layers = []
cursor = 0  # float 计数
for li, base in enumerate(conv_names):
    W = init[base + '.weight']  # [outC, inC, 3, 3]
    b = init[base + '.bias'].reshape(-1)
    outC, inC = W.shape[0], W.shape[1]
    # ONNX Conv 权重是 [oc, ic, ky, kx]；我们要 [ic, ky, kx, oc]
    Wt = np.transpose(W, (1, 2, 3, 0))  # [inC,3,3,outC]
    flat = np.ascontiguousarray(Wt.reshape(-1), dtype=np.float32)
    weight_offset = cursor
    all_weights.append(flat); cursor += flat.size

    bias_offset = cursor
    bias = np.ascontiguousarray(b, dtype=np.float32)
    all_weights.append(bias); cursor += bias.size

    slope_offset = cursor
    if li < len(slopes):
        sl = np.ascontiguousarray(slopes[li], dtype=np.float32)
        assert sl.size == outC
    else:
        sl = np.ones(outC, dtype=np.float32)  # 末层无 PReLU，用 1.0 且不激活
    all_weights.append(sl); cursor += sl.size

    layers.append({
        'inC': int(inC), 'outC': int(outC),
        'weightOffset': int(weight_offset), 'biasOffset': int(bias_offset),
        'slopeOffset': int(slope_offset),
        'prelu': li < len(slopes),
        'inSlots': int((inC + 3) // 4), 'outSlots': int((outC + 3) // 4),
    })

weights = np.concatenate(all_weights).astype(np.float32)
# 补齐到 4 的倍数，保证 RGBA32F texel 整除
pad = (-weights.size) % 4
if pad:
    weights = np.concatenate([weights, np.zeros(pad, dtype=np.float32)])
n_texels = weights.size // 4
WT_W = 1024
WT_H = int(np.ceil(n_texels / WT_W))

ajn = {
    'model': '2x_AnimeJaNai_Strong_V1_SuperUltraCompact_net_g_100000.onnx',
    'sha256': hashlib.sha256(open(AJN_PATH, 'rb').read()).hexdigest(),
    'license': 'CC-BY-NC-SA-4.0',
    'source': 'https://openmodeldb.info/models/2x-AnimeJaNai-Standard-v1-SuperUltraCompact',
    'architecture': 'Real-ESRGAN Compact / SuperUltraCompact (24 features, 10 Conv3x3, PReLU, DepthToSpace x2, nearest residual)',
    'scale': 2,
    'weightTexWidth': WT_W,
    'weightTexHeight': WT_H,
    'weightFloatCount': int(weights.size - pad),
    'weightsB64': base64.b64encode(weights.tobytes()).decode('ascii'),
    'layers': layers,
}

manifest = {
    'version': 1,
    'tiers': TIERS,
    'files': files_meta,
    'licenses': [
        {'name': 'Anime4K GLSL shaders', 'license': 'MIT', 'author': 'bloc97',
         'source': 'https://github.com/bloc97/Anime4K'},
        {'name': 'AMD FidelityFX FSR1 (EASU/RCAS)', 'license': 'MIT', 'author': 'AMD / agyild / hooke007',
         'source': 'https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK'},
        {'name': 'FSRCNNX_x2_8_0_4_1', 'license': 'LGPL-3.0', 'author': 'igv',
         'source': 'https://github.com/hooke007/mpv_PlayKit'},
        {'name': '2x AnimeJaNai SuperUltraCompact', 'license': 'CC-BY-NC-SA-4.0',
         'author': 'the-database',
         'source': 'https://openmodeldb.info/models/2x-AnimeJaNai-Standard-v1-SuperUltraCompact'},
    ],
    'ajn': {k: v for k, v in ajn.items() if k not in ('weightsB64', 'layers')},
}

def write_js(name, var, obj):
    path = os.path.join(DST, name)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('/* 自动生成，勿手改。来源与再生成方式见 README「资源再生成」一节。 */\n')
        f.write(var + ' = ' + json.dumps(obj, ensure_ascii=False, separators=(',', ':')) + ';\n')
    print('wrote', name, os.path.getsize(path), 'bytes')

write_js('shaders.js', 'window.__SR_SHADERS', shaders)
write_js('weights.js', 'window.__SR_AJN', ajn)
write_js('manifest.js', 'window.__SR_MANIFEST', manifest)
print('DONE. weight texels', n_texels, 'WT', WT_W, 'x', WT_H, 'floats', weights.size - pad)
