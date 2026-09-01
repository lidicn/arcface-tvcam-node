package com.example.arcfaceandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.arcfaceandroid.FaceServer.RecognizeResult;

import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import com.arcsoft.face.FaceInfo;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 常驻前台服务：提供 HTTP 接口 + 音频保活 + 摄像头识别。
 *
 * 关键重构：摄像头与识别逻辑从 TvCameraFragment 搬到这里，由 Service 持有，
 * 因此「离开本 App 切到别的界面」时相机仍开、识别持续；仅在熄屏时停相机（用户选择）。
 * 预览 SurfaceTexture 由前台 UI（TvCameraFragment）通过 Binder 注入；切走时 UI 移除预览，
 * 退化为纯 ImageReader 识别流。识别结果发布到 {@link RecognitionState} 供 UI 画框。
 */
public class FaceServerService extends Service {

    private static final String TAG = "FaceServerService";
    private static final String CHANNEL_ID = "face_server_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long ANALYSIS_INTERVAL_MS = 350;
    /** 扫描式识别期间的加速分析间隔 */
    private static final long SCAN_FAST_ANALYSIS_INTERVAL_MS = 120;
    /** 当前分析间隔（可被扫描临时调小以多采样帧）。volatile 保证 frameListener 读到最新值。 */
    private static volatile long analysisIntervalMs = ANALYSIS_INTERVAL_MS;

    /** 扫描期把分析间隔降到 SCAN_FAST_ANALYSIS_INTERVAL_MS，结束后恢复。 */
    public static void setScanAnalysisBoost(boolean boost) {
        analysisIntervalMs = boost ? SCAN_FAST_ANALYSIS_INTERVAL_MS : ANALYSIS_INTERVAL_MS;
        if (instance != null) instance.notifyScanBoost(boost);
    }

    /** 扫描期强制全图检测：使热点框外的多人也能被同时检出。开始/结束扫描时由 ScanManager 调用。 */
    public void setForceFullScan(boolean on) {
        forceFullScan = on;
    }

    /** 扫描期让摄像头推送更频繁（配合分析间隔提速）。 */
    void notifyScanBoost(boolean boost) {
        if (source != null) source.setFastMode(boost);
    }

    // 供 KeepAliveWorker 看门狗使用
    public static volatile boolean userRequestedRunning = false;
    public static volatile boolean isRunning = false;
    /** 单例引用，供 ScanManager / FaceHttpServer 访问服务（摄像头状态查询等）。 */
    public static volatile FaceServerService instance;
    public static FaceServerService getInstance() { return instance; }

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private FaceHttpServer mHttpServer;
    private boolean httpRunning = false;

    /** 人脸节点池客户端：向 memory-agent 注册 / 心跳 / 下行同步人脸库 */
    private FaceNodeClient nodeClient;
    /** 服务内自愈巡检：HTTP 端口 / 唤醒锁丢失时当场自救（比进程级看门狗更快） */
    private final ScheduledExecutorService selfHealExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "face-selfheal");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private PowerManager.WakeLock mWakeLock;

    // 识别相关
    private Camera2CaptureSource source;
    /** 第二路（米家全景）取流识别源，与 TV 路按名融合 */
    private MijiaPanoSource pano;
    private final SmartZoomController zoom = new SmartZoomController();
    private final ScheduledExecutorService analysisExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "face-analysis");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private volatile boolean analyzing = false;
    private long lastAnalysisMs = 0;
    private final AtomicBoolean cameraRunning = new AtomicBoolean(false);
    /** 扫描主动请求时临时绕过息屏门禁开相机（扫描结束/熄屏后恢复）。 */
    private volatile boolean forceCameraForScan = false;

    // ===== hotspot / ROI 提速 =====
    private static final long FEAT_CACHE_MS = 800;     // 跨帧特征缓存有效期（ms）
    private static final int FULL_SCAN_EVERY = 6;       // 每 N 帧强制全图兜底（发现新位置/新人）
    private static final int ROI_NOFACE_FULL = 8;       // ROI 模式连续无脸 N 帧后全图兜底
    private static final int FULL_BURST_FRAMES = 6;     // 触发全图后连续爆发的全图帧数（持续搜到新位置）
    /** 跨帧特征缓存：用中心距离关联同一人，有效期内复用比对结果、不重复提特征。 */
    private final List<CachedFace> faceCache = new ArrayList<>();
    private long analyzeFrameCount = 0;
    private int noFaceRoiStreak = 0;
    private int fullBurst = 0;                          // 剩余全图爆发帧数
    private volatile boolean forceFullScan = false;     // 扫描期强制全图检测（保证同时框内外多人均被检出）

    private static final class CachedFace {
        Rect rect;
        int faceId = -1;
        String name;
        float score;
        long ts;
    }

    private final CameraCaptureSource.FrameListener frameListener =
            (nv21, w, h) -> {
                FrameBuffer.get().pushFrame(nv21, w, h, FrameBuffer.FORMAT_NV21);
                long now = System.currentTimeMillis();
                if (now - lastAnalysisMs < analysisIntervalMs) return;  // 节流：过快的帧跳过分析（可临时加速）
                lastAnalysisMs = now;
                if (analyzing) return;
                analyzing = true;
                analysisExec.execute(() -> {
                    try {
                        analyze();
                    } finally {
                        analyzing = false;
                    }
                });
            };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                Log.i(TAG, "screen off -> stop camera");
                stopCamera();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                Log.i(TAG, "screen on -> start camera if permitted");
                startCameraIfAllowed();
            }
        }
    };

    public class FaceServerBinder extends Binder {
        public FaceServerService getService() {
            return FaceServerService.this;
        }

        public void setPreviewSurfaceTexture(SurfaceTexture st) {
            if (source != null) source.setPreviewTexture(st);
        }

        public void clearPreviewSurfaceTexture() {
            if (source != null) source.clearPreviewTexture();
        }

        public void startCamera() {
            startCameraIfAllowed();
        }

        public boolean isPhysicalZoom() {
            return zoom.isPhysicalZoomSupported();
        }
    }

    private final IBinder binder = new FaceServerBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startHttp();
        acquireWakeLock();
        startForegroundSafe(buildNotification("ArcFace 电视服务运行中"));
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandlerThread = new HandlerThread("face-server");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        FaceServer.getInstance().init(this);
        HotspotManager.get().init(this);
        isRunning = true;
        instance = this;
        userRequestedRunning = true;

        // 摄像头：前台 Service 持有，离开本 App 也持续识别（熄屏则停）
        source = new Camera2CaptureSource(getApplicationContext());
        zoom.attach(null); // Camera2 走数字变焦兜底
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);

        startCameraIfAllowed();

        // 第二路：米家全景补充识别（网络取流，息屏 TV 相机停时仍可运行）
        pano = new MijiaPanoSource(getApplicationContext());
        pano.start();

        // 人脸节点池：注册到 memory-agent + 心跳 + 下行同步人脸库
        nodeClient = new FaceNodeClient(this);
        nodeClient.start();

        // 配置热更新：WebUI /api/config 保存后，自动应用新参数（米家启停、节点重注册）
        AppConfig.get(this).setListener(changedKeys -> {
            if (changedKeys.contains(AppConfig.KEY_PANO_ENABLED)
                    || changedKeys.contains(AppConfig.KEY_PANO_URL)
                    || changedKeys.contains(AppConfig.KEY_PANO_USER)
                    || changedKeys.contains(AppConfig.KEY_PANO_PASS)
                    || changedKeys.contains(AppConfig.KEY_PANO_POLL_MS)
                    || changedKeys.contains(AppConfig.KEY_PANO_FETCH_TIMEOUT_MS)
                    || changedKeys.contains(AppConfig.KEY_PANO_MAX_W)) {
                if (pano != null) pano.onConfigChanged();
            }
            if (changedKeys.contains(AppConfig.KEY_MEMORY_AGENT_BASE)
                    || changedKeys.contains(AppConfig.KEY_NODE_ID)
                    || changedKeys.contains(AppConfig.KEY_NODE_TYPE)
                    || changedKeys.contains(AppConfig.KEY_NODE_ENDPOINT)) {
                if (nodeClient != null) nodeClient.onConfigChanged();
            }
        });
        // 服务内自愈：HTTP 端口 / 唤醒锁丢失时当场恢复
        selfHealExec.scheduleWithFixedDelay(this::selfHeal,
                Constants.SELF_HEAL_INTERVAL_MS, Constants.SELF_HEAL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // 再武装 AlarmManager 看门狗（进程被杀后更快拉起，与 WorkManager 互补）
        AlarmWatchdog.schedule(this);
    }

    private void startCameraIfAllowed() {
        if (!isScreenOn() && !forceCameraForScan) {
            Log.i(TAG, "screen off, defer camera start");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "camera permission not granted, recognition paused");
            RecognitionState.get().setCameraStatus(false, "未授权摄像头");
            return;
        }
        if (cameraRunning.get() && source != null && source.isOpened()) {
            return; // 已在运行，无需重启
        }
        cameraRunning.set(true);
        if (source != null && source.isOpened()) {
            source.stop(); // 状态不一致时先清理再开
        }
        source.start(frameListener);
        RecognitionState.get().setCameraStatus(true, "摄像头启动中");
    }

    private void stopCamera() {
        if (!cameraRunning.get()) return;
        cameraRunning.set(false);
        if (source != null) source.stop();
        RecognitionState.get().setCameraStatus(false, "摄像头已停止");
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    /**
     * 摄像头是否真正可取帧。
     * 仅看 cameraRunning/source.isOpened() 不够：Camera2 打开到 capture session 建好
     * 有数秒到数十秒延迟，此期间声称“就绪”会让扫描白白空转整个窗口后返回 no_face。
     * 故以“最近是否真的出过帧”（RecognitionState.lastUpdateMs 新鲜度）为准。
     */
    public boolean isCameraReady() {
        if (!cameraRunning.get() || source == null || !source.isOpened()) return false;
        long last = RecognitionState.get().getLastUpdateMs();
        if (last <= 0) return false;
        return (System.currentTimeMillis() - last) <= Constants.SCAN_FRAME_FRESH_MS;
    }

    /**
     * 确保摄像头可取帧：已就绪直接返回 true；否则请求启动并轮询等待最多 waitMs。
     * 息屏（门禁不允许开摄像头）时不空等，立即返回 false，由调用方返 503。
     */
    public boolean ensureCameraReady(long waitMs) {
        if (isCameraReady()) return true;
        // 主动扫描请求：临时绕过息屏门禁开相机（WakeLock 已保活 CPU），方便无人值守时按需识别
        forceCameraForScan = true;
        startCameraIfAllowed();
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (isCameraReady()) {
                Log.i(TAG, "camera became ready after "
                        + (waitMs - (deadline - System.currentTimeMillis())) + "ms");
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.w(TAG, "camera still not ready after " + waitMs + "ms");
        return isCameraReady();
    }

    /** 请求启动摄像头（受屏幕状态门禁约束；息屏时可能仍失败，由调用方判 503）。 */
    public void requestCameraStart() {
        startCameraIfAllowed();
    }

    public boolean isPhysicalZoom() {
        return zoom.isPhysicalZoomSupported();
    }

    public void setZoomEnabled(boolean e) {
        zoom.setEnabled(e);
    }

    public boolean isZoomEnabled() {
        return zoom.isEnabled();
    }

    /** 抓一帧当前画面存为 JPEG（存到应用私有 Pictures 目录，无需存储权限） */
    public void takePhoto() {
        byte[] nv21 = FrameBuffer.get().takeCopy();
        if (nv21 == null) {
            Log.w(TAG, "takePhoto: no frame");
            return;
        }
        int w = FrameBuffer.get().getFrameWidth();
        int h = FrameBuffer.get().getFrameHeight();
        Bitmap bmp = toBitmap(nv21, w, h);
        if (bmp == null) return;
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "arcface");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "takePhoto: cannot create dir");
        }
        File f = new File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            Log.i(TAG, "takePhoto saved: " + f.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "takePhoto failed", e);
        } finally {
            bmp.recycle();
        }
    }

    private void analyze() {
        byte[] nv21 = FrameBuffer.get().takeCopy();
        if (nv21 == null) return;
        int w = FrameBuffer.get().getFrameWidth();
        int h = FrameBuffer.get().getFrameHeight();
        if (w <= 0 || h <= 0) return;
        long t0 = System.currentTimeMillis();

        analyzeFrameCount++;
        HotspotManager hs = HotspotManager.get();
        boolean useRoi = hs.hasHotspots();
        List<Rect> rois = useRoi ? hs.toPixelRois(w, h) : null;

        boolean fullScan;
        if (!useRoi || rois == null || rois.isEmpty()) {
            fullScan = true;
        } else if (forceFullScan) {
            fullScan = true;                             // 扫描期强制全图，覆盖热点框外的多人
        } else if (fullBurst > 0) {
            fullScan = true;
            fullBurst--;                         // 消费一帧爆发
        } else if (analyzeFrameCount % FULL_SCAN_EVERY == 0) {
            fullScan = true;                             // 周期全图兜底，发现站新位置的人
        } else {
            fullScan = noFaceRoiStreak >= ROI_NOFACE_FULL;
        }

        FaceServer fs = FaceServer.getInstance();
        List<FaceInfo> faces;
        if (fullScan) {
            faces = fs.detectFacesOnly(nv21, w, h, null);
        } else {
            Rect union = unionRois(rois, w, h);          // 已知热点并集一次检测，同框多人（含两热点间空隙）一次出齐
            faces = fs.detectFacesOnly(nv21, w, h, union);
        }

        // 质量门控：过滤掉过小的人脸（噪点/远处误检），减少“未知”闪烁与无效提特征
        if (faces != null && !faces.isEmpty()) {
            List<FaceInfo> kept = new ArrayList<>();
            for (FaceInfo f : faces) {
                Rect r = f.getRect();
                if (r != null && Math.min(r.width(), r.height()) >= Constants.MIN_FACE_PX) kept.add(f);
            }
            faces = kept;
        }

        // 上报热点（用检测到的脸更新房间布局）
        if (faces != null) {
            for (FaceInfo fi : faces) {
                if (fi.getRect() != null) hs.update(fi.getRect(), w, h);
            }
        }

        // 触发式全图：已知多个热点却只检到更少的人（有人可能站框外），短爆发全图兜底
        if (!fullScan && faces != null && faces.size() > 0 && rois != null
                && faces.size() < rois.size()) {
            fullBurst = Math.max(fullBurst, FULL_BURST_FRAMES);
        }

        // 跨帧特征缓存：同人有效期内复用比对结果，不重复提特征
        List<RecognizeResult> results = new ArrayList<>();
        int featCalls = 0;
        if (faces != null) {
            for (FaceInfo fi : faces) {
                Rect r = fi.getRect();
                CachedFace cached = matchCache(r, fi.getFaceId(), w);
                long now = System.currentTimeMillis();
                RecognizeResult rr;
                if (cached != null && (now - cached.ts) < FEAT_CACHE_MS) {
                    rr = new RecognizeResult();
                    rr.rect = r;
                    rr.faceId = fi.getFaceId();
                    rr.name = cached.name;
                    rr.score = cached.score;
                } else {
                    rr = fs.featureAndCompare(nv21, w, h, fi);
                    putCache(rr.rect, rr.faceId, rr.name, rr.score, w);
                    featCalls++;
                }
                results.add(rr);
            }
        }

        // 无脸计数 / 全图爆发：发现脸则清零；全图仍无脸则续命爆发；ROI 无脸累计到阈值触发爆发
        if (!results.isEmpty()) {
            noFaceRoiStreak = 0;
            fullBurst = 0;
        } else if (fullScan) {
            if (noFaceRoiStreak >= ROI_NOFACE_FULL) fullBurst = FULL_BURST_FRAMES;
        } else {
            noFaceRoiStreak++;
            if (noFaceRoiStreak >= ROI_NOFACE_FULL) fullBurst = FULL_BURST_FRAMES;
        }

        long cost = System.currentTimeMillis() - t0;
        if (!results.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (RecognizeResult r : results) {
                sb.append(r.name).append(':').append(Math.round(r.score * 100)).append("% ");
            }
            Log.i(TAG, "RECOGNIZED faces=" + results.size() + " -> " + sb
                    + " [cost=" + cost + "ms mode=" + (fullScan ? "full" : "roi")
                    + " feat=" + featCalls + " hs=" + hs.count() + "]");
        }
        zoom.update(results, w, h);
        RecognitionState.get().setResults(results, w, h, zoom.getTransform());
    }

    /** 多个热点 ROI 的并集（略外扩），供多人同帧一次检测，覆盖两热点间空隙。 */
    private static Rect unionRois(List<Rect> rois, int w, int h) {
        int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
        for (Rect roi : rois) {
            if (roi.left < l) l = roi.left;
            if (roi.top < t) t = roi.top;
            if (roi.right > r) r = roi.right;
            if (roi.bottom > b) b = roi.bottom;
        }
        int ex = (int) ((r - l) * 0.08f), ey = (int) ((b - t) * 0.08f);
        l = Math.max(0, l - ex);
        t = Math.max(0, t - ey);
        r = Math.min(w, r + ex);
        b = Math.min(h, b + ey);
        l &= ~1; t &= ~1; r &= ~1; b &= ~1;
        if (r - l < 4) r = Math.min(w, l + 4);
        if (b - t < 4) b = Math.min(h, t + 4);
        return new Rect(l, t, r, b);
    }

    /** 关联：视频模式下 faceId 稳定，优先按 faceId 命中（更快更准）；否则回退中心距离（阈值=帧宽10%）。 */
    private CachedFace matchCache(Rect r, int faceId, int w) {
        if (r == null) return null;
        if (faceId > 0) {
            for (CachedFace c : faceCache) if (c.faceId == faceId) return c;
        }
        int cx = r.centerX(), cy = r.centerY();
        int thr = (int) (w * 0.1f);
        CachedFace best = null;
        float bestD = Float.MAX_VALUE;
        for (CachedFace c : faceCache) {
            if (c.rect == null) continue;
            float d = (float) Math.hypot(cx - c.rect.centerX(), cy - c.rect.centerY());
            if (d < bestD) { bestD = d; best = c; }
        }
        return (best != null && bestD < thr) ? best : null;
    }

    private void putCache(Rect r, int faceId, String name, float score, int w) {
        CachedFace c = matchCache(r, faceId, w);
        if (c != null) {
            c.rect = r; c.faceId = faceId; c.name = name; c.score = score; c.ts = System.currentTimeMillis();
        } else {
            CachedFace nc = new CachedFace();
            nc.rect = r; nc.faceId = faceId; nc.name = name; nc.score = score; nc.ts = System.currentTimeMillis();
            faceCache.add(nc);
        }
        long now = System.currentTimeMillis();
        faceCache.removeIf(e -> now - e.ts > FEAT_CACHE_MS * 3);
    }

    /** 立即对当前最新帧跑一次识别（扫描触发时用，省去等待下一个节流窗口）。仅相机就绪时有效。 */
    public void requestImmediateAnalyze() {
        if (!cameraRunning.get() || source == null || !source.isOpened()) return;
        if (analyzing) return;
        analyzing = true;
        analysisExec.execute(() -> {
            try { analyze(); } finally { analyzing = false; }
        });
    }

    private static Bitmap toBitmap(byte[] nv21, int w, int h) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), 90, os);
            byte[] jpeg = os.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            int bw = ensureMultipleOf4(bitmap.getWidth());
            int bh = ensureMultipleOf4(bitmap.getHeight());
            if (bw != bitmap.getWidth() || bh != bitmap.getHeight()) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, bw, bh, true);
                bitmap.recycle();
                return scaled;
            }
            return bitmap;
        } catch (Throwable t) {
            Log.e(TAG, "toBitmap failed", t);
            return null;
        }
    }

    private static int ensureMultipleOf4(int v) {
        return v % 4 == 0 ? v : (v / 4 + 1) * 4;
    }

    private void startForegroundSafe(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startHttp() {
        if (httpRunning) return;
        try {
            mHttpServer = new FaceHttpServer(Constants.SERVER_PORT, getApplicationContext(), FaceServer.getInstance());
            mHttpServer.start();
            httpRunning = true;
            Log.i(TAG, "http started");
        } catch (IOException e) {
            Log.e(TAG, "http start failed", e);
        }
    }

    private void stopHttp() {
        if (mHttpServer != null) {
            mHttpServer.stop();
            mHttpServer = null;
        }
        httpRunning = false;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "人脸识别服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("常驻后台，提供人脸识别与 HTTP 接口");
            nm.createNotificationChannel(ch);
        }
        Intent intent = new Intent(this, TvMainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ArcFace 电视人脸识别")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArcFace:FaceServer");
            mWakeLock.acquire(10 * 60 * 1000L); // 10 分钟内保持 CPU 唤醒，便于后台识别
        }
    }

    /** 用户划掉任务也尽量保活：立即以前台服务重启自身。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            Intent svc = new Intent(this, FaceServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable ignore) {}
        super.onTaskRemoved(rootIntent);
    }

    /** 服务内自愈：HTTP 端口死了就重建监听，唤醒锁丢了就重新获取。 */
    private void selfHeal() {
        try {
            if (mHttpServer != null && !mHttpServer.isListening()) {
                Log.w(TAG, "self-heal: http not listening, restart");
                mHttpServer.restart();
            }
            if (mWakeLock == null || !mWakeLock.isHeld()) {
                Log.w(TAG, "self-heal: wakelock lost, reacquire");
                acquireWakeLock();
            }
        } catch (Throwable t) {
            Log.e(TAG, "self-heal error", t);
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        instance = null;
        if (nodeClient != null) { nodeClient.stop(); nodeClient = null; }
        if (!selfHealExec.isShutdown()) selfHealExec.shutdownNow();
        stopCamera();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignore) {
        }
        if (!analysisExec.isShutdown()) analysisExec.shutdownNow();
        if (pano != null) pano.stop();
        stopHttp();
        if (mHandlerThread != null) mHandlerThread.quitSafely();
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
        super.onDestroy();
    }
}
