package com.dshgo.assist;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.text.InputType;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;

import rikka.shizuku.Shizuku;

/**
 * v5 主界面：Material 观感（全部程序化，无 res/）。
 *
 * 结构：Hero（标题 + 接管状态 pill）→ 状态卡片（6 行）→ 操作卡片（一键接管 + 次要按钮）
 *      → 实验卡片（唤醒截胡开关）→ 内容卡片（root 输出）。
 * 启动时不弹任何系统对话框，所有授权都由按钮触发。
 */
public class MainActivity extends Activity {

    private static final int REQ_ROLE = 1;
    private static final int REQ_MIC = 47;
    private static final int REQ_WRITE_SETTINGS = 2;
    private static final int REQ_NOTIFICATIONS = 3;

    private static final String PREFS = "assist";
    private static final String KEY_VOICE = "voice_on_assist";
    private static final String KEY_PENDING_WRITE = "pending_power_wakeup_write";

    private static final String CHANNEL_ID = "dsh_live";
    private static final String CHANNEL_NAME = "DSH 实时动态";
    private static final int NOTIF_ID = 1001;
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";

    private static final int STATUS_ROLE = 0;
    private static final int STATUS_POWER_MODE = 1;
    private static final int STATUS_OVERLAY = 2;
    private static final int STATUS_SHIZUKU = 3;
    private static final int STATUS_SESSION = 4;
    private static final int STATUS_NOTIFICATION = 5;
    private static final int STATUS_COUNT = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Deque<String> outputLines = new ArrayDeque<>();
    private final TextView[] statusValues = new TextView[STATUS_COUNT];
    private final View[] statusDots = new View[STATUS_COUNT];

    private Ui ui;
    private TextView heroBadge;
    private View heroDot;
    private LinearLayout advancedBody;
    private TextView advancedChevron;
    private TextView outputView;
    private Switch voiceSwitch;
    private String lastIslandResult = "（还没测）";
    private long lastSilentHealAt;
    private boolean startupFailed;
    /** v9：root 桥状态改为读 rootq/.bridge 标记（null=未知/未检测，由 root shell 代读）。 */
    private Boolean bridgeRunning;
    private boolean bridgeChecking;
    /** v11：电源键钩子状态（由 root shell 读 pid 文件探测）。 */
    private String hookState = "检测中…";
    private boolean hookChecking;
    private TextView hookDetail;
    private TextView sessionDetailView;
    private TextView powerModeDetailView;

    public MainActivity() {
        BootTrace.mark("MainActivity.<init>");
    }

    // ------------------------------------------------------- Shizuku 监听 ----
    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshStatus();
                        }
                    });
                }
            };

    private final Shizuku.OnBinderDeadListener binderDeadListener =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshStatus();
                        }
                    });
                }
            };

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != ShizukuShell.REQ_PERMISSION) {
                        return;
                    }
                    final boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(granted ? "Shizuku 已授权" : "Shizuku 授权被拒绝");
                            refreshStatus();
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        BootTrace.mark("MainActivity.onCreate enter");
        try {
            super.onCreate(savedInstanceState);
            ui = new Ui(this);
            BootTrace.mark("MainActivity: new Ui() ok");
            View content = buildUi();
            BootTrace.mark("MainActivity: buildUi() ok");
            // 顺序很关键：先铺内容（会创建 DecorView），再碰 InsetsController，
            // 否则 PhoneWindow.getInsetsController() 会 NPE（v5 瞬间闪退的根因）。
            setContentView(content);
            BootTrace.mark("MainActivity: setContentView ok");
            applyEdgeToEdge();
            BootTrace.mark("MainActivity: applyEdgeToEdge ok");
        } catch (Throwable t) {
            BootTrace.mark("MainActivity.onCreate FAIL " + t);
            try {
                CrashHandler.writeManualReport(this, "onCreate", t);
            } catch (Throwable ignored) {
                // ignore
            }
            showStartupError(t);
            return;   // 之后绝不再碰 content view
        }
        BootTrace.mark("MainActivity.onCreate exit");
        postFirstFrame();
    }

    /** 出错时不再闪退：显示一个不依赖 Ui / res 的纯 TextView 错误页。 */
    private void showStartupError(Throwable t) {
        startupFailed = true;
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String[] lines = sw.toString().split("\n");
            StringBuilder sb = new StringBuilder();
            sb.append("DSH 小V 启动失败（已捕获，未闪退）\n\n");
            sb.append(t.getClass().getName());
            sb.append(": ");
            sb.append(t.getMessage());
            sb.append("\n\n");
            for (int i = 0; i < Math.min(20, lines.length); i++) {
                sb.append(lines[i]).append('\n');
            }
            sb.append("\n崩溃报告落点（proot 可读）：\n");
            sb.append("/sdcard/Android/media/com.dshgo.assist/crash-*.txt\n");
            sb.append("或 下载目录 DSH-crash-*.txt\n\n");
            sb.append("boot-trace：/sdcard/Android/media/com.dshgo.assist/boot-trace.txt\n");
            TextView tv = new TextView(this);
            tv.setText(sb.toString());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tv.setTextColor(0xFF202124);
            tv.setBackgroundColor(0xFFFFFFFF);
            tv.setPadding(24, 24, 24, 24);
            tv.setTextIsSelectable(true);
            tv.setTypeface(Typeface.MONOSPACE);
            ScrollView sv = new ScrollView(this);
            sv.setBackgroundColor(0xFFFFFFFF);
            sv.setFillViewport(true);
            sv.addView(tv, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT));
            setContentView(sv);
            BootTrace.mark("showStartupError: 错误页已铺上屏幕");
        } catch (Throwable inner) {
            BootTrace.mark("showStartupError FAIL " + inner);
            try {
                TextView fallback = new TextView(this);
                fallback.setText("DSH 小V 启动失败: " + t);
                fallback.setTextColor(0xFFFF0000);
                fallback.setBackgroundColor(0xFFFFFFFF);
                setContentView(fallback);
                BootTrace.mark("showStartupError: 兜底 TextView 已铺上");
            } catch (Throwable inner2) {
                BootTrace.mark("showStartupError fallback FAIL " + inner2);
            }
        }
    }

    /** 有风险的初始化全部挪到首帧之后，各自 try/catch。 */
    private void postFirstFrame() {
        try {
            getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    runPostStartup();
                }
            });
        } catch (Throwable t) {
            BootTrace.mark("postFirstFrame FAIL " + t);
        }
    }

    private void runPostStartup() {
        BootTrace.mark("postFirstFrame start");
        if (BuildFlags.SHIZUKU) {
            try {
                registerShizukuListeners();
                BootTrace.mark("postFirstFrame: shizuku listeners ok");
            } catch (Throwable t) {
                BootTrace.mark("postFirstFrame: shizuku listeners FAIL " + t);
            }
        } else {
            BootTrace.mark("postFirstFrame: shizuku disabled in this variant");
        }
        try {
            createNotificationChannel();
            BootTrace.mark("postFirstFrame: notification channel ok");
        } catch (Throwable t) {
            BootTrace.mark("postFirstFrame: notification channel FAIL " + t);
        }
        try {
            refreshStatus();
            BootTrace.mark("postFirstFrame: refreshStatus ok");
        } catch (Throwable t) {
            BootTrace.mark("postFirstFrame: refreshStatus FAIL " + t);
        }
        try {
            silentHealOnce();
            BootTrace.mark("postFirstFrame: silentHealOnce ok");
        } catch (Throwable t) {
            BootTrace.mark("postFirstFrame: silentHealOnce FAIL " + t);
        }
        try {
            refreshBridgeStatusAsync();
            refreshHookStatusAsync();
        } catch (Throwable t) {
            BootTrace.mark("postFirstFrame: refreshBridgeStatusAsync FAIL " + t);
        }
        try {
            showOnboardingIfNeeded();
        } catch (Throwable t) {
            BootTrace.mark("onboarding FAIL " + t);
        }
        BootTrace.mark("postFirstFrame done");
    }

    @Override
    protected void onResume() {
        super.onResume();
        BootTrace.mark("MainActivity.onResume");
        if (startupFailed) {
            return;
        }
        try {
            if (pendingWrite() && Settings.System.canWrite(this)) {
                setPendingWrite(false);
                writeVivoPowerSwitch(0);
            }
            refreshStatus();
            refreshBridgeStatusAsync();
            refreshHookStatusAsync();
            refreshSessionInfo();
        } catch (Throwable t) {
            BootTrace.mark("onResume FAIL " + t);
        }
    }

    @Override
    protected void onDestroy() {
        if (BuildFlags.SHIZUKU) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceivedListener);
                Shizuku.removeBinderDeadListener(binderDeadListener);
                Shizuku.removeRequestPermissionResultListener(permissionListener);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        super.onDestroy();
    }

    private void registerShizukuListeners() {
        if (!BuildFlags.SHIZUKU) {
            return;
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionListener);
        } catch (Throwable ignored) {
            // Shizuku 未安装/未运行时保持沉默
        }
    }

    // ------------------------------------------------------- edge to edge ----
    /**
     * 边到边。必须在 setContentView 之后调用（DecorView 已创建），
     * 而且每一步都单独 try/catch + 空判断：任何失败都只写 boot-trace，绝不抛。
     */
    private void applyEdgeToEdge() {
        try {
            Window window = getWindow();
            if (window == null) {
                BootTrace.mark("applyEdgeToEdge: window null, skip");
                return;
            }
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            } catch (Throwable t) {
                BootTrace.mark("applyEdgeToEdge: addFlags warn " + t);
            }
            try {
                window.setStatusBarColor(Color.TRANSPARENT);
            } catch (Throwable t) {
                BootTrace.mark("applyEdgeToEdge: statusBarColor warn " + t);
            }
            try {
                window.setNavigationBarColor(Ui.BG);
            } catch (Throwable t) {
                BootTrace.mark("applyEdgeToEdge: navBarColor warn " + t);
            }

            View decor = window.getDecorView();
            if (decor == null) {
                BootTrace.mark("applyEdgeToEdge: decor null, skip");
                return;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    window.setDecorFitsSystemWindows(false);
                } catch (Throwable t) {
                    BootTrace.mark("applyEdgeToEdge: setDecorFits warn " + t);
                }
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    // 深色界面：状态栏图标保持浅色（不启用 LIGHT_STATUS_BARS）
                    try {
                        controller.setSystemBarsAppearance(0,
                                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    } catch (Throwable t) {
                        BootTrace.mark("applyEdgeToEdge: appearance warn " + t);
                    }
                } else {
                    BootTrace.mark("applyEdgeToEdge: insetsController null, skip");
                }
            } else {
                try {
                    int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                    decor.setSystemUiVisibility(flags);
                } catch (Throwable t) {
                    BootTrace.mark("applyEdgeToEdge: legacy flags warn " + t);
                }
            }
        } catch (Throwable t) {
            BootTrace.mark("applyEdgeToEdge FAIL (ignored) " + t);
        }
    }

    // ------------------------------------------------------------------ UI --
    private View buildUi() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        final LinearLayout content = ui.column(this);
        int pad = ui.dp(20);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        if (Build.VERSION.SDK_INT >= 20) {
            scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    try {
                        if (insets == null) {
                            return null;
                        }
                        int top;
                        int bottom;
                        if (Build.VERSION.SDK_INT >= 30) {
                            android.graphics.Insets bars =
                                    insets.getInsets(WindowInsets.Type.systemBars());
                            top = bars == null ? 0 : bars.top;
                            bottom = bars == null ? 0 : bars.bottom;
                        } else {
                            top = insets.getSystemWindowInsetTop();
                            bottom = insets.getSystemWindowInsetBottom();
                        }
                        content.setPadding(ui.dp(20), ui.dp(20) + top,
                                ui.dp(20), ui.dp(20) + bottom);
                    } catch (Throwable t) {
                        BootTrace.mark("insets listener warn " + t);
                    }
                    return insets;
                }
            });
        }

        content.addView(buildHero());
        content.addView(ui.sectionTitle(this, "状态"));
        content.addView(buildStatusCard());
        content.addView(spacer(28));
        content.addView(buildPrimary());
        content.addView(spacer(12));
        content.addView(buildCleanButton());
        content.addView(spacer(12));
        content.addView(buildAdvanced());
        content.addView(ui.sectionTitle(this, "语音"));
        content.addView(buildVoiceCard());
        content.addView(ui.sectionTitle(this, "实验"));
        content.addView(buildExperimentalCard());
        content.addView(ui.sectionTitle(this, "运行日志"));
        content.addView(buildAboutCard());

        content.addView(buildOutputCard());
        return scroll;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, ui.dp(dp)));
        return v;
    }

    private View buildHero() {
        LinearLayout hero = ui.row(this);
        LinearLayout left = ui.column(this);
        TextView title = ui.text(this, "DSH 小V", 28f, Ui.TEXT, true);
        TextView subtitle = ui.text(this, "系统助手接管", 13f, Ui.SUB, false);
        subtitle.setPadding(0, ui.dp(4), 0, 0);
        left.addView(title);
        left.addView(subtitle);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(left);

        LinearLayout badge = ui.row(this);
        heroDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(ui.dp(6), ui.dp(6));
        dotParams.rightMargin = ui.dp(8);
        heroDot.setLayoutParams(dotParams);
        heroDot.setBackground(ui.circle(Ui.DOT_IDLE));
        heroBadge = ui.text(this, "未接管", 13f, Ui.SUB, false);
        badge.addView(heroDot);
        badge.addView(heroBadge);
        hero.addView(badge);
        return hero;
    }

    private View buildStatusCard() {
        LinearLayout card = ui.cardView(this);
        String[] labels = {"助手角色", "电源键模式", "悬浮窗", "Shizuku", "会话", "通知"};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = ui.statusRow(this, labels[i]);
            statusValues[i] = ui.valueText(this);
            row.addView(statusValues[i]);
            statusDots[i] = ui.dot(this);
            row.addView(statusDots[i]);
            if ("电源键模式".equals(labels[i])) {
                row.setClickable(true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTileHint();
                    }
                });
            }
            card.addView(row);
            if (i < labels.length - 1) {
                card.addView(ui.divider(this));
            }
        }
        return card;
    }

    private View buildPrimary() {
        Button takeover = ui.primaryBar(this, "一键接管");
        takeover.setOnClickListener(haptic(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takeOver();
            }
        }));
        return takeover;
    }

    /** 一键清理后台：杀后台进程 + 清最近任务（需 Shizuku/root 通道）。 */
    private View buildCleanButton() {
        Button clean = ui.primaryBar(this, "一键清理后台");
        clean.setOnClickListener(haptic(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runClean();
            }
        }));
        return clean;
    }

    /** 清理：优先 Shizuku，没有 Shizuku 时用 su 兜底（KernelSU 授权即可）。 */
    private void runClean() {
        final String script = RootOps.clearBackgroundScript();
        if (ShizukuShell.binderReady()) {
            runRoot("一键清理后台", script, 60000, false);
            toast("正在清理后台…");
            return;
        }
        appendOutput("=== 一键清理后台（su 兜底）===");
        toast("正在清理后台…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", script});
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    p.waitFor();
                    out = sb.toString();
                } catch (Throwable t) {
                    out = "[su 失败] " + t + "\n（可先点「一键接管」，再用本按钮）";
                }
                final String res = out;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appendOutput(res);
                        refreshStatus();
                    }
                });
            }
        }, "dsh-clean-su").start();
    }

    private View buildAdvanced() {
        LinearLayout wrap = ui.column(this);
        advancedChevron = ui.text(this, "›", 20f, 0x88FFFFFF, false);
        LinearLayout header = ui.advancedHeader(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = advancedBody.getVisibility() != View.VISIBLE;
                advancedBody.setVisibility(show ? View.VISIBLE : View.GONE);
                advancedChevron.animate().rotation(show ? 90f : 0f).setDuration(160).start();
            }
        }, advancedChevron);
        wrap.addView(header);

        advancedBody = ui.column(this);
        advancedBody.setVisibility(View.GONE);

        // 常用（按使用频率排序）
        addGroupTitle(advancedBody, "常用");
        addRow(advancedBody, "申请助手角色", "RoleManager 默认助手", "role");
        addRow(advancedBody, "连接 Shizuku", "授权并显示服务状态", "connect");
        addPowerModeRow(advancedBody);
        addRow(advancedBody, "新对话", "重置会话上下文", "new-session");
        addRow(advancedBody, "添加快捷磁贴", "下拉通知栏 → 编辑 → 拖动 DSH 小V", "tile-hint");
        addRow(advancedBody, "申请悬浮窗权限", "SYSTEM_ALERT_WINDOW", "overlay-perm");

        // 调试
        addGroupTitle(advancedBody, "调试");
        addRow(advancedBody, "启动 root 桥", "轮询 rootq/*.sh", "bridge-start");
        addRow(advancedBody, "停止 root 桥", "删除 .bridge 标记", "bridge-stop");
        addRow(advancedBody, "重启电源键钩子", "getevent 守护进程", "hook-restart");
        addRow(advancedBody, "关闭·恢复 vivo 开关", "WRITE_SETTINGS", "vivo");
        addRow(advancedBody, "打开 DSH 小V", "ACTION_SEND / launcher", "open");

        LinearLayout hookRow = ui.advancedRow(this, "电源键钩子", hookState,
                haptic(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onAction("hook-restart");
                    }
                }));
        try {
            hookDetail = (TextView) ((LinearLayout) hookRow.getChildAt(0)).getChildAt(1);
        } catch (Throwable ignored) {
            hookDetail = null;
        }
        advancedBody.addView(hookRow);

        LinearLayout asrRow = ui.advancedRow(this, "识别服务",
                asrEndpointForDisplay(), haptic(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showAsrDialog();
                    }
                }));
        asrRow.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showAsrDialog();
                return true;
            }
        });
        advancedBody.addView(asrRow);

        LinearLayout sessionRow = ui.advancedRow(this, "当前会话", sessionDetail(),
                haptic(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startNewSession();
                    }
                }));
        try {
            sessionDetailView = (TextView) ((LinearLayout) sessionRow.getChildAt(0)).getChildAt(1);
        } catch (Throwable ignored) {
            sessionDetailView = null;
        }
        advancedBody.addView(sessionRow);

        wrap.addView(advancedBody);
        return wrap;
    }

    private void addGroupTitle(LinearLayout parent, String title) {
        TextView t = ui.text(this, title, 12f, Ui.SUB, true);
        t.setPadding(ui.dp(4), ui.dp(14), ui.dp(4), ui.dp(4));
        parent.addView(t);
    }

    private void addRow(LinearLayout parent, String label, String detail, final String action) {
        LinearLayout row = ui.advancedRow(this, label, detail,
                haptic(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onAction(action);
                    }
                }));
        parent.addView(row);
    }

    /** v18：电源键模式行（系统助手 / root 钩子），点击切换。 */
    private void addPowerModeRow(LinearLayout parent) {
        LinearLayout row = ui.advancedRow(this, "电源键模式", powerModeDetail(),
                haptic(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        switchPowerMode();
                    }
                }));
        try {
            powerModeDetailView = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        } catch (Throwable ignored) {
            powerModeDetailView = null;
        }
        parent.addView(row);
    }

    private View buildVoiceCard() {
        LinearLayout card = ui.cardView(this);
        voiceSwitch = new Switch(this);
        voiceSwitch.setText("长按唤起后直接说话");
        voiceSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        voiceSwitch.setTextColor(Ui.TEXT);
        voiceSwitch.setChecked(isVoiceEnabled());
        voiceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setVoiceEnabled(isChecked);
            }
        });
        card.addView(voiceSwitch);
        TextView l1 = ui.text(this, "打开后：唤起时显示顶部悬浮胶囊并听一句话，"
                + "识别结果用 ACTION_SEND 发给 DSH 小V。", 12f, Ui.SUB, false);
        l1.setPadding(0, ui.dp(8), 0, 0);
        TextView l2 = ui.text(this, "关闭后：胶囊只播一小段动画，然后直接打开 DSH 小V。",
                12f, Ui.SUB, false);
        l2.setPadding(0, ui.dp(4), 0, 0);
        card.addView(l1);
        card.addView(l2);

        // v34：回复后自动朗读
        Switch speak = new Switch(this);
        speak.setText("回复后自动朗读");
        speak.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        speak.setTextColor(Ui.TEXT);
        speak.setChecked(prefs().getBoolean(RootOps.prefKeyAutoSpeak(), false));
        speak.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs().edit().putBoolean(RootOps.prefKeyAutoSpeak(), isChecked).apply();
                toast(isChecked ? "已开启：回复后自动朗读" : "已关闭自动朗读");
            }
        });
        LinearLayout.LayoutParams speakLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        speakLp.topMargin = ui.dp(10);
        card.addView(speak, speakLp);
        TextView l3 = ui.text(this, "用系统语音引擎朗读 DSH 的回复（超长只读前 400 字）；"
                + "点一下卡片可打断，媒体音量为 0 时不朗读。", 12f, Ui.SUB, false);
        l3.setPadding(0, ui.dp(4), 0, 0);
        card.addView(l3);

        // v37：先问本地小模型（千问3-0.6B，跑在本机 llama.cpp 上）
        Switch local = new Switch(this);
        local.setText("先问本地模型（千问 0.6B）");
        local.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        local.setTextColor(Ui.TEXT);
        local.setChecked(prefs().getBoolean(RootOps.prefKeyLocalFirst(), true));
        local.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs().edit().putBoolean(RootOps.prefKeyLocalFirst(), isChecked).apply();
                toast(isChecked ? "已开启：先问本地模型，答不了再转 DSH"
                        : "已关闭：全部交给 DSH");
            }
        });
        LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        localLp.topMargin = ui.dp(10);
        card.addView(local, localLp);
        TextView l4 = ui.text(this, "本地模型只答简单问题（约 1~3 秒，离线可用）；"
                + "要操作手机、查实时信息的一律自动转 DSH。", 12f, Ui.SUB, false);
        l4.setPadding(0, ui.dp(4), 0, 0);
        card.addView(l4);
        return card;
    }

    private View buildExperimentalCard() {
        LinearLayout card = ui.cardView(this);
        Switch wake = new Switch(this);
        wake.setText("唤醒截胡（实验）");
        wake.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        wake.setTextColor(Ui.TEXT);
        wake.setChecked(isWakeInterceptEnabled());
        wake.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setWakeInterceptEnabled(isChecked);
                toggleWakeIntercept(isChecked);
            }
        });
        card.addView(wake);
        TextView desc = ui.text(this, "root 起 logcat 管道匹配 voicewakeup / hotword / jovi / "
                + "wakeup / copilot，命中就打开悬浮胶囊。默认关，实验功能。", 12f, Ui.SUB, false);
        desc.setPadding(0, ui.dp(8), 0, 0);
        card.addView(desc);

        if (dotSwitch == null) {
            dotSwitch = new Switch(this);
        }
        dotSwitch.setText("常驻小圆点（收回后保留入口）");
        dotSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        dotSwitch.setTextColor(Ui.TEXT);
        dotSwitch.setPadding(0, ui.dp(14), 0, 0);
        dotSwitch.setChecked(isOverlayDotEnabled());
        dotSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setOverlayDotEnabled(isChecked);
            }
        });
        card.addView(dotSwitch);
        TextView dotDesc = ui.text(this, "开启后胶囊收回会在顶部留一个 8dp 半透明小圆点，"
                + "点它重新展开聆听；长按圆点彻底移除。", 12f, Ui.SUB, false);
        dotDesc.setPadding(0, ui.dp(6), 0, 0);
        card.addView(dotDesc);

        Switch replySwitch = new Switch(this);
        replySwitch.setText("结果用 DSH 打开（关闭=悬浮小窗显示）");
        replySwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        replySwitch.setTextColor(Ui.TEXT);
        replySwitch.setPadding(0, ui.dp(14), 0, 0);
        replySwitch.setChecked(prefs().getBoolean("reply_via_dsh", false));
        replySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs().edit().putBoolean("reply_via_dsh", isChecked).apply();
                toast(isChecked ? "回复将用 DSH 打开（旧行为）" : "回复显示在悬浮小窗（默认）");
            }
        });
        card.addView(replySwitch);

        TextView replyDesc = ui.text(this, "默认：识别结果写到 dsh-inbox，DSH 处理完从 dsh-outbox 取回复，"
                + "在同一个悬浮小窗里显示 8 秒，全程不启动任何界面。", 12f, Ui.SUB, false);
        replyDesc.setPadding(0, ui.dp(6), 0, 0);
        card.addView(replyDesc);
        return card;
    }

    private Switch dotSwitch;

    /** v22：关于（版本 / 引擎地址 / 当前会话 / 电源键模式）。 */
    private View buildAboutCard() {
        LinearLayout card = ui.cardView(this);
        card.addView(ui.sectionTitle(this, "关于"));
        String version = "v22 final";
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (name != null && name.length() > 0) {
                version = "v" + name;
            }
        } catch (Throwable ignored) {
            // 用默认
        }
        addAboutRow(card, "版本", version);
        addAboutRow(card, "识别引擎", asrEndpointForDisplay());
        addAboutRow(card, "当前会话", sessionDetail());
        addAboutRow(card, "电源键模式", isRootPowerMode() ? "root 钩子" : "系统助手（免 root）");
        return card;
    }

    private void addAboutRow(LinearLayout card, String label, String value) {
        LinearLayout row = ui.statusRow(this, label);
        TextView v = ui.valueText(this);
        v.setText(value);
        row.addView(v);
        card.addView(row);
    }

    /** v27：免说话的回复卡片自检（不写 inbox、不轮询）。 */
    private void testReplyCard() {
        try {
            Intent intent = new Intent(this, OverlayService.class);
            intent.setAction(OverlayService.ACTION_TEST_REPLY);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            toast("已发送测试回复卡片");
        } catch (Throwable t) {
            toast("测试失败：" + t);
        }
    }

    /** v22：快捷磁贴使用说明（无 root 时的主要入口）。 */
    private void showTileHint() {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("免 root 唤起方式")
                    .setMessage("1. 快捷磁贴：下拉通知栏 → 点「编辑/添加图块」→ 把「DSH 小V」拖出来，"
                            + "之后点一下就能唤起。\n\n"
                            + "2. 悬浮小圆点：设置里打开「常驻小圆点」，点它也能唤起。\n\n"
                            + "3. 长按电源键：需要 root / Shizuku 写一次系统设置。")
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable t) {
            toast("下拉通知栏 → 编辑 → 拖动 DSH 小V 磁贴");
        }
    }

    private View buildOutputCard() {
        LinearLayout card = ui.cardView(this);
        final ScrollView outScroll = new ScrollView(this);
        outScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(180)));
        outputView = ui.text(this, "（还没有执行过命令）", 12f, Ui.SUB, false);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextIsSelectable(true);
        outScroll.addView(outputView);
        card.addView(outScroll);
        return card;
    }

    private View.OnClickListener haptic(final View.OnClickListener inner) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                inner.onClick(v);
            }
        };
    }

    // ------------------------------------------------------------ status ----
    private void refreshStatus() {
        boolean roleHeld = AssistantRole.isRoleHeld(this);
        boolean roleAvailable = AssistantRole.isAvailable(this);
        int vivo = readSystemInt(RootOps.KEY_VIVO_POWER);
        int longPress = readGlobalInt(RootOps.KEY_LONG_PRESS);
        boolean shizuku = ShizukuShell.binderReady();
        boolean authorized = ShizukuShell.hasPermission();
        boolean bridge = Boolean.TRUE.equals(bridgeRunning);
        boolean notify = hasNotificationPermission();
        boolean takeover = roleHeld && authorized;

        boolean overlay = Settings.canDrawOverlays(this);

        boolean rootMode = isRootPowerMode();
        boolean modeOk = rootMode ? longPress == 0 : longPress == 5;
        String modeText = rootMode ? "root 钩子（需 root）" : "系统助手（免 root）";
        if (longPress < 0) {
            modeText = modeText + " · 读不到设置";
        } else if (!modeOk) {
            modeText = modeText + " · 被重置为 " + longPress;
        }
        setStatus(STATUS_ROLE, roleHeld ? "已是默认助手" : (roleAvailable ? "未授予" : "不可用"),
                roleHeld ? Ui.DOT_OK : (roleAvailable ? Ui.DOT_WARN : Ui.DOT_BAD));
        boolean rootReady = shizuku && authorized;
        if (!rootReady) {
            // v22：无 root 时明确降级文案，引导用快捷磁贴/小圆点
            modeText = "系统助手（无 root，建议用快捷磁贴/圆点）";
            setStatus(STATUS_POWER_MODE, modeText, Ui.DOT_WARN);
        } else {
            setStatus(STATUS_POWER_MODE, modeText,
                    modeOk ? Ui.DOT_OK : (longPress < 0 ? Ui.DOT_IDLE : Ui.DOT_WARN));
        }
        setStatus(STATUS_OVERLAY, overlay ? "已授权" : "未授权",
                overlay ? Ui.DOT_OK : Ui.DOT_WARN);
        setStatus(STATUS_SHIZUKU,
                !shizuku ? "未连接" : (authorized ? "已授权 · " + ShizukuShell.serverMode()
                        + " · api " + ShizukuShell.serverApiVersion() : "待授权"),
                !shizuku ? Ui.DOT_BAD : (authorized ? Ui.DOT_OK : Ui.DOT_WARN));
        String session = readSessionId();
        String sessionText = session == null || session.length() == 0
                ? "新会话" : "…" + (session.length() > 8 ? session.substring(0, 8) : session);
        setStatus(STATUS_SESSION, sessionText, Ui.DOT_IDLE);
        setStatus(STATUS_NOTIFICATION, notify ? "已允许" : "未允许",
                notify ? Ui.DOT_OK : Ui.DOT_WARN);

        if (hookDetail != null) {
            hookDetail.setText(hookState);
        }
        if (powerModeDetailView != null) {
            powerModeDetailView.setText(powerModeDetail());
        }
        if (sessionDetailView != null) {
            sessionDetailView.setText(sessionDetail());
        }
        if (heroBadge != null) {
            heroBadge.setText(takeover ? "已接管" : "未接管");
            heroBadge.setTextColor(takeover ? Ui.TEXT : Ui.SUB);
        }
        if (heroDot != null) {
            heroDot.setBackground(ui.circle(takeover ? Ui.DOT_OK : Ui.DOT_IDLE));
        }
    }

    private String vivoText(int vivo) {
        if (vivo == 0) {
            return "已关闭";
        }
        if (vivo == 1) {
            return "开启（vivo 小V仍生效）";
        }
        return "读不到";
    }

    private void setStatus(int index, String value, int dotColor) {
        if (statusValues[index] != null) {
            statusValues[index].setText(value);
            statusValues[index].setTextColor(Ui.TEXT);
        }
        if (statusDots[index] != null) {
            statusDots[index].setBackground(ui.circle(dotColor));
        }
    }

    private int readSystemInt(String key) {
        try {
            return Settings.System.getInt(getContentResolver(), key, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int readGlobalInt(String key) {
        try {
            return Settings.Global.getInt(getContentResolver(), key, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        try {
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ----------------------------------------------------------- actions ----
    private boolean requiresShizuku(String action) {
        return "connect".equals(action) || "bridge-start".equals(action)
                || "bridge-stop".equals(action) || "install".equals(action)
                || "diagnostics".equals(action) || "hook-restart".equals(action);
    }

    private void onAction(String action) {
        if (!BuildFlags.SHIZUKU && requiresShizuku(action)) {
            BootTrace.mark("action " + action + " disabled (v6a)");
            toast("本版本已禁用 root / Shizuku 功能（v6a 排查包）");
            return;
        }
        if ("connect".equals(action)) {
            requestShizukuPermission();
        } else if ("bridge-start".equals(action)) {
            runRoot("启动 root 桥", RootOps.startBridgeScript(), 30000, true);
        } else if ("bridge-stop".equals(action)) {
            runRoot("停止 root 桥", RootOps.stopBridgeScript(), 15000, false);
        } else if ("install".equals(action)) {
            runRoot("静默安装", RootOps.silentInstallScript(), 120000, false);
        } else if ("island".equals(action)) {
            testAtomicIsland();
        } else if ("animation".equals(action)) {
            try {
                Intent anim = new Intent(this, ListenActivity.class);
                anim.putExtra(ListenActivity.EXTRA_ANIM_ONLY, true);
                startActivity(anim);
            } catch (Throwable t) {
                toast("打不开动画页：" + t);
            }
        } else if ("open".equals(action)) {
            if (!Dispatcher.openDsh(this)) {
                toast("没找到 DSH 小V，请先安装");
            }
        } else if ("diagnostics".equals(action)) {
            runRoot("诊断（读）", RootOps.diagnosticsScript(), 30000, false);
        } else if ("role".equals(action)) {
            requestRole();
        } else if ("hook-restart".equals(action)) {
            runRoot("重启电源键钩子", RootOps.installPowerHookScript(), 30000, false);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshHookStatusAsync();
                }
            }, 1500);
        } else if ("reply-card-test".equals(action)) {
            testReplyCard();
        } else if ("tile-hint".equals(action)) {
            showTileHint();
        } else if ("new-session".equals(action)) {
            startNewSession();
        } else if ("asr-edit".equals(action)) {
            showAsrDialog();
        } else if ("overlay-perm".equals(action)) {
            if (Settings.canDrawOverlays(this)) {
                toast("已授予悬浮窗权限");
            } else {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    toast("打不开悬浮窗权限页：" + t);
                }
            }
        } else if ("overlay-test".equals(action)) {
            if (!Settings.canDrawOverlays(this)) {
                toast("先授予悬浮窗权限");
                return;
            }
            try {
                Intent svc = new Intent(this, OverlayService.class)
                        .setAction(OverlayService.ACTION_START)
                        .putExtra(OverlayService.EXTRA_ANIM_ONLY, true)
                        .putExtra(OverlayService.EXTRA_VOICE, false);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } catch (Throwable t) {
                toast("启动胶囊失败：" + t);
            }
        } else if ("vivo".equals(action)) {
            toggleVivoPowerSwitch();
        }
    }

    private void takeOver() {
        BootTrace.mark("takeOver enter");
        if (!BuildFlags.SHIZUKU || !ShizukuShell.binderReady()) {
            // v22：无 root —— 只做权限自检，并明确告知电源键接管需要 root
            boolean overlay = Settings.canDrawOverlays(this);
            boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            appendOutput("=== 免 root 模式自检 ===");
            appendOutput("悬浮窗权限：" + (overlay ? "已授权" : "未授权"));
            appendOutput("麦克风权限：" + (mic ? "已授权" : "未授权"));
            if (!overlay) {
                toast("先给「悬浮窗」权限；电源键接管需要 root");
                try {
                    startActivityForResult(new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())), REQ_WRITE_SETTINGS);
                } catch (Throwable t) {
                    toast("打不开授权页：" + t);
                }
            } else if (!mic) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } else {
                toast("免 root 已就绪：用快捷磁贴或悬浮小圆点唤起（电源键接管需要 root）");
                showTileHint();
            }
            return;
        }
        if (!ShizukuShell.hasPermission()) {
            ShizukuShell.requestPermission(ShizukuShell.REQ_PERMISSION);
            toast("已弹出 Shizuku 授权，允许后再点一次「一键接管」");
            return;
        }
        final boolean rootMode = isRootPowerMode();
        appendOutput("=== 一键接管（" + (rootMode ? "root 钩子" : "系统助手") + "）===");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = ShizukuShell.run(RootOps.powerModeScript(powerMode()), 40000);
                final String bridge = ShizukuShell.run(RootOps.startBridgeScript(), 30000);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appendOutput(result);
                        appendOutput(bridge);
                        refreshBridgeStatusAsync();
                        refreshHookStatusAsync();
                        refreshStatus();
                        toast("接管完成（" + (rootMode ? "root 钩子" : "系统助手") + "）");
                    }
                });
            }
        }, "dsh-takeover").start();
    }

    private void runRoot(final String label, final String script, final long timeoutMs,
                         final boolean markBridge) {
        appendOutput("=== " + label + " ===");
        if (!ShizukuShell.binderReady()) {
            appendOutput("[Shizuku 未连接]");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = ShizukuShell.run(script, timeoutMs);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appendOutput(result);
                        if (markBridge) {
                            refreshBridgeStatusAsync();
                        }
                        refreshStatus();
                    }
                });
            }
        }, "dsh-root").start();
    }

    private void appendOutput(String text) {
        if (text == null) {
            return;
        }
        for (String line : text.split("\n", -1)) {
            outputLines.addLast(line);
        }
        while (outputLines.size() > 200) {
            outputLines.removeFirst();
        }
        StringBuilder sb = new StringBuilder();
        for (String line : outputLines) {
            sb.append(line).append('\n');
        }
        if (outputView != null) {
            outputView.setText(sb.toString());
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------- role / vivo ----
    private void requestRole() {
        if (!AssistantRole.supported()) {
            toast("Android < 10，打开默认应用设置");
            AssistantRole.openDefaultAppsSettings(this);
            return;
        }
        if (!AssistantRole.isAvailable(this)) {
            toast("系统没有可申请的助手角色");
            return;
        }
        if (AssistantRole.isRoleHeld(this)) {
            toast("DSH 小V 已经是默认助手");
            return;
        }
        Intent request = AssistantRole.requestIntent(this);
        if (request == null) {
            toast("申请助手角色的入口不可用");
            return;
        }
        try {
            startActivityForResult(request, REQ_ROLE);
        } catch (Throwable t) {
            toast("弹不出授权对话框：" + t);
        }
    }

    private void toggleVivoPowerSwitch() {
        int current = readSystemInt(RootOps.KEY_VIVO_POWER);
        int target = (current == 0) ? 1 : 0;
        if (!Settings.System.canWrite(this)) {
            setPendingWrite(true);
            toast("先授予「修改系统设置」，回来会自动写入");
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())), REQ_WRITE_SETTINGS);
            } catch (Throwable t) {
                toast("打不开授权页：" + t);
            }
            return;
        }
        writeVivoPowerSwitch(target);
    }

    private void writeVivoPowerSwitch(int value) {
        boolean ok;
        String detail;
        try {
            ok = Settings.System.putInt(getContentResolver(), RootOps.KEY_VIVO_POWER, value);
            detail = ok ? "成功" : "putInt 返回 false（被系统拒绝）";
        } catch (Throwable t) {
            ok = false;
            detail = "异常：" + t;
        }
        toast((value == 0 ? "已关闭" : "已恢复") + " vivo 电源键小V：" + detail);
        refreshStatus();
    }

    // ------------------------------------------------------------- Shizuku --
    private void requestShizukuPermission() {
        if (!ShizukuShell.binderReady()) {
            toast("Shizuku 未连接：先确认 Shizuku App 正在运行");
            return;
        }
        if (ShizukuShell.hasPermission()) {
            toast("已经拿到 Shizuku 授权");
            return;
        }
        ShizukuShell.requestPermission(ShizukuShell.REQ_PERMISSION);
        toast("已发送请求，请在 Shizuku 弹窗点允许");
    }

    // --------------------------------------------------------- silent heal --
    private void silentHealOnce() {
        if (!BuildFlags.SHIZUKU) {
            return;
        }
        BootTrace.mark("silentHealOnce enter");
        long now = System.currentTimeMillis();
        if (now - lastSilentHealAt < 60000) {
            return;
        }
        lastSilentHealAt = now;
        if (!RootOps.rootReady()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean rootMode = isRootPowerMode();
                final String result = ShizukuShell.run(RootOps.powerModeScript(powerMode()), 30000);
                final String bridge = ShizukuShell.run(RootOps.startBridgeScript(), 20000);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null && result.contains("[exit=0]")) {
                            setHealAppliedNow();
                        }
                        if (bridge != null && bridge.contains("root bridge started")) {
                            refreshBridgeStatusAsync();
                        }
                        refreshStatus();
                    }
                });
            }
        }, "dsh-heal").start();
    }

    private void setHealAppliedNow() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(RootOps.prefKeyHealApplied(), System.currentTimeMillis()).apply();
    }

    // ---------------------------------------------------------- island ------
    /** 通知渠道创建（首帧之后单独 try/catch 调用）。 */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("DSH 小V 实时动态 / 原子岛实验");
        nm.createNotificationChannel(channel);
    }

    private void testAtomicIsland() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATIONS);
                toast("给了通知权限后再点一次");
            } catch (Throwable t) {
                toast("申请通知权限失败：" + t);
            }
            return;
        }
        postIslandNotification();
    }

    private void postIslandNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) {
                toast("拿不到 NotificationManager");
                return;
            }
            createNotificationChannel();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), flags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("DSH 正在执行")
                    .setContentText("原子岛 / 实时动态测试")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setColorized(false)
                    .setContentIntent(contentIntent);

            String shortCritical = "未启用(需 API 36)";
            boolean requestedPromotion = false;
            if (Build.VERSION.SDK_INT >= 36) {
                try {
                    builder.setShortCriticalText("DSH 正在执行");
                    shortCritical = "DSH 正在执行";
                } catch (Throwable t) {
                    shortCritical = "setShortCriticalText 失败：" + t;
                }
                requestedPromotion = requestPromotedOngoing(builder);
            }
            Notification notification = builder.build();
            boolean promotable = false;
            if (Build.VERSION.SDK_INT >= 36) {
                try {
                    promotable = notification.hasPromotableCharacteristics();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            nm.notify(NOTIF_ID, notification);

            boolean promoted = false;
            try {
                StatusBarNotification[] active = nm.getActiveNotifications();
                if (active != null) {
                    for (StatusBarNotification sbn : active) {
                        if (sbn.getId() == NOTIF_ID) {
                            promoted = (sbn.getNotification().flags
                                    & Notification.FLAG_PROMOTED_ONGOING) != 0;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
            boolean canPromote = Build.VERSION.SDK_INT >= 36
                    && nm.canPostPromotedNotifications();
            lastIslandResult = "promoted=" + promoted + " promotable=" + promotable
                    + " canPost=" + canPromote + " 请求提升=" + requestedPromotion
                    + " 短文本=" + shortCritical;
            appendOutput("=== 原子岛 ===\n" + lastIslandResult);
            toast("通知已发出");
        } catch (Throwable t) {
            lastIslandResult = "发送失败：" + t;
            appendOutput("=== 原子岛 ===\n" + lastIslandResult);
            toast(lastIslandResult);
        }
        refreshStatus();
    }

    private boolean requestPromotedOngoing(Notification.Builder builder) {
        try {
            Notification.Builder.class
                    .getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(builder, Boolean.TRUE);
            return true;
        } catch (Throwable ignored) {
            // 落到 extra 方案
        }
        try {
            Bundle extras = builder.getExtras();
            if (extras == null) {
                extras = new Bundle();
                builder.addExtras(extras);
            }
            extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------ wake intercept --
    private void toggleWakeIntercept(boolean enabled) {
        if (!BuildFlags.SHIZUKU) {
            setWakeInterceptEnabled(false);
            toast("本版本已禁用唤醒截胡（v6a 排查包）");
            refreshStatus();
            return;
        }
        if (!RootOps.rootReady()) {
            toast("需要先连接并授权 Shizuku");
            return;
        }
        if (enabled) {
            appendOutput("=== 唤醒截胡（实验）===");
            toast("实验功能：用 root 起 logcat 管道截胡唤醒");
            runRoot("唤醒截胡", RootOps.startWakeInterceptScript(), 30000, false);
        } else {
            runRoot("停止唤醒截胡", RootOps.stopWakeInterceptScript(), 20000, false);
        }
    }

    // ------------------------------------------------------------- prefs ----
    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isVoiceEnabled() {
        return prefs().getBoolean(KEY_VOICE, true);
    }

    private void setVoiceEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_VOICE, enabled).apply();
    }

    /** v18：首次启动的一页极简引导（只显示一次）。 */
    private void showOnboardingIfNeeded() {
        try {
            if (prefs().getBoolean("onboarding_done", false)) {
                return;
            }
            String message = "使用前 3 步：\n\n"
                    + "1. 允许「悬浮窗」权限（否则会退化为全屏界面）\n"
                    + "2. 允许「麦克风」权限（否则没法听你说话）\n"
                    + "3. 点「一键接管」，之后长按电源键就能唤起\n\n"
                    + "默认走「系统助手」模式，不需要 root；"
                    + "想更快可在高级区切到「root 钩子」。";
            new AlertDialog.Builder(this)
                    .setTitle("DSH 小V · 欢迎")
                    .setMessage(message)
                    .setPositiveButton("开始使用", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            prefs().edit().putBoolean("onboarding_done", true).apply();
                        }
                    })
                    .setCancelable(false)
                    .show();
            BootTrace.mark("onboarding shown");
        } catch (Throwable t) {
            BootTrace.mark("onboarding show FAIL " + t);
        }
    }

    private boolean isOverlayDotEnabled() {
        return prefs().getBoolean(RootOps.prefKeyOverlayDot(), true);
    }

    private void setOverlayDotEnabled(boolean enabled) {
        prefs().edit().putBoolean(RootOps.prefKeyOverlayDot(), enabled).apply();
    }

    private boolean isWakeInterceptEnabled() {
        return prefs().getBoolean(RootOps.prefKeyWakeIntercept(), false);
    }

    private void setWakeInterceptEnabled(boolean enabled) {
        prefs().edit().putBoolean(RootOps.prefKeyWakeIntercept(), enabled).apply();
    }

    private static final String ASR_MEDIA_DIR = "/sdcard/Android/media/com.dshgo.assist";

    // ---------------- v18/v33：电源键模式（默认 stock = 完全原生） ----------------
    private String powerMode() {
        return prefs().getString(RootOps.PREF_POWER_MODE, RootOps.MODE_STOCK);
    }

    private boolean isRootPowerMode() {
        return RootOps.MODE_ROOT.equals(powerMode());
    }

    private void setRootPowerMode(boolean rootMode) {
        prefs().edit().putString(RootOps.PREF_POWER_MODE,
                rootMode ? RootOps.MODE_ROOT : RootOps.MODE_STOCK).apply();
    }

    private String powerModeDetail() {
        if (RootOps.MODE_ROOT.equals(powerMode())) {
            return "root 钩子 · 双击电源键唤起 · 点切换";
        }
        return "原生模式 · 短按/长按完全正常 · 点切换为双击唤起";
    }

    private void refreshPowerModeRow() {
        if (powerModeDetailView != null) {
            powerModeDetailView.setText(powerModeDetail());
        }
    }

    /** 切换电源键模式：写设置 + 启停钩子（需要 Shizuku/root，一次即可，重启不丢）。 */
    private void switchPowerMode() {
        boolean targetRoot = !isRootPowerMode();
        if (!RootOps.rootReady()) {
            toast("切换模式需要先连接并授权 Shizuku（写入一次，之后重启不丢）");
            return;
        }
        setRootPowerMode(targetRoot);
        refreshPowerModeRow();
        toast(targetRoot ? "已切换到 root 钩子模式（双击电源键唤起）" : "已切换到原生模式（短按/长按完全正常）");
        runRoot("切换电源键模式", RootOps.powerModeScript(powerMode()), 40000, false);
        refreshStatus();
    }

    /** 读 session-info.txt（先找 media 根目录，再找 dsh-outbox/），取 sid=… */
    private String readSessionId() {
        String[] candidates = {
                ASR_MEDIA_DIR + "/session-info.txt",
                ASR_MEDIA_DIR + "/dsh-outbox/session-info.txt",
        };
        for (String path : candidates) {
            try {
                java.io.File file = new java.io.File(path);
                if (!file.exists()) {
                    continue;
                }
                byte[] buf = new byte[(int) Math.min(file.length(), 4096)];
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                int n;
                try {
                    n = in.read(buf);
                } finally {
                    in.close();
                }
                if (n <= 0) {
                    continue;
                }
                String content = new String(buf, 0, n, "UTF-8");
                int idx = content.lastIndexOf("sid=");
                if (idx >= 0) {
                    String rest = content.substring(idx + 4).trim();
                    int end = rest.length();
                    for (int i = 0; i < rest.length(); i++) {
                        char c = rest.charAt(i);
                        if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                            end = i;
                            break;
                        }
                    }
                    if (end > 0) {
                        return rest.substring(0, end);
                    }
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    private String sessionDetail() {
        try {
            String sid = readSessionId();
            if (sid == null || sid.length() == 0) {
                return "当前会话：未知（还没有回答过）";
            }
            String shortId = sid.length() > 8 ? sid.substring(0, 8) : sid;
            return "当前会话：" + shortId + " / 点我开始新对话";
        } catch (Throwable t) {
            return "当前会话：未知";
        }
    }

    private void refreshSessionInfo() {
        if (sessionDetailView != null) {
            sessionDetailView.setText(sessionDetail());
        }
    }

    /** v17：写 dsh-inbox/reset-<ts>.txt，让 worker 重置会话。 */
    private void startNewSession() {
        try {
            java.io.File dir = new java.io.File(ASR_MEDIA_DIR + "/dsh-inbox");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            long ts = System.currentTimeMillis();
            java.io.File file = new java.io.File(dir, "reset-" + ts + ".txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
            try {
                fos.write("新对话".getBytes("UTF-8"));
                fos.flush();
            } finally {
                fos.close();
            }
            BootTrace.mark("new session requested: " + file.getAbsolutePath());
            toast("已请求新对话");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshSessionInfo();
                }
            }, 1500);
        } catch (Throwable t) {
            toast("新对话失败：" + t);
        }
    }

    private String asrEndpointForDisplay() {
        String endpoint = prefs().getString(RootOps.prefKeyAsrEndpoint(),
                RootOps.DEFAULT_ASR_ENDPOINT);
        return endpoint;
    }

    /** v13：长按「识别服务」行 → 程序化 EditText 弹窗编辑 endpoint / token。 */
    private void showAsrDialog() {
        try {
            LinearLayout box = ui.column(this);
            int pad = ui.dp(16);
            box.setPadding(pad, ui.dp(8), pad, 0);

            final EditText endpointInput = new EditText(this);
            endpointInput.setHint("endpoint");
            endpointInput.setText(prefs().getString(RootOps.prefKeyAsrEndpoint(),
                    RootOps.DEFAULT_ASR_ENDPOINT));
            endpointInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
            endpointInput.setTextSize(13f);
            box.addView(endpointInput);

            final EditText tokenInput = new EditText(this);
            tokenInput.setHint("x-dsh-token");
            tokenInput.setText(prefs().getString(RootOps.prefKeyAsrToken(),
                    RootOps.DEFAULT_ASR_TOKEN));
            tokenInput.setInputType(InputType.TYPE_CLASS_TEXT);
            tokenInput.setTextSize(13f);
            box.addView(tokenInput);

            new AlertDialog.Builder(this)
                    .setTitle("识别服务设置")
                    .setView(box)
                    .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            String endpoint = endpointInput.getText().toString().trim();
                            String token = tokenInput.getText().toString().trim();
                            if (endpoint.length() == 0) {
                                endpoint = RootOps.DEFAULT_ASR_ENDPOINT;
                            }
                            if (token.length() == 0) {
                                token = RootOps.DEFAULT_ASR_TOKEN;
                            }
                            prefs().edit()
                                    .putString(RootOps.prefKeyAsrEndpoint(), endpoint)
                                    .putString(RootOps.prefKeyAsrToken(), token)
                                    .apply();
                            toast("已保存识别服务设置");
                            BootTrace.mark("asr settings saved: " + endpoint);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            toast("打开设置失败：" + t);
        }
    }

    /** v11：电源键钩子状态（读 bin/dsh-powerhook.pid 并 kill -0）。 */
    private void refreshHookStatusAsync() {
        if (!BuildFlags.SHIZUKU || hookChecking) {
            return;
        }
        if (!ShizukuShell.binderReady() || !ShizukuShell.hasPermission()) {
            hookState = "未连接";
            refreshStatus();
            return;
        }
        hookChecking = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = ShizukuShell.run(RootOps.hookStatusScript(), 15000);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        hookChecking = false;
                        boolean on = out != null && out.contains("HOOK=ON");
                        hookState = on ? "运行中" : "未启动";
                        refreshStatus();
                    }
                });
            }
        }, "dsh-hook-status").start();
    }

    /** v9：桥状态以 rootq/.bridge 标记为准（由 root shell 代读，避免内存 flag 骗人）。 */
    private void refreshBridgeStatusAsync() {
        if (!BuildFlags.SHIZUKU) {
            bridgeRunning = null;
            return;
        }
        if (bridgeChecking) {
            return;
        }
        if (!ShizukuShell.binderReady() || !ShizukuShell.hasPermission()) {
            bridgeRunning = null;
            refreshStatus();
            return;
        }
        bridgeChecking = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = ShizukuShell.run(RootOps.bridgeStatusScript(), 15000);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        bridgeChecking = false;
                        bridgeRunning = out != null && out.contains("BRIDGE=ON");
                        refreshStatus();
                    }
                });
            }
        }, "dsh-bridge-status").start();
    }

    private boolean pendingWrite() {
        return prefs().getBoolean(KEY_PENDING_WRITE, false);
    }

    private void setPendingWrite(boolean pending) {
        prefs().edit().putBoolean(KEY_PENDING_WRITE, pending).apply();
    }

    // ------------------------------------------------- callbacks / util ----
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(granted ? "已授予通知权限" : "没有通知权限");
            refreshStatus();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROLE) {
            toast("角色申请回调 resultCode=" + resultCode
                    + (resultCode == RESULT_OK ? "（已授予）" : "（未授予）"));
            refreshStatus();
        } else if (requestCode == REQ_WRITE_SETTINGS) {
            boolean canWrite = Settings.System.canWrite(this);
            toast("修改系统设置权限：" + canWrite);
            if (canWrite && pendingWrite()) {
                setPendingWrite(false);
                writeVivoPowerSwitch(0);
            }
            refreshStatus();
        }
    }
}