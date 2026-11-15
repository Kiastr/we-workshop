#!/usr/bin/env python3
"""
生成 WE 工坊的应用桌面图标（不使用 AI 生成图片，全部由代码绘制）。

## 设计
与前端 style.css 的品牌渐变保持一致：深空蓝紫底 + 青(#5ad1ff) → 紫(#8a6bff)
渐变圆角块，块内是深色播放三角（呼应顶栏 .brand-dot 与「本地视频播放」功能）。

产出：
  res/mipmap-<density>/ic_launcher.png            legacy 方形图标（API < 26）
  res/mipmap-<density>/ic_launcher_round.png      圆形图标
  res/mipmap-<density>/ic_launcher_foreground.png adaptive icon 前景（透明底）
  res/mipmap-<density>/ic_launcher_background.png adaptive icon 背景
  res/mipmap-anydpi-v26/ic_launcher.xml           API 26+ 自适应图标
  res/mipmap-anydpi-v26/ic_launcher_round.xml

## 尺寸约定
legacy/round 按标准 launcher 尺寸（mdpi 48 → xxxhdpi 192）。
adaptive icon 画布是 108dp，系统只保证中间 66dp 直径的「安全区」可见，
因此前景主体缩到画布的 52%（legacy 用 66%），避免被圆形/水滴形 mask 裁掉。

## 重新生成
    python3 -m pip install --break-system-packages pillow numpy
    python3 tools/gen_icon.py
脚本是确定性的，重复运行产出 byte-identical，可直接提交 PNG。

## 想换配色
改 BACKGROUND_COLORS / BRAND_C0 / BRAND_C1 三个常量即可，前端记得同步
style.css 里的 --cyan / --violet / --grad，保持 App 内外视觉一致。
"""

import os
import tempfile
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024  # 设计画布，最后 LANCZOS 缩小到各密度，天然抗锯齿

# 深空底的对角渐变起止色
BACKGROUND_COLORS = ((14, 22, 44), (45, 28, 80))
# 品牌渐变（青 → 紫），与 style.css 的 --cyan / --violet 相同
BRAND_C0 = (90, 209, 255)
BRAND_C1 = (138, 107, 255)
TRIANGLE_COLOR = (10, 13, 22, 238)

# density -> (legacy px, adaptive px)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def _rgba(arr, alpha=255):
    a = np.full(arr.shape[:2], alpha, dtype=np.uint8)
    return Image.fromarray(np.dstack([arr.astype(np.uint8), a]), "RGBA")


def _grid(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    return yy, xx


def background(size):
    """全幅不透明的深空渐变 + 两处柔光，供 legacy 与 adaptive background 共用。"""
    yy, xx = _grid(size)
    t = (xx + yy) / (2.0 * size)
    c0 = np.array(BACKGROUND_COLORS[0], np.float32)
    c1 = np.array(BACKGROUND_COLORS[1], np.float32)
    arr = c0[None, None, :] * (1 - t[..., None]) + c1[None, None, :] * t[..., None]
    # 左上青光、右下紫光，指数衰减，避免过曝
    for cx, cy, r, col, amp in (
        (0.18 * size, 0.10 * size, 0.55 * size, BRAND_C0, 0.24),
        (0.86 * size, 0.86 * size, 0.55 * size, BRAND_C1, 0.24),
    ):
        d2 = ((xx - cx) ** 2 + (yy - cy) ** 2) / (r * r)
        arr += (np.exp(-2.2 * d2) * amp)[..., None] * np.array(col, np.float32)
    return _rgba(np.clip(arr, 0, 255))


def brand_gradient(size):
    """斜向青→紫渐变，用作主体圆角块的填充。"""
    yy, xx = _grid(size)
    t = np.clip((xx + yy * 0.6) / (1.6 * size), 0, 1)
    c0 = np.array(BRAND_C0, np.float32)
    c1 = np.array(BRAND_C1, np.float32)
    arr = c0[None, None, :] * (1 - t[..., None]) + c1[None, None, :] * t[..., None]
    return _rgba(np.clip(arr, 0, 255))


def foreground(size, scale=0.66):
    """透明底上的主体：外发光 + 渐变圆角块 + 内高光 + 播放三角。

    scale 控制主体占画布比例：legacy 用 0.66，adaptive 用 0.52（安全区约束）。
    """
    w = size * scale
    r = w * 0.24
    cx = cy = size / 2.0
    box = (cx - w / 2, cy - w / 2, cx + w / 2, cy + w / 2)

    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # 外发光：单独一层做高斯模糊再合成，不破坏主体边缘
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(glow).rounded_rectangle(box, radius=r, fill=(110, 160, 255, 130))
    fg = Image.alpha_composite(fg, glow.filter(ImageFilter.GaussianBlur(size * 0.028)))

    # 主体：用圆角 mask 把整幅渐变裁出来
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=r, fill=255)
    fg.paste(brand_gradient(size), (0, 0), mask)

    # 顶部柔和高光：先画一个椭圆再与圆角 mask 相乘，只在主体内部留一层玻璃质感。
    hl = Image.new("L", (size, size), 0)
    ImageDraw.Draw(hl).ellipse(
        (box[0] - w * 0.18, box[1] - w * 0.42, box[2] + w * 0.18, box[1] + w * 0.28),
        fill=110,
    )
    hl = ImageChops.multiply(hl.filter(ImageFilter.GaussianBlur(size * 0.02)), mask)
    fg.paste(Image.new("RGBA", (size, size), (255, 255, 255, 255)), (0, 0), hl)

    d = ImageDraw.Draw(fg)
    d.rounded_rectangle(box, radius=r, outline=(255, 255, 255, 72),
                        width=max(2, int(size * 0.004)))
    # 播放三角：视觉上略向左补偿，避免尖端偏重。
    tw, th = w * 0.34, w * 0.42

    def tri_points(grow):
        return [(cx - tw * 0.32 - grow, cy - th / 2 - grow),
                (cx - tw * 0.32 - grow, cy + th / 2 + grow),
                (cx + tw * 0.68 + grow, cy)]

    # 先铺一层放大的青色光晕并模糊，再压上深色三角，边缘留一圈极细的微光。
    tg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(tg).polygon(tri_points(size * 0.006), fill=(120, 220, 255, 110))
    fg = Image.alpha_composite(fg, tg.filter(ImageFilter.GaussianBlur(size * 0.007)))
    ImageDraw.Draw(fg).polygon(tri_points(0), fill=TRIANGLE_COLOR)
    return fg


def legacy(size, fg_scale):
    """背景 + 前景，并剪成圆角方形（Android 桌面常见 squircle 近似）。"""
    comp = Image.alpha_composite(background(size), foreground(size, fg_scale))
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size - 1, size - 1),
                                       radius=size * 0.22, fill=255)
    comp.putalpha(ImageChops.multiply(comp.getchannel("A"), m))
    return comp


def round_icon(size, fg_scale):
    comp = Image.alpha_composite(background(size), foreground(size, fg_scale))
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size - 1, size - 1), fill=255)
    comp.putalpha(ImageChops.multiply(comp.getchannel("A"), m))
    return comp


def main():
    sq = legacy(S, 0.66)
    cir = round_icon(S, 0.66)
    fg_adaptive = foreground(S, 0.52)
    bg = background(S)

    for density, (px, apx) in DENSITIES.items():
        out = os.path.join(RES, "mipmap-" + density)
        os.makedirs(out, exist_ok=True)
        sq.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
        cir.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher_round.png"))
        fg_adaptive.resize((apx, apx), Image.LANCZOS).save(
            os.path.join(out, "ic_launcher_foreground.png"))
        bg.resize((apx, apx), Image.LANCZOS).save(
            os.path.join(out, "ic_launcher_background.png"))
        print("mipmap-%-8s launcher=%d  adaptive=%d" % (density, px, apx))

    v26 = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(v26, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(v26, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(ADAPTIVE_XML)
    print("adaptive xml: 2")

    # 预览图写到系统临时目录，只供开发时目视检查，不落进仓库、不进 APK。
    preview = os.path.join(tempfile.gettempdir(), "we-workshop-icon-preview.png")
    sq.resize((256, 256), Image.LANCZOS).save(preview)
    print("preview:", preview)


if __name__ == "__main__":
    main()
