package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真实摄像头取流实现：基于 Android 标准 Camera2 API 打开系统摄像头。
 *
 * 适配说明：腾讯会议等第三方 App 在 Android 上只能通过 Camera2 拿到摄像头画面，
 * 既然腾讯会议能用这台电视的 USB 摄像头，说明 TVOS 已把它作为「系统 Camera」暴露。
 * 因此直接用 Camera2，零额外 UVC 依赖。
 *
 * 与 UI 解耦：本类不再内部持有 TextureView。预览 SurfaceTexture 由前台 UI 通过
 * {@link #setPreviewTexture(SurfaceTexture)} 注入；切到别的 App 时 UI 调用
 * {@link #clearPreviewTexture()} 退化为「纯识别流」（仅 ImageReader，无预览），
 * 识别在 Service 内继续。相机归属 Service，生命周期独立于 Activity。
 */
public class Camera2CaptureSource implements CameraCaptureSource {

    private static final String TAG = "Camera2CaptureSource";
    private static final int REQ_W = 1920;
    private static final int REQ_H = 1080;
    private static final int FALLBACK_W = 1280;
    private static final int FALLBACK_H = 720;
    private static final int MAX_DIM = 1920; // 不超过 1080p，避免 4K 拉垮性能

    private boolean useFallback = false; // 1080p 会话配不通时退化为 720p
    private boolean firstFrameLogged = false; // 仅记录一次真实帧尺寸
    private boolean fastMode = false; // 扫描期提帧频

    private final Context context;
    private final AtomicBoolean opened = new AtomicBoolean(false);
    private final AtomicBoolean starting = new AtomicBoolean(false);

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private android.hardware.camera2.CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Surface imageSurface;
    private SurfaceTexture previewTexture; // 预览 SurfaceTexture（可选；UI 在前景时挂接）
    private int sessionGen = 0; // 会话代次，用于忽略已被取代的过期 onConfigured 回调

    private String cameraId;
    private int frameW = REQ_W;
    private int frameH = REQ_H;

    private HandlerThread thread;
    private Handler handler;
    private FrameListener frameListener;
    private long lastPushMs = 0;

    public Camera2CaptureSource(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void start(FrameListener listener) {
        this.frameListener = listener;
        if (starting.compareAndSet(false, true)) {
            thread = new HandlerThread("camera2");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(this::openCamera);
        }
    }

    @Override
    public void stop() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "stop session", t);
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "stop device", t);
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "stop reader", t);
        }
        previewSurface = null;
        previewTexture = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        opened.set(false);
        starting.set(false);
    }

    /** UI 在前景时注入预览 SurfaceTexture；传 null 表示移除预览（退到纯识别流） */
    public void setPreviewTexture(SurfaceTexture st) {
        this.previewTexture = st;
        if (st != null && cameraDevice != null) {
            rebuildSession();
        }
    }

    public void clearPreviewTexture() {
        this.previewTexture = null;
        if (cameraDevice != null) {
            rebuildSession();
        }
    }

    @Override
    public boolean isOpened() {
        return opened.get();
    }

    /** 扫描期提帧频：推送节流从 100ms 降到 50ms。 */
    public void setFastMode(boolean fast) { this.fastMode = fast; }

    private void openCamera() {
        try {
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            cameraId = chooseCamera(cameraManager);
            if (cameraId == null) {
                Log.e(TAG, "openCamera: no camera found");
                starting.set(false);
                return;
            }
            Log.i(TAG, "openCamera: using cameraId=" + cameraId + " " + frameW + "x" + frameH);
            cameraManager.openCamera(cameraId, deviceStateCallback, handler);
        } catch (CameraAccessException | SecurityException e) {
            // SecurityException: 未授予 CAMERA 权限（Fragment 负责申请，授权后会重新 start）
            Log.e(TAG, "openCamera failed", e);
            starting.set(false);
        }
    }

    /** 选择摄像头：优先外接(EXTERNAL, USB 摄像头)，其次前置、后置 */
    private String chooseCamera(CameraManager cm) throws CameraAccessException {
        String[] ids = cm.getCameraIdList();
        if (ids.length == 0) return null;
        int[] preference = new int[]{
                CameraCharacteristics.LENS_FACING_EXTERNAL,
                CameraCharacteristics.LENS_FACING_FRONT,
                CameraCharacteristics.LENS_FACING_BACK
        };
        for (int facing : preference) {
            for (String id : ids) {
                Integer f = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) {
                    pickSize(cm, id);
                    return id;
                }
            }
        }
        pickSize(cm, ids[0]);
        return ids[0];
    }

    private void pickSize(CameraManager cm, String id) throws CameraAccessException {
        CameraCharacteristics ch = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return;
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) return;
        final int tw = useFallback ? FALLBACK_W : REQ_W;
        final int th = useFallback ? FALLBACK_H : REQ_H;
        final int target = tw * th;
        Size best = sizes[0];
        int bestDiff = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int w = s.getWidth(), h = s.getHeight();
            if (w > MAX_DIM || h > MAX_DIM) continue;
            int diff = Math.abs(w * h - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        frameW = best.getWidth();
        frameH = best.getHeight();
    }

    private final CameraDevice.StateCallback deviceStateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    Log.i(TAG, "camera opened");
                    tryStartSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.w(TAG, "camera disconnected");
                    opened.set(false);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "camera error " + error);
                    opened.set(false);
                }
            };

    private void ensureImageReader() {
        if (imageReader == null) {
            imageReader = ImageReader.newInstance(frameW, frameH, ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(imageAvailableListener, handler);
            imageSurface = imageReader.getSurface();
        }
    }

    private void tryStartSession() {
        if (cameraDevice == null || captureSession != null) return;
        try {
            ensureImageReader();
            ArrayList<Surface> targets = new ArrayList<>();
            targets.add(imageSurface); // 识别流永远需要
            if (previewTexture != null) {
                previewTexture.setDefaultBufferSize(frameW, frameH);
                previewSurface = new Surface(previewTexture);
                targets.add(previewSurface);
            } else {
                previewSurface = null;
            }
            final int gen = ++sessionGen;
            cameraDevice.createCaptureSession(targets,
                    new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(android.hardware.camera2.CameraCaptureSession session) {
                            if (gen != sessionGen) return; // 已被更新的会话取代，忽略过期回调
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                if (previewSurface != null) builder.addTarget(previewSurface);
                                builder.addTarget(imageSurface);
                                session.setRepeatingRequest(builder.build(), null, handler);
                                opened.set(true);
                                Log.i(TAG, "session started, preview running");
                            } catch (Throwable t) {
                                Log.e(TAG, "start preview failed", t);
                            }
                        }

                        @Override
                        public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession session) {
                            if (gen != sessionGen) return;
                            Log.e(TAG, "session configure failed");
                            opened.set(false);
                            if (!useFallback) {
                                useFallback = true;
                                Log.w(TAG, "1080p session failed, fallback to " + FALLBACK_W + "x" + FALLBACK_H);
                                try {
                                    if (imageReader != null) imageReader.close();
                                } catch (Throwable ignore) {
                                }
                                imageReader = null;
                                captureSession = null;
                                if (cameraDevice != null) {
                                    try {
                                        cameraDevice.close();
                                    } catch (Throwable ignore) {
                                    }
                                    cameraDevice = null;
                                }
                                handler.post(() -> {
                                    try {
                                        cameraId = chooseCamera(cameraManager);
                                        if (cameraId != null) {
                                            cameraManager.openCamera(cameraId, deviceStateCallback, handler);
                                        }
                                    } catch (Throwable t) {
                                        Log.e(TAG, "fallback reopen failed", t);
                                    }
                                });
                            }
                        }
                    }, handler);
        } catch (Throwable t) {
            Log.e(TAG, "tryStartSession failed", t);
        }
    }

    /** 预览 SurfaceTexture 增删时重建会话（不重新打开摄像头） */
    private void rebuildSession() {
        if (captureSession == null) {
            tryStartSession();
            return;
        }
        try {
            captureSession.close();
        } catch (Throwable ignore) {
        }
        captureSession = null;
        handler.post(this::tryStartSession);
    }

    private final ImageReader.OnImageAvailableListener imageAvailableListener =
            reader -> {
                Image image = reader.acquireLatestImage();
                if (image == null) return;
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    Log.i(TAG, "first frame delivered: " + image.getWidth() + "x" + image.getHeight());
                }
                try {
                    long now = System.currentTimeMillis();
                    int pushInterval = fastMode ? 50 : 100;
                    if (now - lastPushMs < pushInterval) return; // 节流推送（扫描期更频繁）
                    lastPushMs = now;
                    byte[] nv21 = yuv420ToNv21(image, frameW, frameH);
                    if (frameListener != null) {
                        frameListener.onFrame(nv21, frameW, frameH);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "frame callback error", t);
                } finally {
                    image.close();
                }
            };

    /** YUV_420_888（多平面，像素步长可能为 1 或 2）转 NV21（Y + VU 交错） */
    private static byte[] yuv420ToNv21(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPix = planes[2].getPixelStride();

        int ySize = width * height;
        byte[] nv21 = new byte[ySize + ySize / 2];

        // Y 平面
        if (yPix == 1 && yRow == width) {
            yBuf.get(nv21, 0, ySize);
        } else {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    nv21[y * width + x] = yBuf.get(y * yRow + x * yPix);
                }
            }
        }

        // UV 平面：NV21 为 [V U V U ...] 逐行交错
        int uvOff = ySize;
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width / 2; x++) {
                byte v = vBuf.get(y * vRow + x * vPix);
                byte u = uBuf.get(y * uRow + x * uPix);
                nv21[uvOff++] = v;
                nv21[uvOff++] = u;
            }
        }
        return nv21;
    }
}
