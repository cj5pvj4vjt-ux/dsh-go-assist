package com.dshgo.assist;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * v6 崩溃取证：全局 UncaughtExceptionHandler。
 *
 * 落点（三个都试，互不影响）：
 *   1. `/sdcard/Android/media/com.dshgo.assist/crash-<ts>.txt`
 *      —— 应用自己的媒体目录，不需要任何存储权限，Lead 在 proot 里读得到；
 *   2. MediaStore Downloads → `DSH-crash-<ts>.txt`（API 29+，IS_PENDING 流程，
 *      同样不需要存储权限）；
 *   3. `/sdcard/Download/DSH-Go/crash-<ts>.txt`（可能被 scoped storage 拒绝）。
 *
 * 报告内容：设备信息 + 异常堆栈 + 最后 25 条面包屑 + boot-trace 全文快照。
 * 写完 sleep 1500ms 再交给默认处理器（保证落盘后才退出进程）。
 */
final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "DshCrash";
    private static final String DIR = "/sdcard/Android/media/com.dshgo.assist";
    private static final String FALLBACK_DIR = "/sdcard/Download/DSH-Go";

    private static boolean installed;

    private final Application app;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Application app, Thread.UncaughtExceptionHandler previous) {
        this.app = app;
        this.previous = previous;
    }

    /** 幂等安装；越早越好（DshApp.attachBaseContext 里就装，provider 阶段的崩溃也能抓到）。 */
    static void install(Application app) {
        if (installed || app == null) {
            return;
        }
        installed = true;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(app, previous));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String written = "";
        try {
            BootTrace.mark("CRASH " + throwable.getClass().getName());
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String report = buildReport(thread, throwable, stamp);
            written = writeEverywhere(stamp, report);
            BootTrace.mark("crash written: " + written);
        } catch (Throwable t) {
            Log.e(TAG, "failed to persist crash report", t);
        }
        try {
            Thread.sleep(1500);
        } catch (Throwable ignored) {
            // ignore
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
            return;
        }
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {
            // ignore
        }
        System.exit(10);
    }

    private String buildReport(Thread thread, Throwable throwable, String stamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DSH 小V 崩溃报告 ===\n");
        sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("package: ").append(app.getPackageName()).append('\n');
        sb.append("versionCode: ").append(versionCode()).append('\n');
        sb.append("versionName: ").append(versionName()).append('\n');
        sb.append("SDK_INT: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("RELEASE: ").append(Build.VERSION.RELEASE).append('\n');
        sb.append("MODEL: ").append(Build.MODEL).append('\n');
        sb.append("thread: ").append(thread == null ? "?" : thread.getName()).append('\n');
        sb.append("stamp: ").append(stamp).append('\n');
        sb.append("\n=== 异常 ===\n");
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        sb.append(sw.toString());

        sb.append("\n=== 面包屑（最后 25 条）===\n");
        Deque<String> crumbs = BootTrace.breadcrumbsSnapshot(25);
        if (crumbs.isEmpty()) {
            sb.append("(空)\n");
        } else {
            for (String line : crumbs) {
                sb.append(line).append('\n');
            }
        }

        sb.append("\n=== boot-trace 全文 ===\n");
        sb.append(BootTrace.readTrace());
        sb.append('\n');
        return sb.toString();
    }

    private long versionCode() {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String versionName() {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return info.versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 三个落点都尝试，返回成功的路径串（用于写进 boot-trace）。 */
    private String writeEverywhere(String stamp, String report) {
        StringBuilder ok = new StringBuilder();
        String direct = DIR + "/crash-" + stamp + ".txt";
        if (writeFile(direct, report)) {
            ok.append(direct).append(' ');
        }
        String mediaStore = writeToMediaStore(stamp, report);
        if (mediaStore != null) {
            ok.append(mediaStore).append(' ');
        }
        String fallback = FALLBACK_DIR + "/crash-" + stamp + ".txt";
        if (writeFile(fallback, report)) {
            ok.append(fallback).append(' ');
        }
        return ok.length() == 0 ? "(全部失败)" : ok.toString().trim();
    }

    private boolean writeFile(String path, String content) {
        FileOutputStream fos = null;
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            fos = new FileOutputStream(file, false);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {
                // ignore
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "writeFile failed: " + path + " " + t);
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** MediaStore 写入（API 29+，不需要存储权限）。 */
    private String writeToMediaStore(String stamp, String content) {
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }
        try {
            ContentResolver resolver = app.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "DSH-crash-" + stamp + ".txt");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                return null;
            }
            try {
                out.write(content.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return "MediaStore:Download/DSH-crash-" + stamp + ".txt";
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore write failed: " + t);
            return null;
        }
    }

    /** 供启动加固的错误页调用：写一份「非崩溃」报告（可选）。 */
    static void writeManualReport(Context context, String tagText, Throwable throwable) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            String report = "=== DSH 小V 启动失败(" + tagText + ") ===\n" + sw.toString();
            new File(DIR).mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(DIR + "/crash-" + stamp + ".txt"));
            fos.write(report.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
            // 错误页本身不能再抛
        }
    }
}