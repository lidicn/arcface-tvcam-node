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

    private static final String TAG = "RecognitionState";
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

    // ===== 四态状态机（候选→确认→稳定→离开）=====
    /** 状态：候选（首次检测到，还没确认，不显示） */
    private static final int STATE_CANDIDATE = 0;
    /** 状态：确认（连续2帧匹配，已显示） */
    private static final int STATE_CONFIRMED = 1;
    /** 状态：稳定（连续5帧匹配+平均相似度>0.8，稳定显示，可降采样） */
    private static final int STATE_STABLE = 2;
    /** 状态：离开（连续未检测到，保持显示中，超时移除） */
    private static final int STATE_LEAVING = 3;
    /** 候选→确认：连续匹配帧数 */
    private static final int CONFIRM_FRAMES = 2;
    /** 确认→稳定：连续匹配帧数 */
    private static final int STABLE_FRAMES = 5;
    /** 确认→稳定：最低平均相似度 */
    private static final float STABLE_MIN_SIM = 0.80f;
    /** 确认/稳定→离开：连续未匹配帧数 */
    private static final int LEAVE_FRAMES = 3;
    /** 离开→移除：超时时间（ms） */
    private static final long LEAVE_TIMEOUT_MS = 3000;
    /** 候选→移除：未匹配超时（ms），候选状态太脆弱，1秒未匹配就移除 */
    private static final long CANDIDATE_TIMEOUT_MS = 1000;
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

    /** 获取识别质量统计信息（JSON 格式字符串）。
     *  P3-状态机: 增加各状态人数统计。 */
    public String getStatsJson() {
        long now = System.currentTimeMillis();
        float detectionRate = statTotalFrames > 0 ? (float) statFramesWithFace / statTotalFrames : 0f;
        float matchRate = (statMatchedCount + statUnknownCount) > 0
                ? (float) statMatchedCount / (statMatchedCount + statUnknownCount) : 0f;
        long lastMatchAgo = statLastMatchMs > 0 ? (now - statLastMatchMs) : -1;
        // 状态机统计
        int candidateCount = 0, confirmedCount = 0, stableCount = 0, leavingCount = 0;
        synchronized (liveTracks) {
            for (LiveTrack t : liveTracks.values()) {
                if (t.state == STATE_CANDIDATE) candidateCount++;
                else if (t.state == STATE_CONFIRMED) confirmedCount++;
                else if (t.state == STATE_STABLE) stableCount++;
                else if (t.state == STATE_LEAVING) leavingCount++;
            }
        }
        return "{"
                + "\"total_frames\":" + statTotalFrames
                + ",\"frames_with_face\":" + statFramesWithFace
                + ",\"detection_rate\":" + String.format("%.3f", detectionRate)
                + ",\"matched_count\":" + statMatchedCount
                + ",\"unknown_count\":" + statUnknownCount
                + ",\"match_rate\":" + String.format("%.3f", matchRate)
                + ",\"avg_similarity\":" + String.format("%.3f", statAvgSimilarity)
                + ",\"current_tracks\":" + statCurrentTracks
                + ",\"state_candidate\":" + candidateCount
                + ",\"state_confirmed\":" + confirmedCount
                + ",\"state_stable\":" + stableCount
                + ",\"state_leaving\":" + leavingCount
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
            // 过期清理（状态机驱动：候选超时/离开超时/原有持有期兜底）
            Iterator<Map.Entry<String, LiveTrack>> it = liveTracks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, LiveTrack> entry = it.next();
                LiveTrack t = entry.getValue();
                boolean remove = false;
                if (t.state == STATE_CANDIDATE) {
                    // 候选状态：超过 CANDIDATE_TIMEOUT_MS 未确认就移除（候选太脆弱）
                    if (t.stateEnterTime > 0 && (now - t.stateEnterTime) > CANDIDATE_TIMEOUT_MS) {
                        remove = true;
                        Log.i(TAG, "state: CANDIDATE timeout removed (unconfirmed)");
                    }
                } else if (t.state == STATE_LEAVING) {
                    // 离开状态：超过 LEAVE_TIMEOUT_MS 未回归就移除
                    if ((now - t.stateEnterTime) > LEAVE_TIMEOUT_MS) {
                        remove = true;
                        Log.i(TAG, "state: LEAVING timeout removed name=" + t.curName);
                    }
                } else {
                    // 确认/稳定状态：原有持有期兜底（正常情况下不会走到这里，因为会先转 LEAVING）
                    if (now - t.lastSeen > LIVE_HOLD_MS) {
                        remove = true;
                    }
                }
                if (remove) it.remove();
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

    /** 更新单条轨迹的状态（姓名、相似度、位置、特征、颜色、移动速度、状态机）。
     *  P3-状态机: 四态转换 候选→确认→稳定→离开，减少跳名和闪烁。 */
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

        // 年龄性别：年龄 EMA 平滑，性别取众数
        if (r.age >= 0) {
            if (t.ageSmoothed < 0) t.ageSmoothed = r.age;
            else t.ageSmoothed = t.ageSmoothed * 0.8f + r.age * 0.2f;
        }
        if (r.gender == 0) t.maleCount++;
        else if (r.gender == 1) t.femaleCount++;
        if (t.maleCount > t.femaleCount) t.genderSmoothed = 0;
        else if (t.femaleCount > t.maleCount) t.genderSmoothed = 1;

        String nm = (r.name == null || "未知".equals(r.name)) ? "" : r.name;
        if (!nm.isEmpty()) {
            // ===== 匹配成功 =====
            t.consecutiveMatches++;
            t.consecutiveMisses = 0;
            t.avgSimilarity = t.avgSimilarity * 0.7f + r.score * 0.3f; // EMA 平滑

            // P3-3: 统计匹配成功
            statMatchedCount++;
            statAvgSimilarity = statAvgSimilarity * 0.9f + r.score * 0.1f; // 滑动平均
            statLastMatchMs = now;

            // 候选姓名累计（用于可能的姓名切换）
            if (nm.equals(t.candName)) t.candCount++;
            else { t.candName = nm; t.candScore = r.score; t.candCount = 1; }

            // ===== 状态机转换（匹配成功时）=====
            if (t.state == STATE_CANDIDATE) {
                // 候选→确认：连续2帧匹配
                if (t.consecutiveMatches >= CONFIRM_FRAMES) {
                    t.state = STATE_CONFIRMED;
                    t.stateEnterTime = now;
                    t.curName = nm;
                    t.curScore = r.score;
                    Log.i(TAG, "state: CANDIDATE→CONFIRMED name=" + nm
                            + " sim=" + String.format("%.2f", r.score));
                }
            } else if (t.state == STATE_CONFIRMED) {
                // 确认→稳定：连续5帧匹配 + 平均相似度>0.8
                if (t.consecutiveMatches >= STABLE_FRAMES && t.avgSimilarity >= STABLE_MIN_SIM) {
                    t.state = STATE_STABLE;
                    t.stateEnterTime = now;
                    Log.i(TAG, "state: CONFIRMED→STABLE name=" + nm
                            + " avg_sim=" + String.format("%.2f", t.avgSimilarity));
                }
                // 确认状态下姓名切换：候选姓名变化且连续2帧
                if (!t.candName.equals(t.curName) && t.candCount >= LIVE_CONSENSUS_FRAMES) {
                    t.curName = t.candName;
                    t.curScore = t.candScore;
                    t.consecutiveMatches = 0; // 切换姓名后重新计数
                    Log.i(TAG, "state: CONFIRMED name switch " + t.curName + "→" + nm);
                }
            } else if (t.state == STATE_STABLE) {
                // 稳定状态下姓名切换：候选姓名变化且连续2帧（稳定状态更谨慎，但家庭场景2帧够了）
                if (!t.candName.equals(t.curName) && t.candCount >= LIVE_CONSENSUS_FRAMES) {
                    t.curName = t.candName;
                    t.curScore = t.candScore;
                    t.state = STATE_CONFIRMED; // 切换姓名后降回确认状态
                    t.stateEnterTime = now;
                    t.consecutiveMatches = 0;
                    Log.i(TAG, "state: STABLE→CONFIRMED name switch " + t.curName + "→" + nm);
                } else {
                    t.curScore = t.curScore * 0.8f + r.score * 0.2f; // 稳定状态平滑更新相似度
                }
            } else if (t.state == STATE_LEAVING) {
                // 离开→确认（回退）：人又出现了
                t.state = STATE_CONFIRMED;
                t.stateEnterTime = now;
                t.consecutiveMatches = 1; // 重新计数
                if (!nm.equals(t.curName)) {
                    t.curName = nm;
                    t.curScore = r.score;
                }
                Log.i(TAG, "state: LEAVING→CONFIRMED name=" + nm + " (returned)");
            }
        } else {
            // ===== 未匹配（检测到人脸但未识别出姓名，或无人脸）=====
            t.consecutiveMisses++;
            t.consecutiveMatches = 0;

            // P3-3: 统计未知/未匹配
            statUnknownCount++;

            // ===== 状态机转换（未匹配时）=====
            if (t.state == STATE_CANDIDATE) {
                // 候选状态未匹配：不立即移除，等超时（候选太脆弱，但给1秒机会）
                // 清理时会检查 CANDIDATE_TIMEOUT_MS
            } else if (t.state == STATE_CONFIRMED || t.state == STATE_STABLE) {
                // 确认/稳定→离开：连续3帧未匹配
                if (t.consecutiveMisses >= LEAVE_FRAMES) {
                    t.state = STATE_LEAVING;
                    t.stateEnterTime = now;
                    Log.i(TAG, "state: " + (t.state == STATE_STABLE ? "STABLE" : "CONFIRMED")
                            + "→LEAVING name=" + t.curName + " misses=" + t.consecutiveMisses);
                }
            }
            // LEAVING 状态不在这里处理，清理时检查 LEAVE_TIMEOUT_MS
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

    /** 共识后的实时名单（供 HA / 仪表盘读取"谁在镜前"）。
     *  P3-状态机: 只返回确认/稳定/离开状态的人（候选不显示），标记稳定/离开状态。 */
    public List<PersonView> getLivePersons() {
        List<PersonView> out = new ArrayList<>();
        synchronized (liveTracks) {
            for (LiveTrack t : liveTracks.values()) {
                // 候选状态不显示（还没确认）
                if (t.state == STATE_CANDIDATE) continue;
                // 确认/稳定状态需要有姓名；离开状态保留最后姓名
                if (t.rect == null && t.curName.isEmpty()) continue;
                PersonView p = new PersonView();
                p.name = t.curName;
                p.similarity = t.curScore;
                p.rect = t.rect;
                p.faceId = t.faceId;
                p.state = t.state;
                p.age = (t.ageSmoothed >= 0) ? Math.round(t.ageSmoothed) : -1;
                p.gender = t.genderSmoothed;
                out.add(p);
            }
        }
        return out;
    }

    /** 共识后的单人视图（姓名 + 相似度 + 位置 + faceId + 状态）。 */
    public static class PersonView {
        public String name = "";
        public float similarity = 0f;
        public Rect rect = null;
        public int faceId = -1;
        /** 状态：0=候选(不显示) 1=确认 2=稳定 3=离开中 */
        public int state = STATE_CONFIRMED;
        /** 年龄（ArcSoft ASF_AGE），-1 表示未知 */
        public int age = -1;
        /** 性别（ArcSoft ASF_GENDER）：0=男，1=女，-1=未知 */
        public int gender = -1;
        /** 是否处于稳定状态（可用于UI区分显示样式） */
        public boolean isStable() { return state == STATE_STABLE; }
        /** 是否处于离开状态（可用于UI显示"离开中"） */
        public boolean isLeaving() { return state == STATE_LEAVING; }
    }

    /** 跨帧共识轨迹：维护当前显示姓名（迟滞）与候选姓名（投票）。
     *  P3-状态机: 四态状态机 候选→确认→稳定→离开，减少跳名和闪烁。 */
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
        /** 年龄（EMA 平滑，-1=未知） */
        float ageSmoothed = -1f;
        /** 性别（取众数，-1=未知，0=男，1=女） */
        int genderSmoothed = -1;
        /** 性别统计：男性帧数、女性帧数（用于众数） */
        int maleCount = 0, femaleCount = 0;
        // ===== 四态状态机字段 =====
        /** 当前状态：STATE_CANDIDATE / STATE_CONFIRMED / STATE_STABLE / STATE_LEAVING */
        int state = STATE_CANDIDATE;
        /** 连续匹配帧数（匹配成功+1，未匹配清零） */
        int consecutiveMatches = 0;
        /** 连续未匹配帧数（未匹配+1，匹配成功清零） */
        int consecutiveMisses = 0;
        /** 滑动平均相似度（用于稳定状态判定） */
        float avgSimilarity = 0f;
        /** 进入当前状态的时间戳（用于离开超时判定） */
        long stateEnterTime = 0;
    }

    public int getFrameW() {
        return frameW;
    }

    /** P3-2: 获取当前活跃轨迹的最大移动速度（px/帧），用于热点 ROI 动态调整。 */
    public float getMaxMoveSpeed() {
        float maxSpeed = 0f;
        synchronized (liveTracks) {
            for (LiveTrack t : liveTracks.values()) {
                if (t.state == STATE_LEAVING) continue; // 离开状态不参与
                if (t.moveSpeed > maxSpeed) maxSpeed = t.moveSpeed;
            }
        }
        return maxSpeed;
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
     * 跨路 ReID 融合 TV 路 + 米家路：
     * 1. 先按特征向量关联两路的同一张脸（余弦相似度 > REID_SIM_THRESHOLD）
     * 2. 任一路识别出名字，另一路也标记为该人（解决一路侧脸没识别出但另一路正脸识别出的情况）
     * 3. 同名取最高分（max），标记各路是否命中
     *
     * 新鲜度过滤：米家路结果超过 PANORAMA_FRESH_MS（默认30s）未更新则不参与融合，
     * 避免掉线后旧结果残留污染实时名单（frame.jpeg 单帧延迟 4-13s）。
     */
    public List<FusedPerson> getFusedPeople() {
        // 跨路 ReID 特征相似度阈值（同一人不同摄像头角度通常 >0.70）
        final float REID_SIM_THRESHOLD = 0.70f;

        // 1. 收集 TV 路所有结果（包括没名字但有特征的）
        List<RecognizeResult> tvList = new ArrayList<>();
        if (results != null) {
            for (RecognizeResult r : results) {
                if (r != null && r.rect != null) tvList.add(r);
            }
        }

        // 2. 收集米家路所有结果（新鲜度过滤）
        List<RecognizeResult> panoList = new ArrayList<>();
        boolean panoFresh = panoUpdateMs > 0
                && (System.currentTimeMillis() - panoUpdateMs) <= Constants.PANORAMA_FRESH_MS;
        if (panoFresh && panoResults != null) {
            for (RecognizeResult r : panoResults) {
                if (r != null && r.rect != null) panoList.add(r);
            }
        }

        // 3. 跨路 ReID 关联：用特征向量匹配两路的同一张脸
        //    构建 TV→Pano 的匹配映射
        Map<Integer, Integer> tvToPanoMatch = new HashMap<>();
        for (int i = 0; i < tvList.size(); i++) {
            RecognizeResult tv = tvList.get(i);
            if (tv.feature == null || tv.feature.length == 0) continue;
            float bestSim = 0f;
            int bestJ = -1;
            for (int j = 0; j < panoList.size(); j++) {
                if (tvToPanoMatch.containsValue(j)) continue; // 已被其他 TV 脸匹配
                RecognizeResult pano = panoList.get(j);
                if (pano.feature == null || pano.feature.length == 0) continue;
                float sim = cosineSimilarity(tv.feature, pano.feature);
                if (sim > bestSim) {
                    bestSim = sim;
                    bestJ = j;
                }
            }
            if (bestJ >= 0 && bestSim >= REID_SIM_THRESHOLD) {
                tvToPanoMatch.put(i, bestJ);
            }
        }

        // 4. 融合：按关联对分组，输出最终名单
        Map<String, FusedPerson> map = new HashMap<>();
        boolean[] panoUsed = new boolean[panoList.size()];

        // 4a. 处理 TV 路结果（按关联对融合）
        for (int i = 0; i < tvList.size(); i++) {
            RecognizeResult tv = tvList.get(i);
            Integer panoIdx = tvToPanoMatch.get(i);
            RecognizeResult pano = (panoIdx != null) ? panoList.get(panoIdx) : null;
            if (panoIdx != null) panoUsed[panoIdx] = true;

            // 确定姓名：TV 路有名字用 TV 的，否则用米家路的（ReID 辅助）
            String tvName = (tv.name == null || tv.name.isEmpty() || "未知".equals(tv.name)) ? "" : tv.name;
            String panoName = (pano != null && pano.name != null && !pano.name.isEmpty() && !"未知".equals(pano.name)) ? pano.name : "";
            String name = !tvName.isEmpty() ? tvName : panoName;

            if (name.isEmpty()) continue; // 两路都没识别出名字，跳过

            float score = !tvName.isEmpty() ? tv.score : (pano != null ? pano.score : 0f);
            // 如果两路都有名字且不同，取分数高的
            if (!tvName.isEmpty() && !panoName.isEmpty() && !tvName.equals(panoName)) {
                if (pano.score > tv.score) {
                    name = panoName;
                    score = pano.score;
                }
            }

            FusedPerson p = map.get(name);
            if (p == null) {
                p = new FusedPerson();
                p.name = name;
                p.bestScore = score;
                map.put(name, p);
            } else if (score > p.bestScore) {
                p.bestScore = score;
            }
            p.fromTv = true;
            if (p.tvFeature == null && tv.feature != null) p.tvFeature = tv.feature.clone();
            // 年龄性别：TV 路优先，米家路补充
            if (p.age < 0 && tv.age >= 0) p.age = tv.age;
            if (p.gender < 0 && tv.gender >= 0) p.gender = tv.gender;
            if (pano != null) {
                p.fromPano = true;
                if (p.panoFeature == null && pano.feature != null) p.panoFeature = pano.feature.clone();
                // 如果 TV 路没识别出名字但米家路识别出了，标记为 ReID 辅助
                if (tvName.isEmpty() && !panoName.isEmpty()) p.matchedByReid = true;
                // 米家路补充年龄性别
                if (p.age < 0 && pano.age >= 0) p.age = pano.age;
                if (p.gender < 0 && pano.gender >= 0) p.gender = pano.gender;
            }
        }

        // 4b. 处理米家路剩余结果（没被 TV 路关联的）
        for (int j = 0; j < panoList.size(); j++) {
            if (panoUsed[j]) continue;
            RecognizeResult pano = panoList.get(j);
            String name = (pano.name == null || pano.name.isEmpty() || "未知".equals(pano.name)) ? "" : pano.name;
            if (name.isEmpty()) continue;

            FusedPerson p = map.get(name);
            if (p == null) {
                p = new FusedPerson();
                p.name = name;
                p.bestScore = pano.score;
                map.put(name, p);
            } else if (pano.score > p.bestScore) {
                p.bestScore = pano.score;
            }
            p.fromPano = true;
            if (p.panoFeature == null && pano.feature != null) p.panoFeature = pano.feature.clone();
            if (p.age < 0 && pano.age >= 0) p.age = pano.age;
            if (p.gender < 0 && pano.gender >= 0) p.gender = pano.gender;
        }

        return new ArrayList<>(map.values());
    }

    /** 融合后的单人：姓名 + 两路最高分 + 各路是否命中 + 特征向量（用于跨路 ReID）。 */
    public static class FusedPerson {
        public String name;
        public float bestScore;
        public boolean fromTv;
        public boolean fromPano;
        /** TV 路的特征向量（可能为 null） */
        public float[] tvFeature = null;
        /** 米家路的特征向量（可能为 null） */
        public float[] panoFeature = null;
        /** 是否通过 ReID 跨路关联（一路没识别出名字但特征匹配） */
        public boolean matchedByReid = false;
        /** 年龄（取两路中有效的，-1=未知） */
        public int age = -1;
        /** 性别（0=男，1=女，-1=未知） */
        public int gender = -1;
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
