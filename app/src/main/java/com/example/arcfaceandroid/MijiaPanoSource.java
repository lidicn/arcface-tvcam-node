package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.example.arcfaceandroid.FaceServer.RecognizeResult;

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
 * 后台线程轮询 go2rtc HTTP 端点（frame.jpeg / 可选 MJPEG）取帧，解码后调用
 * {@link FaceServer#recognize(Bitmap)} 跑同一套 ArcFace 比对，结果按名写入
 * {@link RecognitionState} 的 pano 槽，与 TV 路做按名融合。
 *
 * 部署相关参数（pano_url / pano_user / pano_pass / pano_enabled 等）全部从
 * {@link AppConfig}（SharedPreferences）读取，支持 /api/config 热更新：
 * 保存配置后调用 {@link #onConfigChanged()} 即可 stop→restart 应用新参数。
 *
 * 设计要点：
 *  - 不依赖摄像头权限，息屏 TV 相机停时仍可运行（纯网络取流），照样知道"谁在房间"。
 *  - 不做跨摄像头坐标对齐：米家坐标不上 TV 预览（坐标系不同），仅用于按名融合的"房间状态"。
 */
public class MijiaPanoSource {

    private static final String TAG = "MijiaPanoSource";
    private final Context appContext;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

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
        worker = new Thread(this::loop, "mijia-pano");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
        Log.i(TAG, "pano source started, url=" + cfg.getPanoUrl());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (worker != null) worker.interrupt();
        RecognitionState.get().setPanoStatus(false, "米家全景已停止");
        Log.i(TAG, "pano source stopped");
    }

    /** 配置变更后调用：stop 并用新参数 restart（如果新配置中启用了米家路）。
     *  注意：之前未运行（pano_enabled=false）时，配置变更为启用后也必须启动，
     *  不能只靠 wasRunning 判断（否则首次启用时不会启动）。 */
    public void onConfigChanged() {
        stop();
        AppConfig cfg = AppConfig.get(appContext);
        if (cfg.isPanoEnabled() && cfg.getPanoUrl() != null && !cfg.getPanoUrl().isEmpty()) {
            // 稍等再启动，确保 stop 完成
            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignore) {}
                start();
            }, "pano-restart").start();
        }
    }

    private void loop() {
        while (running.get()) {
            AppConfig cfg = AppConfig.get(appContext);
            // 运行时检测：如果配置被禁用或 URL 清空，自动退出
            if (!cfg.isPanoEnabled() || cfg.getPanoUrl() == null || cfg.getPanoUrl().isEmpty()) {
                Log.i(TAG, "pano disabled or url cleared, exiting loop");
                break;
            }
            String auth = "Basic " + Base64.encodeToString(
                    (cfg.getPanoUser() + ":" + cfg.getPanoPass()).getBytes(), Base64.NO_WRAP);
            long t0 = System.currentTimeMillis();
            boolean ok = false;
            String statusMsg = "";
            try {
                ok = fetchAndRecognize(cfg, auth);
                if (!ok && running.get()) statusMsg = "取帧/识别失败";
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                Log.w(TAG, "pano loop error", t);
                statusMsg = "异常:" + t.getClass().getSimpleName();
                ok = false;
            }
            if (!running.get()) break;
            RecognitionState.get().setPanoStatus(ok, ok ? "米家全景在线" : statusMsg);
            // 节奏控制：实际间隔 = 取帧耗时 + POLL_MS（frame.jpeg 单次约 4~13s）
            long wait = cfg.getPanoPollMs() - (System.currentTimeMillis() - t0);
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        running.set(false);
    }

    /** 取一帧并识别；成功返回 true 且已写入 RecognitionState.panoResults。 */
    private boolean fetchAndRecognize(AppConfig cfg, String auth) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL u = new URL(cfg.getPanoUrl());
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("Authorization", auth);
            int timeout = cfg.getPanoFetchTimeoutMs();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "pano http=" + code);
                return false;
            }
            in = conn.getInputStream();
            byte[] jpg = readAll(in);
            if (jpg == null || jpg.length < 64) return false;
            Bitmap raw = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            if (raw == null) return false;
            Bitmap bmp = downscale(raw, cfg.getPanoMaxW());
            if (bmp != raw) raw.recycle();
            if (bmp == null) return false;
            List<RecognizeResult> res = FaceServer.getInstance().recognize(bmp);
            bmp.recycle();
            if (res == null) res = new ArrayList<>();
            RecognitionState.get().setPanoResults(res);
            return true;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        return os.toByteArray();
    }

    private static Bitmap downscale(Bitmap src, int maxW) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW) return src;
        int dw = maxW, dh = Math.round(h * ((float) maxW / w));
        return Bitmap.createScaledBitmap(src, dw, dh, true);
    }
}
