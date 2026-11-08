#!/usr/bin/env python3
"""生成应用图标 PNG（无 Pillow，纯 Python zlib+struct 手写 PNG）。

v8 设计（用户反馈 v7 太糊）：
  - 深色底 #0B0F14（圆角方，圆角 22%）
  - **清晰的四色圆环**：环外径 0.42×边长、环宽 0.11×边长（内径 0.31×边长）
  - 四段各 90°、段间 5° 缺口：
      蓝 #4285F4  0–90°
      红 #EA4335  90–180°
      黄 #FBBC05  180–270°
      绿 #34A853  270–360°
  - 中心白色实心圆，半径 0.16×边长
  - 边缘硬边：4× 超采样后盒式下采样只做抗锯齿，没有任何模糊/光晕

用法：gen_icon.py <out_dir>
产出：<out_dir>/mipmap-xxhdpi/ic_launcher.png (144×144)
      <out_dir>/mipmap-xxxhdpi/ic_launcher.png (192×192)
"""
import math
import os
import struct
import sys
import zlib

SUPER = 4
BG = (0x0B, 0x0F, 0x14)                 # #0B0F14
WHITE = (0xFF, 0xFF, 0xFF)
# (起角, 终角, 颜色) —— 屏幕坐标系：x 右 y 下，角度顺时针
SEGMENTS = [
    (0.0, 90.0, (0x42, 0x85, 0xF4)),    # blue
    (90.0, 180.0, (0xEA, 0x43, 0x35)),  # red
    (180.0, 270.0, (0xFB, 0xBC, 0x05)), # yellow
    (270.0, 360.0, (0x34, 0xA8, 0x53)), # green
]
GAP_DEG = 5.0                           # 段间缺口总角度（边界两侧各 2.5°）
CORNER_RATIO = 0.22


def clamp01(v):
    return 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)


def write_png(path, width, height, rows):
    raw = b''.join(b'\x00' + row for row in rows)

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as fh:
        fh.write(png)
        fh.flush()
        os.fsync(fh.fileno())


def _seg_color(angle):
    """按角度返回该段颜色；落在缺口里返回 None。"""
    for start, end, color in SEGMENTS:
        lo = start + GAP_DEG / 2.0
        hi = end - GAP_DEG / 2.0
        if lo <= angle < hi:
            return color
    return None


def render(size):
    big = size * SUPER
    c = big / 2.0
    corner = big * CORNER_RATIO
    r_outer = big * 0.42
    r_inner = big * 0.42 - big * 0.11
    r_center = big * 0.16

    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            # 盒式下采样：统计 4×4 子像素的颜色
            acc_r = acc_g = acc_b = acc_a = 0.0
            for sy in range(SUPER):
                for sx in range(SUPER):
                    px = (x * SUPER + sx) + 0.5
                    py = (y * SUPER + sy) + 0.5
                    # 圆角矩形底
                    dx = max(corner - px, px - (big - corner), 0.0)
                    dy = max(corner - py, py - (big - corner), 0.0)
                    inside_bg = True
                    if dx > 0.0 or dy > 0.0:
                        inside_bg = (dx * dx + dy * dy) <= corner * corner
                    if not inside_bg:
                        acc_a += 0.0
                        continue
                    col = BG
                    d = math.hypot(px - c, py - c)
                    if d <= r_center:
                        col = WHITE
                    elif r_inner <= d <= r_outer:
                        ang = math.degrees(math.atan2(py - c, px - c)) % 360.0
                        seg = _seg_color(ang)
                        if seg is not None:
                            col = seg
                    acc_r += col[0]
                    acc_g += col[1]
                    acc_b += col[2]
                    acc_a += 1.0
            n = SUPER * SUPER
            if acc_a == 0.0:
                row += b'\x00\x00\x00\x00'
            else:
                row += bytes((int(round(acc_r / acc_a)) & 0xFF,
                              int(round(acc_g / acc_a)) & 0xFF,
                              int(round(acc_b / acc_a)) & 0xFF,
                              int(round(acc_a / n * 255.0)) & 0xFF))
        rows.append(bytes(row))
    return rows


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else 'build/res'
    for folder, size in (('mipmap-xxhdpi', 144), ('mipmap-xxxhdpi', 192)):
        target = os.path.join(out_dir, folder)
        os.makedirs(target, exist_ok=True)
        path = os.path.join(target, 'ic_launcher.png')
        write_png(path, size, size, render(size))
        print('  -> %s (%d×%d, %d bytes)' % (path, size, size, os.path.getsize(path)))
    return 0


if __name__ == '__main__':
    sys.exit(main())