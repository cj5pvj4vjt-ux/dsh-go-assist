#!/usr/bin/env python3
"""Minimal Android binary XML (AXML) *writer*, no external deps.

The framework parses AndroidManifest.xml through ResXMLTree, so the writer has to
emit the same chunk layout aapt2 emits:

  RES_XML_TYPE
    RES_STRING_POOL_TYPE      (UTF-8 pool, android:attr names first)
    RES_XML_RESOURCE_MAP_TYPE (attr name string index -> android.R.attr id)
    RES_XML_START_NAMESPACE / START_ELEMENT / END_ELEMENT / END_NAMESPACE ...

The resource map is what makes `android:*` attributes visible to
PackageParser (it looks attributes up by resource id, not by name). Attribute
resource ids below were taken from android.jar API 36 with
`javap -constants -classpath android.jar android.R$attr` (see tools/rattr.txt).

Spec format (python dicts), see make_manifest.py:

  {'tag': 'manifest',
   'attrs': [(None, 'package', 'string', 'com.example'),
             ('android', 'versionCode', 'int', 1)],
   'children': [ ... ]}
"""
import json
import struct
import sys

RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180

NO_INDEX = 0xFFFFFFFF
ANDROID_NS = 'http://schemas.android.com/apk/res/android'
UTF8_FLAG = 1 << 8

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

# android.R.attr ids (API 36), verified via javap -constants.
ATTR = {
    'theme': 0x01010000,
    'label': 0x01010001,
    'icon': 0x01010002,
    'roundIcon': 0x0101052C,
    'name': 0x01010003,
    'permission': 0x01010006,
    'enabled': 0x0101000E,
    'debuggable': 0x0101000F,
    'exported': 0x01010010,
    'process': 0x01010011,
    'taskAffinity': 0x01010012,
    'multiprocess': 0x01010013,
    'finishOnTaskLaunch': 0x01010014,
    'excludeFromRecents': 0x01010017,
    'authorities': 0x01010018,
    'value': 0x01010024,
    'grantUriPermissions': 0x0101001B,
    'priority': 0x0101001C,
    'launchMode': 0x0101001D,
    'screenOrientation': 0x0101001E,
    'configChanges': 0x0101001F,
    'scheme': 0x01010027,
    'host': 0x01010028,
    'pathPrefix': 0x0101002B,
    'mimeType': 0x01010026,
    'minSdkVersion': 0x0101020C,
    'versionCode': 0x0101021B,
    'versionName': 0x0101021C,
    'noHistory': 0x0101022D,
    'targetSdkVersion': 0x01010270,
    'allowBackup': 0x01010280,
    'hardwareAccelerated': 0x010102D3,
    'supportsRtl': 0x010103AF,
    'extractNativeLibs': 0x010104EA,
    'usesCleartextTraffic': 0x010104EC,
    'foregroundServiceType': 0x01010599,
    'documentLaunchMode': 0x01010445,
}

# android.R.style ids (API 36), verified via javap -constants.
STYLE = {
    'Theme_NoDisplay': 0x01030055,
    'Theme_Translucent_NoTitleBar': 0x01030010,
    'Theme_DeviceDefault_Light_NoActionBar': 0x0103012C,
}


class StringPool:
    """AXML string pool. Entries that carry an android attr resource id are
    forced to the front so the resource-map chunk is a plain prefix array."""

    def __init__(self):
        self.items = []          # [(text, res_id_or_None)]
        self._by_key = {}

    def add(self, text, res_id=None):
        text = str(text)
        key = (text, res_id)
        if key in self._by_key:
            return self._by_key[key]
        if res_id is None:
            # reuse an existing mapped entry with the same text (keeps pool small)
            for i, (t, r) in enumerate(self.items):
                if t == text and r is not None:
                    self._by_key[key] = i
                    return i
        idx = len(self.items)
        self.items.append((text, res_id))
        self._by_key[key] = idx
        return idx

    @property
    def mapped_count(self):
        n = 0
        for _, r in self.items:
            if r is None:
                break
            n += 1
        return n

    def index(self, text, res_id=None):
        return self._by_key[(str(text), res_id)]

    def encode(self):
        count = len(self.items)
        offsets = []
        data = bytearray()
        for text, _ in self.items:
            offsets.append(len(data))
            raw = text.encode('utf-8')
            units = len(text.encode('utf-16-le')) // 2
            data += _len8(units) + _len8(len(raw)) + raw + b'\x00'
        strings_start = 28 + 4 * count
        while (strings_start + len(data)) % 4:
            data += b'\x00'
        header = struct.pack('<HHI', RES_STRING_POOL_TYPE, 28, strings_start + len(data))
        header += struct.pack('<IIIII', count, 0, UTF8_FLAG, strings_start, 0)
        return header + b''.join(struct.pack('<I', o) for o in offsets) + bytes(data)


def _len8(n):
    if n > 0x7F:
        return bytes([0x80 | (n >> 8), n & 0xFF])
    return bytes([n])


def _chunk(chunk_type, header_size, body):
    return struct.pack('<HHI', chunk_type, header_size, header_size + len(body)) + body


def _resmap_chunk(pool):
    n = pool.mapped_count
    if n != len([1 for _, r in pool.items if r is not None]):
        raise ValueError('mapped attr names must be the first pool entries')
    body = b''.join(struct.pack('<I', pool.items[i][1]) for i in range(n))
    return _chunk(RES_XML_RESOURCE_MAP_TYPE, 8, body)


def _node(chunk_type, body, line=1):
    header = struct.pack('<HHI', chunk_type, 16, 16 + len(body))
    return header + struct.pack('<II', line, NO_INDEX) + body


def _attr_bytes(pool, ns, name, kind, value):
    res_id = ATTR.get(name) if ns == 'android' else None
    ns_idx = pool.index(ANDROID_NS) if ns == 'android' else NO_INDEX
    name_idx = pool.index(name, res_id)
    raw_idx = NO_INDEX
    if kind == 'string':
        data_idx = pool.index(str(value))
        raw_idx = data_idx
        type_, data = TYPE_STRING, data_idx
    elif kind == 'int':
        type_, data = TYPE_INT_DEC, int(value) & 0xFFFFFFFF
    elif kind == 'hex':
        type_, data = TYPE_INT_HEX, int(value) & 0xFFFFFFFF
    elif kind == 'bool':
        type_, data = TYPE_INT_BOOLEAN, (0xFFFFFFFF if value else 0)
    elif kind == 'ref':
        type_, data = TYPE_REFERENCE, int(value) & 0xFFFFFFFF
    else:
        raise ValueError('unknown attr kind %r' % kind)
    return struct.pack('<III', ns_idx, name_idx, raw_idx) + struct.pack(
        '<HBBI', 8, 0, type_, data)


def _walk(spec):
    yield spec
    for child in spec.get('children', []):
        yield from _walk(child)


def encode(spec, line=1):
    """Encode one root element spec into an AXML document (bytes)."""
    pool = StringPool()
    elements = list(_walk(spec))

    # pass 1: every android: attr name first, with its resource id
    for el in elements:
        for ns, name, _kind, _val in el.get('attrs', []):
            if ns == 'android' and name in ATTR:
                pool.add(name, ATTR[name])
    # pass 2: the rest of the strings
    for el in elements:
        pool.add(el['tag'])
        for ns, name, kind, val in el.get('attrs', []):
            pool.add(name)
            if kind == 'string':
                pool.add(val)
    pool.add('android')
    pool.add(ANDROID_NS)

    body = bytearray(_resmap_chunk(pool))
    body += _node(RES_XML_START_NAMESPACE_TYPE,
                  struct.pack('<II', pool.index('android'), pool.index(ANDROID_NS)), line)
    _emit_element(body, spec, pool, line)
    body += _node(RES_XML_END_NAMESPACE_TYPE,
                  struct.pack('<II', pool.index('android'), pool.index(ANDROID_NS)), line)

    root = struct.pack('<HHI', RES_XML_TYPE, 8, 8 + len(pool.encode()) + len(body))
    return root + pool.encode() + bytes(body)


def _emit_element(out, spec, pool, line):
    # manifest tags are not namespaced (only their attributes are)
    ns_idx = NO_INDEX
    name_idx = pool.index(spec['tag'])

    def sort_key(item):
        ns_, name_, _k, _v = item
        rid = ATTR.get(name_) if ns_ == 'android' else None
        return (0, rid) if rid is not None else (1, 0)

    attrs = sorted(spec.get('attrs', []), key=sort_key)
    attrs_blob = b''.join(_attr_bytes(pool, *a) for a in attrs)
    attr_ext = struct.pack('<II', ns_idx, name_idx) + struct.pack(
        '<HHHHHH', 20, 20, len(attrs), 0, 0, 0)
    out += _node(RES_XML_START_ELEMENT_TYPE, attr_ext + attrs_blob, line)
    for child in spec.get('children', []):
        _emit_element(out, child, pool, line)
    out += _node(RES_XML_END_ELEMENT_TYPE,
                 ns_idx.to_bytes(4, 'little') + name_idx.to_bytes(4, 'little'), line)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        print('usage: axml_encode.py spec.json out/AndroidManifest.xml')
        return 2
    spec = json.load(open(sys.argv[1], encoding='utf-8'))
    data = encode(spec)
    with open(sys.argv[2], 'wb') as fh:
        fh.write(data)
    print('wrote %s (%d bytes)' % (sys.argv[2], len(data)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
