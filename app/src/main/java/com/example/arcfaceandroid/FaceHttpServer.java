package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.util.Base64;
import android.util.Log;

import com.arcsoft.face.FaceInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.io.File;
import java.io.FileInputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Java 原生 ServerSocket 的轻量 HTTP 服务器。
 * 完全替换 NanoHTTPD（其 2.3.1 存在不可修复的 POST body 读超时 bug + GET URL 长度限制）。
 * 本实现直接控制 Socket I/O，POST/GET 均稳定可靠，无第三方依赖。
 */
public class FaceHttpServer {
    private static final String TAG = "FaceHttpServer";
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CRLFCRLF = {'\r', '\n', '\r', '\n'};

    private final int port;
    private final Context appContext;
    private final FaceServer faceServer;
    private final ScanManager scanManager = ScanManager.getInstance();
    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService threadPool;

    public FaceHttpServer(int port, Context ctx, FaceServer server) {
        this.port = port;
        this.appContext = ctx.getApplicationContext();
        this.faceServer = server;
    }

    public void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket(port);
        running.set(true);
        threadPool = Executors.newFixedThreadPool(8);
        new Thread(this::acceptLoop, "HttpAccept").start();
        Log.i(TAG, "HTTP server started on port " + port);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (threadPool != null) threadPool.shutdownNow();
        Log.i(TAG, "HTTP server stopped");
    }

    public boolean isRunning() { return running.get(); }

    /** 端口是否真的在监听（比 isRunning 更严格：进程活着不代表端口还开着） */
    public boolean isListening() {
        return running.get() && serverSocket != null && !serverSocket.isClosed();
    }

    /** 重建监听：先停后起。用于端口看门狗在 accept 异常退出后救活。 */
    public void restart() throws IOException {
        stop();
        start();
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ==================== 连接接受循环 ====================

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                try { client.setSoTimeout(SOCKET_TIMEOUT_MS); } catch (Exception ignored) {}
                threadPool.submit(() -> handleClient(client));
            } catch (SocketException se) {
                if (!running.get()) break;            // 主动 stop()，正常退出
                Log.w(TAG, "accept() socket 异常，尝试重建监听", se);
                if (serverSocket.isClosed()) {        // socket 失效则重建，不让端口沉默
                    try {
                        serverSocket = new ServerSocket(port);
                        Log.i(TAG, "监听 socket 已重建");
                    } catch (IOException rebind) {
                        Log.e(TAG, "重建监听失败，1s 后重试", rebind);
                        sleepQuietly(1000);
                    }
                }
            } catch (IOException e) {
                if (!running.get()) break;
                Log.w(TAG, "accept() IO 异常，500ms 后重试", e);
                sleepQuietly(500);
            }
        }
    }

    // ==================== 单请求处理 ====================

    private void handleClient(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 一次性把整个请求（header + body）读进单个缓冲区，再解析。
            // 不能用 BufferedReader 读 header 后切到原始 InputStream 读 body——
            // BufferedReader 的预读缓冲会吞掉 body 前若干字节，导致 Content-Length
            // 永远读不满、阻塞直到 socket 超时（表现为 ECONNRESET / socket hang up）。
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int headerEnd = -1;
            int contentLength = 0;
            String requestLine = null;
            Map<String, String> headers = new HashMap<>();

            // 阶段1：读到 header 结束符 \r\n\r\n
            while (headerEnd < 0) {
                int n = in.read(buf);
                if (n == -1) break;
                raw.write(buf, 0, n);
                headerEnd = indexOf(raw.toByteArray(), CRLFCRLF);
            }
            if (headerEnd < 0) { send(out, 400, "text/plain", "Bad Request"); return; }

            byte[] all = raw.toByteArray();
            int lineEnd = indexOf(all, CRLF, 0);
            if (lineEnd < 0) { send(out, 400, "text/plain", "Bad Request"); return; }
            requestLine = new String(all, 0, lineEnd, StandardCharsets.ISO_8859_1);

            String[] parts = requestLine.split(" ");
            if (parts.length < 3) { send(out, 400, "text/plain", "Bad Request"); return; }
            String method = parts[0];
            String rawUri = parts[1];

            // 解析 headers
            String headStr = new String(all, 0, headerEnd, StandardCharsets.ISO_8859_1);
            for (String line : headStr.split("\r\n", -1)) {
                int colon = line.indexOf(':');
                if (colon > 0)
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                               line.substring(colon + 1).trim());
            }
            contentLength = parseIntSafe(headers.get("content-length"));

            // 阶段2：若 body 尚未读全，按 Content-Length 补足
            int bodyStart = headerEnd + 4;
            int haveBody = all.length - bodyStart;
            int guard = 0;
            while (haveBody < contentLength && guard++ < 100000) {
                int n = in.read(buf);
                if (n == -1) break;
                raw.write(buf, 0, n);
                all = raw.toByteArray();
                haveBody = all.length - bodyStart;
            }

            // 阶段3：按偏移切出 body（绝不依赖 InputStream 的二次读取）
            byte[] body = (contentLength > 0 && bodyStart + contentLength <= all.length)
                    ? Arrays.copyOfRange(all, bodyStart, bodyStart + contentLength)
                    : Arrays.copyOfRange(all, bodyStart, all.length);

            // 路由
            String uri = rawUri.split("\\?", 2)[0];

            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendCorsOptions(out); return;
            }

            String respBody;
            int statusCode;
            try {
                switch (uri) {
                    case "/api/health":
                        if ("GET".equalsIgnoreCase(method)) {
                            statusCode = 200; respBody = jsonHealth();
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/stats":
                        if ("GET".equalsIgnoreCase(method)) {
                            // P3-3: 识别质量统计（识别率、平均相似度、匹配次数、自适应阈值等）
                            statusCode = 200;
                            respBody = RecognitionState.get().getStatsJson();
                        } else if ("DELETE".equalsIgnoreCase(method)) {
                            // 重置统计信息
                            RecognitionState.get().resetStats();
                            statusCode = 200; respBody = "{\"success\":true,\"message\":\"stats reset\"}";
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/config":
                        if ("GET".equalsIgnoreCase(method)) {
                            // 读取配置：密码字段返回掩码（********），不暴露明文
                            statusCode = 200;
                            respBody = AppConfig.get(appContext).toJson(false).toString();
                        } else if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
                            // 保存配置：支持密码三态（缺失=不改、非空=更新、__CLEAR__=清空）
                            // 保存后自动热生效（米家启停、节点重注册等通过 AppConfig Listener 通知）
                            try {
                                JSONObject json = parseJsonBody(body, headers.get("content-type"));
                                java.util.Set<String> changed = AppConfig.get(appContext).updateFromJson(json);
                                JSONObject o = new JSONObject();
                                o.put("success", true);
                                o.put("changed", changed.size());
                                JSONArray arr = new JSONArray();
                                for (String k : changed) arr.put(k);
                                o.put("keys", arr);
                                statusCode = 200; respBody = o.toString();
                            } catch (Exception e) {
                                statusCode = 400; respBody = err("invalid json: " + e.getMessage());
                            }
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/faces":
                        if ("GET".equalsIgnoreCase(method)) {
                            statusCode = 200; respBody = jsonFaces();
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/recognize":
                        if ("GET".equalsIgnoreCase(method)) {
                            statusCode = 200;
                            respBody = recognizeFromQueryParam(rawUri);
                        } else if ("POST".equalsIgnoreCase(method)) {
                            statusCode = 200;
                            respBody = recognizeFromBody(body, headers.get("content-type"));
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/register":
                        if ("POST".equalsIgnoreCase(method)) {
                            statusCode = 200;
                            String name = extractParam(rawUri, "name");
                            String live = extractParam(rawUri, "live");
                            if ("1".equals(live)) {
                                String fp = extractParam(rawUri, "face");
                                int faceIdx = 0;
                                try { if (fp != null) faceIdx = Integer.parseInt(fp); } catch (Exception ignore) {}
                                respBody = registerFromLive(name, faceIdx);   // 用当前实时帧就地注册（真实座位距离）
                            } else {
                                respBody = registerFromBody(body, name, headers.get("content-type"));
                            }
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/clear":
                        if ("POST".equalsIgnoreCase(method)) {
                            statusCode = 200; respBody = jsonClear();
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/":
                    case "/ui":
                    case "/index.html":
                        if ("GET".equalsIgnoreCase(method)) {
                            byte[] html = loadAsset("webui.html");
                            if (html == null) {
                                statusCode = 404; respBody = err("webui.html not found");
                            } else {
                                writeHtml(out, html);
                                return;
                            }
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/settings.html":
                        if ("GET".equalsIgnoreCase(method)) {
                            byte[] html = loadAsset("settings.html");
                            if (html == null) {
                                statusCode = 404; respBody = err("settings.html not found");
                            } else {
                                writeHtml(out, html);
                                return;
                            }
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/face_image":
                        if ("GET".equalsIgnoreCase(method)) {
                            String name = extractParam(rawUri, "name");
                            if (name == null || name.isEmpty()) {
                                statusCode = 400; respBody = err("missing 'name' param");
                            } else {
                                File img = faceServer.getRegisteredImageFile(name);
                                byte[] data = (img != null && img.exists()) ? readFile(img) : null;
                                if (data == null) { statusCode = 404; respBody = err("face image not found"); }
                                else { writeJpeg(out, data); return; }
                            }
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/face":
                        if ("DELETE".equalsIgnoreCase(method)) {
                            String name = extractParam(rawUri, "name");
                            if (name == null || name.isEmpty()) {
                                statusCode = 400; respBody = err("missing 'name' param");
                            } else {
                                boolean deleted = faceServer.removeFace(appContext, name);
                                JSONObject o = new JSONObject();
                                o.put("deleted", deleted);
                                o.put("registered", faceServer.getFaceNumber());
                                statusCode = 200; respBody = o.toString();
                            }
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/snapshot":
                        if ("GET".equalsIgnoreCase(method)) {
                            byte[] jpg = FrameBuffer.get().getLatestSnapshotJpeg();
                            if (jpg == null) {
                                statusCode = 503; respBody = err("no frame / camera not ready");
                            } else {
                                writeJpeg(out, jpg);
                                return; // 直接写二进制，跳过下方 JSON 发送
                            }
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/scan":
                        if ("POST".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method)) {
                            FaceServerService svc = FaceServerService.getInstance();
                            if (svc == null) {
                                statusCode = 503; respBody = err("service_not_running");
                            } else if (!svc.ensureCameraReady(Constants.SCAN_CAMERA_WAIT_MS)) {
                                statusCode = 503; respBody = err("camera_not_ready");
                            } else {
                                String mode = extractParam(rawUri, "mode");
                                if ("faces".equals(mode)) {
                                    // 仅检测：返回按面积降序的人脸列表（含序号），供按脸选人注册
                                    statusCode = 200; respBody = detectFacesJson();
                                    break;
                                }
                                long duration = parseDuration(rawUri, body);
                                float threshold = parseFloatParam(rawUri, body, "threshold", Constants.MATCH_THRESHOLD);
                                String callback = extractParam(rawUri, "callback");
                                if (callback == null) callback = readJsonField(body, "callback");
                                boolean wait = parseBoolParam(rawUri, body, "wait");
                                boolean includeSnapshot = parseBoolParam(rawUri, body, "includeSnapshot");
                                // 多人策略：fast=命中任一熟人即返（最快，可能漏人）；默认 false 全量收集
                                boolean fast = parseBoolParam(rawUri, body, "fast");
                                int settle = 4; // 人数稳定 N 帧即提前收敛
                                String sSettle = extractParam(rawUri, "settle");
                                if (sSettle == null) sSettle = readJsonField(body, "settle");
                                if (sSettle != null) { try { settle = Integer.parseInt(sSettle); } catch (Exception ignore) {} }
                                String jobId = scanManager.startScan(duration, threshold, callback, includeSnapshot, fast, settle);
                                if (wait) {
                                    String result = scanManager.waitForResult(jobId, duration + 3000);
                                    statusCode = 200;
                                    respBody = (result != null) ? result : err("scan timeout");
                                } else {
                                    statusCode = 200;
                                    respBody = "{\"jobId\":\"" + jobId + "\",\"status\":\"running\"}";
                                }
                            }
                        } else {
                            statusCode = 405; respBody = err("method not allowed");
                        }
                        break;
                    case "/api/scan/result":
                        if ("GET".equalsIgnoreCase(method)) {
                            String jobId = extractParam(rawUri, "jobId");
                            if (jobId == null || jobId.isEmpty()) {
                                statusCode = 400; respBody = err("missing 'jobId'");
                            } else {
                                String r = scanManager.getJobResult(jobId);
                                statusCode = 200;
                                respBody = (r != null) ? r : err("job not found");
                            }
                        } else {
                            statusCode = 405; respBody = err("method not allowed");
                        }
                        break;
                    case "/api/scan/latest":
                        if ("GET".equalsIgnoreCase(method)) {
                            // 返回实时识别状态（共识后的名单 + 人数 + 新鲜度），便于不扫描也确认谁在镜前
                            RecognitionState rs = RecognitionState.get();
                            JSONObject o = new JSONObject();
                            List<RecognitionState.PersonView> persons = rs.getLivePersons();
                            int n = persons == null ? 0 : persons.size();
                            o.put("faces", n);
                            o.put("freshMs", rs.getLastUpdateMs() < 0 ? -1
                                    : System.currentTimeMillis() - rs.getLastUpdateMs());
                            o.put("persons", buildPersonsJson(persons));
                            // 米家全景路与 TV 路按名融合，供 HA 展示"谁在"（补 TV 漏检的人员）
                            o.put("fused", buildFusedJson(rs.getFusedPeople()));
                            statusCode = 200; respBody = o.toString();
                        } else {
                            statusCode = 405; respBody = err("method not allowed");
                        }
                        break;
                    case "/api/hotspots":
                        if ("GET".equalsIgnoreCase(method)) {
                            statusCode = 200; respBody = HotspotManager.get().dump();
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/hotspots/reset":
                        if ("POST".equalsIgnoreCase(method)) {
                            HotspotManager.get().clear();
                            JSONObject o = new JSONObject();
                            o.put("reset", true);
                            o.put("hotspots", HotspotManager.get().count());
                            statusCode = 200; respBody = o.toString();
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/api/pano":
                        if ("GET".equalsIgnoreCase(method)) {
                            // 双路融合状态：米家全景原始识别结果 + 与 TV 路按名融合后的名单
                            RecognitionState rs = RecognitionState.get();
                            JSONObject o = new JSONObject();
                            List<?> pano = rs.getPanoResults();
                            JSONArray pa = new JSONArray();
                            if (pano != null) for (Object obj : pano) {
                                FaceServer.RecognizeResult r = (FaceServer.RecognizeResult) obj;
                                JSONObject it = new JSONObject();
                                boolean matched = r.score >= Constants.MATCH_THRESHOLD && !"未知".equals(r.name);
                                it.put("name", matched ? r.name : "Unknown");
                                it.put("matched", matched);
                                it.put("score", Math.round(r.score * 1000d) / 1000d);
                                pa.put(it);
                            }
                            o.put("pano", pa);
                            o.put("panoOnline", rs.isPanoOnline());
                            o.put("panoMsg", rs.getPanoMsg());
                            List<RecognitionState.FusedPerson> fused = rs.getFusedPeople();
                            JSONArray fa = new JSONArray();
                            for (RecognitionState.FusedPerson fp : fused) {
                                JSONObject it = new JSONObject();
                                it.put("name", fp.name);
                                it.put("score", Math.round(fp.bestScore * 1000d) / 1000d);
                                it.put("matched", fp.isMatched());
                                it.put("fromTv", fp.fromTv);
                                it.put("fromPano", fp.fromPano);
                                fa.put(it);
                            }
                            o.put("fused", fa);
                            statusCode = 200; respBody = o.toString();
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/face/recognize":
                        if ("POST".equalsIgnoreCase(method)) {
                            statusCode = 200; respBody = faceRecognize(body, headers.get("content-type"));
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    case "/face/lib":
                        if ("GET".equalsIgnoreCase(method)) {
                            statusCode = 200; respBody = faceLibExport();
                        } else if ("POST".equalsIgnoreCase(method)) {
                            statusCode = 200; respBody = faceLibImport(body);
                        } else { statusCode = 405; respBody = err("method not allowed"); }
                        break;
                    default:
                        statusCode = 404; respBody = err("not found");
                }
            } catch (Exception e) {
                Log.e(TAG, "Handler error for " + uri, e);
                statusCode = 500; respBody = err(e.getMessage());
            }

            sendJson(out, statusCode, respBody);

        } catch (SocketException e) {
            Log.d(TAG, "Client disconnected");
        } catch (IOException e) {
            Log.e(TAG, "IO error", e);
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== HTTP 底层工具 ====================

    /** 发送 JSON 响应（含 CORS header） */
    private void sendJson(OutputStream out, int status, String body) throws IOException {
        send(out, status, "application/json; charset=utf-8", body);
    }

    private void send(OutputStream out, int status, String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.ISO_8859_1));
        pw.print("HTTP/1.1 " + statusText(status) + "\r\n");
        pw.print("Content-Type: " + contentType + "\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("Content-Length: " + bodyBytes.length + "\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
        out.write(bodyBytes);
        out.flush();
    }

    /** 发送 JPEG 二进制响应（供 HA 拉取实时画面） */
    private void writeJpeg(OutputStream out, byte[] jpg) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.ISO_8859_1));
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: image/jpeg\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("Content-Length: " + jpg.length + "\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
        out.write(jpg);
        out.flush();
    }

    private void sendCorsOptions(OutputStream out) throws IOException {
        PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(out, StandardCharsets.ISO_8859_1));
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("Access-Control-Allow-Methods: GET,POST,DELETE,OPTIONS\r\n");
        pw.print("Access-Control-Allow-Headers: *\r\n");
        pw.print("Content-Length: 0\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
    }

    /** 读取 assets 下的 WebUI 文件 */
    private byte[] loadAsset(String name) {
        try (InputStream is = appContext.getAssets().open(name)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Exception e) { return null; }
    }

    /** 读取本地文件为字节数组 */
    private static byte[] readFile(File f) {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Exception e) { return null; }
    }

    /** 直接写出 HTML 页面 */
    private void writeHtml(OutputStream out, byte[] html) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.ISO_8859_1));
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: text/html; charset=utf-8\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("Content-Length: " + html.length + "\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
        out.write(html);
        out.flush();
    }

    private static String statusText(int c) {
        switch(c) {
            case 200: return "200 OK";
            case 400: return "400 Bad Request";
            case 404: return "404 Not Found";
            case 405: return "405 Method Not Allowed";
            case 500: return "500 Internal Server Error";
            default: return c + "";
        }
    }

    /** 在字节数组中查找子数组，返回起始下标，找不到返回 -1 */
    private static int indexOf(byte[] src, byte[] pattern) {
        return indexOf(src, pattern, 0);
    }

    /** 从 fromIndex 开始查找子数组，返回起始下标，找不到返回 -1 */
    private static int indexOf(byte[] src, byte[] pattern, int fromIndex) {
        if (pattern.length == 0) return fromIndex;
        int max = src.length - pattern.length;
        for (int i = fromIndex; i <= max; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (src[i + j] != pattern[j]) { found = false; break; }
            }
            if (found) return i;
        }
        return -1;
    }

    /** 安全解析整数，失败返回 0 */
    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** 从 URL query string 提取参数值 */
    private static String extractParam(String uri, String name) {
        try {
            int q = uri.indexOf('?');
            if (q < 0) return null;
            for (String pair : uri.substring(q + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && URLDecoder.decode(kv[0], "UTF-8").equals(name))
                    return URLDecoder.decode(kv[1], "UTF-8");
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== 业务接口实现 ====================

    private String jsonHealth() throws Exception {
        JSONObject o = new JSONObject();
        o.put("status","ok"); o.put("engine","ArcSoft ArcFace 3.0 (Android)");
        o.put("registered",faceServer.getFaceNumber()); o.put("port",port);
        // 当前检测模式：VIDEO=自带跟踪(faceId连续)，IMAGE=单帧检测(faceId=-1)
        o.put("detect_mode", FaceServer.currentDetectMode == com.arcsoft.face.enums.DetectMode.ASF_DETECT_MODE_VIDEO ? "VIDEO" : "IMAGE");
        o.put("engine_ready", faceServer.isEngineReady());
        return o.toString();
    }

    private String jsonFaces() throws Exception {
        List<String> names = faceServer.getFaceNames();
        JSONObject o = new JSONObject(); o.put("count", names.size());
        JSONArray a = new JSONArray();
        for (String s : names) {
            JSONObject item = new JSONObject();
            item.put("name", s);
            try { item.put("image", "/api/face_image?name=" + URLEncoder.encode(s, "UTF-8")); }
            catch (Exception ignore) { item.put("image", ""); }
            a.put(item);
        }
        o.put("faces", a);
        return o.toString();
    }

    private String jsonClear() throws Exception {
        JSONObject o=new JSONObject(); o.put("deleted",faceServer.clearAllFaces(appContext));
        return o.toString();
    }

    // ---- 识别：从 URL 参数取 base64 ----
    private String recognizeFromQueryParam(String rawUri) {
        String b64 = extractParam(rawUri, "image");
        if (b64==null||b64.isEmpty()) return err("missing 'image' param");
        byte[] raw = decodeBase64(stripDataUri(b64));
        if (raw==null) return err("cannot decode base64");
        Bitmap bmp=loadBitmap(raw, null);
        if(bmp==null){ faceServer.lastDecodeOk=false; return err("cannot decode image"); }
        return buildResult(bmp);
    }

    // ---- 识别：从 POST body 取图 ----
    private String recognizeFromBody(byte[] body, String ct) {
        Bitmap bmp=loadBitmap(body,ct);
        if(bmp==null){ faceServer.lastDecodeOk=false; return err("cannot decode image"); }
        return buildResult(bmp);
    }

    // ---- 注册 ----
    private String registerFromLive(String name, int faceIdx) {
        byte[] nv21 = FrameBuffer.get().takeCopy();
        int w = FrameBuffer.get().getFrameWidth();
        int h = FrameBuffer.get().getFrameHeight();
        if (nv21 == null || w <= 0 || h <= 0) return err("no frame / camera not ready");
        boolean ok = faceServer.registerFromNv21(appContext, nv21, w, h, name == null ? "" : name, faceIdx);
        JSONObject o = new JSONObject();
        try {
            o.put("success", ok);
            o.put("name", name == null ? "" : name);
            o.put("registered", faceServer.getFaceNumber());
            return o.toString();
        } catch (Exception e) { return err(e.getMessage()); }
    }

    private String registerFromBody(byte[] body, String name, String ct) {
        Bitmap bmp = null;
        // JSON body 里带 name / image(base64) 字段
        if (ct != null && ct.toLowerCase().contains("json") && body != null && body.length > 0) {
            try {
                JSONObject jo = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if (name == null || name.isEmpty()) name = jo.optString("name", "");
                String b64 = jo.optString("image", "");
                if (!b64.isEmpty()) {
                    byte[] raw = decodeBase64(stripDataUri(b64));
                    if (raw != null) bmp = loadBitmap(raw, null);
                }
            } catch (Exception ignored) {}
        }
        // 否则按原始二进制 / 原始 base64 文本处理
        if (bmp == null) bmp = loadBitmap(body, ct);
        if (bmp == null) { faceServer.lastDecodeOk = false; return err("no valid image"); }
        boolean ok = faceServer.register(appContext, bmp, name == null ? "" : name);
        JSONObject o = new JSONObject();
        try { o.put("success", ok); o.put("name", name == null ? "" : name);
           o.put("registered", faceServer.getFaceNumber());
           return o.toString();
        }catch(Exception e){return err(e.getMessage());}
    }

    // ---- 实时名单 / 按脸选人辅助 ----

    /** 仅检测当前帧人脸，按面积降序返回（index/faceId/rect），供多人同框时先挑人再注册。 */
    private String detectFacesJson() {
        byte[] nv21 = FrameBuffer.get().takeCopy();
        int w = FrameBuffer.get().getFrameWidth();
        int h = FrameBuffer.get().getFrameHeight();
        if (nv21 == null || w <= 0 || h <= 0) return err("no frame / camera not ready");
        List<FaceInfo> list = faceServer.detectFacesOnly(nv21, w, h, null);
        if (list != null) list.sort((a, b) -> Integer.compare(
                b.getRect().width() * b.getRect().height(),
                a.getRect().width() * a.getRect().height()));
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        int idx = 0;
        try {
            if (list != null) for (FaceInfo fi : list) {
                Rect r = fi.getRect();
                if (r == null) continue;
                JSONObject it = new JSONObject();
                it.put("index", idx++);
                it.put("faceId", fi.getFaceId());
                it.put("width", r.width());
                it.put("height", r.height());
                JSONObject rc = new JSONObject();
                rc.put("left", r.left); rc.put("top", r.top);
                rc.put("right", r.right); rc.put("bottom", r.bottom);
                it.put("rect", rc);
                arr.put(it);
            }
            o.put("faces", arr);
            o.put("count", idx);
        } catch (JSONException e) {
            return err("json: " + e.getMessage());
        }
        return o.toString();
    }

    /** 把共识名单序列化为 JSON 数组（name/similarity/faceId/rect）。 */
    private static JSONArray buildPersonsJson(List<RecognitionState.PersonView> persons) {
        JSONArray arr = new JSONArray();
        if (persons == null) return arr;
        try {
            for (RecognitionState.PersonView p : persons) {
                JSONObject it = new JSONObject();
                it.put("name", p.name == null ? "" : p.name);
                it.put("similarity", Math.round(p.similarity * 1000d) / 1000d);
                it.put("faceId", p.faceId);
                if (p.rect != null) {
                    JSONObject rc = new JSONObject();
                    rc.put("left", p.rect.left); rc.put("top", p.rect.top);
                    rc.put("right", p.rect.right); rc.put("bottom", p.rect.bottom);
                    it.put("rect", rc);
                }
                arr.put(it);
            }
        } catch (JSONException e) {
            // 单条序列化异常忽略，返回已收集部分
        }
        return arr;
    }

    /** 把 TV+米家融合名单序列化为 JSON 数组（name/similarity/matched/fromTv/fromPano）。 */
    private static JSONArray buildFusedJson(List<RecognitionState.FusedPerson> fused) {
        JSONArray arr = new JSONArray();
        if (fused == null) return arr;
        try {
            for (RecognitionState.FusedPerson p : fused) {
                JSONObject it = new JSONObject();
                it.put("name", p.name == null ? "" : p.name);
                it.put("similarity", Math.round(p.bestScore * 1000d) / 1000d);
                it.put("matched", p.isMatched());
                it.put("fromTv", p.fromTv);
                it.put("fromPano", p.fromPano);
                arr.put(it);
            }
        } catch (JSONException e) {
            // 单条序列化异常忽略，返回已收集部分
        }
        return arr;
    }

    // ==================== 图片解码 & 结果构建 ====================

    /**
     * 统一图片解码：兼容 原始字节 / JSON{image:base64} / 裸 base64 字符串 / data: URI，
     * 并做 EXIF 方向校正（官方 ArcSoft demo 做法）+ 超大图缩放。
     */
    private Bitmap loadBitmap(byte[] data, String ct) {
        if(data==null||data.length==0) return null;

        // 1) JSON 包裹的 base64
        if(ct!=null&&ct.toLowerCase().contains("json")){
            try{
                JSONObject jo=new JSONObject(new String(data,StandardCharsets.UTF_8));
                String b64=jo.optString("image",jo.optString("image_base64",""));
                if(!b64.isEmpty()) data=decodeBase64(stripDataUri(b64));
            }catch(Exception ignored){}
        }
        // 2) 整个 body 就是 base64 文本（裸 base64 / data: URI）？
        else if(isBase64Text(data)){
            try{ data=decodeBase64(stripDataUri(new String(data,StandardCharsets.US_ASCII).trim())); }
            catch(Exception ignored){}
        }

        // 3) 解码成 Bitmap
        Bitmap bmp=BitmapFactory.decodeByteArray(data,0,data.length);
        if(bmp==null) return null;
        faceServer.lastDecodeOk=true;

        // 4) EXIF 方向校正（侧拍/竖拍照片必须转正，否则引擎检不出或相似度低）
        bmp=correctOrientation(bmp,data);
        // 5) 过大则缩放，避免 OOM 并提升小脸检测质量
        bmp=downscaleIfTooLarge(bmp,1280);
        return bmp;
    }

    /** 去掉 "data:image/jpeg;base64," 前缀 */
    private static String stripDataUri(String s){
        int i=s.indexOf(',');
        if(i>0 && s.toLowerCase().startsWith("data:")) return s.substring(i+1);
        return s;
    }

    /** 容错 base64 解码：补 = 填充，处理 URL-safe 字符 */
    private static byte[] decodeBase64(String s){
        try{
            String t=s.replace("-","+").replace("_","/").trim();
            int pad=t.length()%4;
            if(pad==2) t+="=="; else if(pad==3) t+="=";
            return Base64.decode(t,Base64.DEFAULT);
        }catch(Exception e){ return null; }
    }

    /** 判断字节是否像 base64 文本（而非原始图片二进制） */
    private static boolean isBase64Text(byte[] d){
        if(d.length<8) return false;
        // 原始 JPG/PNG 的 magic，直接当二进制
        if((d[0]&0xFF)==0xFF && (d[1]&0xFF)==0xD8) return false; // JPEG
        if(d[0]==0x89 && d[1]==0x50) return false;                // PNG
        for(byte b: d){
            int c=b&0xFF;
            if(c==' '||c=='\n'||c=='\r'||c=='\t') continue;
            // 允许的 base64 字符集
            boolean ok=(c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')
                       ||c=='+'||c=='/'||c=='='||c=='-'||c=='_'||c==':'||c==','||c==';';
            if(!ok) return false;
        }
        return true;
    }

    /** 按 EXIF orientation 旋转 bitmap（官方 demo 同款逻辑） */
    private static Bitmap correctOrientation(Bitmap bmp, byte[] jpeg){
        try{
            ExifInterface exif=new ExifInterface(new ByteArrayInputStream(jpeg));
            int ori=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
            if(ori==ExifInterface.ORIENTATION_NORMAL) return bmp;
            Matrix m=new Matrix();
            if(ori==ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90);
            else if(ori==ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180);
            else if(ori==ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270);
            else return bmp;
            Bitmap r=Bitmap.createBitmap(bmp,0,0,bmp.getWidth(),bmp.getHeight(),m,true);
            bmp.recycle();
            return r;
        }catch(Exception e){ return bmp; }
    }

    /** 长边超过 maxDim 则等比缩小 */
    private static Bitmap downscaleIfTooLarge(Bitmap bmp,int maxDim){
        int w=bmp.getWidth(),h=bmp.getHeight();
        int max=Math.max(w,h);
        if(max<=maxDim) return bmp;
        float s=(float)maxDim/max;
        Bitmap r=Bitmap.createScaledBitmap(bmp,Math.round(w*s),Math.round(h*s),true);
        bmp.recycle();
        return r;
    }

    private String buildResult(Bitmap bmp){
        try{
            List<FaceServer.RecognizeResult> list=faceServer.recognize(bmp);
            JSONArray arr=new JSONArray();
            for(FaceServer.RecognizeResult r:list){
                boolean matched=r.score>=Constants.MATCH_THRESHOLD&&!"未知".equals(r.name);
                JSONObject o=new JSONObject();o.put("name",matched?r.name:"Unknown");
                o.put("matched",matched);o.put("score",round3(r.score));
                if(r.rect!=null){JSONObject rc=new JSONObject();
                   rc.put("left",r.rect.left);rc.put("top",r.rect.top);
                   rc.put("right",r.rect.right);rc.put("bottom",r.rect.bottom);
                   o.put("rect",rc);}
                arr.put(o);}
            JSONObject res=new JSONObject();
            res.put("count",list.size());
            res.put("faces",arr);
            // 诊断信息（排查"识别不正确"用）
            res.put("detect_code",faceServer.lastDetectCode);
            res.put("face_count",faceServer.lastFaceCount);
            res.put("threshold",Constants.MATCH_THRESHOLD);
            res.put("decode_ok",faceServer.lastDecodeOk);
            return res.toString();
        }catch(Exception e){return err(e.getMessage());}
    }

    /** 节点契约：memory-agent POST 图片 -> {faces:[{name,confidence,matched,rect}],count,node} */
    private String faceRecognize(byte[] body, String ct) throws Exception {
        Bitmap bmp = loadBitmap(body, ct);
        if (bmp == null) { faceServer.lastDecodeOk = false; return err("cannot decode image"); }
        List<FaceServer.RecognizeResult> list = faceServer.recognize(bmp);
        JSONArray arr = new JSONArray();
        for (FaceServer.RecognizeResult r : list) {
            boolean matched = r.score >= Constants.MATCH_THRESHOLD && !"未知".equals(r.name);
            JSONObject o = new JSONObject();
            o.put("name", matched ? r.name : "Unknown");
            o.put("confidence", round3(r.score));
            o.put("matched", matched);
            if (r.rect != null) {
                JSONObject rc = new JSONObject();
                rc.put("left", r.rect.left); rc.put("top", r.rect.top);
                rc.put("right", r.rect.right); rc.put("bottom", r.rect.bottom);
                o.put("rect", rc);
            }
            arr.put(o);
        }
        JSONObject res = new JSONObject();
        res.put("count", list.size());
        res.put("faces", arr);
        res.put("node", AppConfig.get(appContext).getNodeId());
        res.put("engine", "ArcSoft ArcFace 3.0");
        return res.toString();
    }

    /** 导出本机人脸库（姓名 + 特征）供 memory-agent 中央化。 */
    private String faceLibExport() throws Exception {
        List<FaceRegisterInfo> lib = faceServer.exportFaceLib();
        JSONObject o = new JSONObject();
        o.put("node_id", AppConfig.get(appContext).getNodeId());
        o.put("count", lib.size());
        JSONArray a = new JSONArray();
        for (FaceRegisterInfo i : lib) {
            JSONObject it = new JSONObject();
            it.put("name", i.getName());
            byte[] f = i.getFeatureData();
            if (f != null) it.put("feature", Base64.encodeToString(f, Base64.NO_WRAP));
            a.put(it);
        }
        o.put("members", a);
        return o.toString();
    }

    /** 从 memory-agent 导入一条人脸特征（下行同步）。 */
    private String faceLibImport(byte[] body) throws Exception {
        JSONObject jo = new JSONObject(new String(body, StandardCharsets.UTF_8));
        String name = jo.optString("name", "");
        String b64 = jo.optString("feature", "");
        if (name.isEmpty() || b64.isEmpty()) return err("missing name/feature");
        byte[] f = Base64.decode(b64, Base64.NO_WRAP);
        boolean ok = faceServer.addFaceFeature(f, name);
        JSONObject o = new JSONObject();
        o.put("success", ok);
        o.put("registered", faceServer.getFaceNumber());
        return o.toString();
    }

    private static String err(String msg){return "{\"error\":\""+msg.replace("\"","\\\"")+"\"}";}

    private long parseDuration(String rawUri, byte[] body) {
        long d = parseLongParam(rawUri, body, "duration", Constants.SCAN_DEFAULT_DURATION_MS);
        if (d > Constants.SCAN_MAX_DURATION_MS) d = Constants.SCAN_MAX_DURATION_MS;
        if (d < 500) d = 500;
        return d;
    }

    private long parseLongParam(String rawUri, byte[] body, String name, long def) {
        String v = extractParam(rawUri, name);
        if (v != null) { try { return Long.parseLong(v.trim()); } catch (NumberFormatException ignore) {} }
        String j = readJsonField(body, name);
        if (j != null) { try { return Long.parseLong(j.trim()); } catch (NumberFormatException ignore) {} }
        return def;
    }

    private float parseFloatParam(String rawUri, byte[] body, String name, float def) {
        String v = extractParam(rawUri, name);
        if (v != null) { try { return Float.parseFloat(v.trim()); } catch (NumberFormatException ignore) {} }
        String j = readJsonField(body, name);
        if (j != null) { try { return Float.parseFloat(j.trim()); } catch (NumberFormatException ignore) {} }
        return def;
    }

    private boolean parseBoolParam(String rawUri, byte[] body, String name) {
        String v = extractParam(rawUri, name);
        if (v != null) return "1".equals(v) || "true".equalsIgnoreCase(v);
        return readJsonBool(body, name);
    }

    private String readJsonField(byte[] body, String name) {
        if (body == null || body.length == 0) return null;
        try {
            JSONObject jo = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String s = jo.optString(name, null);
            return (s != null && !s.isEmpty()) ? s : null;
        } catch (Exception ignore) { return null; }
    }

    /** 解析请求体为 JSONObject：支持 application/json 或裸 JSON 文本。 */
    private static JSONObject parseJsonBody(byte[] body, String contentType) throws JSONException {
        if (body == null || body.length == 0) return new JSONObject();
        String text = new String(body, StandardCharsets.UTF_8).trim();
        return new JSONObject(text);
    }

    private boolean readJsonBool(byte[] body, String name) {
        if (body == null || body.length == 0) return false;
        try {
            JSONObject jo = new JSONObject(new String(body, StandardCharsets.UTF_8));
            return jo.optBoolean(name, false);
        } catch (Exception ignore) { return false; }
    }

    private static double round3(float v){return Math.round(v*1000d)/1000d;}
}
