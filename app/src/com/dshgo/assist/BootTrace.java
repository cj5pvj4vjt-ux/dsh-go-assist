package com.dshgo.assist;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * v6 启动轨迹取证（最重要的一条链路）。
 *
 * 为什么是这个路径：`/sdcard/Android/media/<pkg>/` 是应用自己的媒体目录，
 * 不需要任何存储权限就能写；而 Lead 在 proot 里能读到别的 App 的这个目录。
 * 所以即使 App 在启动阶段就崩，只要进程走到过某个阶段，就会留下带时间戳的一行。
 *
 * 每行格式：`HH:mm:ss.SSS <stage>`
 * 同时维护内存里的面包屑（最后 40 条），崩溃报告会带上最后 25 条。
 */
final class BootTrace {

    static final String DIR = "/sdcard/Android/media/com.dshgo.assist";
    static final String FILE = DIR + "/boot-trace.txt";
    static final String FALLBACK_DIR = "/sdcard/Download/DSH-Go";
    static final String FALLBACK_FILE = FALLBACK_DIR + "/boot-trace.txt";

    private static final int MAX_BREADCRUMBS = 40;
    private static final Deque<String> BREADCRUMBS = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private BootTrace() {
    }

    /** 打一个点：内存面包屑 + append+flush+fsync 落盘（两个位置都试）。 */
    static void mark(String stage) {
        String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + " " + stage;
        }
        synchronized (BREADCRUMBS) {
            BREADCRUMBS.addLast(line);
            while (BREADCRUMBS.size() > MAX_BREADCRUMBS) {
                BREADCRUMBS.removeFirst();
            }
        }
        writeLine(FILE, line);
    }

    private static void writeLine(String path, String line) {
        FileOutputStream fos = null;
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            fos = new FileOutputStream(file, true);
            fos.write((line + "\n").getBytes("UTF-8"));
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {
                // 有些文件系统不支持 fsync，忽略
            }
        } catch (Throwable t) {
            if (!FILE.equals(path)) {
                Log.w("DshBootTrace", "write failed: " + path + " " + t);
                return;
            }
            // 主路径失败 → 退到下载目录再试一次
            writeLine(FALLBACK_FILE, line);
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

    /** 读回轨迹文件内容（崩溃报告里附上）。 */
    static String readTrace() {
        for (String path : new String[]{FILE, FALLBACK_FILE}) {
            try {
                File file = new File(path);
                if (!file.exists()) {
                    continue;
                }
                byte[] buf = new byte[(int) Math.min(file.length(), 65536)];
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                try {
                    int n = in.read(buf);
                    if (n > 0) {
                        return new String(buf, 0, n, "UTF-8");
                    }
                } finally {
                    in.close();
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return "(boot-trace 读不到)";
    }

    static Deque<String> breadcrumbsSnapshot(int max) {
        Deque<String> copy = new ArrayDeque<>();
        synchronized (BREADCRUMBS) {
            int skip = Math.max(0, BREADCRUMBS.size() - max);
            int i = 0;
            for (String line : BREADCRUMBS) {
                if (i++ < skip) {
                    continue;
                }
                copy.addLast(line);
            }
        }
        return copy;
    }
}