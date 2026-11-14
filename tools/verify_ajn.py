#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 onnxruntime 作 ground truth，验证 animejanai.js 的 GLSL 卷积链数值等价。

为什么必须有这一步
  「能跑但输出错」的假超分比跑不起来更糟。WebGL2 的 y 轴、DepthToSpace 的
  CRD 通道映射、ONNX Conv 权重的 [oc,ic,ky,kx] 顺序，任意一处搞反都会让画面
  静默出错（有色块/错位/糊），而 shader 依然编译通过。本脚本把 animejanai.js
  的三条核心公式逐元素对齐 ORT，改动公式后必须重跑。

依赖
  python3 -m pip install --break-system-packages numpy onnx onnxruntime
运行（仓库根目录）
  python3 tools/verify_ajn.py
通过标准
  10 层 Conv 逐层 maxdiff < 1e-4，最终输出 maxdiff < 1e-4（实测 ~5e-7）

三个必须对齐的事实（详见 animejanai.js 文件头）
  (1) 权重布局 ((ic*9+ky*3+kx)*outC+oc)：一个 RGBA32F texel 服务 4 个输出通道
  (2) OpenGL y 向上 / ONNX 行向下  =>  采样 dy = 1-ky，边界越界取 0（pad=1）
  (3) DepthToSpace(mode='CRD') 实测映射 in_ch = c*4 + by*2 + bx
      （不是想当然的 c + C*(by*B+bx)！这是用 ORT 中间张量反推出来的）
      Resize 残差是 repeat 2x：src = (Y/2, X/2)
"""
import os, sys, copy
import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P = os.path.join(ROOT, 'tools', 'sources', 'animejanai',
                 '2x_AnimeJaNai_Strong_V1_SuperUltraCompact_net_g_100000.onnx')
H, W = 5, 7

m = onnx.load(P)
g = m.graph
init = {w.name: numpy_helper.to_array(w).astype(np.float32) for w in g.initializer}
conv_nodes = [n for n in g.node if n.op_type == 'Conv']
prelu_nodes = [n for n in g.node if n.op_type == 'PRelu']

# ---- 把每个 Conv / PRelu 的输出加为图输出，一次拿到全部中间张量 ----
m2 = copy.deepcopy(m)
for n in m2.graph.node:
    if n.op_type in ('Conv', 'PRelu'):
        vi = onnx.ValueInfoProto()
        vi.name = n.output[0]
        vi.type.tensor_type.elem_type = onnx.TensorProto.FLOAT
        m2.graph.output.append(vi)
sess = ort.InferenceSession(m2.SerializeToString(), providers=['CPUExecutionProvider'])
rng = np.random.default_rng(7)
X = (rng.random((1, 3, H, W)).astype(np.float32) * 0.8 + 0.1)
names = [o.name for o in sess.get_outputs()]
vals = sess.run(None, {'input': X})
O = dict(zip(names, vals))
ref = np.asarray(O['output'])[0]              # [3,2H,2W], ONNX 行序（上->下）
print('ORT output', ref.shape, 'range', round(float(ref.min()), 4), round(float(ref.max()), 4))

# ---- 复刻 gen_assets.py 的权重打包 ----
chunks, layers, cursor = [], [], 0
for li, cn in enumerate(conv_nodes):
    Wt = init[cn.input[1]]
    b = init[cn.input[2]].reshape(-1)
    outC, inC = Wt.shape[0], Wt.shape[1]
    flat = np.ascontiguousarray(np.transpose(Wt, (1, 2, 3, 0)).reshape(-1))
    wo = cursor; chunks.append(flat); cursor += flat.size
    bo = cursor; chunks.append(b); cursor += b.size
    so = cursor
    sl = (init[prelu_nodes[li].input[1]].reshape(-1)
          if li < len(prelu_nodes) else np.ones(outC, np.float32))
    chunks.append(sl); cursor += sl.size
    layers.append(dict(inC=inC, outC=outC, wo=wo, bo=bo, so=so,
                       prelu=li < len(prelu_nodes),
                       actOut=prelu_nodes[li].output[0] if li < len(prelu_nodes) else cn.output[0]))
WTEX = np.concatenate(chunks).astype(np.float32).reshape(-1, 4)  # RGBA32F 权重纹理
wf = lambda fi: WTEX[fi >> 2][fi & 3]

# ---- 特征图：RGBA slot 打包；OGL y 向上，X_ogl[y] = X_onnx[H-1-y] ----
Xo = X[0][:, ::-1, :].copy()

def pack(feat, inC):
    a = np.zeros(((inC + 3) // 4, H, W, 4), np.float32)
    for ic in range(inC):
        a[ic >> 2, :, :, ic & 3] = feat[ic]
    return a

def get(a, ich, py, px):
    """采样通道 ich 在 (px,py)，越界取 0（对应 Conv pad=1 补零）。"""
    if px < 0 or px >= W or py < 0 or py >= H:
        return 0.0
    return a[ich >> 2, py, px, ich & 3]

cur = pack(Xo, 3)
worst = 0.0
for li, L in enumerate(layers):
    outC, nOut = L['outC'], (L['outC'] + 3) // 4
    nxt = np.zeros((nOut, H, W, 4), np.float32)
    for slot in range(nOut):
        for py in range(H):
            for px in range(W):
                for comp in range(4):
                    oc = slot * 4 + comp
                    if oc >= outC:
                        continue
                    acc = wf(L['bo'] + oc)
                    for ic in range(L['inC']):
                        isl, icp = ic >> 2, ic & 3
                        for ky in range(3):
                            yy = py + (1 - ky)              # dy = 1-ky
                            if yy < 0 or yy >= H:
                                continue
                            for kx in range(3):
                                xx = px + kx - 1
                                if xx < 0 or xx >= W:
                                    continue
                                acc += wf(L['wo'] + (ic * 9 + ky * 3 + kx) * outC + oc) \
                                    * cur[isl, yy, xx, icp]
                    if L['prelu'] and acc < 0:
                        acc *= wf(L['so'] + oc)
                    nxt[slot, py, px, comp] = acc
    cur = nxt
    mine = np.zeros((outC, H, W), np.float32)
    for ic in range(outC):
        for py in range(H):
            for px in range(W):
                mine[ic, py, px] = cur[ic >> 2, py, px, ic & 3]
    d = float(np.abs(mine[:, ::-1, :] - np.asarray(O[L['actOut']])[0]).max())
    worst = max(worst, d)
    print('  layer %02d outC=%-3d maxdiff=%.3g %s' % (li, outC, d, 'OK' if d < 1e-4 else 'FAIL'))
    if d >= 1e-4:
        sys.exit('[FAIL] 第 %d 层 Conv 与 ONNX Runtime 不一致' % li)

# ---- assemble：DepthToSpace(CRD) + nearest residual ----
C, B = 3, 2
ogl = np.zeros((3, 2 * H, 2 * W), np.float32)
for py in range(2 * H):
    Y = 2 * H - 1 - py                      # OGL y -> ONNX 行 Y
    by = Y % B
    for px in range(2 * W):
        bx = px % B
        sRow, sCol = Y // B, px // B
        sy = H - 1 - sRow
        for c in range(C):
            ich = c * (B * B) + by * B + bx   # 实测 CRD 映射
            v = get(cur, ich, sy, sCol)
            v += Xo[c, sy, sCol]              # Resize nearest 2x 残差
            ogl[c, py, px] = v

d = np.abs(ogl[:, ::-1, :] - ref)
print('final max abs diff =', float(d.max()), ' mean =', float(d.mean()))
print('allclose(1e-4) =', np.allclose(ogl[:, ::-1, :], ref, atol=1e-4))
if d.max() >= 1e-4:
    sys.exit('[FAIL] 最终输出与 ONNX Runtime 不一致')
print('[PASS] AnimeJaNai GLSL 卷积链与 ONNX Runtime 数值等价（worst layer %.3g）' % worst)
