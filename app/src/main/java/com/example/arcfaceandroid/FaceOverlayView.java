package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.example.arcfaceandroid.FaceServer.RecognizeResult;

import java.util.List;

/**
 * 电视大屏识别框叠加层（10 英尺 UI）。
 *
 * 识别结果在「图像坐标」系给出（与 UVC 帧一致），这里用 letterbox 等比映射绘制到屏幕。
 * 数字变焦通过给本 View 设置 scaleX/scaleY + pivot 实现（与摄像头预览同步），
 * 因此本类只负责图像→屏幕的映射，无需感知缩放。
 */
public class FaceOverlayView extends View {

    // 设计色板：匹配 = 青绿，未知 = 红，姓名 = 琥珀
    private static final int COLOR_MATCH = 0xFF00E676;
    private static final int COLOR_UNKNOWN = 0xFFFF5252;
    private static final int COLOR_TEXT = 0xFFB0BEC5;

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile List<RecognizeResult> results;
    private volatile int frameW;
    private volatile int frameH;

    public FaceOverlayView(Context context) {
        super(context);
        init();
    }

    public FaceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FaceOverlayView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dip(3));
        boxPaint.setColor(COLOR_MATCH);

        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextSize(dip(18));
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        bgPaint.setColor(0xCC0B0F14);
        bgPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    public void setResults(List<RecognizeResult> results, int frameW, int frameH) {
        this.results = results;
        this.frameW = frameW;
        this.frameH = frameH;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        List<RecognizeResult> list = results;
        if (list == null || list.isEmpty() || frameW <= 0 || frameH <= 0) return;

        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        // letterbox 等比映射（与摄像头预览一致）
        float scale = Math.max((float) vw / frameW, (float) vh / frameH);
        float offX = (vw - frameW * scale) / 2f;
        float offY = (vh - frameH * scale) / 2f;

        for (RecognizeResult r : list) {
            if (r.rect == null) continue;
            float left = offX + r.rect.left * scale;
            float top = offY + r.rect.top * scale;
            float right = offX + r.rect.right * scale;
            float bottom = offY + r.rect.bottom * scale;

            boolean known = r.name != null && !r.name.isEmpty();
            int color = known ? COLOR_MATCH : COLOR_UNKNOWN;
            boxPaint.setColor(color);

            RectF rf = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(rf, dip(8), dip(8), boxPaint);

            // 标签：姓名 / 相似度
            String label = known
                    ? (r.name + "  " + Math.round(r.score * 100) + "%")
                    : ("未知  " + Math.round(r.score * 100) + "%");
            float textY = top - dip(8);
            if (textY < dip(20)) textY = bottom + dip(22);
            float tw = textPaint.measureText(label);
            canvas.drawRoundRect(new RectF(left - dip(4), textY - dip(20),
                    left + tw + dip(4), textY + dip(4)), dip(4), dip(4), bgPaint);
            canvas.drawText(label, left, textY, textPaint);
        }
    }

    private float dip(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
