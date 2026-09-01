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

    // ===== P3-3: 识别质量统计（供 /api/stats 和 WebUI 监控面板）=====
    private volatile long statTotalFrames = 0;      // 总处理帧数
    private volatile long statFramesWithFace = 0;   // 有人脸的帧数
    private volatile long statMatchedCount = 0;      // 匹配成功次数
    private volatile long statUnknownCount = 0;      // 未知/未匹配次数
    private volatile float statAvgSimilarity = 0f;   // 平均相似度（滑动平均）
    private volatile long statLastMatchMs = 0;       // 最近一次匹配时间戳
    private volatile int statCurrentTracks = 0;      // 当前活跃轨迹数

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

    // ===== P3-3: 识别质量统计（供 /api/stats 和 WebUI 监控面板）=====

    /** 获取识别质量统计信息（JSON 格式字符串）。 */
    public String getStatsJson() {
        long now = System.currentTimeMillis();
        float detectionRate = statTotalFrames > 0 ? (float) statFramesWithFace / statTotalFrames : 0f;
        float matchRate = (statMatchedCount + statUnknownCount) > 0
                ? (float) statMatchedCount / (statMatchedCount + statUnknownCount) : 0f;
        long lastMatchAgo = statLastMatchMs > 0 ? (now - statLastMatchMs) : -1;
        return "{"
                + "\"total_frames\":" + statTotalFrames
                + ",\"frames_with_face\":" + statFramesWithFace
                + ",\"detection_rate\":" + String.format("%.3f", detectionRate)
                + ",\"matched_count\":" + statMatchedCount
                + ",\"unknown_count\":" + statUnknownCount
                + ",\"match_rate\":" + String.format("%.3f", matchRate)
                + ",\"avg_similarity\":" + String.format("%.3f", statAvgSimilarity)
                + ",\"current_tracks\":" + statCurrentTracks
                + ",\"last_match_ago_ms\":" + lastMatchAgo
                + ",\"adaptive_threshold\":" + String.format("%.3f", RecognitionOptimizer.get().getAdaptiveThreshold())
                + ",\"detect_mode\":\"" + (FaceServer.currentDetectMode == com.arcsoft.face.enums.DetectMode.ASF_DETECT_MODE_VIDEO ? "VIDEO" : "IMAGE") + "\""
                + "}";
    }

    /** 重置统计信息（如切换场景、清空人脸库时调用）。 */
    public void resetStats() {
        statTotalFrames = 0;
        statFramesWithFace = 0;
        statMatchedCount = 0;
        statUnknownCount = 0;
        statAvgSimilarity = 0f;
        statLastMatchMs = 0;
        statCurrentTracks = 0;
    }

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

    /** 把一帧识别结果累计进跨帧共识轨迹，输出稳定姓名（迟滞，抑制闪烁）。
     *  P2-1: 多人（>=3）时使用匈牙利算法做全局最优分配，替代贪心匹配。
     *  P3-3: 更新识别质量统计信息。 */
    private void updateLiveTracks(List<RecognizeResult> results, int frameW) {
        long now = System.currentTimeMillis();
        synchronized (liveTracks) {
            // P3-3: 统计更新
            statTotalFrames++;
            int faceCount = (results != null) ? results.size() : 0;
            if (faceCount > 0) statFramesWithFace++;

            for (LiveTrack t : liveTracks.values()) t.seenThisFrame = false;

            if (results != null && !results.isEmpty()) {
                // P2-1: 多人（>=3）时使用匈牙利算法做全局最优分配
                if (faceCount >= Constants.HUNGARIAN_MIN_FACES && liveTracks.size() >= 2) {
                    matchWithHungarian(results, frameW, now);
                } else {
                    // 单人或两人时使用逐个匹配（贪心）
                    for (RecognizeResult r : results) {
                        matchAndUpdateTrack(r, frameW, now);
                    }
                }
            }
            // 过期清理（持有期内仍显示，避免短暂遮挡即消失）
            Iterator<Map.Entry<String, LiveTrack>> it = liveTracks.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().lastSeen > LIVE_HOLD_MS) it.remove();
            }
            statCurrentTracks = liveTracks.size();
        }
    }

    /** 匈牙利算法匹配：构建成本矩阵（检测框 × 轨迹），全局最优分配。
     *  成本 = 位置距离(0.4) + 特征距离(0.4) + 颜色距离(0.2，如有 ReID)。 */
    private void matchWithHungarian(List<RecognizeResult> results, int frameW, long now) {
        List<LiveTrack> tracks = new ArrayList<>();
        for (LiveTrack t : liveTracks.values()) {
            if (!t.seenThisFrame && t.rect != null) tracks.add(t);
        }
        if (tracks.isEmpty()) {
            for (RecognizeResult r : results) matchAndUpdateTrack(r, frameW, now);
            return;
        }

        // 构建成本矩阵
        int n = results.size();
        int m = tracks.size();
        float[][] cost = new float[n][m];
        for (int i = 0; i < n; i++) {
            RecognizeResult r = results.get(i);
            for (int j = 0; j < m; j++) {
                LiveTrack t = tracks.get(j);
                float c = 1.0f; // 默认高成本（不匹配）
                if (r.rect != null) {
                    // 位置距离（归一化）
                    float dx = (r.rect.centerX() - t.rect.centerX()) / (float) Math.max(frameW, 1);
                    float dy = (r.rect.centerY() - t.rect.centerY()) / (float) Math.max(frameH, 1);
                    float posDist = (float) Math.hypot(dx, dy);
                    // 特征相似度
                    float featSim = 0f;
                    if (r.feature != null && t.lastFeature != null) {
                        featSim = cosineSimilarity(r.feature, t.lastFeature);
                    }
                    // 颜色相似度（P2-2 人体 ReID）
                    float colorSim = 0f;
                    if (r.colorHist != null && t.colorHist != null) {
                        colorSim = RecognitionOptimizer.histogramSimilarity(r.colorHist, t.colorHist);
                    }
                    // 综合成本：位置近 + 特征像 + 颜色像 = 低成本
                    c = posDist * 0.4f + (1f - featSim) * 0.4f + (1f - colorSim) * 0.2f;
                    if (posDist > 0.3f) c = 1.0f; // 位置太远，强制不匹配
                }
                cost[i][j] = c;
            }
        }

        // 匈牙利算法分配
        int[] assignment = RecognitionOptimizer.hungarianMatch(cost);

        // 应用分配结果
        boolean[] used = new boolean[m];
        for (int i = 0; i < n; i++) {
            RecognizeResult r = results.get(i);
            int j = (assignment != null && i < assignment.length) ? assignment[i] : -1;
            if (j >= 0 && j < m && cost[i][j] < 0.6f) {
                LiveTrack t = tracks.get(j);
                if (!used[j]) {
                    used[j] = true;
                    updateTrack(t, r, now);
                    continue;
                }
            }
            // 未匹配或成本太高，创建新轨迹
            matchAndUpdateTrack(r, frameW, now);
        }
    }

    /** 匹配并更新单条轨迹（逐个匹配的贪心版本，单人/两人时使用）。 */
    private void matchAndUpdateTrack(RecognizeResult r, int frameW, long now) {
        LiveTrack t = matchLiveTrack(r, frameW);
        if (t == null) {
            t = new LiveTrack();
            t.id = "L" + (++liveTrackSeq);
            liveTracks.put(t.id, t);
        }
        updateTrack(t, r, now);
    }

    /** 更新单条轨迹的状态（姓名、相似度、位置、特征、颜色、移动速度）。 */
    private void updateTrack(LiveTrack t, RecognizeResult r, long now) {
        t.seenThisFrame = true;
        t.rect = r.rect;
        if (r.faceId > 0) t.faceId = r.faceId;
        t.frames++;

        // P3-2: 计算移动速度（px/帧），用于热点 ROI 动态调整
        if (r.rect != null && t.lastCx > 0) {
            float speed = (float) Math.hypot(r.rect.centerX() - t.lastCx, r.rect.centerY() - t.lastCy);
            t.moveSpeed = t.moveSpeed * 0.7f + speed * 0.3f; // EMA 平滑
        }
        if (r.rect != null) {
            t.lastCx = r.rect.centerX();
            t.lastCy = r.rect.centerY();
        }

        // P2-2: 更新衣着颜色直方图（人体 ReID）
        if (r.colorHist != null) {
            t.colorHist = r.colorHist.clone();
        }

        String nm = (r.name == null || "未知".equals(r.name)) ? "" : r.name;
        if (!nm.isEmpty()) {
            // P3-3: 统计匹配成功
            statMatchedCount++;
            statAvgSimilarity = statAvgSimilarity * 0.9f + r.score * 0.1f; // 滑动平均
            statLastMatchMs = now;

            if (nm.equals(t.candName)) t.candCount++;
            else { t.candName = nm; t.candScore = r.score; t.candCount = 1; }
            if (t.candName.equals(t.curName) || t.curName.isEmpty()) {
                t.curName = t.candName; t.curScore = t.candScore;
            } else if (t.candCount >= LIVE_CONSENSUS_FRAMES) {
                t.curName = t.candName; t.curScore = t.candScore;   // 迟滞后切换
            }
        } else {
            // P3-3: 统计未知/未匹配
            statUnknownCount++;
        }
        t.lastSeen = now;
        // 更新特征向量（用于下一帧 ReID 关联）
        if (r.feature != null && r.feature.length > 0) {
            t.lastFeature = r.feature.clone();
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
        /** P2-2: 衣着颜色直方图（人体 ReID，人脸遮挡时辅助跟踪）。 */
        float[] colorHist = null;
        /** P3-2: 移动速度（px/帧），用于热点 ROI 动态调整。 */
        float moveSpeed = 0f;
        /** 上一帧中心位置（用于计算移动速度）。 */
        int lastCx = 0, lastCy = 0;
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
