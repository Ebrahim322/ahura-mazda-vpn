#!/usr/bin/env python3
"""Generates res/mipmap-*/ic_launcher.png for the game (needs Pillow)."""
import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:
    sys.stderr.write('Pillow is not installed - keeping the existing icons\n')
    sys.exit(0)

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), 'res')

BG_TOP = (58, 49, 34)
BG_BOT = (16, 13, 9)
GOLD = (224, 166, 60)
OLIVE = (109, 122, 69)
OLIVE_DARK = (58, 65, 38)
OLIVE_LIGHT = (152, 165, 104)
TRACK = (32, 35, 26)

S = 512


def draw(size):
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # background gradient
    for y in range(S):
        t = y / float(S - 1)
        col = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3))
        d.line([(0, y), (S, y)], fill=col + (255,))

    # warm glow behind the tank
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([S * 0.12, S * 0.08, S * 0.88, S * 0.84], fill=(224, 166, 60, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.09))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)

    # ground ring
    d.ellipse([S * 0.06, S * 0.06, S * 0.94, S * 0.94], outline=(224, 166, 60, 120), width=8)

    cx = S * 0.5
    cy = S * 0.53
    w = S * 0.40   # hull width
    h = S * 0.46   # hull height

    def rr(box, r):
        d.rounded_rectangle(box, radius=r)

    # tracks
    tw = w * 0.26
    rr([cx - w / 2 - tw * 0.55, cy - h / 2, cx - w / 2 + tw * 0.45, cy + h / 2], tw * 0.35)
    d.rounded_rectangle([cx - w / 2 - tw * 0.55, cy - h / 2, cx - w / 2 + tw * 0.45, cy + h / 2],
                        radius=tw * 0.35, fill=TRACK + (255,))
    d.rounded_rectangle([cx + w / 2 - tw * 0.45, cy - h / 2, cx + w / 2 + tw * 0.55, cy + h / 2],
                        radius=tw * 0.35, fill=TRACK + (255,))
    # track links
    for i in range(5):
        y = cy - h / 2 + h * (i + 0.5) / 5.0
        d.line([(cx - w / 2 - tw * 0.5, y), (cx - w / 2 + tw * 0.4, y)], fill=(61, 64, 51, 255),
               width=int(S * 0.016))
        d.line([(cx + w / 2 - tw * 0.4, y), (cx + w / 2 + tw * 0.5, y)], fill=(61, 64, 51, 255),
               width=int(S * 0.016))

    # hull
    d.rounded_rectangle([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], radius=S * 0.07,
                        fill=OLIVE + (255,))
    d.rounded_rectangle([cx - w * 0.42, cy - h * 0.44, cx + w * 0.42, cy - h * 0.12],
                        radius=S * 0.05, fill=OLIVE_LIGHT + (255,))
    d.rounded_rectangle([cx - w * 0.42, cy + h * 0.16, cx + w * 0.42, cy + h * 0.44],
                        radius=S * 0.05, fill=OLIVE_DARK + (255,))

    # barrel (pointing up)
    bw = S * 0.075
    d.rounded_rectangle([cx - bw / 2, cy - h * 0.98, cx + bw / 2, cy + h * 0.05],
                        radius=bw * 0.4, fill=TRACK + (255,))
    d.rounded_rectangle([cx - bw * 0.28, cy - h * 0.95, cx + bw * 0.28, cy],
                        radius=bw * 0.25, fill=OLIVE_DARK + (255,))
    d.ellipse([cx - bw * 0.62, cy - h * 1.02, cx + bw * 0.62, cy - h * 0.86], fill=GOLD + (255,))

    # turret
    tr = S * 0.155
    d.ellipse([cx - tr, cy - tr, cx + tr, cy + tr], fill=OLIVE_DARK + (255,))
    d.ellipse([cx - tr * 0.82, cy - tr * 0.82, cx + tr * 0.82, cy + tr * 0.82],
              fill=OLIVE + (255,))
    d.ellipse([cx - tr * 0.38, cy - tr * 0.44, cx + tr * 0.28, cy + tr * 0.22],
              fill=OLIVE_LIGHT + (255,))
    d.ellipse([cx - tr * 0.20, cy - tr * 0.20, cx + tr * 0.20, cy + tr * 0.20], fill=GOLD + (255,))

    if size != S:
        img = img.resize((size, size), Image.LANCZOS)
    return img


DENS = [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]
base = draw(S)
for name, size in DENS:
    folder = os.path.join(RES, 'mipmap-' + name)
    os.makedirs(folder, exist_ok=True)
    out = os.path.join(folder, 'ic_launcher.png')
    (base if size == S else base.resize((size, size), Image.LANCZOS)).save(out, optimize=True)
    print('wrote', out)
