package com.example.arcfaceandroid;

import android.graphics.Rect;
import android.util.Log;

import com.example.arcfaceandroid.FaceServer.RecognizeResult;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 智能变焦控制器：物理优先、数字兜底。
 *
 *  - 物理变焦：摄像头打开后反射探测 {@code ICamera.setZoom(int)}，若支持则根据
 *    最大人脸占比平滑驱动摄像头 zoom，让画面中人脸始终居中且大小合适。
 *  - 数字变焦（兜底）：廉价 UVC 头大多不支持 PTZ/PU 变焦，此时以最大人脸为中心
 *    对预览做缩放变换（不影响送引擎的整帧，保证识别率），让大屏上人脸更醒目。
 *
 * 注：PPTZ(pan/tilt) 需摄像头厂商私有 UVC 指令，通用库无法保证，故此处聚焦 zoom。
 */
public class SmartZoomController {

    private static final String TAG = "SmartZoomController";

    /** 期望人脸占画面宽度比例 */
    private static final float TARGET_FACE_RATIO = 0.32f;
    /** 数字变焦上限（避免放大到满屏丢失上下文） */
    private static final float MAX_DIGITAL = 2.2f;

    private Object camera;
    private Method setZoomMethod;
    private boolean physicalZoomSupported;

    private float scale = 1f;
    private float pivotX = 0.5f;
    private float pivotY = 0.5f;
    private boolean enabled = true;

    public void setEnabled(boolean e) { this.enabled = e; }
    public boolean isEnabled() { return enabled; }

    /** 绑定摄像头实例，探测物理变焦能力 */
    public void attach(Object cameraObj) {
        this.camera = cameraObj;
        this.physicalZoomSupported = false;
        this.setZoomMethod = null;
        if (cameraObj != null) {
            try {
                Method m = cameraObj.getClass().getMethod("setZoom", int.class);
                if (m != null) {
                    setZoomMethod = m;
                    physicalZoomSupported = true;
                }
            } catch (NoSuchMethodException e) {
                Log.i(TAG, "该摄像头不支持 setZoom，将使用数字变焦兜底");
            }
        }
    }

    public void detach() {
        this.camera = null;
        this.setZoomMethod = null;
        this.physicalZoomSupported = false;
    }

    public boolean isPhysicalZoomSupported() {
        return physicalZoomSupported;
    }

    /** 每帧识别后调用，更新变焦状态 */
    public void update(List<RecognizeResult> faces, int frameW, int frameH) {
        if (!enabled) {
            if (physicalZoomSupported) applyPhysicalZoom(0);
            scale += (1f - scale) * 0.15f;
            pivotX = 0.5f;
            pivotY = 0.5f;
            return;
        }
        if (faces == null || faces.isEmpty() || frameW <= 0 || frameH <= 0) {
            if (physicalZoomSupported) applyPhysicalZoom(0);
            scale += (1f - scale) * 0.15f;
            pivotX = 0.5f;
            pivotY = 0.5f;
            return;
        }

        Rect biggest = null;
        float best = -1f;
        for (RecognizeResult r : faces) {
            if (r.rect == null) continue;
            float area = r.rect.width() * r.rect.height();
            if (area > best) {
                best = area;
                biggest = r.rect;
            }
        }
        if (biggest == null) {
            scale += (1f - scale) * 0.15f;
            pivotX = 0.5f;
            pivotY = 0.5f;
            return;
        }

        float faceRatioW = (float) biggest.width() / frameW;

        if (physicalZoomSupported) {
            // 期望 zoom：人脸占比越小越需要放大；摄像头 zoom 量级未知，封顶保守
            float desired = TARGET_FACE_RATIO / Math.max(faceRatioW, 0.05f);
            desired = Math.min(desired, 3f);
            int z = (int) Math.min(100, Math.max(0, (desired - 1f) * 50f));
            applyPhysicalZoom(z);
            // 物理已放大，预览保持 1:1
            scale = 1f;
            pivotX = 0.5f;
            pivotY = 0.5f;
        } else {
            // 数字变焦：以最大人脸为中心平滑放大预览
            float targetScale = Math.min(MAX_DIGITAL, TARGET_FACE_RATIO / Math.max(faceRatioW, 0.08f));
            scale += (targetScale - scale) * 0.15f;
            pivotX = (float) biggest.centerX() / frameW;
            pivotY = (float) biggest.centerY() / frameH;
        }
    }

    private void applyPhysicalZoom(int z) {
        if (setZoomMethod == null || camera == null) return;
        try {
            setZoomMethod.invoke(camera, z);
        } catch (Exception e) {
            // 不支持或越界，忽略
        }
    }

    public ZoomTransform getTransform() {
        return new ZoomTransform(scale, pivotX, pivotY);
    }

    /** 预览数字变焦变换：scale 为放大倍数，pivot 为聚焦点在画面中的相对位置(0..1) */
    public static class ZoomTransform {
        public final float scale;
        public final float pivotX;
        public final float pivotY;

        ZoomTransform(float scale, float pivotX, float pivotY) {
            this.scale = scale;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
        }
    }
}
