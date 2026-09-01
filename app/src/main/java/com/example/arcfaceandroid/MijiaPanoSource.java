package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.example.arcfaceandroid.FaceServer.RecognizeResult;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 第二路（米家全景）取流 + 识别源。
 *
 * 【MJPEG 流模式】持续连接 go2rtc /api/stream.mjpeg 端点，解析 multipart 流中的
 * JPEG 帧，按识别间隔（pano_poll_ms，默认 500ms）抽帧识别。延迟 100-500ms，
 * 远优于旧 frame.jpeg 单帧模式（4-13s，需等关键帧）。
 *
 * 向后兼容：若 pano_url 配置的是 frame.jpeg，自动替换为 stream.mjpeg。
 * 流断开时自动重连（1s 退避）。
 *
 * 部署相关参数（pano_url / pano_user / pano_pass / pano_enabled 等）全部从
 * {@link AppConfig}（SharedPreferences）读取，支持 /api/config 热更新：
 * 保存配置后调用 {@link #onConfigChanged()} 即可 stop→restart 应用新参数。
 *
 * 设计要点：
 *  - 不依赖摄像头权限，息屏 TV 相机停时仍可运行（纯网络取流），照样知道"谁在房间"。
 *  - 不做跨摄像头坐标对齐：米家坐标不上 TV 预览（坐标系不同），仅用于按名融合的"房间状态"。
 *  - MJPEG 解析不依赖 boundary/Content-Length，用 SOI(0xFFD8)/EOI(0xFFD9) 界定帧，健壮性强。
 */
public class MijiaPanoSource {

    private static final String TAG = "MijiaPanoSource";
    private final Context appContext;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private long lastRecognizeMs = 0;
    private long lastFrameLatencyMs = 0; // 最近一帧的取帧+识别延迟（统计用）

    public MijiaPanoSource(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void start() {
        if (running.get()) return;
        AppConfig cfg = AppConfig.get(appContext);
        if (!cfg.isPanoEnabled()) {
            Log.i(TAG, "pano disabled in config, not starting");
            return;
        }
        if (cfg.getPanoUrl() == null || cfg.getPanoUrl().isEmpty()) {
            Log.w(TAG, "pano_url not configured, not starting");
            return;
        }
        running.set(true);
        lastRecognizeMs = 0;
        worker = new Thread(this::loop, "mijia-pano");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
        Log.i(TAG, "pano source started (MJPEG mode), url=" + toMjpegUrl(cfg.getPanoUrl()));
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (worker != null) worker.interrupt();
        RecognitionState.get().setPanoStatus(false, "米家全景已停止");
        Log.i(TAG, "pano source stopped");
    }

    /** 配置变更后调用：stop 并用新参数 restart（如果新配置中启用了米家路）。 */
    public void onConfigChanged() {
        stop();
        AppConfig cfg = AppConfig.get(appContext);
        if (cfg.isPanoEnabled() && cfg.getPanoUrl() != null && !cfg.getPanoUrl().isEmpty()) {
            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignore) {}
                start();
            }, "pano-restart").start();
        }
    }

    /** 将 frame.jpeg URL 自动转换为 stream.mjpeg（向后兼容旧配置）。 */
    private static String toMjpegUrl(String url) {
        if (url == null) return null;
        return url.replace("/api/frame.jpeg?", "/api/stream.mjpeg?");
    }

    private void loop() {
        while (running.get()) {
            AppConfig cfg = AppConfig.get(appContext);
            if (!cfg.isPanoEnabled() || cfg.getPanoUrl() == null || cfg.getPanoUrl().isEmpty()) {
                Log.i(TAG, "pano disabled or url cleared, exiting loop");
                break;
            }
            String auth = "Basic " + Base64.encodeToString(
                    (cfg.getPanoUser() + ":" + cfg.getPanoPass()).getBytes(), Base64.NO_WRAP);

            HttpURLConnection conn = null;
            BufferedInputStream in = null;
            try {
                // 建立 MJPEG 持久连接
                URL u = new URL(toMjpegUrl(cfg.getPanoUrl()));
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestProperty("Authorization", auth);
                conn.setConnectTimeout(cfg.getPanoFetchTimeoutMs());
                // MJPEG 流是持续的，readTimeout 设为 10s（10s 无数据则认为流断开，重连）
                conn.setReadTimeout(10000);
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "pano mjpeg http=" + code);
                    RecognitionState.get().setPanoStatus(false, "HTTP " + code);
                    sleepSafe(2000);
                    continue;
                }
                String contentType = conn.getContentType();
                Log.i(TAG, "mjpeg stream connected, content-type=" + contentType);
                in = new BufferedInputStream(conn.getInputStream(), 65536);

                // 帧读取循环：持续解析 JPEG 帧，按间隔识别
                byte[] frame;
                int frameCount = 0;
                while (running.get() && (frame = readJpegFrame(in)) != null) {
                    frameCount++;
                    long now = System.currentTimeMillis();
                    // 按识别间隔抽帧（MJPEG 流可能 15+ FPS，不需要每帧都识别）
                    if (now - lastRecognizeMs >= cfg.getPanoPollMs()) {
                        lastRecognizeMs = now;
                        boolean ok = recognizeFrame(frame, cfg);
                        lastFrameLatencyMs = System.currentTimeMillis() - now;
                        RecognitionState.get().setPanoStatus(ok,
                                ok ? "米家全景在线 (" + (lastFrameLatencyMs) + "ms)" : "识别失败");
                    }
                    // 每 100 帧打一次日志，确认流存活
                    if (frameCount % 100 == 0) {
                        Log.d(TAG, "mjpeg frames received: " + frameCount + ", last latency: " + lastFrameLatencyMs + "ms");
                    }
                }
                Log.w(TAG, "mjpeg stream ended, reconnecting...");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                if (running.get()) {
                    Log.w(TAG, "mjpeg error, reconnect in 1s", t);
                    RecognitionState.get().setPanoStatus(false, "重连中: " + t.getClass().getSimpleName());
                }
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignore) {}
                if (conn != null) conn.disconnect();
            }

            if (running.get()) {
                sleepSafe(1000); // 重连退避
            }
        }
        running.set(false);
    }

    /** 从 MJPEG 流中读取一个完整 JPEG 帧（用 SOI/EOI 界定，不依赖 boundary）。
     *  @return JPEG 字节数组，流结束返回 null */
    private static byte[] readJpegFrame(InputStream in) throws Exception {
        // 第一步：找到 SOI 标记 (0xFF 0xD8)
        int b;
        while ((b = in.read()) != -1) {
            if (b == 0xFF) {
                int b2 = in.read();
                if (b2 == -1) return null;
                if (b2 == 0xD8) {
                    // 找到 SOI，开始收集帧数据
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
                    baos.write(0xFF);
                    baos.write(0xD8);
                    int prev = 0xD8;
                    // 第二步：读取直到 EOI 标记 (0xFF 0xD9)
                    while ((b = in.read()) != -1) {
                        baos.write(b);
                        if (prev == 0xFF && b == 0xD9) {
                            return baos.toByteArray(); // 完整 JPEG 帧
                        }
                        prev = b;
                    }
                    return null; // 流在帧中间结束
                }
                // 不是 SOI，继续搜索（0xFF 后面可能是其他标记）
            }
        }
        return null; // 流结束
    }

    /** 解码 JPEG 帧并识别；成功返回 true 且已写入 RecognitionState.panoResults。 */
    private boolean recognizeFrame(byte[] jpeg, AppConfig cfg) {
        try {
            if (jpeg == null || jpeg.length < 64) return false;
            Bitmap raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (raw == null) return false;
            Bitmap bmp = downscale(raw, cfg.getPanoMaxW());
            if (bmp != raw) raw.recycle();
            if (bmp == null) return false;
            List<RecognizeResult> res = FaceServer.getInstance().recognize(bmp);
            bmp.recycle();
            if (res == null) res = new ArrayList<>();
            RecognitionState.get().setPanoResults(res);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "recognize frame error", t);
            return false;
        }
    }

    private static void sleepSafe(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static Bitmap downscale(Bitmap src, int maxW) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW) return src;
        int dw = maxW, dh = Math.round(h * ((float) maxW / w));
        return Bitmap.createScaledBitmap(src, dw, dh, true);
    }
}
