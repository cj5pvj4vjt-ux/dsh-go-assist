#!/usr/bin/env python3
"""Manifest spec for the DSH 小V assistant APK (v2) + AXML compile entry point.

v27：修回复卡片空白（文字样式显式切换 + ScrollView maxHeight clamp + 时序）+ 测试回复卡片入口。

v26：回复态改「卡片」（宽 屏宽−48dp / 高 WRAP_CONTENT / max 屏高×0.45 / 圆角 18dp），长回复不再挤进 44dp 胶囊。

v25：VAD 双阈值（迟滞）+ 超时用部分识别结果兜底（识别成功绝不丢）。

v24：辉光窗口改回全屏 + setFitInsetsTypes(0)，辉光盖满整屏（含系统栏下方）。

v23：✕ 推到胶囊最右端（文字区 weight=1 + 中间省略）。

v22 final：单胶囊（流光画进胶囊内）+ 竖波形条 + 免 root 入口（QS 磁贴 + 智能小圆点）。

v21：VAD 阈值下调（max(90, noise*1.5)）+ 连续 2 帧起判 + 软件增益 ×2 + source 改 MIC。

v20：root 模式 short=0（修松手黑屏）+ 钩子启动去重 + reply_via_dsh 升级强制复位。

v19：AssistActivity 彻底不抢前台（finishAndRemoveTask + documentLaunchMode=always）
     + 悬浮窗重复 onStartCommand 状态机 + 划返回/点击窗外收回。

v18（最终版）：失败场景修复（轮询 180s/红字+重试/分阶段看门狗）+ 没识别到立刻收回
     + 免 root 的「系统助手」电源键模式 + 首次引导 + 主界面文案收敛。

v17：全屏边缘流光（EdgeGlowView）+ 逐字实时字幕 + VAD 停顿容错 + 新对话按钮。

v16：放弃原子岛改「流光 + 一行文字」；录音加 2s 环形预录（补 1200ms 句首）；
     结果改成 dsh-inbox/outbox 文件小窗回显，不启动任何 Activity。

v15：胶囊改成从状态栏下沿长出来（y=statusBar-6dp）+ 录音 read 重试加固。

v14：补 INTERNET 权限（本地 http 回环必需）+ 健康检查超时 2.5s + 小圆点下移 10dp。

v13：胶囊进挖孔区（cutout ALWAYS + NO_LIMITS）+ 自建 AudioRecord/VAD + 本地离线识别
     (POST 127.0.0.1:18080/transcribe) + WAKE_LOCK 权限。

v12：胶囊改成原子岛样式（y=0 / 44dp / 22dp 圆角 / 岛形展开动画）
     + overlay.log 诊断日志 + 25s 看门狗 + wake lock + 钩子阈值 4900ms。

v11：电源键钩子内置 + 长按行为改 0 + 守护脚本移入 Download/DSH-Go/bin + 常驻小圆点。

v10：Siri 式顶部悬浮胶囊（不抢前台）+ 跑马灯 + 主界面收敛为深色极简。

v9：root 链路改用常驻交互式 shell（修复 Shizuku SHELL_TEST 校验失败）+
    root-cmd.log 落盘 + root 桥状态读标记文件。

v6 相对 v5：崩溃取证（BootTrace/CrashHandler）+ 启动加固（onCreate try/catch 错误页、
风险初始化挪到首帧之后）+ 变体 v6a（去掉 Shizuku/自愈，用于对半砍排查）。

v5（最终版）相对 v4：
  * Material 风格 UI（全部程序化、无 res/，深色模式两套配色）
  * ListenActivity + OrbView：ASSIST 入口改成彩色球动画 + 语音
  * BootReceiver + HealService：开机自愈（等 Shizuku 提权→幂等 apply→重启 root 桥）
  * 实验开关「唤醒截胡」：root logcat 管道匹配唤醒关键字 → am start ListenActivity
  * versionCode 5 / versionName 5.0

v4 相对 v3 的变化：接入 Shizuku 客户端拿 root shell（本机 Shizuku 13.5 root 模式）：
  * versionCode 4 / versionName 4.0
  * 新增 Shizuku 客户端类（dev.rikka.shizuku:api/provider/aidl/shared 13.1.5，
    四个 AAR 都只有 classes.jar、无 res/，所以不需要 aapt2）
  * manifest：moe.shizuku.manager.permission.API_V23 权限 +
    rikka.shizuku.ShizukuProvider（authorities=com.dshgo.assist.shizuku）+
    meta-data moe.shizuku.client.V3_SUPPORT
  * MainActivity 新增 Shizuku 卡片：连接/授权、一键接管电源键、诊断(读)、
    静默安装、启动/停止 root 桥

v3 相对 v2 的变化：诊断面板 + 按钮驱动（不再自动弹系统对话框）：
  * versionCode 3 / versionName 3.0
  * MainActivity 改为诊断面板：显示助手角色 / vivo 电源键开关 / 长按行为 /
    WRITE_SETTINGS / 通知与实时动态能力 / uid / versionCode
  * 新增权限 POST_NOTIFICATIONS（13+ 运行时）与 POST_PROMOTED_NOTIFICATIONS
    （API 36 实时动态，普通权限），WRITE_SETTINGS（特殊权限，走设置页授权）
  * AssitActivity 的 ASSIST 入口与语音逻辑保持不变

v2 相对 v1 的变化（保留 v1 的全部行为）：
  * versionCode 2 / versionName 2.0（同 keystore 可覆盖升级）
  * 新增 MainActivity：桌面 LAUNCHER 入口，打开即自助申请「默认助手角色」
  * 新增 VoiceActivity：ASSIST 入口的可选语音收音（导出=false，透明窗口）
  * 新增 RECORD_AUDIO 权限（仅语音用，运行时可拒绝）
  * AssitActivity 仍是唯一的 ASSIST 入口（主题 NoDisplay，行为向后兼容）

Equivalent readable XML: manifest/AndroidManifest.xml
"""
import sys

import arsc_encode
import axml_encode

PACKAGE = 'com.dshgo.assist'
TARGET_PACKAGE = 'io.github.ccch1mneyyy.dsh_app'
DEEP_LINK = 'dshgo://open/chat'
LABEL = 'DSH 小V助手'
VERSION_CODE = 56
VERSION_NAME = '27.0 final'
MIN_SDK = 26
TARGET_SDK = 36

SPEC = {
    'tag': 'manifest',
    'attrs': [
        (None, 'package', 'string', PACKAGE),
        ('android', 'versionCode', 'int', VERSION_CODE),
        ('android', 'versionName', 'string', VERSION_NAME),
    ],
    'children': [
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'android.permission.RECORD_AUDIO')]},
        # 原子岛/实时动态（API 36，非运行时权限）
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string',
             'android.permission.POST_PROMOTED_NOTIFICATIONS')]},
        # 通知（Android 13+ 运行时申请）
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'android.permission.POST_NOTIFICATIONS')]},
        # 修改系统设置（特殊权限，需用户在设置页授权；用于关 vivo 电源键开关）
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'android.permission.WRITE_SETTINGS')]},
        # Shizuku 客户端授权（v4：root shell）
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'moe.shizuku.manager.permission.API_V23')]},
        # v14：本地 ASR 走 http://127.0.0.1:18080 —— 没有 INTERNET 权限连 socket 都建不了
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'android.permission.INTERNET')]},
        # v13：胶囊期间持 PARTIAL_WAKE_LOCK
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'android.permission.WAKE_LOCK')]},
        # v10：悬浮胶囊（Siri 式，不抢前台）
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'android.permission.SYSTEM_ALERT_WINDOW')]},
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string',
             'android.permission.FOREGROUND_SERVICE_MICROPHONE')]},
        # v5：重启自愈（开机广播 + 前台服务 specialUse）
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'android.permission.RECEIVE_BOOT_COMPLETED')]},
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string', 'android.permission.FOREGROUND_SERVICE')]},
        {'tag': 'uses-permission', 'attrs': [
            ('android', 'name', 'string',
             'android.permission.FOREGROUND_SERVICE_SPECIAL_USE')]},
        # Android 11+ 包可见性：允许查询/按包名启动 DSH 小V
        {
            'tag': 'queries',
            'attrs': [],
            'children': [
                {'tag': 'package', 'attrs': [('android', 'name', 'string', TARGET_PACKAGE)]},
                {'tag': 'package', 'attrs': [
                    ('android', 'name', 'string', 'moe.shizuku.privileged.api')]},
            ],
        },
        {
            'tag': 'uses-sdk',
            'attrs': [
                ('android', 'minSdkVersion', 'int', MIN_SDK),
                ('android', 'targetSdkVersion', 'int', TARGET_SDK),
            ],
        },
        {
            'tag': 'application',
            'attrs': [
                ('android', 'name', 'string', '.DshApp'),
                ('android', 'label', 'string', LABEL),
                ('android', 'icon', 'ref', arsc_encode.ICON_REF),
                ('android', 'roundIcon', 'ref', arsc_encode.ICON_REF),
                ('android', 'allowBackup', 'bool', False),
                ('android', 'hardwareAccelerated', 'bool', True),
                ('android', 'supportsRtl', 'bool', True),
                ('android', 'usesCleartextTraffic', 'bool', True),
            ],
            'children': [
                # v6：我们自己的 provider，唯一作用是启动轨迹打点（必须排在 Shizuku provider 之前）
                {'tag': 'provider', 'attrs': [
                    ('android', 'name', 'string', '.BootProvider'),
                    ('android', 'authorities', 'string', PACKAGE + '.boot'),
                    ('android', 'exported', 'bool', False),
                    ('android', 'multiprocess', 'bool', False)]},
                # Shizuku 客户端标记（provider AAR 的 manifest 里就有这一条）
                {'tag': 'meta-data', 'attrs': [
                    ('android', 'name', 'string', 'moe.shizuku.client.V3_SUPPORT'),
                    ('android', 'value', 'string', 'true')]},
                # Shizuku 客户端 provider：接受 Shizuku Manager 发来的 binder
                {'tag': 'provider', 'attrs': [
                    ('android', 'name', 'string', 'rikka.shizuku.ShizukuProvider'),
                    ('android', 'authorities', 'string', PACKAGE + '.shizuku'),
                    ('android', 'exported', 'bool', True),
                    ('android', 'multiprocess', 'bool', False)]},
                # v5：开机自愈
                {
                    'tag': 'receiver',
                    'attrs': [
                        ('android', 'name', 'string', '.BootReceiver'),
                        ('android', 'exported', 'bool', False),
                    ],
                    'children': [
                        {
                            'tag': 'intent-filter',
                            'attrs': [],
                            'children': [
                                {'tag': 'action', 'attrs': [
                                    ('android', 'name', 'string',
                                     'android.intent.action.BOOT_COMPLETED')]},
                            ],
                        },
                    ],
                },
                {
                    'tag': 'service',
                    'attrs': [
                        ('android', 'name', 'string', '.HealService'),
                        ('android', 'exported', 'bool', False),
                        ('android', 'foregroundServiceType', 'int', 0x40000000),
                    ],
                    'children': [
                        {'tag': 'property', 'attrs': [
                            ('android', 'name', 'string',
                             'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE'),
                            ('android', 'value', 'string',
                             'boot-heal-wait-for-shizuku')]},
                    ],
                },
                # 桌面入口：打开即申请系统「默认助手」角色
                {
                    'tag': 'activity',
                    'attrs': [
                        ('android', 'name', 'string', '.MainActivity'),
                        ('android', 'label', 'string', LABEL),
                        ('android', 'exported', 'bool', True),
                        ('android', 'theme', 'ref',
                         axml_encode.STYLE['Theme_DeviceDefault_Light_NoActionBar']),
                    ],
                    'children': [
                        {
                            'tag': 'intent-filter',
                            'attrs': [],
                            'children': [
                                {'tag': 'action', 'attrs': [
                                    ('android', 'name', 'string', 'android.intent.action.MAIN')]},
                                {'tag': 'category', 'attrs': [
                                    ('android', 'name', 'string', 'android.intent.category.LAUNCHER')]},
                            ],
                        },
                    ],
                },
                # v10 ASSIST 入口：只起 OverlayService 然后零动画 finish（不抢前台）
                # v13：独立任务（taskAffinity=""、singleInstance）+ Theme.NoDisplay，
                #      彻底避免把用户前台 App 的任务栈顶上来
                {
                    'tag': 'activity',
                    'attrs': [
                        ('android', 'name', 'string', '.AssistActivity'),
                        ('android', 'label', 'string', LABEL),
                        ('android', 'exported', 'bool', True),
                        ('android', 'excludeFromRecents', 'bool', True),
                        ('android', 'noHistory', 'bool', True),
                        ('android', 'taskAffinity', 'string', ''),
                        ('android', 'launchMode', 'int', 3),
                        ('android', 'documentLaunchMode', 'int', 2),
                        ('android', 'theme', 'ref', axml_encode.STYLE['Theme_NoDisplay']),
                    ],
                    'children': [
                        {
                            'tag': 'intent-filter',
                            'attrs': [('android', 'priority', 'int', 10)],
                            'children': [
                                {'tag': 'action', 'attrs': [
                                    ('android', 'name', 'string', 'android.intent.action.ASSIST')]},
                                {'tag': 'category', 'attrs': [
                                    ('android', 'name', 'string', 'android.intent.category.DEFAULT')]},
                            ],
                        },
                    ],
                },
                # 兜底：整屏彩球动画（没有悬浮窗权限时用）
                {
                    'tag': 'activity',
                    'attrs': [
                        ('android', 'name', 'string', '.ListenActivity'),
                        ('android', 'label', 'string', LABEL),
                        ('android', 'exported', 'bool', True),
                        ('android', 'excludeFromRecents', 'bool', True),
                        ('android', 'theme', 'ref',
                         axml_encode.STYLE['Theme_Translucent_NoTitleBar']),
                    ],
                },
                # v10 悬浮胶囊前台服务（type=microphone，退到后台也可收音）
                # v22 快捷设置磁贴（免 root 唤起入口）
                # v38 快捷设置磁贴（本地模型开关）
                {
                    'tag': 'service',
                    'attrs': [
                        ('android', 'name', 'string', '.LocalTileService'),
                        ('android', 'label', 'string', '本地模型'),
                        ('android', 'icon', 'ref', arsc_encode.ICON_REF),
                        ('android', 'permission', 'string',
                         'android.permission.BIND_QUICK_SETTINGS_TILE'),
                        ('android', 'exported', 'bool', True),
                    ],
                    'children': [
                        {
                            'tag': 'intent-filter',
                            'attrs': [],
                            'children': [
                                {'tag': 'action', 'attrs': [
                                    ('android', 'name', 'string',
                                     'android.service.quicksettings.action.QS_TILE')]},
                            ],
                        },
                    ],
                },
                {
                    'tag': 'service',
                    'attrs': [
                        ('android', 'name', 'string', '.DshTileService'),
                        ('android', 'label', 'string', 'DSH 小V'),
                        ('android', 'icon', 'ref', arsc_encode.ICON_REF),
                        ('android', 'permission', 'string',
                         'android.permission.BIND_QUICK_SETTINGS_TILE'),
                        ('android', 'exported', 'bool', True),
                    ],
                    'children': [
                        {
                            'tag': 'intent-filter',
                            'attrs': [],
                            'children': [
                                {'tag': 'action', 'attrs': [
                                    ('android', 'name', 'string',
                                     'android.service.quicksettings.action.QS_TILE')]},
                            ],
                        },
                    ],
                },
                {
                    'tag': 'service',
                    'attrs': [
                        ('android', 'name', 'string', '.OverlayService'),
                        ('android', 'exported', 'bool', False),
                        ('android', 'foregroundServiceType', 'int', 0x80),
                    ],
                },
            ],
        },
    ],
}


def _attr(node, name):
    for a in node.get('attrs', []):
        if a[1] == name:
            return a[3]
    return None


V6A_DROP_PERMISSIONS = {
    'moe.shizuku.manager.permission.API_V23',
    'android.permission.RECEIVE_BOOT_COMPLETED',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
}


def spec_for(variant, no_icon=False):
    """v6 = 全量；v6a = 排查版（去掉 Shizuku provider/监听、BootReceiver、HealService）。
    no_icon=True 时去掉 android:icon / roundIcon（对照包用，不带 resources.arsc）。"""
    import copy
    spec = copy.deepcopy(SPEC)
    if no_icon:
        app = [c for c in spec['children'] if c['tag'] == 'application'][0]
        app['attrs'] = [a for a in app['attrs'] if a[1] not in ('icon', 'roundIcon')]
    if variant != 'v6a':
        return spec
    app = [c for c in spec['children'] if c['tag'] == 'application'][0]
    kept = []
    for child in app['children']:
        tag = child['tag']
        if tag == 'provider' and _attr(child, 'name') == 'rikka.shizuku.ShizukuProvider':
            continue
        if tag == 'meta-data' and _attr(child, 'name') == 'moe.shizuku.client.V3_SUPPORT':
            continue
        if tag in ('receiver', 'service'):
            continue
        kept.append(child)
    app['children'] = kept
    spec['children'] = [
        c for c in spec['children']
        if not (c['tag'] == 'uses-permission'
                and _attr(c, 'name') in V6A_DROP_PERMISSIONS)
    ]
    return spec


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    variant = 'v6'
    if '--variant' in sys.argv:
        variant = sys.argv[sys.argv.index('--variant') + 1]
    no_icon = '--no-icon' in sys.argv
    out = args[0] if args else 'build/AndroidManifest.xml'
    data = axml_encode.encode(spec_for(variant, no_icon=no_icon))
    with open(out, 'wb') as fh:
        fh.write(data)
    print('wrote %s (%d bytes)' % (out, len(data)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
