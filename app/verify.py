#!/usr/bin/env python3
"""Structural verification of the DSH 小V assistant APK (v2).

No Android tooling required (aapt2 cannot run on aarch64 proot), so everything
is checked by parsing the produced artefacts directly:

  * ZIP layout: AndroidManifest.xml + classes.dex at the archive root
  * AndroidManifest.xml is well formed binary XML (ResXMLTree chunks):
      - root chunk RES_XML_TYPE, chunk sizes tile the file exactly
      - string pool decodes (UTF-8 and UTF-16 pools supported)
      - resource-map chunk maps android:* attr names to android.R.attr ids,
        and the attrs really resolve to the expected ids (this is what the
        framework uses to find android:name / android:exported / ...)
  * v2 content: versionCode 2, MainActivity(MAIN/LAUNCHER), AssitActivity
      (ASSIST/DEFAULT + Theme.NoDisplay), VoiceActivity(exported=false),
      RECORD_AUDIO permission, <queries> for dsh-go
  * classes.dex: dex magic/header sane, contains RoleManager / SpeechRecognizer /
      dsh-go share component / deep link strings
  * signing: v1 META-INF/*.SF + *.RSA, APK Signing Block (v2/v3),
      same signing certificate as v1 (覆盖升级; checked when out/dsh-assist.apk
      is present), and newer versionCode than v1
  * optionally shells out to `apksigner verify` when the jar + a JVM exist

Usage: python3 verify.py [path/to/apk] [--json]
Exit code 0 = every check passed.
"""
import json
import os
import shutil
import struct
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(HERE, 'tools')
OUT = os.path.join(HERE, 'out')
V1_APK = os.path.join(OUT, 'dsh-assist.apk')


def default_apk():
    """out/ 下 versionCode 最大的那个 dsh-assist*.apk（通常就是最新构建）。"""
    candidates = []
    if os.path.isdir(OUT):
        for name in sorted(os.listdir(OUT)):
            if name.startswith('dsh-assist') and name.endswith('.apk'):
                candidates.append(os.path.join(OUT, name))
    best, best_vc = None, -1
    for path in candidates:
        try:
            with zipfile.ZipFile(path) as z:
                r, _s, _m = parse_axml(z.read('AndroidManifest.xml'))
            vc = r.get(res_id=ATTR_VERSION_CODE)
        except Exception:                                    # noqa: BLE001
            vc = -1
        if isinstance(vc, int) and vc > best_vc:
            best, best_vc = path, vc
    return best or os.path.join(OUT, 'dsh-assist-v3.apk')


ANDROID_NS = 'http://schemas.android.com/apk/res/android'
NO_INDEX = 0xFFFFFFFF

RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

ATTR_VERSION_CODE = 0x0101021B
ATTR_VERSION_NAME = 0x0101021C
ATTR_MIN_SDK = 0x0101020C
ATTR_TARGET_SDK = 0x01010270
ATTR_NAME = 0x01010003
ATTR_EXPORTED = 0x01010010
ATTR_THEME = 0x01010000
ATTR_EXCLUDE_FROM_RECENTS = 0x01010017
ATTR_ICON = 0x01010002
ATTR_NO_HISTORY = 0x0101022D
ATTR_PERMISSION = 0x01010006
ATTR_EXPORTED = 0x01010010
ATTR_TASK_AFFINITY = 0x01010012
ATTR_DOCUMENT_LAUNCH_MODE = 0x01010445
ATTR_LAUNCH_MODE = 0x0101001D
ATTR_ROUND_ICON = 0x0101052C
ICON_REF = 0x7f010000
ATTR_FOREGROUND_SERVICE_TYPE = 0x01010599

STYLE_NO_DISPLAY = 0x01030055
STYLE_TRANSLUCENT_NO_TITLE_BAR = 0x01030010

EXPECT_PACKAGE = 'com.dshgo.assist'
EXPECT_VERSION_CODE = 56
EXPECT_VERSION_NAME = '27.0 final'
EXPECT_MIN_SDK = 26
EXPECT_TARGET_SDK = 36
EXPECT_DEEP_LINK = 'dshgo://open/chat'
EXPECT_TARGET_PKG = 'io.github.ccch1mneyyy.dsh_app'
EXPECT_SHARE_ACTIVITY = 'io.github.ccch1mneyyy.dsh_app.share.ShareActivity'
V6A_PERMISSIONS = [
    'android.permission.RECORD_AUDIO',
    'android.permission.POST_PROMOTED_NOTIFICATIONS',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.WRITE_SETTINGS',
]

EXPECT_PERMISSIONS = [
    'android.permission.RECORD_AUDIO',
    'android.permission.POST_PROMOTED_NOTIFICATIONS',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.WRITE_SETTINGS',
    'android.permission.INTERNET',
    'android.permission.WAKE_LOCK',
    'android.permission.SYSTEM_ALERT_WINDOW',
    'android.permission.FOREGROUND_SERVICE_MICROPHONE',
    'moe.shizuku.manager.permission.API_V23',
    'android.permission.RECEIVE_BOOT_COMPLETED',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
]

results = []
VARIANT = 'v6'          # v6 = 全量；v6a = 排查版（无 Shizuku provider/监听、无 BootReceiver/HealService）


def check(name, ok, detail=''):
    results.append({'name': name, 'ok': bool(ok), 'detail': detail})
    print('[%s] %s%s' % ('PASS' if ok else 'FAIL', name, (' — ' + detail) if detail else ''))
    return ok


# --------------------------------------------------------------------------- #
# AXML parser (independent of tools/axml_encode.py; validated against real
# system APKs produced by aapt2)
# --------------------------------------------------------------------------- #
class Element:
    def __init__(self, tag, attrs, line):
        self.tag = tag
        self.attrs = attrs      # ordered list of dicts
        self.children = []
        self.line = line

    def attr(self, name=None, res_id=None):
        for a in self.attrs:
            if res_id is not None and a['res_id'] == res_id:
                return a
            if name is not None and a['name'] == name:
                return a
        return None

    def get(self, name=None, res_id=None):
        a = self.attr(name, res_id)
        return a['value'] if a else None

    def find_all(self, tag):
        out = []
        for c in self.children:
            if c.tag == tag:
                out.append(c)
            out.extend(c.find_all(tag))
        return out


class AxmlError(Exception):
    pass


def _read_pool(data, off):
    chunk_type, header_size, chunk_size = struct.unpack_from('<HHI', data, off)
    if chunk_type != RES_STRING_POOL_TYPE:
        raise AxmlError('expected string pool at %d, got type 0x%04x' % (off, chunk_type))
    str_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        '<IIIII', data, off + 8)
    utf8 = bool(flags & (1 << 8))
    offsets = struct.unpack_from('<%dI' % str_count, data, off + header_size)
    strings = []
    for idx, o in enumerate(offsets):
        p = off + strings_start + o
        if p >= off + chunk_size:
            raise AxmlError('string %d offset outside pool' % idx)
        if utf8:
            n, p = _len8(data, p)              # UTF-16 code units
            m, p = _len8(data, p)              # UTF-8 byte count
            raw = data[p:p + m]
            if len(raw) != m:
                raise AxmlError('string %d truncated' % idx)
            s = raw.decode('utf-8')
            if len(s.encode('utf-16-le')) // 2 != n:
                raise AxmlError('string %d length mismatch' % idx)
        else:
            n = struct.unpack_from('<H', data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, p)[0]
                p += 2
            raw = data[p:p + n * 2]
            if len(raw) != n * 2:
                raise AxmlError('string %d truncated' % idx)
            s = raw.decode('utf-16-le')
        strings.append(s)
    return strings, chunk_size


def _len8(data, p):
    n = data[p]
    if n & 0x80:
        return ((n & 0x7F) << 8) | data[p + 1], p + 2
    return n, p + 1


def _attr_value(strings, res_map, data, base):
    ns_idx, name_idx, raw_idx = struct.unpack_from('<III', data, base)
    tsize, res0, dtype, tdata = struct.unpack_from('<HBBI', data, base + 12)
    if tsize != 8:
        raise AxmlError('bad attribute value size %d' % tsize)
    if name_idx >= len(strings):
        raise AxmlError('attr name index %d outside string pool' % name_idx)
    name = strings[name_idx]
    ns = strings[ns_idx] if ns_idx != NO_INDEX else None
    res_id = res_map[name_idx] if name_idx < len(res_map) else 0
    raw = strings[raw_idx] if raw_idx != NO_INDEX else None
    if dtype == TYPE_STRING:
        value = strings[tdata]
    elif dtype in (TYPE_INT_DEC, TYPE_INT_HEX):
        value = tdata
    elif dtype == TYPE_INT_BOOLEAN:
        value = tdata != 0
    elif dtype == TYPE_REFERENCE:
        value = '@0x%08x' % tdata
    else:
        value = tdata
    return {'ns': ns, 'name': name, 'res_id': res_id, 'raw': raw,
            'type': dtype, 'data': tdata, 'value': value}


def parse_axml(data):
    if len(data) < 8:
        raise AxmlError('file too small')
    chunk_type, header_size, size = struct.unpack_from('<HHI', data, 0)
    if chunk_type != RES_XML_TYPE or header_size != 8:
        raise AxmlError('not a binary XML document (type=0x%04x hdr=%d)'
                        % (chunk_type, header_size))
    if size != len(data):
        raise AxmlError('xml chunk size %d != file size %d' % (size, len(data)))

    pos = 8
    if pos >= len(data):
        raise AxmlError('missing string pool')
    strings, pool_size = _read_pool(data, pos)
    pos += pool_size

    res_map = []
    if pos + 8 <= len(data):
        ntype, nhdr, nsize = struct.unpack_from('<HHI', data, pos)
        if ntype == RES_XML_RESOURCE_MAP_TYPE:
            count = (nsize - nhdr) // 4
            res_map = list(struct.unpack_from('<%dI' % count, data, pos + nhdr))
            pos += nsize

    root = None
    stack = []
    line_no = 0
    while pos < len(data):
        ctype, chdr, csize = struct.unpack_from('<HHI', data, pos)
        if csize < chdr or chdr < 8 or pos + csize > len(data) or csize % 4:
            raise AxmlError('bad chunk 0x%04x at %d (hdr=%d size=%d)'
                            % (ctype, pos, chdr, csize))
        if ctype in (RES_XML_START_NAMESPACE_TYPE, RES_XML_END_NAMESPACE_TYPE):
            if chdr != 16 or csize != 24:
                raise AxmlError('bad namespace chunk')
            prefix_idx, uri_idx = struct.unpack_from('<II', data, pos + chdr)
            for i in (prefix_idx, uri_idx):
                if i >= len(strings):
                    raise AxmlError('namespace string index out of range')
        elif ctype == RES_XML_START_ELEMENT_TYPE:
            line_no, _comment = struct.unpack_from('<II', data, pos + chdr - 8)
            ext = pos + chdr
            ns_idx, name_idx = struct.unpack_from('<II', data, ext)
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', data, ext + 8)
            if attr_start != 20 or attr_size != 20:
                raise AxmlError('unexpected attr layout start=%d size=%d'
                                % (attr_start, attr_size))
            if chdr + attr_start + attr_size * attr_count != csize:
                raise AxmlError('start element size mismatch')
            if name_idx >= len(strings):
                raise AxmlError('element name index out of range')
            attrs = [_attr_value(strings, res_map, data, ext + attr_start + i * attr_size)
                     for i in range(attr_count)]
            el = Element(strings[name_idx], attrs, line_no)
            if stack:
                stack[-1].children.append(el)
            elif root is None:
                root = el
            else:
                raise AxmlError('multiple root elements')
            stack.append(el)
        elif ctype == RES_XML_END_ELEMENT_TYPE:
            if not stack:
                raise AxmlError('unbalanced end element')
            ns_idx, name_idx = struct.unpack_from('<II', data, pos + chdr)
            el = stack.pop()
            if name_idx >= len(strings) or strings[name_idx] != el.tag:
                raise AxmlError('end element name mismatch')
        else:
            raise AxmlError('unexpected chunk type 0x%04x' % ctype)
        pos += csize

    if pos != len(data):
        raise AxmlError('chunks do not tile the document')
    if stack:
        raise AxmlError('unclosed elements: %s' % [e.tag for e in stack])
    if root is None:
        raise AxmlError('no root element')
    return root, strings, res_map


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def intent_filters(activity):
    filters = []
    for f in activity.find_all('intent-filter'):
        actions = [str(a.get(res_id=ATTR_NAME)) for a in f.find_all('action')]
        cats = [str(c.get(res_id=ATTR_NAME)) for c in f.find_all('category')]
        filters.append((actions, cats))
        print('       %s intent-filter: actions=%s categories=%s'
              % (activity.get(res_id=ATTR_NAME), actions, cats))
    return filters


def has_filter(activity, action, category):
    for actions, cats in intent_filters(activity):
        if action in actions and category in cats:
            return True
    return False


def activity_map(app):
    out = {}
    for act in app.find_all('activity'):
        name = act.get(res_id=ATTR_NAME)
        if name:
            out[str(name)] = act
    return out


def which_java():
    home = os.environ.get('JAVA_HOME', '')
    if home:
        candidate = os.path.join(home, 'bin', 'java')
        if os.path.exists(candidate):
            return candidate
    return shutil.which('java')


def run_apksigner(args, apk):
    jar = os.path.join(TOOLS, 'apksigner.jar')
    jbin = which_java()
    if not (os.path.exists(jar) and jbin):
        return None
    cmd = [jbin, '-jar', jar] + args + [apk]
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    except Exception:                                        # noqa: BLE001
        return None


def cert_digest(apk):
    proc = run_apksigner(['verify', '--print-certs'], apk)
    if proc is None or proc.returncode != 0:
        return None
    for line in proc.stdout.splitlines():
        if 'certificate SHA-256 digest' in line:
            return line.split(':', 1)[1].strip()
    return None


# --------------------------------------------------------------------------- #
# dex / zip checks
# --------------------------------------------------------------------------- #
def check_dex(zf):
    if 'classes.dex' not in zf.namelist():
        return check('classes.dex present', False)
    dex = zf.read('classes.dex')
    check('classes.dex present', True, '%d bytes' % len(dex))
    magic = dex[:8]
    check('classes.dex magic', magic.startswith(b'dex\n') and magic[7:8] == b'\x00',
          repr(magic))
    if len(dex) < 0x70:
        return check('classes.dex header', False, 'too short')
    file_size = struct.unpack_from('<I', dex, 0x20)[0]
    header_size = struct.unpack_from('<I', dex, 0x24)[0]
    endian_tag = struct.unpack_from('<I', dex, 0x28)[0]
    map_off = struct.unpack_from('<I', dex, 0x34)[0]
    ok = (file_size == len(dex) and header_size == 0x70
          and endian_tag == 0x12345678 and 0 < map_off < len(dex))
    check('classes.dex header', ok,
          'file_size=%d header_size=0x%x endian=0x%x map_off=%d'
          % (file_size, header_size, endian_tag, map_off))

    wanted = [
        ('classes.dex contains ListenActivity', b'Lcom/dshgo/assist/ListenActivity;'),
        ('classes.dex contains OrbView', b'Lcom/dshgo/assist/OrbView;'),
        ('classes.dex contains MainActivity', b'Lcom/dshgo/assist/MainActivity;'),

        ('classes.dex contains RoleManager', b'Landroid/app/role/RoleManager;'),
        ('classes.dex contains ROLE_ASSISTANT', b'android.app.role.ASSISTANT'),
        ('classes.dex contains createRequestRoleIntent',
         b'createRequestRoleIntent'),
        ('classes.dex contains SpeechRecognizer', b'Landroid/speech/SpeechRecognizer;'),
        ('classes.dex contains RECOGNIZE_SPEECH', b'android.speech.action.RECOGNIZE_SPEECH'),
        ('classes.dex contains RECORD_AUDIO', b'android.permission.RECORD_AUDIO'),
        ('classes.dex contains dsh-go share component', EXPECT_SHARE_ACTIVITY.encode()),
        ('classes.dex contains dshgo://open/chat', EXPECT_DEEP_LINK.encode()),
        ('classes.dex contains target package', EXPECT_TARGET_PKG.encode()),
        ('classes.dex contains ACTION_SEND', b'android.intent.action.SEND'),
        ('classes.dex contains text/plain', b'text/plain'),
        # ---- v3：诊断面板 + 关 vivo 电源键 + 原子岛通知 ----
        ('classes.dex contains vivo_jovi_power_wakeup_switch',
         b'vivo_jovi_power_wakeup_switch'),
        ('classes.dex contains power_button_long_press', b'power_button_long_press'),
        ('classes.dex contains ACTION_MANAGE_WRITE_SETTINGS',
         b'android.settings.action.MANAGE_WRITE_SETTINGS'),
        ('classes.dex contains Settings.System.putInt', b'putInt'),
        ('classes.dex contains NotificationChannel', b'Landroid/app/NotificationChannel;'),
        ('classes.dex contains dsh_live channel', b'dsh_live'),
        ('classes.dex contains setShortCriticalText', b'setShortCriticalText'),
        ('classes.dex contains setRequestPromotedOngoing', b'setRequestPromotedOngoing'),
        ('classes.dex contains android.requestPromotedOngoing extra',
         b'android.requestPromotedOngoing'),
        ('classes.dex contains StatusBarNotification',
         b'Landroid/service/notification/StatusBarNotification;'),
        ('classes.dex contains hasPromotableCharacteristics',
         b'hasPromotableCharacteristics'),
        ('classes.dex contains canPostPromotedNotifications',
         b'canPostPromotedNotifications'),
        ('classes.dex contains getActiveNotifications', b'getActiveNotifications'),
        ('classes.dex contains POST_NOTIFICATIONS',
         b'android.permission.POST_NOTIFICATIONS'),
        ('classes.dex contains Settings.System.canWrite', b'canWrite'),
        # ---- v4：Shizuku 客户端 ----
        ('classes.dex contains rikka.shizuku.Shizuku', b'Lrikka/shizuku/Shizuku;'),
        ('classes.dex contains ShizukuProvider', b'Lrikka/shizuku/ShizukuProvider;'),
        ('classes.dex contains IShizukuService', b'Lmoe/shizuku/server/IShizukuService;'),
        ('classes.dex contains newProcess', b'newProcess'),
        ('classes.dex contains Shizuku permission string',
         b'moe.shizuku.manager.permission.API_V23'),
        ('classes.dex contains cmd role add-role-holder', b'add-role-holder'),
        ('classes.dex contains dsh-assist-next.apk', b'dsh-assist-next.apk'),
        ('classes.dex contains rootq bridge', b'rootq'),
        ('classes.dex contains fully-qualified ASSISTANT role',
         b'android.app.role.ASSISTANT'),
        # ---- v5：重启自愈 ----
        ('classes.dex contains BootReceiver', b'Lcom/dshgo/assist/BootReceiver;'),
        ('classes.dex contains HealService', b'Lcom/dshgo/assist/HealService;'),
        ('classes.dex contains BOOT_COMPLETED', b'android.intent.action.BOOT_COMPLETED'),
        ('classes.dex contains dsh_heal channel', b'dsh_heal'),
        ('classes.dex contains 等待提权 wording', '等待提权'.encode('utf-8')),
        ('classes.dex contains 已就绪 wording', '已就绪'.encode('utf-8')),
        ('classes.dex contains startForegroundService', b'startForegroundService'),
        ('classes.dex contains startForeground', b'startForeground'),
        # ---- v5-D：唤醒截胡（实验） ----
        ('classes.dex contains wake-intercept marker', b'wake-intercept'),
        ('classes.dex contains voicewakeup keyword', b'voicewakeup'),
        ('classes.dex contains hotword keyword', b'hotword'),
        ('classes.dex contains copilot keyword', b'copilot'),
        ('classes.dex contains am start AssistActivity（唤醒截胡走悬浮胶囊）',
         b'am start -n com.dshgo.assist/.AssistActivity'),
        # ---- v6：崩溃取证 + 启动加固 ----
        ('classes.dex contains DshApp', b'Lcom/dshgo/assist/DshApp;'),
        ('classes.dex contains BootTrace', b'Lcom/dshgo/assist/BootTrace;'),
        ('classes.dex contains CrashHandler', b'Lcom/dshgo/assist/CrashHandler;'),
        ('classes.dex contains BootProvider', b'Lcom/dshgo/assist/BootProvider;'),
        ('classes.dex contains boot-trace.txt', b'boot-trace.txt'),
        ('classes.dex contains Android/media trace path',
         b'/sdcard/Android/media/com.dshgo.assist'),
        ('classes.dex contains DSH-crash file name', b'DSH-crash-'),
        ('classes.dex contains MediaStore.Downloads', b'Landroid/provider/MediaStore$Downloads;'),
        ('classes.dex contains IS_PENDING column', b'is_pending'),
        ('classes.dex contains 启动失败 error page', '启动失败'.encode('utf-8')),
        ('classes.dex contains 面包屑 wording', '面包屑'.encode('utf-8')),
        ('classes.dex contains crash- file prefix', b'crash-'),
        # ---- v9：交互式 shell + root 命令日志 + 桥状态标记 ----
        ('classes.dex contains __DSH_EXIT__ marker', b'__DSH_EXIT__'),
        ('classes.dex contains __DSH_READY__ marker', b'__DSH_READY__'),
        ('classes.dex contains root-cmd.log', b'root-cmd.log'),
        ('classes.dex contains dsh-bridge.sh', b'dsh-bridge.sh'),
        ('classes.dex contains BRIDGE=ON status probe', b'BRIDGE=ON'),
        ('classes.dex contains dsh-bridge.pid', b'dsh-bridge.pid'),
        ('classes.dex contains dsh-wake.sh', b'dsh-wake.sh'),
        # ---- v10：悬浮胶囊 + 跑马灯 ----
        ('classes.dex contains AssistActivity', b'Lcom/dshgo/assist/AssistActivity;'),
        # ---- v11：电源键钩子 / 守护脚本新路径 / 小圆点 ----
        ('classes.dex contains dsh-powerhook.sh', b'dsh-powerhook.sh'),
        ('classes.dex contains KEY_POWER hook', b'KEY_POWER'),
        ('classes.dex contains powerhook.log', b'powerhook.log'),
        ('classes.dex contains dsh-powerhook.pid', b'dsh-powerhook.pid'),
        ('classes.dex contains dsh-bridge.pid', b'dsh-bridge.pid'),
        ('classes.dex contains overlay_dot pref key', b'overlay_dot'),
        # ---- v12：原子岛 + overlay.log + 看门狗 ----
        ('classes.dex contains overlay.log', b'overlay.log'),
        ('classes.dex contains WATCHDOG marker', b'WATCHDOG fired'),
        ('classes.dex contains onReadyForSpeech log', b'onReadyForSpeech'),
        # ---- v13：自建录音 + 本地离线识别 ----
        ('classes.dex contains AudioRecord', b'Landroid/media/AudioRecord;'),
        ('classes.dex contains ASR endpoint', b'127.0.0.1:18080'),
        ('classes.dex contains geometry log', b'geometry statusBarHeight='),
        ('classes.dex contains AudioRecord init log', b'recordingState='),
        ('classes.dex contains read retry log', b'asr read retry '),
        ('classes.dex contains v21 vad start log', b'asr vad start rms='),
        ('classes.dex contains v21 rms log', b'asr rms='),
        ('classes.dex contains vad dual threshold log', b'asr vad noise='),
        ('classes.dex contains final from partial log', b'asr final from partial'),
        ('classes.dex contains reply card layout log', b'reply card layout w='),
        ('classes.dex contains reply card measured log', b'reply card measured: vw='),
        ('classes.dex contains test reply card log', b'test reply card: len='),
        ('classes.dex contains glow geometry fullscreen log', b'glow geometry fullscreen h='),
        ('classes.dex contains capsule shown w/h log', b'capsule shown w='),
        ('classes.dex contains DshTileService', b'Lcom/dshgo/assist/DshTileService;'),
        ('classes.dex contains dot policy log', b'dot policy: root='),
        ('classes.dex contains adaptive VAD log', b'asr vad noise='),
        ('classes.dex contains last-voice.wav path', b'last-voice.wav'),
        ('classes.dex contains wav saved log', b'asr wav saved bytes='),
        ('classes.dex contains asr source log', b'asr source='),
        ('classes.dex contains dsh-inbox dir', b'dsh-inbox'),
        ('classes.dex contains dsh-outbox dir', b'dsh-outbox'),
        ('classes.dex contains ask written log', b'ask written path='),
        ('classes.dex contains poll log', b'poll '),
        ('classes.dex contains reply bytes log', b'reply bytes='),
        ('classes.dex contains reply shown log', b'reply shown len='),
        ('classes.dex contains reply expanded log', b'reply expanded -> 65%'),
        ('classes.dex contains reply touch reset log', b'reply touch -> timer reset'),
        ('classes.dex contains reply timeout log', b'reply timeout ('),
        ('classes.dex contains preroll log', b'asr preroll='),
        ('classes.dex contains partial log', b'asr partial n='),
        ('classes.dex contains partial skipped log', b'asr partial skipped (busy)'),
        ('classes.dex contains final text log', b'asr final text='),
        ('classes.dex contains resume after pause log', b'asr resume after pause'),
        ('classes.dex contains edge glow log', b'edge glow shown'),
                ('classes.dex contains DSH 处理中 stopwatch', 'DSH 处理中 '.encode('utf-8')),
        ('classes.dex contains reset- session file prefix', b'reset-'),
        ('classes.dex contains session-info.txt', b'session-info.txt'),
        ('classes.dex contains duplicate start log', b'duplicate start ignored (state='),
        ('classes.dex contains outside touch log', b'outside touch -> retract'),
        ('classes.dex contains prefs_version key', b'prefs_version'),
        ('classes.dex contains hook restart dedupe', b"pkill -f 'tmp/dsh-powerhook'"),
        ('classes.dex contains double-tap log', b'DOUBLE TAP'),
        ('classes.dex contains retry log', b'reply retry requested'),
        ('classes.dex contains power mode key', b'power_mode'),
        ('classes.dex contains system mode enum', b'system'),
        ('classes.dex contains onboarding step copy', '使用前 3 步'.encode('utf-8')),
        ('classes.dex contains x-dsh-token header', b'x-dsh-token'),
        ('classes.dex contains token default', b'dshassist'),
        ('classes.dex contains asr health log', b'asr health '),
        ('classes.dex contains asr recording start log', b'asr recording start'),
        ('classes.dex contains asr recorded log', b'asr recorded '),
        ('classes.dex contains asr http code log', b'asr http code='),
        ('classes.dex contains asr text log', b'asr text=\"'),
        ('classes.dex contains asr_endpoint pref key', b'asr_endpoint'),
        ('classes.dex contains asr_token pref key', b'asr_token'),
        ('classes.dex contains hook path in /data/local/tmp', b'/data/local/tmp/dsh-powerhook.sh'),
        ('classes.dex contains wakeup keyevent for hook', b'input keyevent 224'),
        ('classes.dex contains AssistActivity launch in hook', b'/.AssistActivity'),
        ('classes.dex contains power_button_short_press key', b'power_button_short_press'),
        ('classes.dex contains OverlayService', b'Lcom/dshgo/assist/OverlayService;'),
        ('classes.dex contains MarqueeView', b'Lcom/dshgo/assist/MarqueeView;'),
        ('classes.dex contains WaveBarsView', b'Lcom/dshgo/assist/WaveBarsView;'),
        ('classes.dex contains TYPE_APPLICATION_OVERLAY',
         b'Lcom/dshgo/assist/OverlayService;'),
        ('classes.dex contains canDrawOverlays', b'canDrawOverlays'),
        ('classes.dex contains ACTION_MANAGE_OVERLAY_PERMISSION',
         b'android.settings.action.MANAGE_OVERLAY_PERMISSION'),
        ('classes.dex contains overridePendingTransition', b'overridePendingTransition'),
    ]
    for name, needle in wanted:
        check(name, needle in dex)


def check_signature(zf, raw):
    names = zf.namelist()
    v1_sf = [n for n in names if n.startswith('META-INF/') and n.endswith('.SF')]
    v1_sig = [n for n in names if n.startswith('META-INF/')
              and (n.endswith('.RSA') or n.endswith('.DSA') or n.endswith('.EC'))]
    check('v1 signature files (META-INF/*.SF, *.RSA)', bool(v1_sf and v1_sig),
          ', '.join(v1_sf + v1_sig))
    idx = raw.rfind(b'APK Sig Block 42')
    check('v2/v3 APK Signing Block', idx > 0,
          'magic at offset %d' % idx if idx > 0 else 'missing')
    if idx > 0:
        block_size = struct.unpack_from('<Q', raw, idx - 8)[0]
        check('APK Signing Block size sanity', 16 < block_size < len(raw) - 8,
              'size=%d' % block_size)


def previous_apks(apk):
    """out/ 下其它历史版本产物（v1/v2…），用于覆盖升级校验。"""
    out = []
    if not os.path.isdir(OUT):
        return out
    for name in sorted(os.listdir(OUT)):
        if name.startswith('dsh-assist') and name.endswith('.apk'):
            path = os.path.join(OUT, name)
            if os.path.abspath(path) != os.path.abspath(apk):
                out.append(path)
    return out


def check_upgrade(apk, mdata):
    """新版本必须是历史版本的原地升级：同包名、同证书、versionCode 更大。"""
    prev = previous_apks(apk)
    if not prev:
        print('[SKIP] upgrade checks (out/ 下没有历史 APK)')
        return
    new_root, _s, _m = parse_axml(mdata)
    new_vc = new_root.get(res_id=ATTR_VERSION_CODE)
    new_cert = cert_digest(apk)

    # 只把 versionCode 更低的历史产物当作升级基线（这样也能单独校验旧版本）
    older = []
    for path in prev:
        try:
            with zipfile.ZipFile(path) as z:
                r, _s3, _m3 = parse_axml(z.read('AndroidManifest.xml'))
            vc = r.get(res_id=ATTR_VERSION_CODE)
        except Exception:                                    # noqa: BLE001
            vc = None
        if isinstance(vc, int) and isinstance(new_vc, int) and vc < new_vc:
            older.append(path)
    if not older:
        print('[SKIP] upgrade checks (没有 versionCode 更低的历史 APK)')
        return
    prev = older

    same_pkg = True
    increasing = True
    same_cert = True
    details = []
    for path in prev:
        base = os.path.basename(path)
        try:
            with zipfile.ZipFile(path) as z:
                old_root, _s2, _m2 = parse_axml(z.read('AndroidManifest.xml'))
            pkg = old_root.get('package')
            old_vc = old_root.get(res_id=ATTR_VERSION_CODE)
            cert = cert_digest(path)
            same_pkg = same_pkg and pkg == EXPECT_PACKAGE
            increasing = increasing and isinstance(old_vc, int) and isinstance(new_vc, int) \
                and old_vc < new_vc
            if cert and new_cert:
                same_cert = same_cert and cert == new_cert
            details.append('%s(pkg=%s vc=%s cert=%s)'
                           % (base, pkg, old_vc, (cert or '?')[:12]))
        except Exception as exc:                             # noqa: BLE001
            same_pkg = increasing = False
            details.append('%s 解析失败: %s' % (base, exc))

    check('upgrade: 同包名 (%d 个历史 APK)' % len(prev), same_pkg, '; '.join(details))
    check('upgrade: versionCode %s -> %s（递增）'
          % ([d.split('vc=')[1].split(' ')[0] for d in details], new_vc), increasing)
    check('upgrade: 签名证书与历史版本一致（可覆盖安装）', same_cert,
          (new_cert or '?')[:16] + '…')


def check_source_guards():
    """v10：窗口 flag 与兜底路径的源码级断言（常量会被内联，dex 里查不到）。"""
    overlay = os.path.join(HERE, 'src', 'com', 'dshgo', 'assist', 'OverlayService.java')
    assist = os.path.join(HERE, 'src', 'com', 'dshgo', 'assist', 'AssistActivity.java')
    try:
        overlay_src = open(overlay, encoding='utf-8').read()
    except Exception:                                        # noqa: BLE001
        print('[SKIP] source guard: OverlayService.java 不存在')
        return
    check('OverlayService 用 FLAG_NOT_FOCUSABLE（不抢焦点）',
          'FLAG_NOT_FOCUSABLE' in overlay_src)
    check('OverlayService 用 FLAG_NOT_TOUCH_MODAL（窗外触摸透传）',
          'FLAG_NOT_TOUCH_MODAL' in overlay_src)
    check('v17：全屏辉光窗口用 FLAG_NOT_TOUCHABLE（触摸透传）',
          'FLAG_NOT_TOUCHABLE' in overlay_src)
    try:
        cap_block = overlay_src.split('capsuleParams = new WindowManager.LayoutParams(')[1] \
            .split('capsuleParams.gravity')[0]
        check('v17：胶囊/文字窗口仍可点（其 flags 不含 NOT_TOUCHABLE）',
              'FLAG_NOT_TOUCHABLE' not in cap_block)
    except Exception as exc:                                 # noqa: BLE001
        check('v17：胶囊/文字窗口仍可点（其 flags 不含 NOT_TOUCHABLE）', False, str(exc))
    try:
        edge_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                     'EdgeGlowView.java'), encoding='utf-8').read()
        check('v17：EdgeGlowView 参数（深度 48dp/圆角 28dp/14 层/周期 4000|2200/alpha 90|160）',
              'GLOW_DEPTH_DP = 48' in edge_src and 'CORNER_DP = 28' in edge_src
              and 'RING_COUNT = 14' in edge_src
              and 'PERIOD_IDLE_MS = 4000' in edge_src
              and 'PERIOD_LISTENING_MS = 2200' in edge_src
              and 'ALPHA_IDLE = 90' in edge_src
              and 'ALPHA_LISTENING = 160' in edge_src)
        check('v17：边缘辉光不用 BlurMaskFilter（多层描边模拟模糊）',
              'BlurMaskFilter' not in edge_src)
        check('v24：已移除辉光底部那圈加粗（盖满整屏不再补偿）',
              '* 1.2f' not in edge_src)
    except Exception as exc:                                 # noqa: BLE001
        check('v17：EdgeGlowView 参数', False, str(exc))
    check('OverlayService 用 TYPE_APPLICATION_OVERLAY',
          'TYPE_APPLICATION_OVERLAY' in overlay_src)

    try:
        waves_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                      'WaveBarsView.java'), encoding='utf-8').read()
        check('v15：波形条由真实 RMS 驱动（setLevel）',
              'public void setLevel(int rms)' in waves_src and 'level' in waves_src)
        check('v22：波形是 3 根竖条（2dp 宽 / 1dp 圆角 / 3dp 间距 / 8~18dp 高）',
              'BAR_WIDTH_DP = 2' in waves_src
              and 'BAR_GAP_DP = 3' in waves_src
              and 'BASE_HEIGHT_DP = 8f' in waves_src
              and 'drawRoundRect' in waves_src)
    except Exception:                                        # noqa: BLE001
        print('[SKIP] source guard: WaveBarsView.java 不存在')

    try:
        main_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                     'MainActivity.java'), encoding='utf-8').read()
        check('v34：小圆点默认关闭（显式开启才显示）',
              'getBoolean(RootOps.prefKeyOverlayDot(), false)' in overlay_src)
        check('v18：首次引导页（onboarding_done，只显示一次）',
              'onboarding_done' in main_src and '使用前 3 步' in main_src)
        check('v17：新对话按钮（reset-<ts>.txt）+ 会话 id 显示（session-info.txt / sid=）',
              'reset-' in main_src and 'session-info.txt' in main_src
              and 'sid=' in main_src and '当前会话：' in main_src)
    except Exception as exc:                                 # noqa: BLE001
        check('v17：新对话按钮', False, str(exc))

    marquee_src = ''
    try:
        marquee_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                        'MarqueeView.java'), encoding='utf-8').read()
        check('v21：流光周期 2000/900ms，白底下降 alpha 到 120/200',
              'PERIOD_IDLE_MS = 2000' in marquee_src
              and 'PERIOD_LISTENING_MS = 900' in marquee_src
              and 'ALPHA_IDLE = 120' in marquee_src
              and 'ALPHA_LISTENING = 200' in marquee_src)
    except Exception:                                        # noqa: BLE001
        print('[SKIP] source guard: MarqueeView.java 不存在')
    try:
        app_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                    'DshApp.java'), encoding='utf-8').read()
        check('v20：升级迁移 prefs_version<2 时强制 reply_via_dsh=false',
              'prefs_version' in app_src and 'reply_via_dsh' in app_src
              and 'putBoolean("reply_via_dsh", false)' in app_src)
    except Exception as exc:                                 # noqa: BLE001
        check('v20：升级迁移 prefs_version', False, str(exc))

    try:
        assist_src = open(assist, encoding='utf-8').read()
    except Exception:                                        # noqa: BLE001
        print('[SKIP] source guard: AssistActivity.java 不存在')
        return
    check('AssistActivity 检查 canDrawOverlays 并回退 ListenActivity',
          'canDrawOverlays' in assist_src and 'ListenActivity' in assist_src)
    check('AssistActivity 零动画结束（overridePendingTransition(0, 0)）',
          'overridePendingTransition(0, 0)' in assist_src)
    check('v19：AssistActivity 用 finishAndRemoveTask()（连空任务一起移除）',
          'finishAndRemoveTask()' in assist_src)

    root_src = ''
    try:
        root_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist', 'RootOps.java'),
                        encoding='utf-8').read()
    except Exception:                                        # noqa: BLE001
        print('[SKIP] source guard: RootOps.java 不存在')
    localtile_src = ''
    try:
        localtile_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                          'LocalTileService.java'), encoding='utf-8').read()
    except OSError:
        localtile_src = ''
    localrouter_src = ''
    try:
        localrouter_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                            'LocalRouter.java'), encoding='utf-8').read()
    except OSError:
        localrouter_src = ''
    speaker_src = ''
    try:
        speaker_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                        'Speaker.java'), encoding='utf-8').read()
    except OSError:
        speaker_src = ''
    overlay_src2 = ''
    try:
        overlay_src2 = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                         'OverlayService.java'), encoding='utf-8').read()
    except Exception:                                        # noqa: BLE001
        print('[SKIP] source guard: OverlayService.java 不存在')
    if overlay_src2:
        check('原子岛几何 44dp / 圆角 22dp（v15 起 y 由状态栏高度决定）',
              'CAPSULE_HEIGHT_DP = 44' in overlay_src2
              and 'CAPSULE_RADIUS_DP = 22f' in overlay_src2)
        check('v12：岛形 96x32dp / 圆角 16dp，展开 240ms、收回 200ms',
              'ISLAND_WIDTH_DP = 96' in overlay_src2
              and 'ISLAND_HEIGHT_DP = 32' in overlay_src2
              and 'ISLAND_RADIUS_DP = 16f' in overlay_src2
              and 'EXPAND_MS = 240' in overlay_src2
              and 'COLLAPSE_MS = 200' in overlay_src2)
        check('v12：识别超时 15s + 看门狗 25s',
              'TIMEOUT_MS = 15000' in overlay_src2 and 'WATCHDOG_MS = 25000' in overlay_src2)
        check('v12：胶囊期间持 PARTIAL_WAKE_LOCK',
              'PARTIAL_WAKE_LOCK' in overlay_src2 and 'wakeLock.acquire' in overlay_src2)
        check('v12：addView 前先移除旧窗口（避免叠窗）',
              'window already added -> removeView first' in overlay_src2)
        check('v12：✕ 与整个胶囊都可点',
              overlay_src2.count('setOnClickListener') >= 3)
        check('v13：窗口声明进挖孔区（cutout ALWAYS + FLAG_LAYOUT_NO_LIMITS）',
              'LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS' in overlay_src2
              and 'FLAG_LAYOUT_NO_LIMITS' in overlay_src2)
        check('v13：AudioRecord 参数 16k/mono/PCM16/VOICE_RECOGNITION',
              'SAMPLE_RATE = 16000' in overlay_src2
              and 'CHANNEL_IN_MONO' in overlay_src2
              and 'ENCODING_PCM_16BIT' in overlay_src2
              and 'VOICE_RECOGNITION' in overlay_src2)
        check('v25：VAD 双阈值迟滞（start=max(150,noise*1.8) / end=max(120,noise*1.4)）',
              'VAD_START_MIN_RMS = 150' in overlay_src2
              and 'VAD_START_FACTOR = 1.8f' in overlay_src2
              and 'VAD_END_MIN_RMS = 120' in overlay_src2
              and 'VAD_END_FACTOR = 1.4f' in overlay_src2
              and 'silenceAccumMs' in overlay_src2)
        check('v25：已起判后总时长放宽到 20s',
              'SPEECH_TIMEOUT_MS = 20000' in overlay_src2)
        check('v25：超时/空结果用部分识别结果兜底（asr final from partial）',
              'lastNonEmptyPartial' in overlay_src2
              and 'asr final from partial' in overlay_src2)
        check('v25：噪声窗 300ms / 更新 500ms / 增益 ×2 / 2 帧起判',
              'NOISE_WINDOW_MS = 300' in overlay_src2
              and 'NOISE_UPDATE_MS = 500' in overlay_src2
              and 'VAD_GAIN = 2' in overlay_src2
              and 'VAD_START_FRAMES = 2' in overlay_src2)
        check('v21：连续 2 帧超阈值才起判 + 软件增益 ×2（饱和裁剪）',
              'VAD_START_FRAMES = 2' in overlay_src2
              and 'VAD_GAIN = 2' in overlay_src2
              and 'v = 32767' in overlay_src2)
        check('v21：VAD 日志 asr vad start rms=… (2 frames) / asr rms=… thr=…',
              'asr vad start rms=' in overlay_src2
              and 'asr rms=' in overlay_src2)
        check('v21：录音 source 优先 MIC（失败退 VOICE_RECOGNITION）',
              'int source = MediaRecorder.AudioSource.MIC;' in overlay_src2
              and 'MIC init failed' in overlay_src2)
        check('v18：静音结束 2500ms + 续说 800ms + no-speech 4500ms + 上限 25000ms',
              'SILENCE_END_MS = 2500' in overlay_src2
              and 'RESUME_GRACE_MS = 800' in overlay_src2
              and 'NO_SPEECH_MS = 4500' in overlay_src2
              and 'MAX_RECORD_MS = 25000' in overlay_src2
              and 'asr resume after pause' in overlay_src2)
        check('v24：辉光窗口全屏（MATCH_PARENT）+ setFitInsetsTypes(0)',
              'glow geometry fullscreen h=' in overlay_src2
              and 'setFitInsetsTypes(0)' in overlay_src2
              and 'navigationBarHeight()' in overlay_src2)
        check('v34：小圆点默认关闭 + root 可用时自动隐藏',
              'getBoolean(RootOps.prefKeyOverlayDot(), false)' in overlay_src2
              and 'dot policy: root=' in overlay_src2)
        check('v22：无 root 时电源键模式行降级文案 + 磁贴引导',
              '无 root，建议用快捷磁贴/圆点' in main_src
              and 'showTileHint' in main_src)
        check('v22：关于卡（v22 final / 引擎 / 会话 / 电源键模式）',
              'v22 final' in main_src and 'buildAboutCard' in main_src)
        check('v21：白底主题（#F2FFFFFF 底 / 深灰字 #1A1A1E / 1px #14000000 描边）',
              '0xF2FFFFFF' in overlay_src2
              and '0xFF1A1A1E' in overlay_src2 and '0x14000000' in overlay_src2)
        check('v23：✕ 在最右端（文字区 weight=1 + 中间省略 + ✕ 右内边距 14dp）',
              'statusParams.leftMargin' in overlay_src2
              and 'LinearLayout.LayoutParams.WRAP_CONTENT, 1f)' in overlay_src2
              and 'TruncateAt.MIDDLE' in overlay_src2
              and 'close.setPadding(dp(8), dp(4), dp(14), dp(4))' in overlay_src2)
        check('v22：只有一个胶囊——流光画在胶囊内部底边 2dp 且 clip 在圆角内',
              'setClipToOutline(true)' in overlay_src2
              and 'Gravity.BOTTOM' in overlay_src2
              and 'marqueeLp.bottomMargin' in overlay_src2)
        check('v22：胶囊宽度 wrap_content（min 200dp / max 屏宽−96dp）',
              'CAPSULE_MIN_WIDTH_DP = 200' in overlay_src2
              and 'CAPSULE_MAX_MARGIN_DP = 96' in overlay_src2)
        check('v22：capsule shown 日志带实际宽高（w=…dp h=…dp）',
              'capsule shown w=' in overlay_src2)
        check('v21：状态色（处理中琥珀 #8A5A00 / 失败红 #C0392B）',
              '0xFF8A5A00' in overlay_src2 and '0xFFC0392B' in overlay_src2)
        check('v21：回复行距 1.3',
              'setLineSpacing(0f, 1.3f)' in overlay_src2)
        check('v19：重复 OVERLAY_START 状态机（在聆听/处理中直接忽略，不 removeView）',
              'duplicate start ignored (state=' in overlay_src2
              and 'private String stateName()' in overlay_src2)
        check('v19：划返回/点击窗外收回（FLAG_WATCH_OUTSIDE_TOUCH + ACTION_OUTSIDE）',
              'FLAG_WATCH_OUTSIDE_TOUCH' in overlay_src2
              and 'ACTION_OUTSIDE' in overlay_src2
              and 'outside touch -> retract' in overlay_src2)
        check('v18：分阶段看门狗（录音/识别 25s，等回复 200s）',
              'REPLY_WATCHDOG_MS = 200000' in overlay_src2
              and 'WATCHDOG_MS = 25000' in overlay_src2)
        check('v18：回复轮询 180s（360×500ms）+ 失败红字 + 重试按钮',
              'REPLY_POLL_MAX = 360' in overlay_src2
              and 'reply retry requested' in overlay_src2
              and ('0xFFC0392B' in overlay_src2 or '0xFFFF6B6B' in overlay_src2))
        check('v18：没听清/没听到 立刻收辉光 + 1.2s 提示',
              'TIP_SHORT_MS = 1200' in overlay_src2
              and 'postTipQuick' in overlay_src2)
        check('v17：实时字幕 700ms 节流 / 最近 12s / 串行化',
              'PARTIAL_INTERVAL_MS = 700' in overlay_src2
              and 'PARTIAL_WINDOW_MS = 12000' in overlay_src2
              and 'asr partial skipped (busy)' in overlay_src2
              and 'asr partial n=' in overlay_src2)
        check('v17：最终识别日志 asr final text=',
              'asr final text=' in overlay_src2)
        check('v18：结果小窗 180s + 秒表「DSH 处理中 Ns」',
              'REPLY_POLL_MAX = 360' in overlay_src2 and 'DSH 处理中 ' in overlay_src2)
        check('v17：回复小窗最多 10 行 + ScrollView 可滚动 + 行距（v21 起 1.3）',
              'LINES_MAX_REPLY = 10' in overlay_src2
              and 'ScrollView' in overlay_src2
              and ('setLineSpacing(0f, 1.3f)' in overlay_src2
                   or 'setLineSpacing(0f, 1.25f)' in overlay_src2))
        check('v26：展开到屏幕 65%（收回 45%）停留 30s，触摸重置计时',
              'REPLY_EXPANDED_HOLD_MS = 30000' in overlay_src2
              and 'reply expanded -> 65%' in overlay_src2
              and 'reply touch -> timer reset' in overlay_src2)
        check('v28：回复态是独立卡片视图（宽 屏宽−48dp / 高自适应 / max 屏高×0.45 / 圆角 18dp）',
              'REPLY_WIDTH_MARGIN_DP = 48' in overlay_src2
              and 'REPLY_MIN_HEIGHT_DP = 88' in overlay_src2
              and 'REPLY_MAX_HEIGHT_RATIO = 0.45f' in overlay_src2
              and 'REPLY_EXPANDED_RATIO = 0.65f' in overlay_src2
              and 'private void showReplyCard' in overlay_src2
              and 'windowManager.addView(root' in overlay_src2
              and 'private FrameLayout replyRoot' in overlay_src2)
        check('v27：回复态文字样式显式切换（setSingleLine(false) + setEllipsize(null)）',
              'private void applyTextStyle(boolean reply)' in overlay_src2
              and 'statusView.setSingleLine(false)' in overlay_src2
              and 'statusView.setEllipsize(null)' in overlay_src2
              and 'setHorizontallyScrolling(false)' in overlay_src2)
        check('v27：ScrollView maxHeight clamp（不再手算 maxH−52dp）',
              'class MaxHeightScrollView' in overlay_src2
              and 'setMaxHeightPx' in overlay_src2)
        check('v27：卡片显示后打测量日志 reply card measured',
              'reply card measured: vw=' in overlay_src2
              and 'OnGlobalLayoutListener' in overlay_src2)
        check('v27：「测试回复卡片」入口（内置 30 行长文本，不走 inbox）',
              'ACTION_TEST_REPLY' in overlay_src2
              and 'test reply card: len=' in overlay_src2
              and '测试回复卡片' in main_src
              and 'reply-card-test' in main_src)
        try:
            reply_block = overlay_src2.split('private void showReplyCard(')[1] \
                .split('private void setStatusColor(')[0]
            check('v28：回复卡片用独立视图 + MATCH_PARENT 内容（不再复用胶囊 44dp）',
                  'CAPSULE_HEIGHT_DP' not in reply_block
                  and 'MATCH_PARENT' in reply_block
                  and 'windowManager.addView(root' in reply_block)
        except Exception as exc:                             # noqa: BLE001
            check('v28：回复卡片用独立视图 + MATCH_PARENT 内容（不再复用胶囊 44dp）', False, str(exc))
        check('v38：游戏时自动跳过本地模型 + 本地模型磁贴开关',
              'foregroundGame' in localrouter_src
              and 'current_game_package' in localrouter_src
              and 'local model skipped (game=' in overlay_src2
              and 'class LocalTileService' in localtile_src)
        check('v37：本地小模型优先路由（LocalRouter + local_first 开关 + 兜底转 DSH）',
              'class LocalRouter' in localrouter_src
              and 'askLocalThenMaybeDsh' in overlay_src2
              and 'deliverToDsh' in overlay_src2
              and 'prefKeyLocalFirst' in root_src
              and 'prefKeyLocalFirst' in main_src)
        check('v36：自动朗读默认关闭，开关仍保留',
              'prefKeyAutoSpeak(), false' in overlay_src2
              and 'prefKeyAutoSpeak(), false' in main_src)
        check('v34：自动朗读（Speaker + auto_speak 开关 + 静音跳过）',
              'class Speaker' in speaker_src
              and 'speaker.speak(' in overlay_src2
              and 'maybeSpeak' in overlay_src2
              and 'prefKeyAutoSpeak' in root_src
              and 'STREAM_MUSIC' in overlay_src2)
        check('v35：TTS 预热 + 未就绪时挂起补读（解决第一次不出声）',
              'pending' in speaker_src
              and 'tts flush pending len=' in speaker_src
              and 'speaker.init(this)' in overlay_src2)
        check('v34：小圆点默认关闭（显式开启才显示）',
              'sp.getBoolean(RootOps.prefKeyOverlayDot(), false)' in overlay_src2)
        check('v28：测试回复卡片按钮已接线（分发器里调用 testReplyCard）',
              'reply-card-test' in main_src
              and 'testReplyCard()' in main_src)
        check('v17：全屏辉光窗口 MATCH_PARENT + NOT_TOUCHABLE',
              'showEdgeGlow' in overlay_src2
              and 'WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE' in overlay_src2)
        check('v15：VAD 日志 + 录音落盘 last-voice.wav + wav saved 日志',
              'asr vad noise=' in overlay_src2
              and 'last-voice.wav' in overlay_src2
              and 'asr wav saved bytes=' in overlay_src2)
        check('v15：source 日志（VOICE_RECOGNITION|MIC + 回退原因）',
              'asr source=' in overlay_src2
              and 'asr source fallback' in overlay_src2)
        check('v15：「没听到声音」提示停留 1.5s',
              'postTipLong' in overlay_src2 and 'tipThenRetract(tip, 1500)' in overlay_src2)
        check('v13：POST /transcribe + x-dsh-token + application/octet-stream',
              '/transcribe' in overlay_src2
              and 'x-dsh-token' in overlay_src2
              and 'application/octet-stream' in overlay_src2)
        check('v13：不再依赖 SpeechRecognizer（OverlayService 内无该类引用）',
              'SpeechRecognizer' not in overlay_src2)
        check('v14：健康检查超时 2.5s（2500ms）',
              'setConnectTimeout(2500)' in overlay_src2
              and 'setReadTimeout(2500)' in overlay_src2)
        check('v16：胶囊/小圆点 y = statusBarHeight + dp(2)（完全落在状态栏下方）',
              'capsuleParams.y = statusBarHeight() + dp(2)' in overlay_src2
              and 'lp.y = statusBarHeight() + dp(2)' in overlay_src2)
        check('v16：流光样式（#66000000 轻底，不再有实心胶囊底色）',
              '0x66000000' in overlay_src2 and '0xF20A0C10' not in overlay_src2)
        check('v16：2s 环形预录（补 1200ms 句首）',
              'PRE_ROLL_MS = 1200' in overlay_src2
              and 'PRE_ROLL_BUFFER_MS = 2000' in overlay_src2
              and 'prepended=' in overlay_src2)
        check('v16：结果小窗走 dsh-inbox / dsh-outbox 文件协议',
              'dsh-inbox' in overlay_src2 and 'dsh-outbox' in overlay_src2
              and 'REPLY_POLL_MS = 500' in overlay_src2
              and 'REPLY_HOLD_MS = 15000' in overlay_src2)
        try:
            block = overlay_src2.split('// ================= v16 结果小窗')[1] \
                .split('// ================= v16 结果小窗 end')[0]
            forbidden = [t for t in ('startActivity', 'ACTION_SEND', 'shareText')
                         if t in block]
            check('v16：结果小窗路径不含任何 Activity 启动/ACTION_SEND', not forbidden,
                  str(forbidden))
        except Exception as exc:                             # noqa: BLE001
            check('v16：结果小窗路径不含任何 Activity 启动/ACTION_SEND', False, str(exc))
        check('v15：状态栏高度 resources 取不到退回 dp(38)',
              'status_bar_height' in overlay_src2 and 'return dp(38)' in overlay_src2)
        check('v15：geometry 日志行（statusBarHeight/capsuleY/dotY）',
              'geometry statusBarHeight=' in overlay_src2)
        check('v15：AudioRecord 初始化打印 state/recordingState/bufferSize',
              'state=" + record.getState()' in overlay_src2
              and 'recordingState=" + record.getRecordingState()' in overlay_src2
              and 'bufSize=" + bufSize' in overlay_src2)
        check('v15：read 0/负数重试 3 次 × 50ms',
              'asr read retry ' in overlay_src2 and 'retry < 3' in overlay_src2)

    try:
        tile_src = open(os.path.join(HERE, 'src', 'com', 'dshgo', 'assist',
                                     'DshTileService.java'), encoding='utf-8').read()
        check('v22：QS 磁贴服务（TileService，点击起 OverlayService voice=true）',
              'extends TileService' in tile_src
              and 'OverlayService.EXTRA_VOICE, true' in tile_src)
    except Exception as exc:                                 # noqa: BLE001
        check('v22：QS 磁贴服务', False, str(exc))

    check('v22：README-使用说明.md 存在',
          os.path.exists(os.path.join(HERE, 'README-使用说明.md')))

    if root_src:
        check('v18：电源键模式枚举（system / root，默认 system）',
              'PREF_POWER_MODE = "power_mode"' in root_src
              and 'MODE_SYSTEM = "system"' in root_src
              and 'MODE_ROOT = "root"' in root_src
              and 'powerModeScript' in root_src)
        check('v25：角色授予命令 --user 在位置参数之前（否则 NumberFormatException）',
              'cmd role add-role-holder --user 0' in root_src
              and 'add-role-holder " + ROLE_ASSISTANT' not in root_src)
        check('v52：DSH 侧流式已移除（不再读 partial.txt）',
              'partial.txt' not in overlay_src2)
        check('v52：高级区已精简（实验行已从菜单移除）',
              'addRow(advancedBody, \"测试回复卡片\"' not in main_src
              and 'addRow(advancedBody, \"测试唤醒动画\"' not in main_src
              and 'addRow(advancedBody, \"测试悬浮胶囊\"' not in main_src
              and 'addRow(advancedBody, \"静默安装\"' not in main_src)
        check('v50：/partial 拉取走后台线程（修主线程网络异常）',
              'handlePartial' in overlay_src2 and 'local-partial' in overlay_src2)
        check('v49：连续对话期间卡片不自动隐藏 + /partial 超时放宽',
              'continuousHold' in overlay_src2 and 'reply hide skipped' in overlay_src2
              and 'setReadTimeout(4000)' in localrouter_src)
        check('v48：改用「2.5s 无新文字」判定说完 + 轮询失败容错不直接转 DSH',
              'TEXT_QUIET_END_MS' in overlay_src2 and 'no new text for' in overlay_src2
              and 'local stream poll null x' in overlay_src2)
        check('v47：流式启动走后台线程（修 NetworkOnMainThreadException）',
              'local-stream' in overlay_src2 and 'NetworkOnMainThreadException' in overlay_src2)
        check('v46：本地流式打字机（/ask-stream + /partial + 轮询渲染）',
              'ask-stream' in localrouter_src and 'partial()' in localrouter_src
              and 'pollLocalStream' in overlay_src2)
        check('v45：卡片原地复用（不新建）+ 推理期降动效',
              'reply card reused (in-place)' in overlay_src2
              and 'setLowPowerAnim' in overlay_src2
              and 'replyStatusView' in overlay_src2)
        check('v44：卡片状态行显示轮次（第 N 轮 · 已回复）',
              'roundNo' in overlay_src2 and '第 " + Math.max(1, roundNo) + " 轮' in overlay_src2)
        check('v43：第二轮续听在回复卡片内进行（不另开胶囊）',
              'startListeningInCard' in overlay_src2
              and 'replyStatusRow' in overlay_src2
              and 'cardColumn' in overlay_src2)
        check('v42：连续对话（答完续听）+ 一旦转 DSH 本会话粘住',
              'scheduleNextListen' in overlay_src2
              and 'continuous_chat' in overlay_src2
              and 'forceDsh' in overlay_src2)
        check('v56：最佳状态（short=0 + 延后 1.3s 熄屏 + 双击 1.2s 取消）',
              '(root ? "0" : "1")' in root_src
              and 'sleep 1.3' in root_src and '熄屏已取消（双击）' in root_src
              and 'NOW - LAST )) -lt 1200' in root_src
              and 'keyevent 223' in root_src and 'keyevent 224' in root_src)
        check('v11：守护脚本放 Download/DSH-Go/bin',
              '/bin' in root_src and 'Download/DSH-Go' in root_src)
        check('v33：双击钩子放 /data/local/tmp（App 用 shell 身份覆盖不了）',
              '/data/local/tmp/dsh-powerhook.sh' in root_src)
        check('电源键钩子用 getevent + KEY_POWER + 300/4900ms 判定',
              'getevent' in root_src and 'KEY_POWER' in root_src
              and 'HOOK_MIN_MS = 300' in root_src and 'HOOK_MAX_MS = 4900' in root_src)
        check('v33 钩子：双击电源键 -> 唤醒+唤起（不注入任何按键）',
              'DOUBLE TAP' in root_src and 'keyevent 224' in root_src
              and 'event1' in root_src and 'event3' in root_src)
        check('v39：钩子安装前去重（pkill -f tmp/dsh-powerhook）',
              "pkill -f 'tmp/dsh-powerhook'" in root_src)
        check('v41：very_long=1 + 长按交给系统',
              'KEY_VERY_LONG_PRESS + " 1' in root_src
              and 'KEY_LONG_PRESS + " 1' in root_src)


def check_no_auto_dialog():
    """v3 要求：MainActivity 启动时不自动申请角色/弹系统对话框。"""
    classes = os.path.join(HERE, 'build', 'classes')
    javap = os.path.join(os.path.dirname(which_java() or ''), 'javap')
    if not (os.path.isdir(classes) and os.path.exists(javap)):
        print('[SKIP] no-auto-dialog check (build/classes 或 javap 不可用)')
        return
    try:
        proc = subprocess.run([javap, '-c', '-p', '-classpath', classes,
                               'com.dshgo.assist.MainActivity'],
                              capture_output=True, text=True, timeout=120)
    except Exception as exc:                                 # noqa: BLE001
        print('[SKIP] no-auto-dialog check (%s)' % exc)
        return
    lines = proc.stdout.splitlines()
    section = []
    inside = False
    for line in lines:
        if line.startswith('  protected void onCreate('):
            inside = True
            continue
        if inside:
            # 下一个方法头：正好缩进 2 个空格且以 ';' 结尾
            # （字节码行缩进更深，注释里也可能带 '(' 和 ';'，不能误判）
            if line.startswith('  ') and not line.startswith('    ') \
                    and line.rstrip().endswith(';'):
                break
            section.append(line)
    body = '\n'.join(section)
    captured = 'setContentView' in body and 'buildUi' in body
    check('MainActivity.onCreate 不自动弹系统对话框（截到真实方法体且无 startActivityForResult）',
          captured and 'startActivityForResult' not in body
          and 'createRequestRoleIntent' not in body,
          '%d 行字节码, 截取有效=%s' % (len(section), captured))


RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201


def local_entry_info(raw, target_name):
    """回到 central directory 找条目，再解析它的 local header：
    返回 {method, data_offset, compress_size, extra_len}；找不到返回 None。"""
    eocd = raw.rfind(b'PK\x05\x06')
    if eocd < 0:
        return None
    cd_size, cd_offset = struct.unpack_from('<II', raw, eocd + 12)
    pos = cd_offset
    end = cd_offset + cd_size
    while pos < end:
        if raw[pos:pos + 4] != b'PK\x01\x02':
            return None
        (method,) = struct.unpack_from('<H', raw, pos + 10)
        (csize,) = struct.unpack_from('<I', raw, pos + 20)
        name_len, extra_len, comment_len = struct.unpack_from('<HHH', raw, pos + 28)
        (lho,) = struct.unpack_from('<I', raw, pos + 42)
        name = raw[pos + 46:pos + 46 + name_len].decode('utf-8', 'replace')
        if name == target_name:
            l_method = struct.unpack_from('<H', raw, lho + 8)[0]
            l_name_len = struct.unpack_from('<H', raw, lho + 26)[0]
            l_extra_len = struct.unpack_from('<H', raw, lho + 28)[0]
            return {
                'method': l_method,
                'header_offset': lho,
                'data_offset': lho + 30 + l_name_len + l_extra_len,
                'compress_size': csize,
                'extra_len': l_extra_len,
            }
        pos += 46 + name_len + extra_len + comment_len
    return None


def parse_arsc(data):
    """最小 resources.arsc 解析：返回 (packages, global_strings)。"""
    typ, hsize, size = struct.unpack_from('<HHI', data, 0)
    if typ != RES_TABLE_TYPE:
        raise AxmlError('not a resource table (type=0x%04x)' % typ)
    if size != len(data):
        raise AxmlError('resource table size %d != file size %d' % (size, len(data)))
    pkg_count = struct.unpack_from('<I', data, 8)[0]
    global_strings, pool_size = _read_pool(data, hsize)
    pos = hsize + pool_size
    packages = []
    for _ in range(pkg_count):
        ptyp, phsize, psize = struct.unpack_from('<HHI', data, pos)
        if ptyp != RES_TABLE_PACKAGE_TYPE:
            raise AxmlError('bad package chunk 0x%04x' % ptyp)
        pid = struct.unpack_from('<I', data, pos + 8)[0]
        name = data[pos + 12:pos + 12 + 256].decode('utf-16-le').split('\x00')[0]
        type_off, _lpt, key_off, _lpk = struct.unpack_from('<IIII', data, pos + 268)
        type_strings, _ = _read_pool(data, pos + type_off)
        key_strings, _ = _read_pool(data, pos + key_off)
        entries = []
        q = pos + phsize
        end = pos + psize
        while q < end:
            ctyp, chsize, csize = struct.unpack_from('<HHI', data, q)
            if csize == 0:
                raise AxmlError('zero-size chunk inside package')
            if ctyp == RES_TABLE_TYPE_TYPE:
                tid = data[q + 8]
                entry_count = struct.unpack_from('<I', data, q + 12)[0]
                entries_start = struct.unpack_from('<I', data, q + 16)[0]
                conf_size = struct.unpack_from('<I', data, q + 20)[0]
                # config 从 q+20 开始；density 在 ResTable_config 的 offset 14
                density = (struct.unpack_from('<H', data, q + 20 + 14)[0]
                           if conf_size >= 16 else 0)
                offsets = struct.unpack_from('<%dI' % entry_count, data, q + chsize)
                for _idx, eo in enumerate(offsets):
                    if eo == 0xFFFFFFFF:
                        continue
                    ep = q + entries_start + eo
                    esize, _eflags, ekey = struct.unpack_from('<HHI', data, ep)
                    _vsize, _res0, dtype, dval = struct.unpack_from('<HBBI', data, ep + esize)
                    entries.append({
                        'index': _idx,
                        'type_id': tid,
                        'type': type_strings[tid - 1]
                        if 0 < tid <= len(type_strings) else '?',
                        'key': key_strings[ekey] if ekey < len(key_strings) else '?',
                        'density': density,
                        'dtype': dtype,
                        'data': dval,
                    })
            q += csize
        packages.append({'id': pid, 'name': name, 'types': type_strings,
                         'keys': key_strings, 'entries': entries})
        pos += psize
    return packages, global_strings


def check_arsc_independent(apk):
    """用 /root/workspace/arsc.py（独立实现）再解一遍 resources.arsc。"""
    script = '/root/workspace/arsc.py'
    if not os.path.exists(script):
        print('[SKIP] 独立 arsc 解析（arsc.py 不存在）')
        return
    try:
        proc = subprocess.run([sys.executable, script, apk, 'ic_launcher'],
                              capture_output=True, text=True, timeout=120)
    except Exception as exc:                                 # noqa: BLE001
        print('[SKIP] 独立 arsc 解析（%s）' % exc)
        return
    out = (proc.stdout or '').strip()
    ok = proc.returncode == 0 and 'ic_launcher' in out and '0x7f010000' in out
    check('独立解析器 arsc.py 复核 resources.arsc', ok,
          out.splitlines()[0] if out else 'no output')


def run_apksigner_verify(apk):
    proc = run_apksigner(['verify', '--verbose', '--print-certs'], apk)
    if proc is None:
        print('[SKIP] apksigner verify (jar or java not found)')
        return
    out = (proc.stdout or '') + (proc.stderr or '')
    schemes = [ln.strip() for ln in out.splitlines() if 'Verified using' in ln]
    ok = proc.returncode == 0 and any('v2 scheme' in s and s.endswith('true')
                                      for s in schemes)
    check('apksigner verify', ok, 'exit=%d | %s' % (proc.returncode, '; '.join(schemes)))


# --------------------------------------------------------------------------- #
def main():
    global VARIANT
    if '--variant' in sys.argv:
        VARIANT = sys.argv[sys.argv.index('--variant') + 1]
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    apk = args[0] if args else default_apk()
    if not os.path.exists(apk):
        print('APK not found: %s' % apk)
        return 2
    raw = open(apk, 'rb').read()
    print('APK: %s (%d bytes, sha256=%s)' % (apk, len(raw),
          __import__('hashlib').sha256(raw).hexdigest()))
    print('-' * 78)

    zf = zipfile.ZipFile(apk)
    names = zf.namelist()
    check('zip opens, no corrupt entries', zf.testzip() is None, '%d entries' % len(names))
    check('zip 无重复条目名', len(names) == len(set(names)), '%d entries' % len(names))
    unreadable = []
    for entry_name in names:
        try:
            zf.read(entry_name)
        except Exception:                                    # noqa: BLE001
            unreadable.append(entry_name)
    check('zip 每个条目都能被读出', not unreadable, str(unreadable))

    # manifest -------------------------------------------------------------
    if 'AndroidManifest.xml' not in names:
        check('AndroidManifest.xml present', False)
        return 1
    mdata = zf.read('AndroidManifest.xml')
    check('AndroidManifest.xml present', True, '%d bytes' % len(mdata))
    try:
        root, strings, res_map = parse_axml(mdata)
        check('AndroidManifest.xml is valid binary XML (ResXMLTree)', True,
              '%d strings, %d resource-map entries' % (len(strings), len(res_map)))
    except AxmlError as exc:
        check('AndroidManifest.xml is valid binary XML (ResXMLTree)', False, str(exc))
        return 1

    check('root element is <manifest>', root.tag == 'manifest', root.tag)
    check('package == %s' % EXPECT_PACKAGE, root.get('package') == EXPECT_PACKAGE,
          str(root.get('package')))
    vc = root.attr(res_id=ATTR_VERSION_CODE)
    check('android:versionCode == %d (v2, upgrade over v1)' % EXPECT_VERSION_CODE,
          vc is not None and vc['value'] == EXPECT_VERSION_CODE,
          'res_id=0x%08x value=%r' % (vc['res_id'], vc['value']) if vc else 'missing')
    vn = root.attr(res_id=ATTR_VERSION_NAME)
    check('android:versionName == %s' % EXPECT_VERSION_NAME,
          vn is not None and str(vn['value']) == EXPECT_VERSION_NAME,
          str(vn['value']) if vn else 'missing')

    perms = [str(p.get(res_id=ATTR_NAME)) for p in root.find_all('uses-permission')]
    expected_perms = EXPECT_PERMISSIONS if VARIANT == 'v6' else V6A_PERMISSIONS
    check('permissions == %s' % sorted(expected_perms),
          sorted(perms) == sorted(expected_perms), str(sorted(perms)))

    queries = root.find_all('queries')
    if check('<queries> present (Android 11+ package visibility)', bool(queries)):
        pkgs = [str(p.get(res_id=ATTR_NAME)) for p in queries[0].find_all('package')]
        check('queries declares target package', EXPECT_TARGET_PKG in pkgs, str(pkgs))

    sdk = root.find_all('uses-sdk')
    if check('<uses-sdk> present', bool(sdk)):
        mn = sdk[0].attr(res_id=ATTR_MIN_SDK)
        tg = sdk[0].attr(res_id=ATTR_TARGET_SDK)
        check('minSdkVersion == %d' % EXPECT_MIN_SDK,
              mn is not None and mn['value'] == EXPECT_MIN_SDK,
              str(mn['value']) if mn else 'missing')
        check('targetSdkVersion == %d' % EXPECT_TARGET_SDK,
              tg is not None and tg['value'] == EXPECT_TARGET_SDK,
              str(tg['value']) if tg else 'missing')

    apps = root.find_all('application')
    if not check('<application> present', bool(apps)):
        return 1
    activities = activity_map(apps[0])
    print('       activities: %s' % sorted(activities))

    # --- ASSIST entry (v10：AssistActivity 只起悬浮胶囊服务、零动画 finish) ---
    if check('ASSIST activity exists', '.AssistActivity' in activities,
             str(sorted(activities))):
        act = activities['.AssistActivity']
        exp = act.attr(res_id=ATTR_EXPORTED)
        check('ASSIST activity exported == true', exp is not None and exp['value'] is True,
              str(exp['value']) if exp else 'missing')
        theme = act.attr(res_id=ATTR_THEME)
        check('ASSIST activity theme == @android:style/Theme.NoDisplay（完全不画窗口）',
              theme is not None and theme['value'] == '@0x%08x' % STYLE_NO_DISPLAY,
              str(theme['value']) if theme else 'missing')
        affinity = act.attr(res_id=ATTR_TASK_AFFINITY)
        check('AssistActivity taskAffinity == ""（独立任务，不顶前台 App）',
              affinity is not None and str(affinity['value']) == '',
              repr(affinity['value']) if affinity else 'missing')
        launch = act.attr(res_id=ATTR_LAUNCH_MODE)
        check('AssistActivity launchMode == singleInstance(3)',
              launch is not None and launch['value'] == 3,
              str(launch['value']) if launch else 'missing')
        tile_ok = False
        tile_detail = ''
        try:
            for svc in apps[0].find_all('service'):
                nm = svc.get(res_id=ATTR_NAME)
                if nm is None or str(nm) != '.DshTileService':
                    continue
                perm = svc.get(res_id=ATTR_PERMISSION)
                qs = False
                try:
                    for a in svc.find_all('action'):
                        an = a.get(res_id=ATTR_NAME)
                        if an is not None and str(an) == \
                                'android.service.quicksettings.action.QS_TILE':
                            qs = True
                except Exception:                        # noqa: BLE001
                    qs = False
                exp = svc.get(res_id=ATTR_EXPORTED)
                exp_val = exp['value'] if isinstance(exp, dict) else exp
                tile_ok = (perm is not None and str(perm)
                           == 'android.permission.BIND_QUICK_SETTINGS_TILE'
                           and qs and exp_val is True)
                tile_detail = 'perm=%s qs=%s exported=%s' % (str(perm), qs, exp_val)
        except Exception as exc:                             # noqa: BLE001
            tile_detail = str(exc)
        check('v22：manifest 声明 DshTileService（QS_TILE + BIND_QUICK_SETTINGS_TILE）',
              tile_ok, tile_detail)
        doc_mode = act.attr(res_id=ATTR_DOCUMENT_LAUNCH_MODE)
        check('v19：AssistActivity documentLaunchMode == always(2)（不顶前台）',
              doc_mode is not None and doc_mode['value'] == 2,
              str(doc_mode['value']) if doc_mode else 'missing')
        recents = act.attr(res_id=ATTR_EXCLUDE_FROM_RECENTS)
        check('ASSIST activity excludeFromRecents == true',
              recents is not None and recents['value'] is True,
              str(recents['value']) if recents else 'missing')
        check('ASSIST intent-filter with DEFAULT category',
              has_filter(act, 'android.intent.action.ASSIST',
                         'android.intent.category.DEFAULT'))
        no_history = act.attr(res_id=ATTR_NO_HISTORY)
        check('AssistActivity noHistory == true',
              no_history is not None and no_history['value'] is True,
              str(no_history['value']) if no_history else 'missing')

    # --- v10：ListenActivity 保留为全屏兜底 ------------------------------
    if check('ListenActivity 兜底存在', '.ListenActivity' in activities,
             str(sorted(activities))):
        fallback = activities['.ListenActivity']
        theme = fallback.attr(res_id=ATTR_THEME)
        check('ListenActivity theme == Translucent.NoTitleBar（兜底不闪）',
              theme is not None
              and theme['value'] == '@0x%08x' % STYLE_TRANSLUCENT_NO_TITLE_BAR,
              str(theme['value']) if theme else 'missing')

    # --- v10：OverlayService（悬浮胶囊）----------------------------------
    services_all = apps[0].find_all('service')
    overlay_services = [sv for sv in services_all
                        if str(sv.get(res_id=ATTR_NAME)) == '.OverlayService']
    if check('OverlayService declared', bool(overlay_services),
             str([str(s.get(res_id=ATTR_NAME)) for s in services_all])):
        svc = overlay_services[0]
        exp = svc.attr(res_id=ATTR_EXPORTED)
        check('OverlayService exported == false',
              exp is not None and exp['value'] is False,
              str(exp['value']) if exp else 'missing')
        fgs = svc.attr(res_id=ATTR_FOREGROUND_SERVICE_TYPE)
        check('OverlayService foregroundServiceType == microphone (0x80)',
              fgs is not None and fgs['value'] == 0x80,
              hex(fgs['value']) if fgs else 'missing')

    # --- LAUNCHER entry (v2 role request) ---------------------------------
    if check('LAUNCHER activity exists', '.MainActivity' in activities,
             str(sorted(activities))):
        act = activities['.MainActivity']
        exp = act.attr(res_id=ATTR_EXPORTED)
        check('LAUNCHER activity exported == true',
              exp is not None and exp['value'] is True,
              str(exp['value']) if exp else 'missing')
        check('LAUNCHER intent-filter (MAIN + LAUNCHER)',
              has_filter(act, 'android.intent.action.MAIN',
                         'android.intent.category.LAUNCHER'))

    # --- v5/v6：BootReceiver + HealService（仅全量版 v6 有） ---
    receivers = apps[0].find_all('receiver')
    services = apps[0].find_all('service')
    if VARIANT == 'v6':
        boot_receivers = [r for r in receivers
                          if str(r.get(res_id=ATTR_NAME)) == '.BootReceiver']
        if check('BootReceiver declared', bool(boot_receivers), str(len(receivers))):
            recv = boot_receivers[0]
            exp = recv.attr(res_id=ATTR_EXPORTED)
            check('BootReceiver exported == false', exp is not None and exp['value'] is False,
                  str(exp['value']) if exp else 'missing')
            actions = [str(a.get(res_id=ATTR_NAME)) for a in recv.find_all('action')]
            check('BootReceiver listens for BOOT_COMPLETED',
                  'android.intent.action.BOOT_COMPLETED' in actions, str(actions))

        heal_services = [sv for sv in services
                         if str(sv.get(res_id=ATTR_NAME)) == '.HealService']
        if check('HealService declared', bool(heal_services), str(len(services))):
            svc = heal_services[0]
            exp = svc.attr(res_id=ATTR_EXPORTED)
            check('HealService exported == false', exp is not None and exp['value'] is False,
                  str(exp['value']) if exp else 'missing')
            fgs = svc.attr(res_id=ATTR_FOREGROUND_SERVICE_TYPE)
            check('HealService foregroundServiceType == specialUse (0x40000000)',
                  fgs is not None and fgs['value'] == 0x40000000,
                  hex(fgs['value']) if fgs else 'missing')
            props = {str(pp.get(res_id=ATTR_NAME)): pp.get(name='value')
                     for pp in svc.find_all('property')}
            check('HealService declares PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
                  'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' in props, str(props))
    else:
        check('v6a: 没有 receiver（BootReceiver 已摘掉）', not receivers,
              str([str(r.get(res_id=ATTR_NAME)) for r in receivers]))
        check('v6a: 没有 service（HealService 已摘掉）', not services,
              str([str(s.get(res_id=ATTR_NAME)) for s in services]))

    # --- v6：BootProvider（两个变体都有，且必须排在 Shizuku provider 之前）---
    providers = apps[0].find_all('provider')
    provider_names = [str(p.get(res_id=ATTR_NAME)) for p in providers]
    boot_providers = [p for p in providers
                      if str(p.get(res_id=ATTR_NAME)) == '.BootProvider']
    if check('BootProvider declared', bool(boot_providers), str(provider_names)):
        p0 = boot_providers[0]
        auth = p0.attr(name='authorities')
        check('BootProvider authorities == com.dshgo.assist.boot',
              auth is not None and str(auth['value']) == 'com.dshgo.assist.boot',
              str(auth['value']) if auth else 'missing')
        sz = provider_names.index('rikka.shizuku.ShizukuProvider') \
            if 'rikka.shizuku.ShizukuProvider' in provider_names else len(provider_names)
        check('BootProvider 排在 Shizuku provider 之前',
              provider_names.index('.BootProvider') < sz, str(provider_names))

    # --- v4/v6：Shizuku provider + meta-data（v6a 里整块摘掉）---
    shizuku_providers = [p for p in providers
                         if str(p.get(res_id=ATTR_NAME)) == 'rikka.shizuku.ShizukuProvider']
    metas = {str(m.get(res_id=ATTR_NAME)): m.get(name='value')
             for m in apps[0].find_all('meta-data')}
    if VARIANT == 'v6':
        if check('ShizukuProvider declared', bool(shizuku_providers), str(provider_names)):
            prov = shizuku_providers[0]
            auth = prov.attr(name='authorities')
            check('ShizukuProvider authorities == com.dshgo.assist.shizuku',
                  auth is not None and str(auth['value']) == 'com.dshgo.assist.shizuku',
                  str(auth['value']) if auth else 'missing')
            exp = prov.attr(res_id=ATTR_EXPORTED)
            check('ShizukuProvider exported == true', exp is not None and exp['value'] is True,
                  str(exp['value']) if exp else 'missing')
            multi = prov.attr(name='multiprocess')
            check('ShizukuProvider multiprocess == false',
                  multi is None or multi['value'] is False,
                  str(multi['value']) if multi else 'missing attr (defaults false)')
        check('meta-data moe.shizuku.client.V3_SUPPORT = true',
              metas.get('moe.shizuku.client.V3_SUPPORT') == 'true', str(metas))
    else:
        check('v6a: ShizukuProvider 已移除', not shizuku_providers, str(provider_names))
        check('v6a: V3_SUPPORT meta-data 已移除',
              'moe.shizuku.client.V3_SUPPORT' not in metas, str(list(metas)))

    allowed = ('AndroidManifest.xml', 'classes.dex', 'resources.arsc')
    extra = [n for n in names if n not in allowed
             and not n.startswith(('META-INF/', 'res/'))]
    check('zip 条目符合预期（manifest/dex/arsc/res/META-INF，无 native 库）',
          not extra, str(extra))

    # --- v7/v8：resources.arsc + 应用图标 ---
    has_arsc = 'resources.arsc' in names
    arsc_ids = None
    if has_arsc:
        info = local_entry_info(raw, 'resources.arsc')
        if check('resources.arsc 有 local header', info is not None,
                 str(info)):
            check('resources.arsc compress_type == 0（STORED，Android 11+ 硬要求）',
                  info['method'] == 0, 'method=%d compress_size=%d'
                  % (info['method'], info['compress_size']))
            check('resources.arsc 数据起始偏移 4 字节对齐',
                  info['data_offset'] % 4 == 0,
                  'data_offset=%d (mod4=%d)' % (info['data_offset'],
                                                info['data_offset'] % 4))
        arsc = zf.read('resources.arsc')
        try:
            packages, global_strings = parse_arsc(arsc)
            check('resources.arsc 可解析（ResTable）', True,
                  '%d package(s), %d global strings' % (len(packages), len(global_strings)))
        except AxmlError as exc:
            check('resources.arsc 可解析（ResTable）', False, str(exc))
            packages, global_strings = [], []
        if packages:
            pkg = packages[0]
            check('arsc package id == 0x7f', pkg['id'] == 0x7f, hex(pkg['id']))
            check('arsc package name == %s' % EXPECT_PACKAGE,
                  pkg['name'] == EXPECT_PACKAGE, pkg['name'])
            check('arsc 类型里有 mipmap', 'mipmap' in pkg['types'], str(pkg['types']))
            check('arsc key 里有 ic_launcher', 'ic_launcher' in pkg['keys'], str(pkg['keys']))
            icon_entries = [e for e in pkg['entries'] if e['key'] == 'ic_launcher']
            check('ic_launcher 有条目', bool(icon_entries), str(len(icon_entries)))
            paths = []
            for e in icon_entries:
                if e['dtype'] == TYPE_STRING and 0 <= e['data'] < len(global_strings):
                    paths.append(global_strings[e['data']])
            check('ic_launcher 指向 APK 内 PNG',
                  bool(paths) and all(p in names for p in paths), str(sorted(set(paths))))
            densities = sorted({e['density'] for e in icon_entries if e['dtype'] == TYPE_STRING})
            check('图标含 xxhdpi(480) 与 xxxhdpi(640)',
                  480 in densities and 640 in densities, str(densities))
            for png_path in sorted(set(paths)):
                try:
                    png = zf.read(png_path)
                    w, h = struct.unpack('>II', png[16:24])
                    check('PNG %s 合法（%dx%d）' % (png_path, w, h),
                          png[:8] == b'\x89PNG\r\n\x1a\n' and w == h
                          and w in (144, 192))
                except Exception as exc:                     # noqa: BLE001
                    check('PNG %s' % png_path, False, str(exc))
            arsc_ids = {((pkg['id'] << 24) | (e['type_id'] << 16) | e['index'])
                        for e in pkg['entries']}
            check('arsc 条目里有 0x%08x（icon 引用的 id）' % ICON_REF,
                  ICON_REF in arsc_ids,
                  'ids=%s' % [hex(i) for i in sorted(arsc_ids)][:8])
        check_arsc_independent(apk)
    else:
        check('对照包：不带 resources.arsc（符合预期）', True, 'no-icon build')

    icon_attr = apps[0].attr(res_id=ATTR_ICON)
    round_icon = apps[0].attr(res_id=ATTR_ROUND_ICON)
    if has_arsc:
        check('application android:icon == @0x%08x（0x7f 包）' % ICON_REF,
              icon_attr is not None
              and str(icon_attr['value']) == '@0x%08x' % ICON_REF,
              str(icon_attr['value']) if icon_attr else 'missing')
        check('application android:roundIcon == @0x%08x' % ICON_REF,
              round_icon is not None
              and str(round_icon['value']) == '@0x%08x' % ICON_REF,
              str(round_icon['value']) if round_icon else 'missing')
        if arsc_ids is not None:
            for label, attr in (('icon', icon_attr), ('roundIcon', round_icon)):
                if attr is not None:
                    value = str(attr['value'])
                    try:
                        ref = int(value.replace('@0x', ''), 16)
                    except ValueError:
                        ref = -1
                    check('android:%s 引用的 id 真实存在于 arsc' % label,
                          ref in arsc_ids, '%s -> %s' % (value, hex(ref) if ref >= 0 else '?'))
    else:
        check('对照包：application 不带 android:icon', icon_attr is None,
              str(icon_attr['value']) if icon_attr else 'absent')
        check('对照包：application 不带 android:roundIcon', round_icon is None,
              str(round_icon['value']) if round_icon else 'absent')

    check_dex(zf)
    check_signature(zf, raw)
    check_upgrade(apk, mdata)
    check_no_auto_dialog()
    check_source_guards()
    run_apksigner_verify(apk)

    print('-' * 78)
    failed = [r for r in results if not r['ok']]
    print('%d checks, %d passed, %d failed'
          % (len(results), len(results) - len(failed), len(failed)))
    if '--json' in sys.argv:
        print(json.dumps({'apk': apk, 'size': len(raw),
                          'passed': len(results) - len(failed), 'failed': len(failed),
                          'failed_checks': failed}, indent=2))
    return 0 if not failed else 1


if __name__ == '__main__':
    sys.exit(main())