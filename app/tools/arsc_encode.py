#!/usr/bin/env python3
"""手写最小 resources.arsc（没有 aapt2）。

结构：
  RES_TABLE (0x0002, headerSize=12)
    packageCount = 1
    全局 string pool（存放 PNG 在 APK 里的路径）
    RES_TABLE_PACKAGE (0x0200, headerSize=0x120)
      id = 0x7f, name = com.dshgo.assist
      typeStrings: ["mipmap"]
      keyStrings:  ["ic_launcher"]
      RES_TABLE_TYPE_SPEC (0x0202, id=0x02, entryCount=1)
      RES_TABLE_TYPE (0x0201) × 3 个 config：
        density=0    -> res/mipmap-xxxhdpi/ic_launcher.png（默认兜底）
        density=480  -> res/mipmap-xxhdpi/ic_launcher.png
        density=640  -> res/mipmap-xxxhdpi/ic_launcher.png
      每个 entry 的值是 TYPE_STRING，指向全局串池里的路径。

资源引用：package 0x7f, type 0x02, entry 0x0000 → `0x7f020000`
（manifest 里 android:icon 就是引用这个 id）。
"""
import struct
import sys

RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC_TYPE = 0x0202

PACKAGE_ID = 0x7f
TYPE_ID_MIPMAP = 0x01
ENTRY_INDEX = 0x0000
ICON_REF = (PACKAGE_ID << 24) | (TYPE_ID_MIPMAP << 16) | ENTRY_INDEX

PNG_XXXHDPI = 'res/mipmap-xxxhdpi/ic_launcher.png'
PNG_XXHDPI = 'res/mipmap-xxhdpi/ic_launcher.png'

DENSITY_XXHIGH = 480
DENSITY_XXXHIGH = 640


def _len8(n):
    if n > 0x7F:
        return bytes([0x80 | (n >> 8), n & 0xFF])
    return bytes([n])


def string_pool(strings):
    """UTF-8 string pool（和 AXML 用同一套格式）。"""
    count = len(strings)
    offsets = []
    data = bytearray()
    for text in strings:
        offsets.append(len(data))
        raw = text.encode('utf-8')
        units = len(text.encode('utf-16-le')) // 2
        data += _len8(units) + _len8(len(raw)) + raw + b'\x00'
    strings_start = 28 + 4 * count
    while (strings_start + len(data)) % 4:
        data += b'\x00'
    header = struct.pack('<HHI', RES_STRING_POOL_TYPE, 28, strings_start + len(data))
    header += struct.pack('<IIIII', count, 0, 0x100, strings_start, 0)
    return header + b''.join(struct.pack('<I', o) for o in offsets) + bytes(data)


def config(density=0):
    """ResTable_config：size=64 的全零默认配置，只改 density（offset 14）。"""
    buf = bytearray(64)
    struct.pack_into('<I', buf, 0, 64)
    struct.pack_into('<H', buf, 14, density)
    return bytes(buf)


def type_spec_chunk(entry_count):
    body = (struct.pack('<BBH', TYPE_ID_MIPMAP, 0, 0)
            + struct.pack('<I', entry_count)
            + b''.join(struct.pack('<I', 0) for _ in range(entry_count)))
    header_size = 16
    return struct.pack('<HHI', RES_TABLE_TYPE_SPEC_TYPE, header_size,
                       header_size + 4 * entry_count) + body


def type_chunk(global_string_index, density):
    cfg = config(density)
    entry_count = 1
    header_size = 20 + len(cfg)
    entries_start = header_size + 4 * entry_count
    entry = (struct.pack('<HHI', 8, 0, 0)                       # ResTable_entry: key=0
             + struct.pack('<HBBI', 8, 0, 0x03, global_string_index))  # TYPE_STRING
    offsets = struct.pack('<I', 0)
    payload = (struct.pack('<BBH', TYPE_ID_MIPMAP, 0, 0)
               + struct.pack('<II', entry_count, entries_start)
               + cfg + offsets + entry)
    return struct.pack('<HHI', RES_TABLE_TYPE_TYPE, header_size,
                       header_size + len(offsets) + len(entry)) + payload


def package_chunk(name):
    type_pool = string_pool(['mipmap'])
    key_pool = string_pool(['ic_launcher'])
    header_size = 0x120
    type_strings_off = header_size
    key_strings_off = header_size + len(type_pool)

    name_raw = name.encode('utf-16-le')[:256]
    name_padded = name_raw + b'\x00' * (256 - len(name_raw))
    body = (struct.pack('<I', PACKAGE_ID) + name_padded
            + struct.pack('<IIII', type_strings_off, 0, key_strings_off, 0)
            + struct.pack('<I', 0))                      # typeIdOffset
    children = (type_pool + key_pool
                + type_spec_chunk(1)
                + type_chunk(0, 0)                       # default → xxxhdpi PNG
                + type_chunk(1, DENSITY_XXHIGH)          # xxhdpi
                + type_chunk(0, DENSITY_XXXHIGH))        # xxxhdpi
    payload = body + children
    # 注意：header_size(0x120) 已经包含 body（id+name+offsets），size 只再加 children
    return struct.pack('<HHI', RES_TABLE_PACKAGE_TYPE, header_size,
                       header_size + len(children)) + payload


def build():
    # 全局串池：0 = xxxhdpi 路径，1 = xxhdpi 路径
    global_pool = string_pool([PNG_XXXHDPI, PNG_XXHDPI])
    packages = package_chunk('com.dshgo.assist')
    header_size = 12
    total = header_size + len(global_pool) + len(packages)
    header = (struct.pack('<HHI', RES_TABLE_TYPE, header_size, total)
              + struct.pack('<I', 1))
    return header + global_pool + packages


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'build/resources.arsc'
    data = build()
    with open(out, 'wb') as fh:
        fh.write(data)
    print('wrote %s (%d bytes), icon ref = 0x%08x' % (out, len(data), ICON_REF))
    return 0


if __name__ == '__main__':
    sys.exit(main())