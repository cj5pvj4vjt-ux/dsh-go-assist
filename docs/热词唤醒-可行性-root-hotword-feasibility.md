# 有 root 时，语音唤醒词能否换成「DSH」

## 结论

| 路线 | 能否「喊一句就醒」 | 原因 |
|---|---|---|
| (a) 真 root + 特权应用 | **不能**（除非改框架） | priv-app 只解锁 `signature\|privileged`；`BIND_VOICE_INTERACTION` 本机是纯 `signature`(2)，拿不到就当不成 VoiceInteractionService |
| (b) 无 root 常驻监听 | **能**——唯一现实路径 | 自己跑本地 KWS，喊「DSH」能醒；代价是常驻麦克风、耗电、被杀 |
| (c) 改 vivo 入口 | **不能** | 唤醒词编在 Mobvoi `.jet` 声学模型里，交给高通 SVA/DSP 跑，没有「DSH」版模型 |

root 在 (a) 里唯一买得到的是 `MANAGE_SOUND_TRIGGER`／`CAPTURE_AUDIO_HOTWORD`／`MANAGE_VOICE_KEYPHRASES`（priv-app 可拿），但它们只能驱动**已有的**「小V小V／Hi Jovi」模型，换不了词。

## 【事实】权限模型（本机 framework-res.apk 实测）

Android 16 本机 protectionLevel 原始值：

- `BIND_VOICE_INTERACTION` = 2 → **signature**
- `BIND_HOTWORD_DETECTION_SERVICE`、`BIND_SOUND_TRIGGER_DETECTION_SERVICE` = 2 → signature
- `MANAGE_VOICE_KEYPHRASES`、`KEYPHRASE_ENROLLMENT_APPLICATION`、`SOUND_TRIGGER_RUN_IN_BATTERY_SAVER` = 18 → signature\|privileged
- `MANAGE_SOUND_TRIGGER`、`CAPTURE_AUDIO_HOTWORD` = 0x4000012 → signature\|privileged

⇒ `/system/priv-app` 只解锁后两类；`BIND_VOICE_INTERACTION` 必须平台私钥签名。Android 12 起 `AlwaysOnHotwordDetector` 及其工厂方法 `createAlwaysOnHotwordDetector()` 也从公开 API 降为 `@SystemApi`。

## 【事实】vivo 的「小V小V」不走 AOSP 热词

1. 扫描本机 296 个 APK（/system、/system_ext、/product 等全部可读分区）的 manifest，**只有 framework-res.apk 出现 `BIND_VOICE_INTERACTION`**——全机没有任何应用声明 VoiceInteractionService。
2. `com.vivo.voicewakeup`(16.0.6.9) 直接用 `SoundTriggerManager`，把模型建成 `SoundTrigger$KeyphraseSoundModel`（dex 内 `createSTKeyphraseSoundModel`、`HotwordController_LoadModelFile`、`startRecognition`）。
3. 识别引擎是闭源 JNI 库 `com.mobvoi.be.speech.hotword.jni.HotwordController`（出门问问）。模型就在 APK 里：`assets/ivw/vivo_xvxv_wakeup_20220422_606.jet`（小V小V）、`vivo_jovi_wakeup_20220325_606.jet`（Hi Jovi）、`assets/wakeup/mobvoi/20221119_xvxv93.config`；另有高通 SVA 的 `sva/sml_config.xml` 与 ADSP 侧 `liftandtalk/nvd_1.1_adsp_enpu_off.eai`。**唤醒词编进声学模型，不是字符串。**

## 逐条

**(a) 特权应用**：装进 priv-app 能拿 sign\|priv 那批，但 `BIND_VOICE_INTERACTION` 拿不到，除非 LSPosed hook 权限校验、改 `framework.jar`/`services.jar`、或伪造 `packages.xml` 签名记录。都属刷机级改框架：KernelSU 模块好装，但 OTA 即失效、可能 bootloop、破坏完整性校验。即便当上 VoiceInteractionService，Android 12+ 也没有公开 API 设热词。【推测】成功率低、维护成本高，不建议。

**(b) 常驻监听（不需 root，推荐）**：Porcupine／openWakeWord／sherpa-onnx 做 KWS+VAD，前台服务+常驻通知+电池白名单，能做到「喊一句就醒」。代价：常驻占麦克风；Porcupine 官方约 0.6% CPU，实践反馈 1–2% CPU、10–20mA；屏幕关闭时靠 CPU 而非 DSP，比系统级费电，国产 ROM 省电策略会杀前台服务；误唤醒阈值要自己调。

**(c) 改 vivo 入口**：`com.vivo.voicewakeup` 在 /system/app、平台签名，与 `com.vivo.voicerecognition` 及 DSP 侧协同；hook 要同过签名校验、SELinux 与 SystemApi 校验，换词还需新的 Mobvoi `.jet` 模型——**用户无法自行生成**，OTA 还会覆盖。不可行。

## 最小实施清单（走 b）

- 依赖：`ai.picovoice:porcupine-android`（或开源 openWakeWord／sherpa-onnx）
- 权限：`RECORD_AUDIO`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`POST_NOTIFICATIONS`
- 触发：唤醒后起 DSH（Intent／Shizuku 均可）
- 设置：关闭电池优化、允许后台常驻；换词＝换模型文件，无需 root、无需重启

## 来源

- [AOSP 权限定义](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml)｜[Android 12 版本说明](https://source.android.com/docs/whatsnew/android-12-release)
- [priv-app 白名单只覆盖 signature\|privileged](https://source.android.google.cn/docs/core/permissions/perms-allowlist)｜[语音助手需系统签名权限](https://source.android.google.cn/docs/automotive/voice/voice_interaction_guide/app_development)
- [VoiceInteractionManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java)
- [前台服务必要性](https://github.com/Picovoice/porcupine/issues/338)｜[Porcupine 功耗指标](https://picovoice.ai/products/voice/wake-word/)｜[KernelSU Magic Mount](https://modules.kernelsu.org/zh-CN/modules/magic_mount_rs/)
