package com.example.arcfaceandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 人脸常现位置（热点）学习器。
 *
 * 摄像头正对固定座位区域（如沙发、书桌），人脸高概率出现在固定位置。本类把每次检测到的人脸
 * 位置（归一化）做聚类/加权，自动学到若干热点矩形，供识别时只在热点 ROI 内 detect（缩小搜索
 * 范围、提速），并周期性全图兜底以防漏掉新位置的人。
 *
 * 热点持久化到 SharedPreferences（JSON），开机加载，越用越准。
 */
public final class HotspotManager {

    private static final String TAG = "Hotspot";
    private static final String PREFS = "arcface_hotspots";
    private static final String KEY = "hotspots";

    /** 归一化热点：中心(x,y)与尺寸(w,h)均在 [0,1]。 */
    private static final class Hot {
        float x, y, w, h;
        float weight;
        long lastHit;
        Hot(float x, float y, float w, float h, float weight) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.weight = weight;
            this.lastHit = System.currentTimeMillis();
        }
    }

    private static final HotspotManager INST = new HotspotManager();
    public static HotspotManager get() { return INST; }

    private final List<Hot> hots = new ArrayList<>();
    private Context ctx;
    private long frameCounter = 0;

    // 调参
    private static final float ASSOC_DIST = 0.18f;   // 归一化中心距离阈值（关联/新建判定）
    private static final float MIN_W = 0.05f, MIN_H = 0.05f;
    private static final float DECAY = 0.985f;         // 每帧衰减系数
    private static final float REMOVE_BELOW = 0.5f;    // 权重下限（过低移除）
    private static final float ACTIVE_W = 1.0f;        // 至少命中一次才参与 ROI
    private static final int MAX_HOTS = 6;
    private static final float PAD = 0.35f;            // ROI 扩边容错比例（并排多人也能纳入框内）
    /** P3-2: 动态 PAD（基于移动速度调整 ROI 大小）。静止时缩小 ROI 提速，移动时扩大 ROI 跟人。 */
    private volatile float dynamicPad = PAD;
    private static final float PAD_MIN = 0.08f;   // 静止时最小 PAD
    private static final float PAD_MAX = 0.80f;   // 快速移动时最大 PAD
    private static final long MAX_HOTSPOT_AGE_MS = 5 * 60 * 1000L; // 旧位置最大存活时长：无命中即失效，避免锁死过期位置（摄像头移位/换座后自动重学）

    public void init(Context c) {
        this.ctx = c.getApplicationContext();
        load();
    }

    /** 上报一次检测到的人脸（像素坐标），更新热点表。 */
    public synchronized void update(Rect faceRect, int frameW, int frameH) {
        if (faceRect == null || frameW <= 0 || frameH <= 0) return;
        float cx = (faceRect.left + faceRect.right) / 2f / frameW;
        float cy = (faceRect.top + faceRect.bottom) / 2f / frameH;
        float w = (faceRect.right - faceRect.left) / (float) frameW;
        float h = (faceRect.bottom - faceRect.top) / (float) frameH;

        // 全局衰减
        for (Hot hp : hots) hp.weight *= DECAY;

        // 找最近热点
        Hot best = null;
        float bestD = Float.MAX_VALUE;
        for (Hot hp : hots) {
            float d = (float) Math.hypot(cx - hp.x, cy - hp.y);
            if (d < bestD) { bestD = d; best = hp; }
        }
        if (best != null && bestD < ASSOC_DIST) {
            float a = 0.2f; // EMA
            best.x += a * (cx - best.x);
            best.y += a * (cy - best.y);
            best.w += a * (w - best.w);
            best.h += a * (h - best.h);
            best.weight = Math.min(20f, best.weight + 1f);
            best.lastHit = System.currentTimeMillis();
        } else {
            Hot n = new Hot(cx, cy, Math.max(MIN_W, w), Math.max(MIN_H, h), 1f);
            hots.add(n);
            if (hots.size() > MAX_HOTS) {
                Hot weak = null;
                float mw = Float.MAX_VALUE;
                for (Hot hp : hots) if (hp.weight < mw) { mw = hp.weight; weak = hp; }
                if (weak != null) hots.remove(weak);
            }
        }
        // 清理弱热点
        Iterator<Hot> it = hots.iterator();
        while (it.hasNext()) if (it.next().weight < REMOVE_BELOW) it.remove();

        frameCounter++;
        if (frameCounter % 200 == 0) save();
    }

    /** 返回像素 ROI 列表（已偶数对齐、宽高4倍数、夹紧在图内），供 detect 限定范围。 */
    public synchronized List<Rect> toPixelRois(int frameW, int frameH) {
        List<Rect> out = new ArrayList<>();
        if (frameW <= 0 || frameH <= 0) return out;
        long now = System.currentTimeMillis();
        // 失效旧位置（无命中超过最大存活时长），避免锁死过期座位
        Iterator<Hot> pit = hots.iterator();
        while (pit.hasNext()) {
            if (now - pit.next().lastHit > MAX_HOTSPOT_AGE_MS) pit.remove();
        }
        for (Hot hp : hots) {
            if (hp.weight < ACTIVE_W) continue;
            int rw = (int) (hp.w * frameW);
            int rh = (int) (hp.h * frameH);
            int rx = (int) ((hp.x - hp.w / 2) * frameW);
            int ry = (int) ((hp.y - hp.h / 2) * frameH);
            int padX = (int) (dynamicPad * rw), padY = (int) (dynamicPad * rh);
            rx -= padX; ry -= padY; rw += padX * 2; rh += padY * 2;
            // 对齐
            rx &= ~1; ry &= ~1; rw &= ~1; rh &= ~1;
            if (rw < 4) rw = 4;
            if (rh < 4) rh = 4;
            if (rx < 0) rx = 0;
            if (ry < 0) ry = 0;
            if (rx + rw > frameW) rw = frameW - rx;
            if (ry + rh > frameH) rh = frameH - ry;
            rw -= rw % 4; rh -= rh % 4;
            if (rw < 4 || rh < 4 || rx + rw > frameW || ry + rh > frameH) continue;
            out.add(new Rect(rx, ry, rx + rw, ry + rh));
        }
        return out;
    }

    public synchronized boolean hasHotspots() { return !hots.isEmpty(); }
    public synchronized int count() { return hots.size(); }

    /** P3-2: 根据移动速度设置动态 PAD（ROI 扩边比例）。 */
    public void setDynamicPadBySpeed(float moveSpeedPxPerFrame) {
        float pad;
        if (moveSpeedPxPerFrame < 5f) pad = PAD_MIN;
        else if (moveSpeedPxPerFrame < 15f) pad = 0.25f;
        else if (moveSpeedPxPerFrame < 30f) pad = 0.45f;
        else pad = PAD_MAX;
        dynamicPad = dynamicPad * 0.7f + pad * 0.3f;
    }

    public float getDynamicPad() { return dynamicPad; }

    /** 当前活跃（已命中且未过期）热点数，供 analyze 决策与触发式全图判断。 */
    public synchronized int activeCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Hot hp : hots) {
            if (hp.weight >= ACTIVE_W && (now - hp.lastHit) <= MAX_HOTSPOT_AGE_MS) n++;
        }
        return n;
    }

    public synchronized String dump() {
        JSONArray a = new JSONArray();
        for (Hot hp : hots) {
            try {
                JSONObject o = new JSONObject();
                o.put("x", Math.round(hp.x * 1000) / 1000d);
                o.put("y", Math.round(hp.y * 1000) / 1000d);
                o.put("w", Math.round(hp.w * 1000) / 1000d);
                o.put("h", Math.round(hp.h * 1000) / 1000d);
                o.put("weight", Math.round(hp.weight * 10) / 10d);
                a.put(o);
            } catch (Exception ignore) { }
        }
        return a.toString();
    }

    public synchronized void clear() {
        hots.clear();
        save();
    }

    private void load() {
        if (ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String s = sp.getString(KEY, null);
            if (s == null) return;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                hots.add(new Hot((float) o.getDouble("x"), (float) o.getDouble("y"),
                        (float) o.getDouble("w"), (float) o.getDouble("h"), (float) o.getDouble("weight")));
            }
            Log.i(TAG, "loaded " + hots.size() + " hotspots");
        } catch (Exception e) {
            Log.w(TAG, "load hotspots failed", e);
        }
    }

    private void save() {
        if (ctx == null) return;
        try {
            JSONArray a = new JSONArray();
            for (Hot hp : hots) {
                JSONObject o = new JSONObject();
                o.put("x", hp.x); o.put("y", hp.y);
                o.put("w", hp.w); o.put("h", hp.h);
                o.put("weight", hp.weight);
                a.put(o);
            }
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, a.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "save hotspots failed", e);
        }
    }
}
