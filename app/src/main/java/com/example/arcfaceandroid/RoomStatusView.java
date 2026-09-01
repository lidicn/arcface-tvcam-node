package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.example.arcfaceandroid.RecognitionState.FusedPerson;

import java.util.List;

/**
 * 双路融合「房间状态」面板（右上角）：列出当前识别到的人（TV 路 + 米家全景路按名融合取最高分），
 * 标注来源（TV / 全景 / 双路）与相似度，并显示米家全景在线状态。
 * 米家坐标不上 TV 预览（坐标系不同），仅在此面板按名字汇总呈现。
 */
public class RoomStatusView extends View {

    private static final int COLOR_BG = 0xCC0B0F14;
    private static final int COLOR_MATCH = 0xFF00E676;
    private static final int COLOR_MISMATCH = 0xFFFF5252;
    private static final int COLOR_TEXT = 0xFFECEFF1;
    private static final int COLOR_SUB = 0xFF78909C;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile List<FusedPerson> people;
    private volatile boolean panoOnline;
    private volatile String panoMsg = "";

    private float pad, titleH, lineH;

    public RoomStatusView(Context context) { super(context); init(); }
    public RoomStatusView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public RoomStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle); init();
    }

    private void init() {
        bgPaint.setColor(COLOR_BG); bgPaint.setStyle(Paint.Style.FILL);
        titlePaint.setColor(COLOR_TEXT); titlePaint.setTextSize(dip(18));
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textPaint.setColor(COLOR_TEXT); textPaint.setTextSize(dip(18));
        subPaint.setColor(COLOR_SUB); subPaint.setTextSize(dip(13));
        pad = dip(14); titleH = dip(30); lineH = dip(30);
        setWillNotDraw(false);
    }

    public void update(List<FusedPerson> people, boolean panoOnline, String panoMsg) {
        this.people = people;
        this.panoOnline = panoOnline;
        this.panoMsg = panoMsg == null ? "" : panoMsg;
        requestLayout();
        postInvalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (w <= 0) w = (int) dip(340);
        int count = (people == null) ? 0 : people.size();
        int h = (int) (pad * 2 + titleH + Math.max(1, count) * lineH + dip(4));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawRoundRect(new RectF(0, 0, w, h), dip(10), dip(10), bgPaint);

        float x = pad;
        float y = pad + titleH * 0.72f;
        canvas.drawText("房间状态", x, y, titlePaint);

        String ps = panoOnline ? "全景●" : "全景○";
        subPaint.setColor(panoOnline ? COLOR_MATCH : COLOR_MISMATCH);
        float psw = subPaint.measureText(ps);
        canvas.drawText(ps, w - pad - psw, y, subPaint);

        float ly = pad + titleH + lineH * 0.72f;
        List<FusedPerson> list = people;
        if (list == null || list.isEmpty()) {
            subPaint.setColor(COLOR_SUB);
            canvas.drawText("（暂无识别到的人）", x, ly, subPaint);
            return;
        }
        for (FusedPerson p : list) {
            if (ly > h - pad) break;
            boolean matched = p.isMatched();
            textPaint.setColor(matched ? COLOR_MATCH : COLOR_MISMATCH);
            String tag = (p.fromTv && p.fromPano) ? "双路"
                    : (p.fromTv ? "TV" : "全景");
            String line = p.name + "  " + Math.round(p.bestScore * 100) + "%  [" + tag + "]";
            canvas.drawText(line, x, ly, textPaint);
            ly += lineH;
        }
    }

    private float dip(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
