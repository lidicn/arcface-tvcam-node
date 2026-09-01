package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.arcsoft.face.FaceInfo;

import java.util.List;

/**
 * 注册引导叠加层：绘制中央椭圆引导区域 + 人脸框 + 质量状态。
 *
 * 与 FaceOverlayView 不同，本视图专门用于注册引导：
 * - 中央椭圆提示用户站在正确位置
 * - 人脸框绿色=合格，红色=不合格
 * - 椭圆在质量全部合格时变绿，提示"可以采集"
 */
public class RegisterOverlayView extends View {

    private static final String TAG = "RegisterOverlay";

    // 椭圆区域占画面的比例（宽 40%，高 50%）
    private static final float GUIDE_ELLIPSE_W_RATIO = 0.40f;
    private static final float GUIDE_ELLIPSE_H_RATIO = 0.50f;

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<FaceInfo> faces;
    private int frameW, frameH;
    private boolean qualityPassed = false; // 质量是否全部合格
    private String statusText = "";

    public RegisterOverlayView(Context context) {
        super(context);
        init();
    }

    public RegisterOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(4f);
        guidePaint.setColor(Color.parseColor("#90A4AE")); // 默认灰色

        facePaint.setStyle(Paint.Style.STROKE);
        facePaint.setStrokeWidth(3f);
        facePaint.setColor(Color.RED); // 默认红色（不合格）

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 更新人脸数据（在 UI 线程调用）。 */
    public void setFaces(List<FaceInfo> faces, int frameW, int frameH, boolean qualityPassed) {
        this.faces = faces;
        this.frameW = frameW;
        this.frameH = frameH;
        this.qualityPassed = qualityPassed;
        invalidate();
    }

    /** 设置状态文本（显示在椭圆下方）。 */
    public void setStatusText(String text) {
        this.statusText = text;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frameW <= 0 || frameH <= 0) return;

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        // 计算缩放比例（保持画面比例，居中显示）
        float scaleX = (float) viewW / frameW;
        float scaleY = (float) viewH / frameH;
        float scale = Math.min(scaleX, scaleY);
        float offsetX = (viewW - frameW * scale) / 2f;
        float offsetY = (viewH - frameH * scale) / 2f;

        // 1. 绘制中央椭圆引导区域
        float ellipseW = viewW * GUIDE_ELLIPSE_W_RATIO;
        float ellipseH = viewH * GUIDE_ELLIPSE_H_RATIO;
        float ellipseLeft = (viewW - ellipseW) / 2f;
        float ellipseTop = (viewH - ellipseH) / 2f - viewH * 0.05f; // 略偏上
        RectF ellipseRect = new RectF(ellipseLeft, ellipseTop,
                ellipseLeft + ellipseW, ellipseTop + ellipseH);

        if (qualityPassed) {
            guidePaint.setColor(Color.parseColor("#4CAF50")); // 绿色=合格
            guidePaint.setStrokeWidth(5f);
        } else {
            guidePaint.setColor(Color.parseColor("#90A4AE")); // 灰色=待调整
            guidePaint.setStrokeWidth(4f);
        }
        canvas.drawOval(ellipseRect, guidePaint);

        // 椭圆四角的小标记（更直观）
        float cornerLen = 30f;
        guidePaint.setStrokeWidth(4f);
        // 左上角
        canvas.drawLine(ellipseLeft, ellipseTop + cornerLen, ellipseLeft, ellipseTop, guidePaint);
        canvas.drawLine(ellipseLeft, ellipseTop, ellipseLeft + cornerLen, ellipseTop, guidePaint);
        // 右上角
        canvas.drawLine(ellipseLeft + ellipseW - cornerLen, ellipseTop, ellipseLeft + ellipseW, ellipseTop, guidePaint);
        canvas.drawLine(ellipseLeft + ellipseW, ellipseTop, ellipseLeft + ellipseW, ellipseTop + cornerLen, guidePaint);
        // 左下角
        canvas.drawLine(ellipseLeft, ellipseTop + ellipseH - cornerLen, ellipseLeft, ellipseTop + ellipseH, guidePaint);
        canvas.drawLine(ellipseLeft, ellipseTop + ellipseH, ellipseLeft + cornerLen, ellipseTop + ellipseH, guidePaint);
        // 右下角
        canvas.drawLine(ellipseLeft + ellipseW - cornerLen, ellipseTop + ellipseH, ellipseLeft + ellipseW, ellipseTop + ellipseH, guidePaint);
        canvas.drawLine(ellipseLeft + ellipseW, ellipseTop + ellipseH - cornerLen, ellipseLeft + ellipseW, ellipseTop + ellipseH, guidePaint);

        // 2. 绘制人脸框
        if (faces != null && !faces.isEmpty()) {
            for (FaceInfo fi : faces) {
                Rect r = fi.getRect();
                if (r == null) continue;
                // 映射到视图坐标
                float left = offsetX + r.left * scale;
                float top = offsetY + r.top * scale;
                float right = offsetX + r.right * scale;
                float bottom = offsetY + r.bottom * scale;

                if (qualityPassed && faces.size() == 1) {
                    facePaint.setColor(Color.parseColor("#4CAF50")); // 绿色=合格
                } else {
                    facePaint.setColor(Color.parseColor("#F44336")); // 红色=不合格
                }
                facePaint.setStrokeWidth(4f);
                canvas.drawRect(left, top, right, bottom, facePaint);

                // 人脸框四角标记
                float fl = 20f;
                facePaint.setStrokeWidth(5f);
                canvas.drawLine(left, top + fl, left, top, facePaint);
                canvas.drawLine(left, top, left + fl, top, facePaint);
                canvas.drawLine(right - fl, top, right, top, facePaint);
                canvas.drawLine(right, top, right, top + fl, facePaint);
                canvas.drawLine(left, bottom - fl, left, bottom, facePaint);
                canvas.drawLine(left, bottom, left + fl, bottom, facePaint);
                canvas.drawLine(right - fl, bottom, right, bottom, facePaint);
                canvas.drawLine(right, bottom - fl, right, bottom, facePaint);
            }
        }

        // 3. 绘制状态文本（椭圆下方）
        if (statusText != null && !statusText.isEmpty()) {
            textPaint.setColor(qualityPassed ? Color.parseColor("#4CAF50") : Color.WHITE);
            textPaint.setTextSize(24f);
            canvas.drawText(statusText, viewW / 2f, ellipseTop + ellipseH + 50f, textPaint);
        }
    }
}
