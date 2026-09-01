package com.example.arcfaceandroid;

import android.graphics.Rect;
import android.util.Log;

import com.example.arcfaceandroid.FaceServer.RecognizeResult;
import com.example.arcfaceandroid.SmartZoomController.ZoomTransform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 识别状态单例：Service 写、UI 读。
 *
 * 前台 Service 持有摄像头并持续识别，结果发布到这里；
 * Activity 通过 {@link #register(Listener)} 订阅变化以重绘 Overlay（切到别的 App 时 Activity 会
 * 取消订阅，识别仍在 Service 内继续，只是不画框）。
 */
public final class RecognitionState {

    private static final RecognitionState INSTANCE = new RecognitionState();

    public static RecognitionState get() {
        return INSTANCE;
    }

    private volatile List<RecognizeResult> results;
    private volatile int frameW;
    private volatile int frameH;
    private volatile ZoomTransform zoom;
    private volatile boolean cameraOpened;
    private volatile String cameraMsg = "";
    /** 最近一次 setResults 的时间戳（毫秒），供扫描判断结果是否新鲜（息屏前可能陈旧）。 */
    private volatile long lastUpdateMs;

    // ===== 实时单人/多人共识（消除逐帧闪烁）=====
    /** 同一姓名需连续出现 N 帧才提交/切换显示，抑制单帧误判造成的闪烁。
     *  单人场景优化：从 3 降到 2，减少显示迟滞，更快响应识别结果。 */
    private static final int LIVE_CONSENSUS_FRAMES = 2;
    /** 人脸消失后保留该人显示的最长时间（ms），避免短暂遮挡即消失。
     *  单人场景优化：从 1500 增加到 3000，减少短暂遮挡/姿态变化导致的频繁消失。 */
    private static final long LIVE_HOLD_MS = 3000;
    /** 跨帧稳定轨迹表（faceId 优先，回退中心距离 + 特征相似度关联）。 */
    private final Map<String, LiveTrack> liveTracks = new HashMap<>();
    private int liveTrackSeq = 0;

    // ===== 第二路（米家全景）状态：与 TV 路按名融合，坐标不上 TV 预览 =====
    private volatile List<RecognizeResult> panoResults;
    private volatile long panoUpdateMs;
    private volatile boolean panoOnline;
    private volatile String panoMsg = "";

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public interface Listener {
        void onUpdate();
    }

    private RecognitionState() {
    }

    public long getLastUpdateMs() { return lastUpdateMs; }

    public void setResults(List<RecognizeResult> results, int frameW, int frameH, ZoomTransform zoom) {
        this.results = results;
        this.frameW = frameW;
        this.frameH = frameH;
        this.zoom = zoom;
        this.lastUpdateMs = System.currentTimeMillis();
        updateLiveTracks(results, frameW);   // 逐帧累计共识，输出稳定名单
        notifyListeners();
    }

    public void setCameraStatus(boolean opened, String msg) {
        this.cameraOpened = opened;
        this.cameraMsg = msg != null ? msg : "";
        notifyListeners();
    }

    public List<RecognizeResult> getResults() {
        return results;
    }

    // ===== 实时共识：逐帧结果经投票/迟滞后输出稳定名单（供 /api/scan/latest） =====

    /** 把一帧识别结果累计进跨帧共识轨迹，输出稳定姓名（迟滞，抑制闪烁）。 */
    private void updateLiveTracks(List<RecognizeResult> results, int frameW) {
        long now = System.currentTimeMillis();
        synchronized (liveTracks) {
            for (LiveTrack t : liveTracks.values()) t.seenThisFrame = false;
            if (results != null) {
                for (RecognizeResult r : results) {
                    LiveTrack t = matchLiveTrack(r, frameW);
                    if (t == null) {
                        t = new LiveTrack();
                        t.id = "L" + (++liveTrackSeq);
                        liveTracks.put(t.id, t);
                    }
                    t.seenThisFrame = true;
                    t.rect = r.rect;
                    if (r.faceId > 0) t.faceId = r.faceId;
                    t.frames++;
                    String nm = (r.name == null || "未知".equals(r.name)) ? "" : r.name;
                    if (!nm.isEmpty()) {
                        if (nm.equals(t.candName)) t.candCount++;
                        else { t.candName = nm; t.candScore = r.score; t.candCount = 1; }
                        if (t.candName.equals(t.curName) || t.curName.isEmpty()) {
                            t.curName = t.candName; t.curScore = t.candScore;
                        } else if (t.candCount >= LIVE_CONSENSUS_FRAMES) {
                            t.curName = t.candName; t.curScore = t.candScore;   // 迟滞后切换
                        }
                        // 否则保持 curName（候选与当前不同且票数不足 -> 抑制闪烁）
                    }
                    t.lastSeen = now;
                }
            }
            // 过期清理（持有期内仍显示，避免短暂遮挡即消失）
            Iterator<Map.Entry<String, LiveTrack>> it = liveTracks.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().lastSeen > LIVE_HOLD_MS) it.remove();
            }
        }
    }

    /**
     * 将单帧一张脸关联到已有轨迹。
     * 优先级：faceId 精确匹配 > 特征相似度+位置距离加权匹配 > 中心距离贪心回退。
     *
     * 特征相似度 ReID：当 RecognizeResult 携带 feature（ArcSoft 512维特征向量）时，
     * 用余弦相似度辅助关联，解决两人交叉/faceId跳变时的误关联问题。
     * 同一人跨帧特征相似度通常 >0.85，不同人 <0.7，区分度足够。
     */
    private LiveTrack matchLiveTrack(RecognizeResult r, int frameW) {
        // 1. faceId 精确匹配（最快最准，视频检测模式下稳定）
        if (r.faceId > 0) {
            for (LiveTrack t : liveTracks.values())
                if (t.faceId == r.faceId) return t;
        }

        // 2. 特征相似度 + 位置距离加权匹配（ReID 增强）
        if (r.feature != null && r.feature.length > 0) {
            LiveTrack best = null;
            float bestCost = Float.MAX_VALUE;
            int posThr = frameW > 0 ? (int) (frameW * 0.20f) : 300; // 位置阈值放宽到20%
            for (LiveTrack t : liveTracks.values()) {
                if (t.seenThisFrame || t.rect == null) continue;
                if (t.lastFeature == null || t.lastFeature.length == 0) continue;
                // 位置距离（归一化到 [0,1]）
                float posDist = 0f;
                if (r.rect != null) {
                    float dx = (r.rect.centerX() - t.rect.centerX()) / (float) Math.max(frameW, 1);
                    float dy = (r.rect.centerY() - t.rect.centerY()) / (float) Math.max(frameH, 1);
                    posDist = (float) Math.hypot(dx, dy);
                }
                // 特征余弦相似度（越高越像同一人）
                float sim = cosineSimilarity(r.feature, t.lastFeature);
                // 综合成本：位置近 + 特征像 = 低成本
                float cost = posDist * 0.4f + (1f - sim) * 0.6f;
                if (posDist < 0.25f && sim > 0.70f && cost < bestCost) {
                    bestCost = cost;
                    best = t;
                }
            }
            if (best != null) {
                // 更新轨迹的最后特征（用于下一帧关联）
                best.lastFeature = r.feature.clone();
                return best;
            }
        }

        // 3. 中心距离贪心回退（faceId 不可用、特征缺失时的兜底）
        if (r.rect != null) {
            int cx = r.rect.centerX(), cy = r.rect.centerY();
            LiveTrack best = null;
            float bestD = Float.MAX_VALUE;
            for (LiveTrack t : liveTracks.values()) {
                if (t.seenThisFrame || t.rect == null) continue;
                float d = (float) Math.hypot(cx - t.rect.centerX(), cy - t.rect.centerY());
                if (d < bestD) { bestD = d; best = t; }
            }
            int thr = frameW > 0 ? (int) (frameW * 0.15f) : 200;
            if (best != null && bestD < thr) {
                if (r.feature != null && r.feature.length > 0) {
                    best.lastFeature = r.feature.clone();
                }
                return best;
            }
        }
        return null;
    }

    /** 计算两个 float 数组的余弦相似度（返回 [0,1]，1=完全相同）。 */
    private static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0f;
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0f || normB == 0f) return 0f;
        return dot / (float)(Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 共识后的实时名单（供 HA / 仪表盘读取"谁在镜前"）。 */
    public List<PersonView> getLivePersons() {
        List<PersonView> out = new ArrayList<>();
        synchronized (liveTracks) {
            for (LiveTrack t : liveTracks.values()) {
                if (t.rect == null && t.curName.isEmpty()) continue;
                PersonView p = new PersonView();
                p.name = t.curName;
                p.similarity = t.curScore;
                p.rect = t.rect;
                p.faceId = t.faceId;
                out.add(p);
            }
        }
        return out;
    }

    /** 共识后的单人视图（姓名 + 相似度 + 位置 + faceId）。 */
    public static class PersonView {
        public String name = "";
        public float similarity = 0f;
        public Rect rect = null;
        public int faceId = -1;
    }

    /** 跨帧共识轨迹：维护当前显示姓名（迟滞）与候选姓名（投票）。 */
    private static final class LiveTrack {
        String id;
        int faceId = -1;
        Rect rect = null;
        String curName = "";      // 当前已提交显示姓名
        float curScore = 0f;
        String candName = "";     // 候选姓名（连续出现累积票数）
        float candScore = 0f;
        int candCount = 0;
        long lastSeen = 0;
        int frames = 0;
        boolean seenThisFrame = false;
        /** 最后一次关联时的特征向量（用于 ReID 相似度匹配）。 */
        float[] lastFeature = null;
    }

    public int getFrameW() {
        return frameW;
    }

    public int getFrameH() {
        return frameH;
    }

    public ZoomTransform getZoom() {
        return zoom;
    }

    public boolean isCameraOpened() {
        return cameraOpened;
    }

    public String getCameraMsg() {
        return cameraMsg;
    }

    public void register(Listener l) {
        if (l != null) listeners.add(l);
    }

    public void unregister(Listener l) {
        if (l != null) listeners.remove(l);
    }

    // ===== 第二路（米家全景）写入/读取 =====

    public void setPanoResults(List<RecognizeResult> results) {
        this.panoResults = results;
        this.panoUpdateMs = System.currentTimeMillis();
        notifyListeners();
    }

    public List<RecognizeResult> getPanoResults() {
        return panoResults;
    }

    public void setPanoStatus(boolean online, String msg) {
        this.panoOnline = online;
        this.panoMsg = msg != null ? msg : "";
        notifyListeners();
    }

    public boolean isPanoOnline() { return panoOnline; }

    public String getPanoMsg() { return panoMsg; }

    public long getPanoUpdateMs() { return panoUpdateMs; }

    /**
     * 按名融合 TV 路 + 米家路：同名取最高分（max），标记各路是否命中。
     * 返回所有在任一路被识别到的人（bestScore 取两路最大），UI 只展示 matched=true 的即可。
     *
     * 新鲜度过滤：米家路结果超过 PANORAMA_FRESH_MS（默认30s）未更新则不参与融合，
     * 避免掉线后旧结果残留污染实时名单（frame.jpeg 单帧延迟 4-13s）。
     */
    public List<FusedPerson> getFusedPeople() {
        Map<String, FusedPerson> map = new HashMap<>();
        mergeInto(map, results, true);
        boolean panoFresh = panoUpdateMs > 0
                && (System.currentTimeMillis() - panoUpdateMs) <= Constants.PANORAMA_FRESH_MS;
        if (panoFresh) {
            mergeInto(map, panoResults, false);
        }
        return new ArrayList<>(map.values());
    }

    private void mergeInto(Map<String, FusedPerson> map, List<RecognizeResult> list, boolean fromTv) {
        if (list == null) return;
        for (RecognizeResult r : list) {
            if (r == null || r.name == null || r.name.isEmpty() || "未知".equals(r.name)) continue;
            FusedPerson p = map.get(r.name);
            if (p == null) {
                p = new FusedPerson();
                p.name = r.name;
                p.bestScore = r.score;
                map.put(r.name, p);
            } else if (r.score > p.bestScore) {
                p.bestScore = r.score;
            }
            if (fromTv) p.fromTv = true; else p.fromPano = true;
        }
    }

    /** 融合后的单人：姓名 + 两路最高分 + 各路是否命中。 */
    public static class FusedPerson {
        public String name;
        public float bestScore;
        public boolean fromTv;
        public boolean fromPano;
        public boolean isMatched() { return bestScore >= Constants.MATCH_THRESHOLD; }
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            try {
                l.onUpdate();
            } catch (Throwable t) {
                Log.w("RecognitionState", "listener error", t);
            }
        }
    }
}
