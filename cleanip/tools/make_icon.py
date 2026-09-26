#!/usr/bin/env python3
"""Generates res/mipmap-*/ic_launcher.png for the clean IP scanner (needs Pillow).

The icon: a dark navy tile, a golden globe made of latitude/longitude rings and a
green check mark in front of it - "checked CDN edge".
"""
import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:
    sys.stderr.write('Pillow is not installed - keeping the existing icons\n')
    sys.exit(0)

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), 'res')

BG_TOP = (18, 26, 40)
BG_BOT = (8, 11, 18)
GOLD = (233, 196, 106)
GOLD_DEEP = (167, 129, 47)
GREEN = (63, 214, 143)
GREEN_DEEP = (24, 122, 84)
BLUE = (82, 169, 242)

S = 512
DENSITIES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def draw_master():
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # background gradient with rounded corners
    bg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bg)
    for y in range(S):
        t = y / float(S - 1)
        col = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3))
        bd.line([(0, y), (S, y)], fill=col + (255,))
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.22, fill=255)
    img.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(img)

    # soft golden glow
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([S * 0.16, S * 0.16, S * 0.84, S * 0.84], fill=(233, 196, 106, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.06))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)

    # globe: outer ring + latitude lines + meridian ellipse
    cx = cy = S * 0.5
    r = S * 0.29
    ring = max(3, int(S * 0.018))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=GOLD, width=ring)
    for factor in (0.33, 0.66):
        half = r * factor
        d.arc([cx - r, cy - half, cx + r, cy + half], 0, 180, fill=GOLD_DEEP, width=ring)
        d.arc([cx - r, cy - half, cx + r, cy + half], 180, 360, fill=GOLD_DEEP, width=ring)
    d.ellipse([cx - r * 0.45, cy - r, cx + r * 0.45, cy + r], outline=GOLD_DEEP, width=ring)
    d.line([(cx, cy - r), (cx, cy + r)], fill=BLUE, width=max(2, int(S * 0.01)))

    # green check mark over the globe
    thickness = int(S * 0.085)
    d.line([(S * 0.33, S * 0.53), (S * 0.45, S * 0.66)], fill=GREEN_DEEP, width=thickness + 6)
    d.line([(S * 0.45, S * 0.66), (S * 0.70, S * 0.34)], fill=GREEN_DEEP, width=thickness + 6)
    d.line([(S * 0.33, S * 0.53), (S * 0.45, S * 0.66)], fill=GREEN, width=thickness)
    d.line([(S * 0.45, S * 0.66), (S * 0.70, S * 0.34)], fill=GREEN, width=thickness)

    return img


def main():
    master = draw_master()
    for density, size in DENSITIES.items():
        target = os.path.join(RES, 'mipmap-' + density)
        if not os.path.isdir(target):
            os.makedirs(target)
        master.resize((size, size), Image.LANCZOS).save(os.path.join(target, 'ic_launcher.png'))
    print('icons: ' + ', '.join(DENSITIES))


if __name__ == '__main__':
    main()
