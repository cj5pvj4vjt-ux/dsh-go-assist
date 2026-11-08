package com.dshgo.assist;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * v12：原子岛样式的语音胶囊 + 可诊断日志 + 看门狗。
 *
 * 几何 / 动画参数（真机调参看这里）：
 *   - 位置：gravity = TOP | CENTER_HORIZONTAL，**y = 0**（压在状态栏上，像原子岛）
 *   - 展开态：高 44dp、圆角 22dp、宽 = 内容宽度（最小 168dp、最大 屏宽 − 64dp）
 *   - 岛形态：宽 96dp、高 32dp、圆角 16dp（同一 y，居中）
 *   - 展开：岛形态 → 展开态，240ms Linear（宽/高/圆角/alpha 一起插值）
 *   - 收回：展开态 → 岛形态 + 淡出，200ms
 *   - 底 #F20A0C10 + 1px #26FFFFFF 细描边；文字 14sp；波形条 10~16dp；✕ 18dp
 *   - 跑马灯（减弱版）：alpha 待机 110 / 聆听 190；周期待机 2400ms / 聆听 1200ms
 *   - 小圆点：同一位置（岛正中），8dp
 *
 * 稳定性：
 *   - 识别超时 15s；**看门狗 25s** 无条件 hide（onDestroy / onStartCommand 都会清理）
 *   - 显示胶囊期间持 PARTIAL_WAKE_LOCK（收回时 release）
 *   - onStartCommand 若已有窗口，先 removeView 再重加，避免叠窗
 *   - ✕ 和整个胶囊都可点，点击先写日志再收回
 *   - 全过程追加写 /sdcard/Android/media/com.dshgo.assist/overlay.log
 */
public class OverlayService extends Service {

    /** v27：带 maxHeight 的 ScrollView——由它自己把内容高度 clamp 住，不用手算固定值。 */
    static class MaxHeightScrollView extends ScrollView {
        private int maxHeightPx;

        MaxHeightScrollView(android.content.Context context) {
            super(context);
        }

        void setMaxHeightPx(int px) {
            this.maxHeightPx = px;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            try {
                int heightSpec = heightMeasureSpec;
                if (maxHeightPx > 0) {
                    heightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthMeasureSpec, heightSpec);
                if (maxHeightPx > 0 && getMeasuredHeight() > maxHeightPx) {
                    setMeasuredDimension(getMeasuredWidth(), maxHeightPx);
                }
            } catch (Throwable t) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    static final String ACTION_START = "com.dshgo.assist.action.OVERLAY_START";
    static final String EXTRA_ANIM_ONLY = "anim_only";
    static final String EXTRA_VOICE = "voice";
    /** v27：免说话的测试入口——直接用内置长文本走一遍回复卡片流程。 */
    static final String ACTION_TEST_REPLY = "com.dshgo.assist.action.TEST_REPLY";
    private static final String TEST_REPLY_TEXT =
            "这是回复卡片的自检测试文本（第 1 行）。\n"
            + "第 2 行：用来确认多行渲染与换行是否生效。\n"
            + "第 3 行：如果卡片只显示一行，说明 setSingleLine(false) 没生效。\n"
            + "第 4 行：下面继续堆 26 行，凑够 30 行。\n"
            + "第 5 行：卡片宽度应为屏宽减 48dp。\n"
            + "第 6 行：卡片最大高度为屏高的 45%。\n"
            + "第 7 行：超出部分可以上下滚动。\n"
            + "第 8 行：右下角有展开箭头。\n"
            + "第 9 行：点展开会到屏高的 65%。\n"
            + "第 10 行：停留 8 秒后自动收回（展开 30 秒）。\n"
            + "第 11 行：底边内侧有一条 2dp 流光。\n"
            + "第 12 行：流光被裁在圆角内。\n"
            + "第 13 行：右上角是关闭按钮。\n"
            + "第 14 行：点一下卡片会重置停留计时。\n"
            + "第 15 行：划返回或点窗外会收回。\n"
            + "第 16 行：文字颜色是 #1A1A1E。\n"
            + "第 17 行：行距 1.3、字号 13sp。\n"
            + "第 18 行：这一段内容不会写进 dsh-inbox。\n"
            + "第 19 行：也不会轮询 dsh-outbox。\n"
            + "第 20 行：纯粹为了截图验收。\n"
            + "第 21 行：中文字符宽度测试：一二三四五六七八九十。\n"
            + "第 22 行：英文与数字：ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789。\n"
            + "第 23 行：标点：，。！？；：“”‘’（）【】《》。\n"
            + "第 24 行：长句测试——这是一句比较长的话，用来确认换行与省略号不会互相干扰。\n"
            + "第 25 行：继续填充内容。\n"
            + "第 26 行：继续填充内容。\n"
            + "第 27 行：继续填充内容。\n"
            + "第 28 行：继续填充内容。\n"
            + "第 29 行：继续填充内容。\n"
            + "第 30 行：结束（如果你能看到这一行，说明卡片渲染正常）。";

    private static final String CHANNEL_ID = "dsh_overlay";
    private static final int NOTIF_ID = 3001;

    // ---- 时间参数 ----
    static final long TIMEOUT_MS = 15000;            // 识别超时
    static final long WATCHDOG_MS = 25000;           // 看门狗：无条件 hide
    static final long EXPAND_MS = 240;
    static final long COLLAPSE_MS = 200;

    // ---- 几何参数（原子岛）----
    static final int CAPSULE_HEIGHT_DP = 44;
    static final float CAPSULE_RADIUS_DP = 22f;
    static final int CAPSULE_MIN_WIDTH_DP = 200;
    static final int CAPSULE_MAX_MARGIN_DP = 96;     // 最大宽 = 屏宽 − 96dp
    static final int ISLAND_WIDTH_DP = 96;
    static final int ISLAND_HEIGHT_DP = 32;
    static final float ISLAND_RADIUS_DP = 16f;

    private static final String PREFS = "assist";
    private static final String KEY_VOICE = "voice_on_assist";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private FrameLayout capsule;
    private WindowManager.LayoutParams capsuleParams;
    private FrameLayout contentRoot;
    private MarqueeView marquee;
    private WaveBarsView waves;
    private FrameLayout capsuleBody;
    // v28：回复卡片是独立窗口（独立视图 + 独立 LayoutParams），不再和胶囊共用一套
    private FrameLayout replyRoot;
    private MaxHeightScrollView replyScroll;
    private TextView replyText;
    private TextView replyExpand;
    private WindowManager.LayoutParams replyParams;
    private boolean replyAdded;
    private boolean replyCardMode;
    private TextView statusView;
    private TextView subtitleView;
    private MaxHeightScrollView statusScroll;
    private TextView expandView;
    private TextView retryView;
    private String lastAskText;
    /** v25：最近一次非空的部分识别结果——超时时用它兜底，绝不丢已识别的文字。 */
    private volatile String lastNonEmptyPartial;
    private boolean replyError;
    private boolean replyExpanded;
    // v34：自动朗读
    private final Speaker speaker = new Speaker();
    // v42：连续对话 —— 答完自动续听；一旦转给 DSH，后续轮次全部转 DSH
    private boolean forceDsh;
    private android.widget.LinearLayout replyStatusRow;
    private int roundNo;   // v44：本次会话第几轮
    private boolean continuousHold;   // v49：连续对话进行中 → 卡片不许自动隐藏
    private TextView replyStatusView;   // v45：卡片顶部状态行
    // v28：记住最近一次回复，供「展开/收起」重建卡片用
    private String lastReplyBody;
    private boolean lastReplyError;
    private Runnable replyHideRunnable;
    private volatile boolean asrCancelled;
    private EdgeGlowView edgeGlow;
    private boolean edgeAdded;
    private volatile boolean partialBusy;
    private long lastPartialAt;
    private long lastTextAt;          // v48：最近一次识别出非空文字的时刻
    private static final int TEXT_QUIET_END_MS = 2500;   // v48：连续 2.5 秒没有新文字 → 认为说完了
    private int partialCount;
    private AudioRecord audioRecord;
    private Thread asrThread;
    private ValueAnimator morph;
    private PowerManager.WakeLock wakeLock;
    private boolean added;
    private boolean finishing;
    private int capsuleWidth;
    private int capsuleHeight;
    private long lastRmsLogAt;

    // 小圆点
    private View dotContainer;
    private boolean dotAdded;

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            OverlayLog.mark("timeout fired (" + TIMEOUT_MS + "ms) -> retract");
            tipThenRetract("超时，已收回");
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            OverlayLog.mark("WATCHDOG fired (" + WATCHDOG_MS + "ms) -> force hide");
            forceHide();
        }
    };

    // ------------------------------------------------------------- lifecycle --
    @Override
    public void onCreate() {
        super.onCreate();
        OverlayLog.mark("service onCreate");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:overlay");
            wakeLock.setReferenceCounted(false);
        }
        // v35：服务一起来就预热 TTS 引擎（第一次拉起引擎要 1~2 秒，回复时再初始化就来不及读了）
        try {
            speaker.init(this);
        } catch (Throwable ignored) {
            // 预热失败不影响其它功能
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        OverlayLog.mark("service onStartCommand intent=" + (intent == null ? "null"
                : intent.getAction()) + " animOnly="
                + (intent != null && intent.getBooleanExtra(EXTRA_ANIM_ONLY, false))
                + " state=" + stateName());
        final String action = intent == null ? null : intent.getAction();
        boolean animOnly = intent != null && intent.getBooleanExtra(EXTRA_ANIM_ONLY, false);
        boolean voice = intent == null || intent.getBooleanExtra(EXTRA_VOICE, true);
        startForegroundCompat();
        if (ACTION_TEST_REPLY.equals(action)) {
            // v27：免说话的回复卡片自检——不写 inbox、不轮询
            try {
                OverlayLog.mark("test reply card: len=" + TEST_REPLY_TEXT.length()
                        + " lines=" + (TEST_REPLY_TEXT.split("\n").length));
                // v29：不要再先 showCapsule()，否则胶囊的 post 与卡片的 add 会抢同一个窗口
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        showReply(TEST_REPLY_TEXT);
                    }
                }, 120);
            } catch (Throwable t) {
                OverlayLog.mark("test reply card FAIL " + t.getClass().getSimpleName());
            }
            return START_NOT_STICKY;
        }
        // v19：已经在「聆听/处理中/等回复」时收到重复 OVERLAY_START → 直接忽略，
        // 不做 removeView、不重启录音（之前会看到浮窗突然消失）
        if (!animOnly && added && !finishing) {
            OverlayLog.mark("duplicate start ignored (state=" + stateName() + ")");
            return START_NOT_STICKY;
        }
        // 清理上一次可能残留的状态（避免叠窗/卡住）
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(watchdog);
        cancelMorph();
        releaseWakeLock();
        if (added) {
            OverlayLog.mark("onStartCommand: window already added -> removeView first");
            removeCapsuleNow();
        }
        finishing = false;
        showCapsule();
        handler.postDelayed(watchdog, WATCHDOG_MS);
        OverlayLog.mark("watchdog armed (" + WATCHDOG_MS + "ms)");

        if (animOnly) {
            setStatus("语音助手", "轻触任意处收起");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retractInternal(false);
                }
            }, 2600);
        } else if (!voice) {
            setStatus("语音已关闭", "直接打开 DSH 小V");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retractInternal(true);
                }
            }, 700);
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            OverlayLog.mark("RECORD_AUDIO not granted -> tip and retract");
            tipThenRetract("没有麦克风权限");
        } else {
            // v13：不再用系统语音识别；本地 ASR 是否可用由 /health 探活判断
            OverlayLog.mark("voice path: start local ASR (AudioRecord)");
            setStatus("正在聆听…", null);
            handler.postDelayed(timeout, TIMEOUT_MS);
            startAsr();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        OverlayLog.mark("service onDestroy");        handler.removeCallbacksAndMessages(null);
        cancelMorph();
        stopAsr();
        removeCapsuleNow();
        hideDot();
        speaker.shutdown();   // v34：服务销毁时释放 TTS
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 当前状态（日志/重复 start 判定用）。 */
    private String stateName() {
        if (added && replyHideRunnable != null) {
            return "waiting/reply";
        }
        if (added) {
            return "listening/processing";
        }
        if (dotAdded) {
            return "idle-dot";
        }
        return "idle";
    }

    // ------------------------------------------------------- 窗口（原子岛）----
    /** v17：全屏边缘辉光（NOT_TOUCHABLE，触摸全透传）。 */
    private void showEdgeGlow() {
        try {
            if (edgeAdded) {
                return;
            }
            edgeGlow = new EdgeGlowView(this);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int navBar = navigationBarHeight();
            OverlayLog.mark("glow geometry fullscreen h=" + screenHeight
                    + " navBar=" + navBar);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                // v24：不让系统按状态栏/导航栏 insets 收缩窗口（系统栏是透明的，辉光画进去会被透出来）
                params.setFitInsetsTypes(0);
            }
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            windowManager.addView(edgeGlow, params);
            edgeAdded = true;
            edgeGlow.start();
            OverlayLog.mark("edge glow shown (fullscreen, NOT_TOUCHABLE, depth="
                    + EdgeGlowView.GLOW_DEPTH_DP + "dp)");
        } catch (Throwable t) {
            OverlayLog.mark("showEdgeGlow FAIL " + t);
        }
    }

    private void hideEdgeGlow(boolean animate) {
        final EdgeGlowView view = edgeGlow;
        if (view == null || !edgeAdded) {
            return;
        }
        edgeAdded = false;
        edgeGlow = null;
        try {
            view.stop();
            if (animate) {
                view.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            windowManager.removeView(view);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        OverlayLog.mark("edge glow removed (after fade)");
                    }
                }).start();
            } else {
                windowManager.removeView(view);
                OverlayLog.mark("edge glow removed");
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void showCapsule() {
        try {
            contentRoot = buildCapsuleView();
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int maxWidth = screenWidth - dp(CAPSULE_MAX_MARGIN_DP);
            int targetWidth = measureTargetWidth(contentRoot, maxWidth);
            capsuleWidth = targetWidth;
            capsuleHeight = dp(CAPSULE_HEIGHT_DP);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            capsuleParams = new WindowManager.LayoutParams(
                    targetWidth, dp(CAPSULE_HEIGHT_DP), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            if (Build.VERSION.SDK_INT >= 28) {
                // v13：允许进入挖孔/刘海区，否则会被系统推到摄像头下面
                capsuleParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            capsuleParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            capsuleParams.x = 0;
            // v16：应用悬浮窗永远排在 StatusBar 下面，所以完全落在状态栏下方 2dp，
            // 做成「一条流光 + 一行文字」，不再试图压状态栏。
            capsuleParams.y = statusBarHeight() + dp(2);
            OverlayLog.mark("geometry statusBarHeight=" + statusBarHeight() + "px capsuleY="
                    + capsuleParams.y + "px dotY=" + (statusBarHeight() + dp(2)) + "px");
            contentRoot.setAlpha(0f);
            contentRoot.setTranslationY(-dp(8));
            windowManager.addView(contentRoot, capsuleParams);
            added = true;
            showEdgeGlow();
            acquireWakeLock();
            OverlayLog.mark("capsule shown w=" + Math.round(targetWidth
                    / getResources().getDisplayMetrics().density) + "dp h="
                    + CAPSULE_HEIGHT_DP + "dp (y=" + capsuleParams.y + "px)");
            animateMorph(true, null);
        } catch (Throwable t) {
            OverlayLog.mark("showCapsule FAIL " + t);
            stopSelf();
        }
    }

    private int measureTargetWidth(View view, int maxWidth) {
        try {
            int widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth,
                    View.MeasureSpec.AT_MOST);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(dp(CAPSULE_HEIGHT_DP),
                    View.MeasureSpec.EXACTLY);
            view.measure(widthSpec, heightSpec);
            int measured = view.getMeasuredWidth();
            return Math.max(dp(CAPSULE_MIN_WIDTH_DP), Math.min(maxWidth, measured));
        } catch (Throwable t) {
            return Math.max(dp(CAPSULE_MIN_WIDTH_DP),
                    Math.min(maxWidth, dp(CAPSULE_MIN_WIDTH_DP)));
        }
    }

    /**
     * v16：出现 = alpha 0→1 + translationY −8dp→0，160ms；收回反向。
     * 不再做宽高/圆角的岛形缩放。
     */
    private void animateMorph(final boolean expand, final Runnable onEnd) {
        try {
            cancelMorph();
            float fromAlpha = expand ? 0f : 1f;
            float toAlpha = expand ? 1f : 0f;
            float fromY = expand ? -dp(8) : 0f;
            float toY = expand ? 0f : -dp(8);
            morph = ValueAnimator.ofFloat(0f, 1f);
            morph.setDuration(160);
            morph.setInterpolator(new LinearInterpolator());
            morph.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (float) animation.getAnimatedValue();
                    try {
                        contentRoot.setAlpha(fromAlpha + (toAlpha - fromAlpha) * t);
                        contentRoot.setTranslationY(fromY + (toY - fromY) * t);
                    } catch (Throwable ignored) {
                        // 窗口可能已被移除
                    }
                }
            });
            morph.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (onEnd != null) {
                        onEnd.run();
                    }
                }
            });
            morph.start();
        } catch (Throwable t) {
            OverlayLog.mark("animateMorph FAIL " + t);
            if (onEnd != null) {
                onEnd.run();
            }
        }
    }

    private void setBackgroundRadius(float radiusDp) {
        try {
            android.graphics.drawable.Drawable bg = contentRoot.getBackground();
            if (bg instanceof GradientDrawable) {
                ((GradientDrawable) bg).setCornerRadius(dp(radiusDp));
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void cancelMorph() {
        if (morph != null) {
            morph.cancel();
            morph = null;
        }
    }

    /**
     * v16 视图：不再画实心胶囊盒子，只有
     *   - 顶部一条 2dp 流光（MarqueeView，两端羽化）
     *   - 下面 8dp 一行文字（14sp #F2FFFFFF + 1px 深色描边，底 #66000000 极轻圆角）
     *   - 右侧 ✕ 18dp #8AFFFFFF
     */
    /**
     * v22：整个悬浮条只有一个胶囊元素（不再有独立的顶部流光带）：
     *   - 白色胶囊 44dp 高、圆角 22dp、白底 #F2FFFFFF + 1px #14000000 描边
     *   - 流光画在胶囊底边内侧 2dp，clip 在圆角内（setClipToOutline），两端羽化
     *   - 左侧 3 根竖波形条（2dp 宽、1dp 圆角、3dp 间距、8~18dp 高）
     *   - 文字后紧跟 ✕（右侧 12dp 内边距），宽度 wrap_content（200dp ~ 屏宽−96dp）
     */
    private FrameLayout buildCapsuleView() {
        final FrameLayout root = new FrameLayout(this);
        // 外层：模拟投影（#1A000000，圆角比胶囊大 1dp）
        GradientDrawable shadowBg = new GradientDrawable();
        shadowBg.setShape(GradientDrawable.RECTANGLE);
        shadowBg.setColor(0x1A000000);
        shadowBg.setCornerRadius(dp(CAPSULE_RADIUS_DP) + dp(1));
        root.setBackground(shadowBg);
        root.setPadding(dp(1), dp(1), dp(1), dp(1));
        root.setClickable(true);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayLog.mark("overlay tapped -> retract");
                retract();
            }
        });

        // 内层：白色胶囊本体（children 裁剪在圆角内）
        FrameLayout body = new FrameLayout(this);
        this.capsuleBody = body;
        GradientDrawable whiteBg = new GradientDrawable();
        whiteBg.setShape(GradientDrawable.RECTANGLE);
        whiteBg.setColor(0xF2FFFFFF);
        whiteBg.setCornerRadius(dp(CAPSULE_RADIUS_DP));
        whiteBg.setStroke(Math.max(1, dp(1)), 0x14000000);
        body.setBackground(whiteBg);
        body.setClipToOutline(true);
        root.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 底边内侧的流光（2dp，两端留 24dp 羽化，clip 在胶囊圆角内）
        marquee = new MarqueeView(this);
        FrameLayout.LayoutParams marqueeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM);
        marqueeLp.leftMargin = dp(24);
        marqueeLp.rightMargin = dp(24);
        marqueeLp.bottomMargin = dp(1);
        body.addView(marquee, marqueeLp);
        marquee.setListening(true);

        // 内容列：一行（竖条 + 文字 + ✕），下面可选的展开/重试
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        body.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, 0, 0);
        column.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        waves = new WaveBarsView(this);
        row.addView(waves, new LinearLayout.LayoutParams(dp(12), dp(18)));
        waves.setListening(true);

        statusView = new TextView(this);
        statusView.setText("正在聆听…");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        statusView.setTextColor(0xFF1A1A1E);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxBarWidth = screenWidth - dp(CAPSULE_MAX_MARGIN_DP);
        statusView.setMaxWidth(Math.max(dp(100), maxBarWidth - dp(150)));

        statusScroll = new MaxHeightScrollView(this);
        statusScroll.setVerticalScrollBarEnabled(false);
        statusScroll.setFillViewport(false);
        statusScroll.addView(statusView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.WRAP_CONTENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        // v23：文字区 layout_weight=1 撑满剩余空间 → ✕ 被推到最右端
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.leftMargin = dp(8);
        row.addView(statusScroll, statusParams);

        subtitleView = new TextView(this);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subtitleView.setTextColor(0x66000000);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitleView.setVisibility(View.GONE);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        close.setTextColor(0x66000000);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(8), dp(4), dp(14), dp(4));
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayLog.mark("close ✕ tapped -> retract");
                retract();
            }
        });
        row.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        expandView = new TextView(this);
        expandView.setText("\u2304");
        expandView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        expandView.setTextColor(0x66000000);
        expandView.setGravity(Gravity.END);
        expandView.setPadding(dp(12), dp(2), dp(12), 0);
        expandView.setVisibility(View.GONE);
        expandView.setClickable(true);
        expandView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleExpand();
            }
        });
        column.addView(expandView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        retryView = new TextView(this);
        retryView.setText("重试");
        retryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        retryView.setTextColor(0xFF1A56DB);
        retryView.setGravity(Gravity.END);
        retryView.setPadding(dp(12), dp(2), dp(12), dp(4));
        retryView.setVisibility(View.GONE);
        retryView.setClickable(true);
        retryView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retryAsk();
            }
        });
        column.addView(retryView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 用户一碰小窗就重置停留计时；划返回/点窗外收回
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                int action = event.getAction();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    resetReplyTimer();
                } else if (action == android.view.MotionEvent.ACTION_OUTSIDE) {
                    // v29：系统在窗口获得/失去焦点时也会发 ACTION_OUTSIDE（无指针、坐标 0），
                    // 这种事件必须忽略，否则悬浮条一出现就被自己收掉。
                    if (event.getPointerCount() == 0
                            || (event.getX() == 0f && event.getY() == 0f)) {
                        OverlayLog.mark("outside touch ignored (spurious)");
                        return false;
                    }
                    OverlayLog.mark("outside touch -> retract");
                    retract();
                    return true;
                }
                return false;
            }
        });
        return root;
    }

    private void setStatus(final String status, final String subtitle) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) {
                    statusView.setText(status);
                }
                if (subtitleView != null) {
                    if (subtitle == null || subtitle.length() == 0) {
                        subtitleView.setVisibility(View.GONE);
                    } else {
                        subtitleView.setText(subtitle);
                        subtitleView.setVisibility(View.VISIBLE);
                    }
                }
            }
        });
    }

    private void retract() {
        retractInternal(false);
    }

    private void tipThenRetract(final String tip) {
        tipThenRetract(tip, 900);
    }

    /** v18：短提示 1.2s（没听到/没听清），辉光已先收起。 */
    private void postTipQuick(final String tip) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!finishing) {
                    tipThenRetract(tip, TIP_SHORT_MS);
                }
            }
        });
    }

    /** v15：需要更久停留的提示（例如「没听到声音」1.5s）。 */
    private void postTipLong(final String tip) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!finishing) {
                    tipThenRetract(tip, 1500);
                }
            }
        });
    }

    private void tipThenRetract(final String tip, final long stayMs) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                OverlayLog.mark("tip: " + tip);
                setStatus(tip, null);
                if (marquee != null) {
                    marquee.setListening(false);
                }
                if (edgeGlow != null) {
                    edgeGlow.setListening(false);
                }
                if (waves != null) {
                    waves.setListening(false);
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        retractInternal(false);
                    }
                }, stayMs);
            }
        });
    }

    private void retractInternal(final boolean openDsh) {
        if (finishing) {
            return;
        }
        finishing = true;
        replyCancelled = true;
        forceDsh = false;   // v42：会话结束，下次重新从本地模型开始
        roundNo = 0;        // v44：轮次归零
        continuousHold = false;   // v49
        replyHideRunnable = null;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(watchdog);
        stopAsr();
        hideEdgeGlow(true);
        // v30：回复卡片是独立窗口，retract 时必须一起移除，否则 ✕ 点了也关不掉
        removeReplyCard();
        speaker.stop();   // v34：收回时停止朗读
        OverlayLog.mark("capsule retract (openDsh=" + openDsh + ")");
        try {
            if (contentRoot != null && added) {
                animateMorph(false, new Runnable() {
                    @Override
                    public void run() {
                        removeCapsuleNow();
                        releaseWakeLock();
                        if (openDsh) {
                            Dispatcher.openDsh(OverlayService.this);
                        }
                        finishToDotOrStop();
                    }
                });
            } else {
                finishToDotOrStop();
            }
        } catch (Throwable t) {
            OverlayLog.mark("retract FAIL " + t);
            removeCapsuleNow();
            releaseWakeLock();
            stopSelf();
        }
    }

    /** 看门狗：无条件立刻收掉窗口，避免「卡住不回去」。 */
    private void forceHide() {
        finishing = true;
        replyCancelled = true;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(watchdog);
        stopAsr();
        cancelMorph();
        removeCapsuleNow();
        // v30：看门狗兜底时也要移除回复卡片
        removeReplyCard();
        speaker.stop();   // v34：兜底收紧时也停止朗读
        releaseWakeLock();
        OverlayLog.mark("forceHide done");
        finishToDotOrStop();
    }

    private void removeCapsuleNow() {
        if (contentRoot != null && added) {
            try {
                windowManager.removeView(contentRoot);
            } catch (Throwable ignored) {
                // ignore
            }
            OverlayLog.mark("capsule hidden");
        }
        added = false;
        contentRoot = null;
        marquee = null;
        waves = null;
        statusView = null;
        subtitleView = null;
    }

    // ------------------------------------------------------------- wake lock --
    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(40000);      // 保底 40s，收回也会 release
                OverlayLog.mark("wake lock acquired");
            }
        } catch (Throwable t) {
            OverlayLog.mark("wake lock acquire FAIL " + t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                OverlayLog.mark("wake lock released");
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------ 小圆点 ----
    /**
     * v22：小圆点「智能默认」——
     *   - prefs 明确关掉 → 不显示；
     *   - 否则默认显示（免 root 时的唤起入口）；
     *   - 仅当 power_mode=root 且 Shizuku 可用（root 路径可靠）时自动隐藏。
     */
    private boolean isDotEnabled() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean pref = sp.getBoolean(RootOps.prefKeyOverlayDot(), false);
            if (!pref) {
                OverlayLog.mark("dot policy: pref=false -> hide");
                return false;
            }
            String mode = sp.getString(RootOps.PREF_POWER_MODE, RootOps.MODE_SYSTEM);
            boolean rootMode = RootOps.MODE_ROOT.equals(mode);
            boolean rootReady = false;
            try {
                rootReady = ShizukuShell.binderReady() && ShizukuShell.hasPermission();
            } catch (Throwable ignored) {
                // ignore
            }
            boolean show = !(rootMode && rootReady);
            OverlayLog.mark("dot policy: root=" + rootMode + " shizuku=" + rootReady
                    + " -> " + (show ? "show" : "hide"));
            return show;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isVoiceEnabled() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_VOICE, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void finishToDotOrStop() {
        if (isDotEnabled()) {
            showDot();
        } else {
            stopSelf();
        }
    }

    private void showDot() {
        try {
            if (dotAdded) {
                return;
            }
            FrameLayout container = new FrameLayout(this);
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0x99FFFFFF);
            dot.setBackground(g);
            container.addView(dot, new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER));
            container.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    OverlayLog.mark("dot tapped -> expand again");
                    hideDot();
                    expandAgain();
                }
            });
            container.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    OverlayLog.mark("dot long-pressed -> stop service");
                    hideDot();
                    stopSelf();
                    return true;
                }
            });
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(28), dp(28), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.x = 0;
            // v16：状态栏下方 2dp（与流光同一条水平线，永不被状态栏遮挡）
            lp.y = statusBarHeight() + dp(2);
            container.setAlpha(0f);
            windowManager.addView(container, lp);
            dotContainer = container;
            dotAdded = true;
            container.animate().alpha(1f).setDuration(180).start();
            OverlayLog.mark("dot shown (8dp @ y=" + (statusBarHeight() + dp(2)) + "px)");
        } catch (Throwable t) {
            OverlayLog.mark("showDot FAIL " + t);
            stopSelf();
        }
    }

    private void hideDot() {
        if (dotContainer != null && dotAdded) {
            try {
                windowManager.removeView(dotContainer);
            } catch (Throwable ignored) {
                // ignore
            }
            OverlayLog.mark("dot hidden");
        }
        dotAdded = false;
        dotContainer = null;
    }

    /**
     * v42：连续对话——回复显示完约 2.5 秒后自动重新聆听下一段。
     *   - 需要「连续对话」开关（默认开）；
     *   - 会话里已转过 DSH 的话，forceDsh 会保证后续也走 DSH。
     */
    private void scheduleNextListen() {
        try {
            if (!prefs().getBoolean("continuous_chat", true)) {
                return;
            }
            continuousHold = true;   // v49：本会话内卡片常驻，直到用户收起
            if (replyHideRunnable != null) {
                handler.removeCallbacks(replyHideRunnable);
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (finishing) {
                            return;
                        }
                        OverlayLog.mark("continuous: 自动续听下一段（卡片内）"
                                + (forceDsh ? " DSH" : " 本地"));
                        startListeningInCard();
                    } catch (Throwable t) {
                        OverlayLog.mark("continuous restart FAIL " + t.getClass().getSimpleName());
                    }
                }
            }, 2500);
        } catch (Throwable t) {
            OverlayLog.mark("scheduleNextListen FAIL " + t.getClass().getSimpleName());
        }
    }

    /** v50：轮询本地模型的流式输出（网络一律走后台线程），把生成中的文字实时打到卡片上。 */
    private void pollLocalStream(final String text, final int tick) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finishing) {
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final LocalRouter.Partial pp = LocalRouter.partial();
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                handlePartial(text, tick, pp);
                            }
                        });
                    }
                }, "local-partial").start();
            }
        }, 150);
    }

    /** v50：在主线程处理一次 /partial 的结果。 */
    private void handlePartial(final String text, final int tick, final LocalRouter.Partial p) {
        if (finishing) {
            return;
        }
        if (p == null) {
            // 推理期间接口可能短暂无响应：容忍 40 次（约 6 秒），再不行退回阻塞式，绝不直接转 DSH
            if (tick < 40) {
                if (tick % 10 == 0) {
                    OverlayLog.mark("local stream poll null x" + (tick + 1) + " (继续等)");
                }
                pollLocalStream(text, tick + 1);
                return;
            }
            OverlayLog.mark("local stream gave up -> 阻塞式兜底");
            setLowPowerAnim(false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String r = LocalRouter.ask(text);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (finishing) {
                                return;
                            }
                            if (r != null && r.startsWith("LOCAL:")) {
                                showReply(r.substring(6));
                            } else {
                                forceDsh = true;
                                deliverToDsh(text);
                            }
                        }
                    });
                }
            }, "local-fallback").start();
            return;
        }
        if (!p.done) {
            if (p.text.length() > 0) {
                showReplyCard(p.text, false);
                if (replyStatusView != null) {
                    replyStatusView.setText("正在输入…");
                }
            } else if (tick == 4) {
                setStatus("正在思考…", text);
            }
            pollLocalStream(text, tick + 1);
            return;
        }
        setLowPowerAnim(false);
        if ("local".equals(p.route) && p.answer.length() > 0) {
            showReply(p.answer);
        } else {
            forceDsh = true;
            deliverToDsh(text);
        }
    }


    /** v45：推理期间把流光/波形条降到低功耗档（周期变长），给模型腾 CPU。 */
    private void setLowPowerAnim(boolean low) {
        try {
            if (marquee != null) {
                marquee.setListening(!low);
            }
            if (edgeGlow != null) {
                edgeGlow.setListening(!low);
            }
            OverlayLog.mark("anim low-power=" + low);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** v43：第二轮起不再单开胶囊，直接在回复卡片里继续聆听。 */
    private void startListeningInCard() {
        finishing = false;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
        if (replyStatusRow != null) {
            replyStatusRow.setVisibility(android.view.View.VISIBLE);
        }
        if (marquee != null) {
            marquee.setListening(true);
        }
        setStatus("正在聆听…", null);
        handler.postDelayed(timeout, TIMEOUT_MS);
        startAsr();
    }

    /** 点小圆点：重新展开胶囊并听一句话。 */
    private void expandAgain() {
        finishing = false;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(watchdog);
        showCapsule();
        handler.postDelayed(watchdog, WATCHDOG_MS);
        if (marquee != null) {
            marquee.setListening(true);
        }
        if (edgeGlow != null) {
            edgeGlow.setListening(true);
        }
        if (waves != null) {
            waves.setListening(true);
        }
        if (!isVoiceEnabled()) {
            setStatus("语音已关闭", null);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retractInternal(true);
                }
            }, 700);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            tipThenRetract("没有麦克风权限");
            return;
        }
        OverlayLog.mark("expandAgain: start local ASR");
        setStatus("正在聆听…", null);
        handler.postDelayed(timeout, TIMEOUT_MS);
        startAsr();
    }

    // ----------------------------------------------------------- 自建录音 ASR ----
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_MS = 100;
    private static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;   // 1600
    // v15：自适应能量 VAD（真机实测：安静底噪 154、真人说话峰值 433，
    // 固定阈值 1500 会把说话当静音，所以改成「先测噪、再定阈」）
    private static final int NOISE_WINDOW_MS = 300;       // 前 300ms 估噪声底
    private static final int NOISE_UPDATE_MS = 500;       // 每 500ms 用移动最小值更新噪声底
    // v25：双阈值（迟滞）——起判高、判静音低，环境噪声抖动不会一直「像在说话」
    private static final int VAD_START_MIN_RMS = 150;     // start = max(150, noise*1.8)
    private static final float VAD_START_FACTOR = 1.8f;
    private static final int VAD_END_MIN_RMS = 120;       // end   = max(120, noise*1.4)
    private static final float VAD_END_FACTOR = 1.4f;
    private static final long SPEECH_TIMEOUT_MS = 20000;  // v25：已起判后总时长放宽到 20s
    private static final int VAD_GAIN = 2;                // v21：软件增益 ×2（算 RMS 和拼 WAV 前）
    private static final int VAD_START_FRAMES = 2;        // v21：连续 2 帧超阈值才起判
    private static final int SILENCE_END_MS = 2500;       // v17：句中停顿容忍 2.5s
    private static final int RESUME_GRACE_MS = 800;       // v17：停顿后再说话视为同一句
    private static final int NO_SPEECH_MS = 4500;         // v18：4.5s 内没起始 → 立刻收回
    private static final int MAX_RECORD_MS = 25000;       // v17：单次最长录音 25s
    private static final int PARTIAL_INTERVAL_MS = 700;   // v17：实时字幕节流
    private static final int PARTIAL_WINDOW_MS = 12000;   // 部分识别只取最近 12s
    private static final String VOICE_WAV_PATH =
            "/sdcard/Android/media/com.dshgo.assist/last-voice.wav";

    // v16：2 秒环形预录（VAD 起判前 1200ms 也要送进去，避免吃掉句首）
    private static final int PRE_ROLL_MS = 1200;
    private static final int PRE_ROLL_BUFFER_MS = 2000;
    private static final int PRE_ROLL_FRAMES = PRE_ROLL_MS / FRAME_MS;              // 12
    private static final int PRE_ROLL_MAX_FRAMES = PRE_ROLL_BUFFER_MS / FRAME_MS;   // 20

    // v16：结果小窗（不启动任何 Activity，走文件收件箱/发件箱）
    private static final String ASR_DIR = "/sdcard/Android/media/com.dshgo.assist";
    private static final String INBOX_DIR = ASR_DIR + "/dsh-inbox";
    private static final String OUTBOX_DIR = ASR_DIR + "/dsh-outbox";
    private static final long REPLY_POLL_MS = 500;
    private static final int REPLY_POLL_MAX = 360;          // v18：360 × 500ms = 180s
    private static final long REPLY_WATCHDOG_MS = 200000;   // v18：等 DSH 回复阶段看门狗 200s
    private static final long REPLY_HOLD_MS = 15000;         // 有回复后停留 8s
    private static final long REPLY_EXPANDED_HOLD_MS = 30000; // 展开后停留 30s
    private static final long REPLY_ERROR_HOLD_MS = 5000;   // v18：失败提示停留 5s
    private static final long TIP_SHORT_MS = 1200;          // v18：没听清/没听到 提示 1.2s
    private static final int LINES_MAX_REPLY = 10;          // （保留）回复小窗行数参考值
    // v26：回复态 = 卡片（宽 屏宽−48dp，高 WRAP_CONTENT，min 88dp / max 屏高×0.45）
    private static final int REPLY_WIDTH_MARGIN_DP = 48;
    private static final int REPLY_MIN_HEIGHT_DP = 88;
    private static final float REPLY_MAX_HEIGHT_RATIO = 0.45f;
    private static final float REPLY_EXPANDED_RATIO = 0.65f;
    private static final String PREF_REPLY_VIA_DSH = "reply_via_dsh";

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** v13：自建录音 + 本地离线识别（系统识别服务全废）。 */
    private void startAsr() {
        stopAsr();
        asrCancelled = false;
        lastNonEmptyPartial = null;
        final String endpoint = prefs().getString(RootOps.prefKeyAsrEndpoint(),
                RootOps.DEFAULT_ASR_ENDPOINT);
        final String token = prefs().getString(RootOps.prefKeyAsrToken(),
                RootOps.DEFAULT_ASR_TOKEN);
        asrThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runAsr(endpoint, token);
            }
        }, "dsh-asr");
        asrThread.start();
    }

    private void stopAsr() {
        asrCancelled = true;
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try {
                record.stop();
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                record.release();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        Thread thread = asrThread;
        asrThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(300);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private void runAsr(String endpoint, String token) {
        long overallStart = System.currentTimeMillis();
        // 1) 探活
        String healthUrl = endpoint;
        if (healthUrl.endsWith("/transcribe")) {
            healthUrl = healthUrl.substring(0, healthUrl.length() - "/transcribe".length());
        }
        if (healthUrl.endsWith("/")) {
            healthUrl = healthUrl.substring(0, healthUrl.length() - 1);
        }
        healthUrl = healthUrl + "/health";
        boolean healthy = httpHealth(healthUrl);
        OverlayLog.mark("asr health " + (healthy ? "ok" : "fail") + " url=" + healthUrl);
        if (asrCancelled) {
            return;
        }
        if (!healthy) {
            postTip("识别服务未启动");
            return;
        }
        // 2) 录音 + VAD
        byte[] pcm = recordWithVad(overallStart);
        if (pcm == null || pcm.length == 0 || asrCancelled) {
            return;
        }
        // 3) 上传
        postWav(endpoint, token, pcm, overallStart);
    }

    private boolean httpHealth(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            int code = conn.getResponseCode();
            String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            OverlayLog.mark("asr health response code=" + code + " body=" + abbreviate(body));
            return code == 200;
        } catch (Throwable t) {
            OverlayLog.mark("asr health error " + t.getClass().getName()
                    + ": " + t.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private byte[] recordWithVad(long overallStart) {
        AudioRecord record = null;
        ByteArrayOutputStream speechPcm = new ByteArrayOutputStream();
        ByteArrayOutputStream allPcm = new ByteArrayOutputStream();
        ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
        boolean speech = false;
        boolean calibrated = false;
        long silenceAccumMs = 0;
        int overCount = 0;
        long speechStart = 0;
        long lastVoice = 0;
        long lastNoiseUpdate = 0;
        int peak = 0;
        long prependedMs = 0;
        int noiseSum = 0;
        int noiseSamples = 0;
        int minRms = Integer.MAX_VALUE;
        float noise = VAD_START_MIN_RMS;
        float startThreshold = VAD_START_MIN_RMS;
        float endThreshold = VAD_END_MIN_RMS;
        int bufSize = 0;
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            bufSize = Math.max(minBuf, FRAME_SAMPLES * 2 * 4);
            // v21：MIC 优先（VOICE_RECOGNITION 在很多机器上增益偏低），失败再退 VOICE_RECOGNITION
            int source = MediaRecorder.AudioSource.MIC;
            String sourceName = "MIC";
            try {
                record = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
            } catch (Throwable t) {
                OverlayLog.mark("asr source fallback: MIC init failed ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + ") -> VOICE_RECOGNITION");
                source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
                sourceName = "VOICE_RECOGNITION";
                record = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
            }
            OverlayLog.mark("asr source=" + sourceName + " state=" + record.getState()
                    + " recordingState=" + record.getRecordingState()
                    + " bufSize=" + bufSize + " minBuf=" + minBuf);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                OverlayLog.mark("asr error AudioRecord not initialized");
                postTip("录音初始化失败");
                return null;
            }
            audioRecord = record;
            OverlayLog.mark("asr recording start sr=" + SAMPLE_RATE
                    + " mono pcm16 vad=dual(start=max(" + VAD_START_MIN_RMS + ",noise*"
                    + VAD_START_FACTOR + "), end=max(" + VAD_END_MIN_RMS + ",noise*"
                    + VAD_END_FACTOR + "))");
            record.startRecording();
            lastNoiseUpdate = overallStart;
            lastPartialAt = overallStart;
            lastTextAt = overallStart;
            short[] buffer = new short[FRAME_SAMPLES];
            long now;
            while (true) {
                if (asrCancelled) {
                    OverlayLog.mark("asr cancelled");
                    return null;
                }
                now = System.currentTimeMillis();
                long limitMs = speech ? SPEECH_TIMEOUT_MS : TIMEOUT_MS;
                if (now - overallStart > limitMs) {
                    OverlayLog.mark("asr error overall timeout (" + limitMs + "ms, speech="
                            + speech + ")");
                    // v25：绝不丢已识别的文字——有非空部分结果就用它当最终文本继续交给 DSH
                    String salvage = lastNonEmptyPartial;
                    if (salvage != null && salvage.trim().length() > 0) {
                        final String text = salvage.trim();
                        OverlayLog.mark("asr final from partial: \"" + abbreviate(text) + "\"");
                        saveWav(allPcm.toByteArray(), peak, now - overallStart);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                deliver(text);
                            }
                        });
                        return null;
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            hideEdgeGlow(false);
                        }
                    });
                    postTipQuick("没听清，再说一次");
                    return null;
                }
                int n = record.read(buffer, 0, buffer.length);
                if (n <= 0) {
                    int retry = 0;
                    while (n <= 0 && retry < 3) {
                        retry++;
                        OverlayLog.mark("asr read retry " + retry + "/3 n=" + n);
                        try {
                            Thread.sleep(50);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        n = record.read(buffer, 0, buffer.length);
                    }
                }
                if (n <= 0) {
                    OverlayLog.mark("asr error read=" + n + " (after 3 retries)");
                    postTip("录音失败");
                    return null;
                }
                long sum = 0;
                for (int i = 0; i < n; i++) {
                    // v21：软件增益 ×2（PCM16 饱和裁剪），同时改善识别率
                    int v = buffer[i] * VAD_GAIN;
                    if (v > 32767) {
                        v = 32767;
                    } else if (v < -32768) {
                        v = -32768;
                    }
                    buffer[i] = (short) v;
                    sum += (long) v * v;
                }
                int rms = (int) Math.sqrt(sum / (double) n);
                if (rms > peak) {
                    peak = rms;
                }
                postLevel(rms);
                byte[] frame = shortToBytes(buffer, n);
                allPcm.write(frame);
                if (frame.length > 0 && peak == 0) {
                    // 不会发生，占位保证编译器不优化
                    peak = 0;
                }

                // --- 自适应噪声底/阈值 ---
                if (!calibrated) {
                    noiseSum += rms;
                    noiseSamples++;
                    if (now - overallStart >= NOISE_WINDOW_MS) {
                        // 噪声底用实测值（不夹），阈值才夹下限：
                        // 真机 noise=154 → threshold=max(220, 385)=385 < 说话峰值 433，能触发
                        noise = Math.max(1f, noiseSum / (float) Math.max(1, noiseSamples));
                        startThreshold = Math.max(VAD_START_MIN_RMS, noise * VAD_START_FACTOR);
                        endThreshold = Math.max(VAD_END_MIN_RMS, noise * VAD_END_FACTOR);
                        calibrated = true;
                        lastNoiseUpdate = now;
                        OverlayLog.mark("asr vad noise=" + (int) noise + " start="
                                + (int) startThreshold + " end=" + (int) endThreshold
                                + " (from first " + NOISE_WINDOW_MS + "ms)");
                    }
                } else {
                    if (rms < minRms) {
                        minRms = rms;
                    }
                    if (now - lastNoiseUpdate >= NOISE_UPDATE_MS) {
                        lastNoiseUpdate = now;
                        if (minRms < noise) {          // 只降不升，避免把说话算进底噪
                            noise = Math.max(1f, minRms);
                            startThreshold = Math.max(VAD_START_MIN_RMS,
                                    noise * VAD_START_FACTOR);
                            endThreshold = Math.max(VAD_END_MIN_RMS,
                                    noise * VAD_END_FACTOR);
                        }
                        OverlayLog.mark("asr vad noise=" + (int) noise + " start="
                                + (int) startThreshold + " end=" + (int) endThreshold
                                + " rms=" + rms);
                        OverlayLog.mark("asr rms=" + rms + " start=" + (int) startThreshold
                                + " end=" + (int) endThreshold);
                        minRms = Integer.MAX_VALUE;
                    }
                }

                boolean overThreshold = calibrated && rms > startThreshold;
                if (overThreshold && !speech) {
                    overCount++;
                } else if (!overThreshold && !speech) {
                    overCount = 0;
                }
                if (overThreshold && !speech && overCount >= VAD_START_FRAMES) {
                    {
                        speech = true;
                        speechStart = now;
                        // v16：把环形预录缓冲里「起始点前 1200ms」拼到最前面，避免吃掉句首
                        byte[][] ring = preRoll.toArray(new byte[0][]);
                        int from = Math.max(0, ring.length - PRE_ROLL_FRAMES);
                        int prependedBytes = 0;
                        for (int k = from; k < ring.length; k++) {
                            speechPcm.write(ring[k]);
                            prependedBytes += ring[k].length;
                        }
                        prependedMs = prependedBytes * 1000L / (SAMPLE_RATE * 2);
                        OverlayLog.mark("asr speech start rms=" + rms + " start="
                                + (int) startThreshold + " end=" + (int) endThreshold
                                + " preroll=" + PRE_ROLL_MS + "ms prepended="
                                + prependedMs + "ms");
                        OverlayLog.mark("asr vad start rms=" + rms + " start="
                                + (int) startThreshold + " (" + VAD_START_FRAMES + " frames)");
                    }
                    lastVoice = now;
                } else if (overThreshold && speech) {
                    lastVoice = now;
                }
                if (speech) {
                    speechPcm.write(frame);
                    // v25：双阈值迟滞——只有 rms < end 才累计静音；
                    // end~start 之间算「中性」（不累计也不清零），>= start 视为还在说
                    if (rms < endThreshold) {
                        silenceAccumMs += FRAME_MS;
                    } else if (rms >= startThreshold) {
                        if (silenceAccumMs > 0) {
                            OverlayLog.mark("asr resume after pause");
                        }
                        silenceAccumMs = 0;
                    }
                    // v48：主判据 = 连续 2.5s 没有识别出新文字（噪声再大也不影响，因为噪声不产生文字）
                    if (now - lastTextAt >= TEXT_QUIET_END_MS
                            && lastNonEmptyPartial != null) {
                        OverlayLog.mark("asr speech end (no new text for "
                                + TEXT_QUIET_END_MS + "ms)");
                        break;
                    }
                    // 兜底：RMS 长时间低于静音阈值（阈值放宽到 3.5s，避免噪声抖动误判）
                    if (silenceAccumMs >= 3500) {
                        OverlayLog.mark("asr speech end (rms silence 3500ms)");
                        break;
                    }
                    if (now - speechStart > MAX_RECORD_MS) {
                        OverlayLog.mark("asr speech end (max " + MAX_RECORD_MS + "ms)");
                        break;
                    }
                    // v17：每 700ms 发一次「截至目前」的部分识别，做实时字幕
                    if (now - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                        if (partialBusy) {
                            OverlayLog.mark("asr partial skipped (busy)");
                        } else {
                            lastPartialAt = now;
                            launchPartial(allPcm.toByteArray());
                        }
                    }
                } else {
                    preRoll.addLast(frame);
                    while (preRoll.size() > PRE_ROLL_MAX_FRAMES) {
                        preRoll.removeFirst();
                    }
                    if (calibrated && now - overallStart > NO_SPEECH_MS) {
                        OverlayLog.mark("asr rms peak=" + peak + " no speech in "
                                + NO_SPEECH_MS + "ms (noise=" + (int) noise
                                + ")");
                        saveWav(allPcm.toByteArray(), peak, now - overallStart);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                hideEdgeGlow(false);
                            }
                        });
                        postTipQuick("没听到声音");
                        return null;
                    }
                }
            }
            long waitUntil = System.currentTimeMillis() + 3000;
            while (partialBusy && System.currentTimeMillis() < waitUntil) {
                try {
                    Thread.sleep(100);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            byte[] pcm = speech ? speechPcm.toByteArray() : allPcm.toByteArray();
            long durationMs = pcm.length * 1000L / (SAMPLE_RATE * 2);
            OverlayLog.mark("asr rms peak=" + peak);
            OverlayLog.mark("asr recorded " + durationMs + "ms bytes=" + pcm.length);
            OverlayLog.mark("asr preroll=" + PRE_ROLL_MS + "ms prepended=" + prependedMs
                    + "ms totalMs=" + durationMs);
            saveWav(pcm, peak, durationMs);
            return pcm;
        } catch (Throwable t) {
            OverlayLog.mark("asr error " + t.getClass().getName() + ": " + t.getMessage());
            saveWav(allPcm.toByteArray(), peak, 0);
            postTip("录音失败");
            return null;
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                    // ignore
                }
                try {
                    record.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            audioRecord = null;
        }
    }

    /** 把录音喂给胶囊里的波形条（真实 RMS 驱动，用户说话能看见起伏）。 */
    private void postLevel(final int rms) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (waves != null) {
                    waves.setLevel(rms);
                }
            }
        });
    }

    /** v15：录音落盘（无论识别成功失败），方便离线复跑模型对比。 */
    private void saveWav(byte[] pcm, int peak, long durationMs) {
        if (pcm == null || pcm.length == 0) {
            OverlayLog.mark("asr wav skipped (empty), rmsPeak=" + peak);
            return;
        }
        try {
            byte[] wav = buildWav(pcm);
            java.io.File file = new java.io.File(VOICE_WAV_PATH);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
            try {
                fos.write(wav);
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {
                    // ignore
                }
            } finally {
                fos.close();
            }
            OverlayLog.mark("asr wav saved bytes=" + wav.length + " rmsPeak=" + peak
                    + " durationMs=" + durationMs + " path=" + VOICE_WAV_PATH);
        } catch (Throwable t) {
            OverlayLog.mark("asr wav save FAIL " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /** v17：实时字幕——只解码最近 12s，串行化（上一次没回来就跳过）。 */
    private void launchPartial(final byte[] allPcmSnapshot) {
        partialBusy = true;
        final int n = ++partialCount;
        final long started = System.currentTimeMillis();
        final String endpoint = prefs().getString(RootOps.prefKeyAsrEndpoint(),
                RootOps.DEFAULT_ASR_ENDPOINT);
        final String token = prefs().getString(RootOps.prefKeyAsrToken(),
                RootOps.DEFAULT_ASR_TOKEN);
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    byte[] wav = buildWav(lastSeconds(allPcmSnapshot, PARTIAL_WINDOW_MS / 1000));
                    conn = (HttpURLConnection) new URL(endpoint).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(6000);
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(wav.length);
                    conn.setRequestProperty("Content-Type", "application/octet-stream");
                    conn.setRequestProperty("x-dsh-token", token);
                    conn.getOutputStream().write(wav);
                    conn.getOutputStream().flush();
                    int code = conn.getResponseCode();
                    String body = readAll(code >= 400 ? conn.getErrorStream()
                            : conn.getInputStream());
                    long ms = System.currentTimeMillis() - started;
                    final String text = extractJsonText(body);
                    OverlayLog.mark("asr partial n=" + n + " ms=" + ms + " code=" + code
                            + " text=\"" + abbreviate(text) + "\"");
                    if (code == 200 && text != null && text.trim().length() > 0) {
                        lastNonEmptyPartial = text.trim();
                        lastTextAt = System.currentTimeMillis();   // v48：有文字 → 刷新静默计时
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!finishing && statusView != null) {
                                    statusView.setText(text.trim());
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    OverlayLog.mark("asr partial error n=" + n + " "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                    partialBusy = false;
                }
            }
        }, "dsh-partial").start();
    }

    private static byte[] lastSeconds(byte[] pcm, int seconds) {
        int max = SAMPLE_RATE * 2 * seconds;
        if (pcm.length <= max) {
            return pcm;
        }
        byte[] out = new byte[max];
        System.arraycopy(pcm, pcm.length - max, out, 0, max);
        return out;
    }

    private void postWav(String endpoint, String token, byte[] pcm, long overallStart) {
        HttpURLConnection conn = null;
        try {
            byte[] wav = buildWav(pcm);
            long started = System.currentTimeMillis();
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(wav.length);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("x-dsh-token", token);
            conn.getOutputStream().write(wav);
            conn.getOutputStream().flush();
            int code = conn.getResponseCode();
            long ms = System.currentTimeMillis() - started;
            String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            OverlayLog.mark("asr http code=" + code + " ms=" + ms + " wav=" + wav.length);
            String text = extractJsonText(body);
            OverlayLog.mark("asr text=\"" + abbreviate(text) + "\"");
            OverlayLog.mark("asr final text=\"" + abbreviate(text) + "\"");
            if (code == 200 && text != null && text.trim().length() > 0) {
                lastNonEmptyPartial = text.trim();
            } else {
                // v25：最终 POST 空/失败时，退回最近一次非空的部分识别结果
                String salvage = lastNonEmptyPartial;
                if (salvage != null && salvage.trim().length() > 0) {
                    final String fallback = salvage.trim();
                    OverlayLog.mark("asr final from partial: \"" + abbreviate(fallback)
                            + "\"");
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            deliver(fallback);
                        }
                    });
                    return;
                }
            }
            if (code != 200 || text == null || text.trim().length() == 0) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        hideEdgeGlow(false);
                    }
                });
                postTipQuick("没听清，再说一次");
                return;
            }
            final String finalText = text.trim();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    deliver(finalText);
                }
            });
        } catch (Throwable t) {
            OverlayLog.mark("asr http error " + t);
            postTip("识别服务异常");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void postTip(final String tip) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!finishing) {
                    tipThenRetract(tip);
                }
            }
        });
    }

    private static byte[] shortToBytes(short[] buffer, int count) {
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            bytes[i * 2] = (byte) (buffer[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((buffer[i] >> 8) & 0xFF);
        }
        return bytes;
    }

    /** 44 字节 WAV 头 + PCM16 LE。 */
    private static byte[] buildWav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'});
        b.putInt(36 + pcm.length);
        b.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        b.putInt(16);
        b.putShort((short) 1);                 // PCM
        b.putShort((short) 1);                 // mono
        b.putInt(SAMPLE_RATE);
        b.putInt(SAMPLE_RATE * 2);             // byte rate
        b.putShort((short) 2);                 // block align
        b.putShort((short) 16);                // bits per sample
        b.put(new byte[]{'d', 'a', 't', 'a'});
        b.putInt(pcm.length);
        b.put(pcm);
        return b.array();
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toString("UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 极简 JSON: 取出 "text":"..."（处理反斜杠引号与 uXXXX）。 */
    private static String extractJsonText(String json) {
        if (json == null) {
            return null;
        }
        int key = json.indexOf("\"text\"");
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key + 6);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == 'u' && i + 5 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    i += 6;
                    continue;
                }
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == 't') {
                    sb.append('\t');
                } else {
                    sb.append(next);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private void deliver(final String text) {
        if (finishing) {
            return;
        }
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(watchdog);
        OverlayLog.mark("deliver text(len=" + text.length() + "): " + abbreviate(text));

        boolean legacy = prefs().getBoolean(PREF_REPLY_VIA_DSH, false);
        if (legacy) {
            // 旧路径：把文本 ACTION_SEND 给 dsh-go（高级区开关打开时才走）
            OverlayLog.mark("deliver mode=legacy ACTION_SEND");
            setStatus("正在交给 DSH…", text);
            if (marquee != null) {
                marquee.setListening(false);
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (finishing) {
                        return;
                    }
                    finishing = true;
                    stopAsr();
                    hideThen(new Runnable() {
                        @Override
                        public void run() {
                            Dispatcher.shareText(OverlayService.this, text);
                            finishToDotOrStop();
                        }
                    });
                }
            }, 350);
            return;
        }

        // v37：先问本地小模型（千问3-0.6B）；它答不了再转 DSH。可在设置里关掉。
        if (prefs().getBoolean(RootOps.prefKeyLocalFirst(), true)) {
            askLocalThenMaybeDsh(text);
            return;
        }
        deliverToDsh(text);
    }

    /** v37：本地优先——本地能答就直接当回复，否则原样走 DSH。 */
    private void askLocalThenMaybeDsh(final String text) {
        // v42：本轮会话里一旦转过 DSH，后续一律继续转 DSH（不论问题多简单）
        if (forceDsh) {
            OverlayLog.mark("forceDsh: 本会话已转 DSH，继续转交");
            deliverToDsh(text);
            return;
        }
        // v38：游戏在前台时不用本地模型（把 CPU/内存让给游戏），直接走 DSH
        final String game = LocalRouter.foregroundGame(this);
        if (game != null) {
            OverlayLog.mark("local model skipped (game=" + game + ") -> DSH");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    LocalRouter.unload();
                }
            }, "local-unload").start();
            deliverToDsh(text);
            return;
        }
        setStatus("问一下本地模型…", text);
        if (marquee != null) {
            marquee.setListening(false);
        }
        // v47：流式生成必须走后台线程（主线程做网络会抛 NetworkOnMainThreadException）
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = LocalRouter.startAsk(text);
                if (ok) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pollLocalStream(text, 0);
                        }
                    });
                } else {
                    // 流式不可用 → 退回一次性拿完整结果
                    final String r = LocalRouter.ask(text);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (finishing) {
                                return;
                            }
                            setLowPowerAnim(false);
                            if (r != null && r.startsWith("LOCAL:")) {
                                OverlayLog.mark("local model answered (blocking) len=" + (r.length() - 6));
                                showReply(r.substring(6));
                            } else {
                                forceDsh = true;
                                deliverToDsh(text);
                            }
                        }
                    });
                }
            }
        }, "local-stream").start();
        if (true) {
            return;
        }
        setLowPowerAnim(true);   // v45：模型推理期间降动效，把 CPU 让出来
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = LocalRouter.ask(text);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        setLowPowerAnim(false);
                    }
                });
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finishing) {
                            return;
                        }
                        if (r != null && r.startsWith("LOCAL:")) {
                            OverlayLog.mark("local model answered len=" + (r.length() - 6));
                            showReply(r.substring(6));
                        } else {
                            forceDsh = true;
                            OverlayLog.mark("local model -> escalate ("
                                    + (r == null ? "unavailable" : "model") + ")，本会话后续都转 DSH");
                            deliverToDsh(text);
                        }
                    }
                });
            }
        }, "local-router").start();
    }

    /** v16 默认路径：写收件箱 + 轮询发件箱 + 同一个小窗显示回复，全程不启动任何 Activity。 */
    private void deliverToDsh(final String text) {
        setStatus("正在交给 DSH…", text);
        if (marquee != null) {
            marquee.setListening(false);
        }
        final long taskId = System.currentTimeMillis();
        String askPath = writeAsk(taskId, text);
        if (askPath == null) {
            tipThenRetract("任务写入失败", 1500);
            return;
        }
        // v18：等 DSH 回复阶段不再用 25s 看门狗（会误杀），改成 200s
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, REPLY_WATCHDOG_MS);
        OverlayLog.mark("watchdog re-armed for reply wait (" + REPLY_WATCHDOG_MS + "ms)");
        lastAskText = text;
        startReplyPoll(taskId);
    }

    private void hideThen(final Runnable after) {
        try {
            if (contentRoot != null && added) {
                animateMorph(false, new Runnable() {
                    @Override
                    public void run() {
                        removeCapsuleNow();
                        releaseWakeLock();
                        after.run();
                    }
                });
            } else {
                after.run();
            }
        } catch (Throwable t) {
            OverlayLog.mark("hideThen FAIL " + t);
            after.run();
        }
    }

    // ================= v16 结果小窗（不允许启动任何 Activity） =================
    private volatile boolean replyCancelled;

    /** 把识别文本写到 dsh-inbox/<ts>.txt，文件名即任务 id。 */
    private String writeAsk(long taskId, String text) {
        try {
            java.io.File dir = new java.io.File(INBOX_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.File file = new java.io.File(dir, taskId + ".txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
            try {
                fos.write(text.getBytes("UTF-8"));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {
                    // ignore
                }
            } finally {
                fos.close();
            }
            OverlayLog.mark("ask written path=" + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (Throwable t) {
            OverlayLog.mark("ask write FAIL " + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
            return null;
        }
    }

    /** 每 500ms 轮询 dsh-outbox/<ts>.txt，最多 180 次（90s）。 */
    private void startReplyPoll(final long taskId) {
        replyCancelled = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.File out = new java.io.File(OUTBOX_DIR, taskId + ".txt");
                for (int i = 1; i <= REPLY_POLL_MAX; i++) {
                    if (replyCancelled || finishing) {
                        OverlayLog.mark("poll cancelled at " + i + "/" + REPLY_POLL_MAX);
                        return;
                    }
                    OverlayLog.mark("poll " + i + "/" + REPLY_POLL_MAX);
                    if (i % 2 == 0) {          // 每 1s 刷新一次秒表
                        final int sec = (int) (i * REPLY_POLL_MS / 1000);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!finishing) {
                                    setStatusColor(0xFF8A5A00);
                                    setStatus("DSH 处理中 " + sec + "s", null);
                                }
                            }
                        });
                    }
                    try {
                        if (out.exists() && out.length() > 0) {
                            String reply = readTextFile(out);
                            OverlayLog.mark("reply bytes=" + out.length());
                            final String body = reply;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    showReply(body);
                                }
                            });
                            return;
                        }
                    } catch (Throwable t) {
                        OverlayLog.mark("poll read FAIL " + t.getClass().getSimpleName()
                                + ": " + t.getMessage());
                    }
                    try {
                        Thread.sleep(REPLY_POLL_MS);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                OverlayLog.mark("reply timeout (" + (REPLY_POLL_MAX * REPLY_POLL_MS / 1000) + "s)");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finishing) {
                            return;
                        }
                        replyError = true;
                        setStatusColor(0xFFC0392B);
                        setStatus("DSH 没答上来，可点「重试」", null);
                        if (retryView != null) {
                            retryView.setVisibility(View.VISIBLE);
                        }
                        scheduleReplyHide(REPLY_ERROR_HOLD_MS);
                    }
                });
            }
        }, "dsh-reply-poll").start();
    }

    /**
     * v17：把回复正文显示在同一个悬浮窗里。
     *   - 默认最多 10 行（13sp、行距 1.25），超出可上下滚动
     *   - 超 10 行时右下角出现「展开」箭头，点开高度撑到屏幕 60%，停留 30s
     *   - 停留计时从「内容显示完成」开始；用户一碰小窗就重置计时
     */
    /**
     * v34：根据情况自动朗读回复。
     *   - 设置里关掉「回复后自动朗读」→ 不读；
     *   - 媒体音量为 0（静音）→ 不读；
     *   - 错误提示不读。
     */
    private void maybeSpeak(String body) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!sp.getBoolean(RootOps.prefKeyAutoSpeak(), false)) {
                OverlayLog.mark("tts skip (auto_speak=false)");
                return;
            }
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null
                    && am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
                OverlayLog.mark("tts skip (media volume 0)");
                return;
            }
            speaker.init(this);
            speaker.speak(body);
        } catch (Throwable t) {
            OverlayLog.mark("tts maybeSpeak FAIL " + t.getClass().getSimpleName());
        }
    }

    private void showReply(String reply) {
        if (finishing) {
            return;
        }
        String body = reply == null ? "" : reply.trim();
        // v18：服务端没答上来时（会话锁冲突等）显示红字 + 重试
        boolean failed = body.startsWith("DSH ")
                && (body.contains("没能回答") || body.contains("无输出") || body.contains("没答"));
        replyError = failed;
        // v28：回复一律走「独立回复卡片视图」，不再改动胶囊视图
        replyExpanded = false;
        lastReplyBody = body;
        lastReplyError = failed;
        OverlayLog.mark("reply delegating to card len=" + body.length() + (failed ? " error" : ""));
        if (!failed) {
            maybeSpeak(body);
        }
        roundNo++;              // v44：轮次 +1（状态行会显示，方便确认内容确实更新了）
        showReplyCard(body, failed);
        scheduleNextListen();   // v42：答完自动续听下一段
    }

    /** v27：卡片显示后打一行测量结果，便于判断是否真的多行渲染、窗口是否被撑开。 */
    private void logReplyMeasured(final TextView tv) {
        try {
            if (tv.getViewTreeObserver().isAlive()) {
                tv.getViewTreeObserver().addOnGlobalLayoutListener(
                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                try {
                                    if (tv.getViewTreeObserver().isAlive()) {
                                        tv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                    }
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                try {
                                    int winH = capsuleParams == null ? -1 : capsuleParams.height;
                                    OverlayLog.mark("reply card measured: vw="
                                            + tv.getWidth() + "px vh=" + tv.getHeight()
                                            + "px textLines=" + tv.getLineCount()
                                            + " winH=" + winH + "px");
                                } catch (Throwable t) {
                                    OverlayLog.mark("reply card measured FAIL "
                                            + t.getClass().getSimpleName());
                                }
                            }
                        });
            }
        } catch (Throwable t) {
            OverlayLog.mark("logReplyMeasured FAIL " + t.getClass().getSimpleName());
        }
    }

    private long replyStayMs = REPLY_HOLD_MS;

    /** v27：聆听态 / 回复态的文字样式必须显式切换（只改 maxLines 可能不生效）。 */
    private void applyTextStyle(boolean reply) {
        try {
            if (statusView == null) {
                return;
            }
            if (reply) {
                statusView.setSingleLine(false);
                statusView.setMaxLines(Integer.MAX_VALUE);
                statusView.setEllipsize(null);
                statusView.setHorizontallyScrolling(false);
                statusView.setTextColor(0xFF1A1A1E);
                statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                statusView.setLineSpacing(0f, 1.3f);
                statusView.setGravity(Gravity.START);
            } else {
                statusView.setSingleLine(true);
                statusView.setMaxLines(1);
                statusView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                statusView.setHorizontallyScrolling(true);
                statusView.setTextColor(0xFF1A1A1E);
                statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                statusView.setGravity(Gravity.CENTER_VERTICAL);
            }
            statusView.requestLayout();
        } catch (Throwable t) {
            OverlayLog.mark("applyTextStyle FAIL " + t.getClass().getSimpleName());
        }
    }

    private void setStatusColor(int color) {
        try {
            if (statusView != null) {
                statusView.setTextColor(color);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** v18：重发同一条文本，重新轮询。 */
    private void retryAsk() {
        if (lastAskText == null || lastAskText.length() == 0) {
            return;
        }
        OverlayLog.mark("reply retry requested");
        replyError = false;
        setStatusColor(0xFF1A1A1E);
        if (retryView != null) {
            retryView.setVisibility(View.GONE);
        }
        long taskId = System.currentTimeMillis();
        String path = writeAsk(taskId, lastAskText);
        if (path == null) {
            tipThenRetract("任务写入失败", 1500);
            return;
        }
        setStatus("DSH 处理中 0s", null);
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, REPLY_WATCHDOG_MS);
        startReplyPoll(taskId);
    }

    /** v28：回复卡片的文字样式（独立视图用）。 */
    private void applyReplyTextStyle(TextView tv, boolean error) {
        tv.setSingleLine(false);
        tv.setMaxLines(Integer.MAX_VALUE);
        tv.setEllipsize(null);
        tv.setHorizontallyScrolling(false);
        tv.setTextColor(error ? 0xFFC0392B : 0xFF1A1A1E);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setLineSpacing(0f, 1.3f);
        tv.setGravity(Gravity.START);
    }

    private void removeReplyCard() {
        if (replyRoot != null && replyAdded) {
            try {
                windowManager.removeView(replyRoot);
            } catch (Throwable ignored) {
                // ignore
            }
            OverlayLog.mark("reply card removed");
        }
        replyAdded = false;
        replyRoot = null;
        replyScroll = null;
        replyText = null;
        replyExpand = null;
    }

    /**
     * v28：显示「回复卡片」——**独立窗口 + 独立视图**（不再复用胶囊，避免 LayoutParams 不跟随）。
     * 视图自身 MATCH_PARENT × WRAP_CONTENT，跟着窗口宽度走；窗口 width = 屏宽−48dp、height = WRAP_CONTENT。
     */
    private void showReplyCard(final String body, final boolean error) {
        try {
            // v45：已经有一张卡片就【原地换内容】，不再 remove + 新建（旧卡会堆在最上层）
            if (replyRoot != null && replyAdded && replyText != null) {
                applyReplyTextStyle(replyText, error);
                replyText.setText(body.length() == 0 ? "(空回复)" : body);
                if (replyStatusView != null) {
                    replyStatusView.setText("第 " + Math.max(1, roundNo) + " 轮 · 已回复"
                            + (forceDsh ? " · DSH" : ""));
                }
                if (replyScroll != null) {
                    replyScroll.scrollTo(0, 0);
                }
                OverlayLog.mark("reply card reused (in-place) len=" + body.length());
                return;
            }
            if (added) {
                removeCapsuleNow();          // 先把胶囊收掉，避免两个窗口叠着
            }
            removeReplyCard();

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int maxHeight = (int) (screenHeight * (replyExpanded
                    ? REPLY_EXPANDED_RATIO : REPLY_MAX_HEIGHT_RATIO));

            FrameLayout root = new FrameLayout(this);
            // v43：卡片里也放一条状态行（波形条 + 状态文字），第二轮续听就不再单开胶囊
            android.widget.LinearLayout cardColumn = new android.widget.LinearLayout(this);
            cardColumn.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout statusRow = new android.widget.LinearLayout(this);
            statusRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            statusRow.setPadding(dp(14), dp(10), dp(14), 0);
            WaveBarsView cardBars = new WaveBarsView(this);
            statusRow.addView(cardBars, new android.widget.LinearLayout.LayoutParams(dp(18), dp(18)));
            TextView cardStatus = new TextView(this);
            cardStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
            cardStatus.setTextColor(0x66000000);
            cardStatus.setPadding(dp(8), 0, 0, 0);
            cardStatus.setText("第 " + Math.max(1, roundNo) + " 轮 · 已回复" + (forceDsh ? " · DSH" : ""));
            statusRow.addView(cardStatus, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            cardColumn.addView(statusRow);
            replyStatusRow = statusRow;
            replyStatusView = cardStatus;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(0xF7FFFFFF);
            bg.setCornerRadius(dp(18));
            bg.setStroke(Math.max(1, dp(1)), 0x14000000);
            root.setBackground(bg);
            root.setClipToOutline(true);
            root.setClickable(true);

            replyText = new TextView(this);
            applyReplyTextStyle(replyText, error);
            replyText.setText(body.length() == 0 ? "(空回复)" : body);
            replyText.setPadding(dp(14), dp(12), dp(36), dp(12));

            replyScroll = new MaxHeightScrollView(this);
            replyScroll.setFillViewport(false);
            replyScroll.setVerticalScrollBarEnabled(true);
            // v32：卡片根是 ViewGroup，子控件会吃掉触摸 → 必须在 ScrollView 自己身上重置停留计时，
            // 否则用户滚动看内容时 8s 到点就被自动收回。
            replyScroll.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    int a = event.getActionMasked();
                    if (a == android.view.MotionEvent.ACTION_DOWN
                            || a == android.view.MotionEvent.ACTION_MOVE
                            || a == android.view.MotionEvent.ACTION_UP) {
                        resetReplyTimer();
                    }
                    return false;   // 不消费，交给 ScrollView 正常滚动
                }
            });
            replyScroll.setMaxHeightPx(Math.max(dp(REPLY_MIN_HEIGHT_DP), maxHeight));
            replyScroll.addView(replyText, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            scrollLp.topMargin = dp(6);
            scrollLp.bottomMargin = dp(6);
            cardColumn.addView(replyScroll, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            root.addView(cardColumn, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            // ASR 的状态更新直接打到卡片上（第二轮续听就在这张卡里进行）
            statusView = cardStatus;
            cardBars.setListening(true);   // 卡片里的波形条常驻轻微动效

            TextView close = new TextView(this);
            close.setText("\u2715");
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            close.setTextColor(0x66000000);
            close.setPadding(dp(8), dp(4), dp(10), dp(8));
            close.setClickable(true);
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    OverlayLog.mark("reply close ✕ tapped -> retract");
                    retract();
                }
            });
            FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            root.addView(close, closeLp);

            replyExpand = new TextView(this);
            replyExpand.setText("\u2304");
            replyExpand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            replyExpand.setTextColor(0x66000000);
            replyExpand.setPadding(dp(10), dp(4), dp(10), dp(4));
            replyExpand.setClickable(true);
            replyExpand.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleExpand();
                }
            });
            FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            root.addView(replyExpand, arrowLp);

            root.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    int action = event.getAction();
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        resetReplyTimer();
                        speaker.stop();   // v34：碰一下卡片就停止朗读
                    } else if (action == android.view.MotionEvent.ACTION_OUTSIDE) {
                        // v31：忽略系统在焦点变化时发出的假 ACTION_OUTSIDE（无指针/坐标 0）
                        if (event.getPointerCount() == 0
                                || (event.getX() == 0f && event.getY() == 0f)) {
                            OverlayLog.mark("card outside touch ignored (spurious)");
                            return false;
                        }
                        OverlayLog.mark("card outside touch -> retract");
                        retract();
                        return true;
                    }
                    return false;
                }
            });

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            replyParams = new WindowManager.LayoutParams(
                    screenWidth - dp(REPLY_WIDTH_MARGIN_DP),
                    WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            if (Build.VERSION.SDK_INT >= 28) {
                replyParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                replyParams.setFitInsetsTypes(0);
            }
            replyParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            replyParams.x = 0;
            replyParams.y = statusBarHeight() + dp(2);

            windowManager.addView(root, replyParams);
            replyRoot = root;
            replyAdded = true;
            OverlayLog.mark("reply card layout w=" + (screenWidth - dp(REPLY_WIDTH_MARGIN_DP))
                    + "px maxH=" + maxHeight + "px expanded=" + replyExpanded);
            root.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (replyRoot != null && replyAdded && replyParams != null) {
                            windowManager.updateViewLayout(replyRoot, replyParams);
                        }
                        if (replyScroll != null) {
                            replyScroll.scrollTo(0, 0);
                        }
                        int lines = replyText == null ? 0 : replyText.getLineCount();
                        OverlayLog.mark("reply shown len=" + body.length() + " lines="
                                + lines + " expanded=" + replyExpanded
                                + (error ? " error" : ""));
                        logReplyMeasured();
                        scheduleReplyHide(error ? REPLY_ERROR_HOLD_MS : -1);
                    } catch (Throwable t) {
                        OverlayLog.mark("reply card post FAIL "
                                + t.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable t) {
            OverlayLog.mark("showReplyCard FAIL " + t.getClass().getSimpleName());
        }
    }

    /** v28：打完布局后记录实测尺寸（vw 应 ≈ 屏宽−48dp；长文本 textLines > 1）。 */
    private void logReplyMeasured() {
        try {
            final View target = replyRoot;
            if (target == null || !target.getViewTreeObserver().isAlive()) {
                return;
            }
            target.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                if (target.getViewTreeObserver().isAlive()) {
                                    target.getViewTreeObserver()
                                            .removeOnGlobalLayoutListener(this);
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            try {
                                int lines = replyText == null ? 0 : replyText.getLineCount();
                                int winH = replyParams == null ? -1 : replyParams.height;
                                OverlayLog.mark("reply measured: vw=" + target.getWidth()
                                        + "px vh=" + target.getHeight() + "px textLines="
                                        + lines + " winH=" + winH + "px");
                            } catch (Throwable t) {
                                OverlayLog.mark("reply measured FAIL "
                                        + t.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (Throwable t) {
            OverlayLog.mark("logReplyMeasured FAIL " + t.getClass().getSimpleName());
        }
    }

    private void toggleExpand() {
        try {
            if (statusView == null || statusScroll == null) {
                return;
            }
            replyExpanded = !replyExpanded;
            if (lastReplyBody != null) {
                // v28：卡片是独立视图，展开/收起 = 按新上限重建一次
                showReplyCard(lastReplyBody, lastReplyError);
            }
            OverlayLog.mark(replyExpanded ? "reply expanded -> 65%" : "reply collapsed -> 45%");
            scheduleReplyHide();
        } catch (Throwable t) {
            OverlayLog.mark("toggleExpand FAIL " + t.getClass().getSimpleName());
        }
    }

    private void scheduleReplyHide() {
        scheduleReplyHide(-1);
    }

    private void scheduleReplyHide(long overrideStayMs) {
        if (continuousHold) {
            OverlayLog.mark("reply hide skipped (continuous session)");
            return;
        }
        if (replyHideRunnable != null) {
            handler.removeCallbacks(replyHideRunnable);
        }
        replyStayMs = overrideStayMs > 0 ? overrideStayMs
                : (replyExpanded ? REPLY_EXPANDED_HOLD_MS : REPLY_HOLD_MS);
        replyHideRunnable = new Runnable() {
            @Override
            public void run() {
                retractInternal(false);
            }
        };
        handler.postDelayed(replyHideRunnable, replyStayMs);
    }

    /** 用户碰小窗：重置停留计时。 */
    private void resetReplyTimer() {
        if (replyHideRunnable == null) {
            // v32：计时器还没建立（或已被清掉）时，重新建立一次，别直接返回
            scheduleReplyHide();
            OverlayLog.mark("reply touch -> timer (re)armed");
            return;
        }
        handler.removeCallbacks(replyHideRunnable);
        handler.postDelayed(replyHideRunnable, replyStayMs);
        OverlayLog.mark("reply touch -> timer reset");
    }

    private static String readTextFile(java.io.File file) {
        try {
            byte[] buf = new byte[(int) Math.min(file.length(), 65536)];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }
    // ================= v16 结果小窗 end =================

    private static String abbreviate(String text) {
        if (text == null) {
            return "(null)";
        }
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    // ----------------------------------------------------------- 前台通知 ----
    private void startForegroundCompat() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Throwable t) {
            OverlayLog.mark("startForeground FAIL " + t);
            stopSelf();
        }
    }

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DSH 小V")
                .setContentText("语音助手")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "DSH 语音助手", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("原子岛语音胶囊的前台服务通知");
                channel.setShowBadge(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * v15：状态栏高度（px）。优先 resources.getIdentifier("status_bar_height","dimen","android")，
     * 取不到退回 dp(38)（本机实测约 38dp）。
     */
    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int px = getResources().getDimensionPixelSize(id);
                if (px > 0) {
                    return px;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return dp(38);
    }

    /**
     * v21：底部导航栏高度（px）。系统窗口永远盖在应用悬浮窗上，盖不过去，
     * 所以让辉光窗口高度 = 屏高 − 导航栏高度，底边正好贴住导航栏上沿。
     */
    private int navigationBarHeight() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) {
                    android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
                    android.view.WindowInsets insets = metrics.getWindowInsets();
                    android.graphics.Insets nav = insets.getInsets(
                            android.view.WindowInsets.Type.navigationBars());
                    if (nav.bottom > 0) {
                        return nav.bottom;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 退资源查询
        }
        try {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) {
                int px = getResources().getDimensionPixelSize(id);
                if (px > 0) {
                    return px;
                }
            }
        } catch (Throwable ignored) {
            // 退 dp(48)
        }
        return dp(48);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}