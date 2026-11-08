#!/usr/bin/env python3
"""手写 zip（APK）打包器 —— 控制压缩方式与 4 字节对齐。

为什么不用 zipfile：Android 11+（targetSdk>=30）安装时强制要求
`resources.arsc` **必须 STORED（不压缩）且数据起始偏移 4 字节对齐**，
否则报「安装应用与当前系统有兼容性问题」。zipfile 无法给单个 entry 做对齐，
所以这里自己写 local header + central directory。

对齐方式：在 local header 的 extra field 里放一个合法的自定义 extra
（id=0xCAFE），长度按需补齐，保证：
    (header_offset + 30 + len(name) + len(extra)) % 4 == 0
（这就是 zipalign 的做法；central directory 里镜像同一段 extra。）

用法：mkzip.py <out.apk> <manifest> <dex> [arsc] [resdir]
  传 arsc/resdir 时：arsc 与 res/*.png 用 STORED+对齐，dex 用 DEFLATED
  不传时：只有 manifest(STORED) + dex(DEFLATED)
"""
import os
import struct
import sys
import zlib

DOS_TIME = 0
DOS_DATE = ((2020 - 1980) << 9) | (1 << 5) | 1   # 2020-01-01 00:00:00
FLAG_UTF8 = 0x0800


def _dos_time_date():
    return DOS_TIME, DOS_DATE


def _def(deflate):
    co = zlib.compressobj(9, zlib.DEFLATED, -15)
    return co.compress(deflate) + co.flush()


def build_zip(entries, out_path):
    """entries: [(arcname, bytes, method)]，method 0=store 8=deflate。"""
    offset = 0
    locals_blob = []
    central = []
    for name, data, method in entries:
        name_b = name.encode('utf-8')
        crc = zlib.crc32(data) & 0xFFFFFFFF
        if method == 0:
            comp = data
        else:
            comp = _def(data)
        csize = len(comp)
        usize = len(data)

        # 让 data 的起始偏移 4 字节对齐
        base = offset + 30 + len(name_b)
        pad = (-base) % 4
        if pad == 1:
            pad = 5
        elif pad == 2:
            pad = 6
        elif pad == 3:
            pad = 7
        extra = b''
        if pad >= 4:
            extra = struct.pack('<HH', 0xCAFE, pad - 4) + b'\x00' * (pad - 4)
        data_offset = offset + 30 + len(name_b) + len(extra)
        assert data_offset % 4 == 0, (name, data_offset)

        t, d = _dos_time_date()
        local_header = struct.pack(
            '<IHHHHHIIIHH',
            0x04034B50, 20, FLAG_UTF8, method, t, d,
            crc, csize, usize, len(name_b), len(extra))
        local = local_header + name_b + extra + comp
        locals_blob.append(local)

        central.append(struct.pack(
            '<IHHHHHHIIIHHHHHII',
            0x02014B50, 20, 20, FLAG_UTF8, method, t, d,
            crc, csize, usize, len(name_b), len(extra), 0, 0, 0, 0, offset)
            + name_b + extra)
        offset += len(local)

    cd_offset = offset
    cd = b''.join(central)
    eocd = struct.pack('<IHHHHIIH', 0x06054B50, 0, 0,
                       len(entries), len(entries), len(cd), cd_offset, 0)
    blob = b''.join(locals_blob) + cd + eocd
    with open(out_path, 'wb') as fh:
        fh.write(blob)
        fh.flush()
        os.fsync(fh.fileno())
    return len(entries), len(blob)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    out = sys.argv[1]
    manifest = sys.argv[2]
    dex = sys.argv[3]
    arsc = sys.argv[4] if len(sys.argv) > 4 and sys.argv[4] != '-' else None
    resdir = sys.argv[5] if len(sys.argv) > 5 else None

    entries = [('AndroidManifest.xml', open(manifest, 'rb').read(), 0),
               ('classes.dex', open(dex, 'rb').read(), 8)]
    if arsc:
        entries.append(('resources.arsc', open(arsc, 'rb').read(), 0))
    if resdir and os.path.isdir(resdir):
        for root, _dirs, files in os.walk(resdir):
            for fname in sorted(files):
                full = os.path.join(root, fname)
                rel = os.path.relpath(full, resdir).replace(os.sep, '/')
                entries.append(('res/' + rel, open(full, 'rb').read(), 0))

    count, size = build_zip(entries, out)
    print('    wrote %s (%d entries, %d bytes)' % (out, count, size))
    for name, _data, method in entries:
        print('      %-42s %s' % (name, 'STORED+aligned' if method == 0 else 'DEFLATED'))
    return 0


if __name__ == '__main__':
    sys.exit(main())