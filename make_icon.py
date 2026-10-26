#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「BlackCat 手写签名」原图合成为 App 图标母版。

原图特点：1024×1024 白底 + 纯黑手写 "BlackCat"，右下角有一枚浅灰水印（亮度 ~223）。
处理思路：
  1. 用亮度反推墨迹 alpha（白底→透明，黑字→不透明），自然保留抗锯齿边缘；
  2. 水印亮度高于阈值，天然被排除，无需手工擦除；
  3. 合成到深色底上、字迹反白 —— 形成「黑底白签名」的图标，和「黑猫」气质一致；
  4. 输出 icon-master.png（满幅）与 icon-maskable-master.png（内容缩到安全区）。

用法：python3 make_icon.py [原图路径]
产物：android/icon-master.png / android/icon-maskable-master.png / android/icon-embed.png
"""
import pathlib
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent
ANDROID = ROOT / "android"

DEFAULT_SRC = ("/Users/muhan/Documents/qwen-agent/42Olsmi9bU/default/visual/"
               "vis_20261026_222610_642ef273/BlackCat纯签名去图标.png")

SIZE = 1024
INK_THRESHOLD = 200      # 亮度低于此值视为墨迹（水印约 223，会被排除）
# 字迹占画布宽度的比例：满幅版稍大，可遮罩版留足安全区（中心 80%）
RATIO_FULL = 0.74
RATIO_MASK = 0.58


def load_ink(src_path):
    """返回 (字形图层RGBA, 裁切后的边界)，字形为纯白 + 墨迹 alpha"""
    im = Image.open(src_path).convert("RGB")
    a = np.array(im).astype(np.float32)
    lum = a.mean(axis=2)
    # 反相：白底 → 0，黑字 → 255
    alpha = np.clip((INK_THRESHOLD - lum) / INK_THRESHOLD * 255.0, 0, 255)
    alpha[lum >= INK_THRESHOLD] = 0
    # 轻微提对比，并清掉极低 alpha 的压缩噪点（原图是白底 JPEG 转存，笔画周围有灰雾）
    alpha = np.clip(alpha * 1.12, 0, 255)
    alpha[alpha < 30] = 0
    alpha = alpha.astype(np.uint8)

    ys, xs = np.where(alpha > 12)
    if len(xs) == 0:
        raise SystemExit("未在图片中找到墨迹，请检查原图")
    x0, x1 = int(xs.min()), int(xs.max()) + 1
    y0, y1 = int(ys.min()), int(ys.max()) + 1
    crop_a = alpha[y0:y1, x0:x1]

    glyph = Image.new("RGBA", (x1 - x0, y1 - y0), (255, 255, 255, 0))
    glyph.putalpha(Image.fromarray(crop_a))
    # 白色字身（alpha 单独控制透明度）
    white = Image.new("RGBA", glyph.size, (255, 255, 255, 255))
    white.putalpha(glyph.getchannel("A"))
    return white, (x0, y0, x1, y1)


def make_bg(size):
    """深色底 + 一抹极淡的暖光，避免纯黑死板"""
    bg = Image.new("RGB", (size, size), (14, 14, 18))
    d = ImageDraw.Draw(bg)
    # 从右上向左下压暗一点，做出细微层次
    for i in range(size):
        t = i / max(1, size - 1)
        v = int(6 * (1 - t))
        d.line([(0, i), (size, i)], fill=(14 + v, 14 + v, 18 + v))
    # 暖色光晕（右下）
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = int(size * 0.78), int(size * 0.86)
    r = int(size * 0.62)
    steps = 26
    for k in range(steps, 0, -1):
        rr = int(r * k / steps)
        al = int(16 * (1 - k / steps) ** 2)
        if al <= 0:
            continue
        gd.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=(255, 168, 72, al))
    bg = Image.alpha_composite(bg.convert("RGBA"), glow).convert("RGB")
    return bg


def compose(glyph, ratio, size=SIZE):
    bg = make_bg(size).convert("RGBA")
    gw = int(size * ratio)
    gh = max(1, round(glyph.height * gw / glyph.width))
    g = glyph.resize((gw, gh), Image.LANCZOS)
    x = (size - gw) // 2
    y = (size - gh) // 2
    bg.alpha_composite(g, (x, y))
    return bg


def main():
    src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(DEFAULT_SRC)
    if not src.exists():
        raise SystemExit("找不到原图: %s" % src)

    glyph, bbox = load_ink(src)
    print("原图:", src.name)
    print("字迹区域:", bbox, "→", glyph.size, "(比例 %.2f:1)" % (glyph.width / glyph.height))

    full = compose(glyph, RATIO_FULL)
    mask = compose(glyph, RATIO_MASK)
    # 本机沙箱 shim 的 mkdir 不认 exist_ok=True，目录已存在时会抛 EEXIST
    if not ANDROID.exists():
        ANDROID.mkdir()
    full.convert("RGB").save(ANDROID / "icon-master.png", optimize=True)
    mask.convert("RGB").save(ANDROID / "icon-maskable-master.png", optimize=True)
    # 页面顶部 logo / favicon 用的小图（base64 内联到 HTML）
    full.resize((128, 128), Image.LANCZOS).save(ANDROID / "icon-embed.png", optimize=True)

    for n in ("icon-master.png", "icon-maskable-master.png", "icon-embed.png"):
        p = ANDROID / n
        print("  %-26s %6d 字节" % (n, p.stat().st_size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
