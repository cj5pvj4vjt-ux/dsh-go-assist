package com.dshgo.assist;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * v37：本地小模型路由（千问3-0.6B 跑在手机上的 llama.cpp 服务）。
 *   - 返回 "LOCAL:答案" → 本地能答，直接用这个答案；
 *   - 返回 "ESCALATE"  → 交给 DSH；
 *   - 返回 null        → 服务不可用/超时，兜底也是交给 DSH。
 * 判断逻辑（规则 + 模型）在服务端，App 只负责问一次。
 */
final class LocalRouter {

    private static final int CONNECT_TIMEOUT_MS = 800;
    private static final int READ_TIMEOUT_MS = 20000;

    private LocalRouter() {
    }

    static String endpoint() {
        return "http://127.0.0.1:18085/ask";
    }

    /** v46：启动流式生成（立刻返回），随后用 partial() 轮询正在生成的文字。 */
    static boolean startAsk(String text) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:18085/ask-stream");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = conn.getOutputStream();
            try {
                os.write(("{\"text\":\"" + escape(text) + "\"}").getBytes("UTF-8"));
                os.flush();
            } finally {
                os.close();
            }
            boolean ok = conn.getResponseCode() == 200;
            OverlayLog.mark("local stream start ok=" + ok);
            return ok;
        } catch (Throwable t) {
            OverlayLog.mark("local stream start FAIL " + t.getClass().getSimpleName());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** v46：流式状态（一次轮询的结果）。 */
    static final class Partial {
        String text = "";
        String route = "";
        String answer = "";
        boolean done;
    }

    /** v46：读当前生成进度（很快，150ms 一次没问题）。 */
    static Partial partial() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:18085/partial");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);   // v49：推理时接口响应慢，放宽超时
            conn.setReadTimeout(4000);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
            String body = bos.toString("UTF-8");
            Partial p = new Partial();
            String t = jsonString(body, "text");
            String r = jsonString(body, "route");
            String a = jsonString(body, "answer");
            p.text = t == null ? "" : t;
            p.route = r == null ? "" : r;
            p.answer = a == null ? "" : a;
            p.done = body.contains("\"done\":true");
            return p;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 阻塞调用，请在后台线程使用。 */
    static String ask(String text) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String payload = "{\"text\":\"" + escape(text) + "\"}";
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload.getBytes("UTF-8"));
                os.flush();
            } finally {
                os.close();
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                OverlayLog.mark("local router http " + code);
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
            String body = bos.toString("UTF-8");
            String route = jsonString(body, "route");
            String answer = jsonString(body, "answer");
            OverlayLog.mark("local router resp route=" + route + " len="
                    + (answer == null ? 0 : answer.length()));
            if ("local".equals(route) && answer != null && answer.trim().length() > 0) {
                return "LOCAL:" + answer.trim();
            }
            return "ESCALATE";
        } catch (Throwable t) {
            OverlayLog.mark("local router FAIL " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }


    /** v38：当前是否有游戏在前台（vivo 把状态写在 System 设置里，读它免权限、零延迟）。 */
    static String foregroundGame(android.content.Context ctx) {
        try {
            String cur = android.provider.Settings.System.getString(
                    ctx.getContentResolver(), "current_game_package");
            if (cur != null && cur.trim().length() > 0) {
                return cur.trim();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    /** v38：让本地服务立刻卸载模型，把内存/CPU 让给游戏。 */
    static void unload() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:18085/unload");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(500);
            conn.setReadTimeout(1500);
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            int code = conn.getResponseCode();
            OverlayLog.mark("local router unload http " + code);
        } catch (Throwable t) {
            OverlayLog.mark("local router unload FAIL " + t.getClass().getSimpleName());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** 极简 JSON 取值（只处理字符串字段），够用且不引依赖。 */
    private static String jsonString(String json, String key) {
        try {
            String pat = "\"" + key + "\"";
            int i = json.indexOf(pat);
            if (i < 0) {
                return null;
            }
            int colon = json.indexOf(':', i + pat.length());
            if (colon < 0) {
                return null;
            }
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int p = q1 + 1; p < json.length(); p++) {
                char c = json.charAt(p);
                if (c == '\\') {
                    p++;
                    if (p >= json.length()) {
                        break;
                    }
                    char e = json.charAt(p);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (p + 4 < json.length()) {
                                sb.append((char) Integer.parseInt(
                                        json.substring(p + 1, p + 5), 16));
                                p += 4;
                            }
                            break;
                        default: sb.append(e);
                    }
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }
}
