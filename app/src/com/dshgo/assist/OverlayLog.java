package com.dshgo.assist;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v12：悬浮胶囊 / 语音链路的诊断日志。
 *
 * 落点 **`/sdcard/Android/media/com.dshgo.assist/overlay.log`**
 * （App 自己的媒体目录，不需要存储权限，Lead 在 proot 里可直接读）。
 * 超过 256KB 轮转成 overlay.log.1。
 *
 * 每行格式：`HH:mm:ss.SSS [thread] message`
 */
final class OverlayLog {

    private static final String DIR = "/sdcard/Android/media/com.dshgo.assist";
    private static final String FILE = DIR + "/overlay.log";
    private static final long MAX_BYTES = 256 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private OverlayLog() {
    }

    static void mark(String message) {
        try {
            File dir = new File(DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(FILE);
            if (file.exists() && file.length() > MAX_BYTES) {
                File old = new File(FILE + ".1");
                if (old.exists()) {
                    old.delete();
                }
                file.renameTo(old);
            }
            String stamp;
            synchronized (FMT) {
                stamp = FMT.format(new Date());
            }
            String line = stamp + " [" + Thread.currentThread().getName() + "] "
                    + message + "\n";
            FileOutputStream fos = new FileOutputStream(file, true);
            try {
                fos.write(line.getBytes("UTF-8"));
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

    /** 语音错误码转可读名字，方便对日志。 */
    static String errorName(int code) {
        switch (code) {
            case 1:
                return "ERROR_NETWORK_TIMEOUT";
            case 2:
                return "ERROR_NETWORK";
            case 3:
                return "ERROR_AUDIO";
            case 4:
                return "ERROR_SERVER";
            case 5:
                return "ERROR_CLIENT";
            case 6:
                return "ERROR_SPEECH_TIMEOUT";
            case 7:
                return "ERROR_NO_MATCH";
            case 8:
                return "ERROR_RECOGNIZER_BUSY";
            case 9:
                return "ERROR_INSUFFICIENT_PERMISSIONS";
            case 10:
                return "ERROR_TOO_MANY_REQUESTS";
            case 11:
                return "ERROR_SERVER_DISCONNECTED";
            case 12:
                return "ERROR_LANGUAGE_NOT_SUPPORTED";
            case 13:
                return "ERROR_LANGUAGE_UNAVAILABLE";
            case 14:
                return "ERROR_CANNOT_CHECK_SUPPORT";
            default:
                return "ERROR_UNKNOWN(" + code + ")";
        }
    }
}