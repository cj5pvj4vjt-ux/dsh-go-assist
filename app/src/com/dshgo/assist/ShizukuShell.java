package com.dshgo.assist;

import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 客户端封装（v9：常驻交互式 shell）。
 *
 * v8 之前我们用 `newProcess(new String[]{"sh","-c",script})`，但 Shizuku 13 在
 * 创建进程时会做校验（反编译设备上的 Shizuku 得到 `Lrikka/shizuku/yh0;->l()`）：
 *   1. 往 stdin 写 "echo SHELL_TEST\n"，读一行必须包含 SHELL_TEST；
 *   2. 再写 "id\n"，读一行包含 uid=0 就标记 root。
 * `sh -c` 根本不读 stdin → 校验必然失败（IOException: Created process is not a shell），
 * 这就是「root 功能全失败 / root 桥不干活」的根因。
 *
 * v9 改为：`newProcess(new String[]{"sh"}, null, null)` —— 非 tty 的 sh 会从 stdin
 * 逐行读命令，SHELL_TEST 探测因此自然通过；之后我们复用同一个进程当常驻 shell：
 *
 *   写：`{\n<script>\n} </dev/null 2>&1\nprintf '\n__DSH_EXIT__%d\n' $?\n`
 *   读：逐行读到 `__DSH_EXIT__<code>`，前面的都是输出。
 *   （`</dev/null` 防止脚本里的子命令偷吃我们的命令流；退出码用 $? 回传）
 *
 * 并发：单例 + synchronized 串行化；进程死了（EOF / exitValue 可读）就重建一次；
 * 每次调用有超时；所有执行都会追加写 /sdcard/Android/media/com.dshgo.assist/root-cmd.log。
 *
 * 旧的 `sh -c` 一次性路径保留为 fallback：交互模式失败时自动降级。
 */
final class ShizukuShell {

    static final int REQ_PERMISSION = 0x5348;   // 'SH'

    private static final String LOG_DIR = "/sdcard/Android/media/com.dshgo.assist";
    private static final String LOG_FILE = LOG_DIR + "/root-cmd.log";
    private static final long LOG_MAX_BYTES = 256 * 1024;

    private static final Object LOCK = new Object();
    private static final String EXIT_PREFIX = "__DSH_EXIT__";
    private static final String READY_PREFIX = "__DSH_READY__";
    private static final String EOF = "__DSH_EOF__";

    private static Process shell;
    private static BufferedWriter shellIn;
    private static LinkedBlockingQueue<String> shellLines;
    private static boolean interactiveBroken;

    private ShizukuShell() {
    }

    // ------------------------------------------------------------- 状态 ----
    static boolean binderReady() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean hasPermission() {
        if (!binderReady()) {
            return false;
        }
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int serverUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int serverApiVersion() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static String serverMode() {
        int uid = serverUid();
        if (uid < 0) {
            return "未连接";
        }
        return uid == 0 ? "root" : "shell(uid=" + uid + ")";
    }

    static void requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
        } catch (Throwable ignored) {
            // 未连接时会抛，调用方负责提示
        }
    }

    // --------------------------------------------------------- 进程创建 ----
    /**
     * Shizuku 13.x 把 newProcess 改成了 private（老版本是 public）：
     * 反射调用即可，类都在本 APK 里，不受隐藏 API 限制。
     */
    private static Process newProcess(String[] cmd) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        return (Process) method.invoke(null, (Object) cmd, null, null);
    }

    // ------------------------------------------------------------ 执行 ----
    /**
     * 执行一段 shell（root/shell 取决于 Shizuku 运行模式）。
     * 返回「输出 + [exit=N]」；所有执行都会写 root-cmd.log。
     */
    static String run(String script, long timeoutMs) {
        long started = SystemClock.uptimeMillis();
        synchronized (LOCK) {
            String result = null;
            Throwable error = null;
            try {
                result = runInteractive(script, timeoutMs);
            } catch (Throwable t) {
                error = t;
                killShell();
            }
            String mode = "interactive";
            if (result == null) {
                try {
                    result = runOneShot(script, timeoutMs);
                    mode = "one-shot(fallback)";
                } catch (Throwable t) {
                    error = t;
                    result = "[执行失败] " + describe(t);
                }
            }
            long elapsed = SystemClock.uptimeMillis() - started;
            appendLog(script, result, mode, elapsed, error);
            return result;
        }
    }

    /** 交互式 shell 执行；失败返回 null（交给 fallback）。 */
    private static String runInteractive(String script, long timeoutMs) {
        if (!binderReady()) {
            return "[Shizuku 未连接：请先打开 Shizuku 并点「连接 Shizuku」]";
        }
        if (!hasPermission()) {
            return "[Shizuku 未授权：请点「连接 Shizuku」并在弹窗里点允许]";
        }
        if (interactiveBroken) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureShell();
                if (shell == null) {
                    return null;
                }
                // 清掉上一次的残留
                shellLines.clear();
                String cmd = "{\n" + script + "\n} </dev/null 2>&1\n"
                        + "printf '\\n" + EXIT_PREFIX + "%d\\n' $?\n";
                shellIn.write(cmd);
                shellIn.flush();

                StringBuilder out = new StringBuilder();
                long deadline = SystemClock.uptimeMillis() + timeoutMs;
                while (true) {
                    long remain = deadline - SystemClock.uptimeMillis();
                    if (remain <= 0) {
                        killShell();
                        out.append("\n[超时 ").append(timeoutMs).append("ms，已重建 shell]");
                        return out.toString();
                    }
                    String line = shellLines.poll(remain, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        continue;
                    }
                    if (EOF.equals(line)) {
                        killShell();
                        if (attempt == 0) {
                            break;          // 重建一次再试
                        }
                        return out.append("\n[shell 已退出]").toString();
                    }
                    if (line.startsWith(EXIT_PREFIX)) {
                        int exit = -1;
                        try {
                            exit = Integer.parseInt(
                                    line.substring(EXIT_PREFIX.length()).trim());
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        return out.append("[exit=").append(exit).append(']').toString();
                    }
                    out.append(line).append('\n');
                }
            } catch (Throwable t) {
                killShell();
                appendLog("SHELL-ERROR " + script, describe(t), "interactive-error",
                        0, t);
                if (attempt == 0) {
                    continue;
                }
                return null;
            }
        }
        return null;
    }

    /** 兜底：老的一次性 `sh -c`（Shizuku 会做 SHELL_TEST 校验，通常用不了，但保留）。 */
    private static String runOneShot(String script, long timeoutMs) throws Exception {
        if (!binderReady() || !hasPermission()) {
            return "[Shizuku 未连接/未授权]";
        }
        Process process = null;
        final ByteArrayOutputStreamHolder holder = new ByteArrayOutputStreamHolder();
        try {
            process = newProcess(new String[]{"sh", "-c", "{\n" + script + "\n} 2>&1"});
            final java.io.InputStream in = process.getInputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[4096];
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) {
                            holder.buffer.write(buf, 0, n);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
            reader.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                reader.join(500);
                return holder.asString() + "\n[超时 " + timeoutMs + "ms，已终止]";
            }
            reader.join(1000);
            return holder.asString() + "[exit=" + process.exitValue() + "]";
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    private static final class ByteArrayOutputStreamHolder {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        String asString() {
            try {
                return buffer.toString("UTF-8");
            } catch (Throwable ignored) {
                return buffer.toString();
            }
        }
    }

    // ------------------------------------------------ 常驻 shell 生命周期 ----
    private static void ensureShell() throws Exception {
        if (shell != null) {
            try {
                shell.exitValue();
                // 能读到 exitValue 说明已经退出 → 重建
            } catch (IllegalThreadStateException alive) {
                return;                                   // 还活着，直接复用
            } catch (Throwable ignored) {
                // 未知状态 → 重建
            }
            killShell();
        }
        Process process = newProcess(new String[]{"sh"});   // 关键：交互式，不带 -c
        shell = process;
        shellIn = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        shellLines = queue;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(
                            new InputStreamReader(shell.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        queue.offer(line);
                    }
                } catch (Throwable ignored) {
                    // 进程被销毁时 readLine 抛/EOF
                } finally {
                    queue.offer(EOF);
                }
            }
        }, "dsh-shizuku-shell-reader");
        reader.setDaemon(true);
        reader.start();

        // 和 Shizuku 的 SHELL_TEST/id 探测同步一下：写一个哨兵，读到它为止
        queue.clear();
        shellIn.write("printf '\\n" + READY_PREFIX + "\\n'\n");
        shellIn.flush();
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (true) {
            long remain = deadline - SystemClock.uptimeMillis();
            if (remain <= 0) {
                throw new IOException("interactive shell 没有回应 READY 哨兵");
            }
            String line = queue.poll(remain, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            if (line.startsWith(READY_PREFIX)) {
                break;
            }
        }
    }

    private static void killShell() {
        interactiveBroken = false;
        try {
            if (shell != null) {
                shell.destroy();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        shell = null;
        shellIn = null;
        shellLines = null;
    }

    // --------------------------------------------------------- 日志落盘 ----
    /**
     * 每次执行都追加写 `/sdcard/Android/media/com.dshgo.assist/root-cmd.log`
     * （这个目录 App 自己可写、Lead 在 proot 里可读）。
     * 超过 256KB 轮转成 root-cmd.log.1（覆盖旧 .1）。
     *
     * 格式：
     *   [2026-11-06 18:20:01.123] mode=interactive elapsed=123ms
     *   $ <命令（多行原样）>
     *   --- output ---
     *   <输出>
     *   --- end ---
     */
    private static void appendLog(String script, String output, String mode,
                                  long elapsedMs, Throwable error) {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(LOG_FILE);
            if (file.exists() && file.length() > LOG_MAX_BYTES) {
                File old = new File(LOG_FILE + ".1");
                if (old.exists()) {
                    old.delete();
                }
                file.renameTo(old);
            }
            StringBuilder sb = new StringBuilder();
            String stamp;
            synchronized (TIME_FMT) {
                stamp = TIME_FMT.format(new Date());
            }
            sb.append('[').append(stamp).append("] mode=").append(mode)
                    .append(" elapsed=").append(elapsedMs).append("ms\n");
            sb.append("$ ").append(script.trim()).append('\n');
            sb.append("--- output ---\n");
            sb.append(output == null ? "(null)" : output).append('\n');
            if (error != null) {
                sb.append("--- exception ---\n");
                sb.append(describe(error)).append('\n');
            }
            sb.append("--- end ---\n\n");

            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
            try {
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {
                    // ignore
                }
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
            // 日志失败绝不影响主流程
        }
    }

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** 完整堆栈（等价 Log.getStackTraceString，不依赖 android.util.Log）。 */
    static String describe(Throwable t) {
        if (t == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        StackTraceElement[] elements = t.getStackTrace();
        if (elements != null) {
            for (int i = 0; i < Math.min(elements.length, 40); i++) {
                sb.append("    at ").append(elements[i]).append('\n');
            }
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("Caused by: ").append(describe(cause));
        }
        return sb.toString();
    }
}