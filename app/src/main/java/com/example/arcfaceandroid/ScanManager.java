package com.example.arcfaceandroid;

import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫描式人脸识别会话管理。
 *
 * 设计：TV 前台服务本来就在持续逐帧识别（FaceServerService.frameListener -> analyze() ->
 * FaceServer.recognizeNv21() -> RecognitionState）。扫描只「订阅」RecognitionState 的更新并跨帧
 * 累计取最优结果，命中熟人（score >= threshold）即提前结束。这样不重复调用 FaceServer.recognize()
 * （它是 synchronized，会阻塞 UI 识别线程并翻倍算力），即用户要的「最短路径」。
 *
 * 多人场景：本类对每一帧识别结果做跨帧轨迹聚合（优先用视频模式 faceId，回退用人脸中心距离关联），
 * 输出 {@code persons[]} 数组（每人姓名/相似度/位置/帧数/首末出现时间），保证镜头里同时多人均被回传。
 * 默认策略：扫满窗口，但「人数稳定N帧」即提前收敛（settle），兼顾完整与速度。
 */
public final class ScanManager {

    private static final String TAG = "ScanManager";

    private static final ScanManager INSTANCE = new ScanManager();
    public static ScanManager getInstance() { return INSTANCE; }

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "scan-scheduler");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private final ExecutorService callbackExec =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "scan-callback"));

    /** 活跃扫描：同一时刻只允许一个。 */
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile Job currentJob = null;

    /** 历史 job 结果（有界 + TTL），供 /api/scan/result 查询。 */
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private volatile Job latest = null;

    private ScanManager() {}

    /**
     * 触发一次扫描。
     * @param fast    true=命中任一熟人立即结束（最快，但可能漏掉镜头里的其他人）；默认 false
     * @param settleFrames 人数连续稳定达到该帧数即提前收敛结束（0=不提前，等满窗口）
     * @return jobId（若已有活跃扫描，返回同一 jobId，避免重复回调）
     */
    public synchronized String startScan(long durationMs, float threshold, String callback,
                                          boolean includeSnapshot, boolean fast, int settleFrames) {
        // TTL 清理，避免历史结果无限堆积
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Job> e : jobs.entrySet()) {
            if (now - e.getValue().startMs > Constants.SCAN_JOB_TTL_MS) jobs.remove(e.getKey());
        }

        if (active.get() && currentJob != null && !currentJob.finished.get()) {
            Log.i(TAG, "scan already running -> reuse " + currentJob.jobId);
            return currentJob.jobId;
        }

        final Job job = new Job(durationMs, threshold, callback, includeSnapshot, fast, settleFrames);
        job.frameW = RecognitionState.get().getFrameW();
        currentJob = job;
        active.set(true);
        jobs.put(job.jobId, job); // 立即可被 /api/scan/result 查询（running 状态）

        // 扫描期提速分析频率与帧推送，恢复在 finishJob 中
        FaceServerService svc = FaceServerService.getInstance();
        if (svc != null) svc.setScanAnalysisBoost(true);
        if (svc != null) svc.setForceFullScan(true);     // 扫描期强制全图，保证多人同时检出

        final RecognitionState.Listener listener = new RecognitionState.Listener() {
            @Override
            public void onUpdate() {
                accumulate(job);
                boolean anyMatched = jobAnyMatched(job);
                if (job.fast && anyMatched) {
                    finishJob(job, "matched");
                } else if (job.settleTarget > 0 && job.settleStableCount >= job.settleTarget) {
                    finishJob(job, "settled");
                }
            }
        };
        job.listener = listener;
        RecognitionState.get().register(listener);

        // 立即分析当前帧，省去等待下一个节流窗口（首帧即时可用）
        if (svc != null) svc.requestImmediateAnalyze();

        job.timeoutTask = scheduler.schedule(() -> finishJob(job, "timeout"),
                durationMs, TimeUnit.MILLISECONDS);

        Log.i(TAG, "scan started jobId=" + job.jobId + " durationMs=" + durationMs
                + " threshold=" + threshold + " fast=" + fast + " settle=" + settleFrames
                + " callback=" + (callback != null ? callback : "-"));
        return job.jobId;
    }

    /** 把一次识别结果帧累计进 job 的多人轨迹。 */
    private void accumulate(Job job) {
        List<FaceServer.RecognizeResult> results = RecognitionState.get().getResults();
        if (results == null || results.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (job) {
            job.claimed.clear();                 // 每帧重置认领表
            job.sawFace = true;
            job.framesSampled++;
            for (FaceServer.RecognizeResult r : results) {
                Track t = matchTrack(job, r);
                t.update(r, job.threshold, now);
                // 维持全局最高分（兼容旧字段 name/similarity/rect）
                if (r.score > job.bestScore) {
                    job.bestScore = r.score;
                    job.bestName = r.name;
                    job.bestRect = r.rect;
                }
            }
            job.bestFaces = results.size();
            // settle 提前收敛判定：连续 settleTarget 帧人数稳定
            if (job.settleTarget > 0) {
                int n = job.tracks.size();
                if (n == job.lastCount) job.settleStableCount++;
                else { job.settleStableCount = (n > 0) ? 1 : 0; job.lastCount = n; }
            }
        }
    }

    /** 将单帧的一张脸关联到已有轨迹（faceId 优先，回退中心距离贪心）。 */
    private Track matchTrack(Job job, FaceServer.RecognizeResult r) {
        if (r.faceId > 0) {
            for (Track t : job.tracks.values()) {
                if (t.faceId == r.faceId && !job.claimed.contains(t.id)) {
                    job.claimed.add(t.id);
                    return t;
                }
            }
        }
        if (r.rect != null) {
            int cx = r.rect.centerX(), cy = r.rect.centerY();
            Track best = null;
            float bestD = Float.MAX_VALUE;
            for (Track t : job.tracks.values()) {
                if (t.bestRect == null || job.claimed.contains(t.id)) continue;
                float d = (float) Math.hypot(cx - t.bestRect.centerX(), cy - t.bestRect.centerY());
                if (d < bestD) { bestD = d; best = t; }
            }
            int thr = job.frameW > 0 ? (int) (job.frameW * 0.2f) : 200;
            if (best != null && bestD < thr) { job.claimed.add(best.id); return best; }
        }
        Track t = new Track();
        t.faceId = r.faceId;
        t.bestName = r.name;
        t.bestScore = r.score;
        t.bestRect = r.rect;
        t.frames = 0;
        t.firstMs = System.currentTimeMillis();
        t.lastMs = t.firstMs;
        t.id = "p" + job.tracks.size();
        job.tracks.put(t.id, t);
        job.claimed.add(t.id);
        return t;
    }

    private static boolean jobAnyMatched(Job job) {
        for (Track t : job.tracks.values()) if (t.matchedOnce) return true;
        return false;
    }

    /** 把米家全景路（RecognitionState.panoResults）命中阈值的人并入扫描轨迹：
     *  TV 已有同名轨迹则取最高分，否则新增一条 pano 来源轨迹，确保 personCount 含 TV 漏检的人。 */
    private void mergePano(Job job) {
        List<FaceServer.RecognizeResult> pano = RecognitionState.get().getPanoResults();
        if (pano == null || pano.isEmpty()) return;
        synchronized (job) {
            for (FaceServer.RecognizeResult r : pano) {
                if (r == null || r.name == null || "未知".equals(r.name)) continue;
                if (r.score < job.threshold) continue;
                Track existing = null;
                for (Track t : job.tracks.values()) {
                    if (t.bestName != null && t.bestName.equals(r.name)) { existing = t; break; }
                }
                if (existing != null) {
                    if (r.score > existing.bestScore) {
                        existing.bestScore = r.score;
                        existing.bestRect = r.rect;
                    }
                    existing.matchedOnce = true;
                } else {
                    Track t = new Track();
                    t.bestName = r.name;
                    t.bestScore = r.score;
                    t.bestRect = r.rect;
                    t.frames = 1;
                    t.firstMs = System.currentTimeMillis();
                    t.lastMs = t.firstMs;
                    t.faceId = -1;
                    t.id = "pano_" + r.name;
                    t.matchedOnce = true;
                    job.tracks.put(t.id, t);
                }
            }
        }
    }

    /** 结束扫描并产出结果。幂等：同一 job 仅结束一次。 */
    private void finishJob(Job job, String reason) {
        if (!job.finished.compareAndSet(false, true)) return;
        long elapsed = System.currentTimeMillis() - job.startMs;
        String state = classify(job);
        Log.i(TAG, "scan finished jobId=" + job.jobId + " reason=" + reason
                + " state=" + state + " persons=" + job.tracks.size()
                + " bestScore=" + job.bestScore + " bestName=" + job.bestName
                + " frames=" + job.framesSampled + " elapsed=" + elapsed);

        if (job.timeoutTask != null) job.timeoutTask.cancel(false);
        if (job.listener != null) RecognitionState.get().unregister(job.listener);

        FaceServerService svc = FaceServerService.getInstance();
        if (svc != null) svc.setScanAnalysisBoost(false);
        if (svc != null) svc.setForceFullScan(false);    // 恢复 ROI 提速

        // 双路融合：把米家全景路在扫描窗口内识别到的人并入结果（补 TV 漏检的人员）
        mergePano(job);

        job.resultJson = buildResultJson(job);
        latest = job;            // 缓存为最新结果
        job.doneLatch.countDown();

        if (job.callback != null && !job.callback.isEmpty()) {
            final String cb = job.callback;
            final String payload = job.resultJson;
            callbackExec.execute(() -> postCallback(cb, payload));
        }

        if (active.get() && currentJob == job) active.set(false);
    }

    private static String classify(Job job) {
        if (jobAnyMatched(job)) return "matched";
        if (job.sawFace) return "unknown";
        return "no_face";
    }

    private String buildResultJson(Job job) {
        try {
            JSONObject o = new JSONObject();
            String state = classify(job);
            boolean anyMatched = jobAnyMatched(job);
            o.put("jobId", job.jobId);
            o.put("success", true);
            o.put("state", state);
            o.put("matched", anyMatched);

            // 兼容旧字段：取最高分轨迹
            Track best = null;
            for (Track t : job.tracks.values()) {
                if (best == null || t.bestScore > best.bestScore) best = t;
            }
            o.put("name", best != null && best.matchedOnce ? (best.bestName == null ? "" : best.bestName) : "");
            o.put("similarity", best != null ? Math.round(best.bestScore * 1000d) / 1000d : 0d);
            o.put("threshold", job.threshold);
            o.put("faces", job.bestFaces);
            if (best != null && best.bestRect != null) {
                JSONObject rc = new JSONObject();
                rc.put("left", best.bestRect.left);
                rc.put("top", best.bestRect.top);
                rc.put("right", best.bestRect.right);
                rc.put("bottom", best.bestRect.bottom);
                o.put("rect", rc);
            }

            // 多人结果
            o.put("personCount", job.tracks.size());
            o.put("strategy", job.fast ? "fast" : (job.settleTarget > 0 ? "settle" : "full"));
            JSONArray arr = new JSONArray();
            for (Track t : job.tracks.values()) {
                JSONObject it = new JSONObject();
                it.put("id", t.id);
                it.put("matched", t.matchedOnce);
                it.put("name", t.matchedOnce ? (t.bestName == null ? "" : t.bestName) : "");
                it.put("similarity", Math.round(t.bestScore * 1000d) / 1000d);
                it.put("frames", t.frames);
                it.put("faceId", t.faceId);
                if (t.bestRect != null) {
                    JSONObject rc = new JSONObject();
                    rc.put("left", t.bestRect.left);
                    rc.put("top", t.bestRect.top);
                    rc.put("right", t.bestRect.right);
                    rc.put("bottom", t.bestRect.bottom);
                    it.put("rect", rc);
                }
                it.put("firstMs", t.firstMs);
                it.put("lastMs", t.lastMs);
                arr.put(it);
            }
            o.put("persons", arr);

            o.put("framesSampled", job.framesSampled);
            o.put("durationMs", System.currentTimeMillis() - job.startMs);
            o.put("ts", job.startMs);
            if (job.includeSnapshot) {
                byte[] jpg = FrameBuffer.get().getLatestSnapshotJpeg();
                if (jpg != null) o.put("snapshot", Base64.encodeToString(jpg, Base64.NO_WRAP));
            }
            return o.toString();
        } catch (Exception e) {
            Log.e(TAG, "buildResultJson error", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 同步等待结果（wait=1 模式）；超时返回 null。 */
    public String waitForResult(String jobId, long timeoutMs) {
        Job j = jobs.get(jobId);
        if (j == null) return null;
        try {
            j.doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return j.resultJson;
    }

    /** 查询指定 job 结果；未结束返回 running 占位；超时清理返回 null。 */
    public String getJobResult(String jobId) {
        Job j = jobs.get(jobId);
        if (j == null) return null;
        if (System.currentTimeMillis() - j.startMs > Constants.SCAN_JOB_TTL_MS) {
            jobs.remove(jobId);
            return null;
        }
        if (j.resultJson != null) return j.resultJson;
        return "{\"jobId\":\"" + jobId + "\",\"status\":\"running\"}";
    }

    /** 最近一次扫描结果；从未扫描时回落到 RecognitionState 当前实时结果 + 新鲜度。 */
    public String getLatest() {
        Job j = latest;
        if (j == null) {
            JSONObject o = new JSONObject();
            try {
                o.put("live", true);
                o.put("success", true);
                List<FaceServer.RecognizeResult> results = RecognitionState.get().getResults();
                int n = results == null ? 0 : results.size();
                o.put("faces", n);
                o.put("personCount", n);
                long last = RecognitionState.get().getLastUpdateMs();
                o.put("freshMs", last > 0 ? System.currentTimeMillis() - last : -1);
                o.put("ts", last);
                JSONArray arr = new JSONArray();
                if (results != null) for (FaceServer.RecognizeResult r : results) {
                    JSONObject it = new JSONObject();
                    it.put("name", r.name == null ? "" : r.name);
                    it.put("score", Math.round(r.score * 1000d) / 1000d);
                    arr.put(it);
                }
                o.put("results", arr);
                o.put("persons", arr);
                return o.toString();
            } catch (Exception e) {
                return "{\"error\":\"no scan yet\"}";
            }
        }
        return j.resultJson;
    }

    /** 异步回调 POST（带超时与重试）。 */
    private void postCallback(String url, String payload) {
        int retries = Constants.SCAN_CALLBACK_RETRIES;
        for (int attempt = 0; attempt <= retries; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(Constants.SCAN_CALLBACK_TIMEOUT_MS);
                conn.setReadTimeout(Constants.SCAN_CALLBACK_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Connection", "close");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    Log.i(TAG, "callback ok -> " + url);
                    return;
                }
                Log.w(TAG, "callback http " + code + " -> " + url);
            } catch (Exception e) {
                Log.w(TAG, "callback failed attempt " + attempt + " -> " + url, e);
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignore) {}
            }
            if (attempt < retries) sleepQuietly(500);
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** 单张人脸轨迹：跨帧累计最优分数/姓名/位置，用于多人聚合。 */
    private static final class Track {
        String id;
        int faceId = -1;
        String bestName = null;
        float bestScore = 0f;
        Rect bestRect = null;
        int frames = 0;
        long firstMs;
        long lastMs;
        boolean matchedOnce = false;

        void update(FaceServer.RecognizeResult r, float threshold, long now) {
            frames++;
            if (r.score > bestScore) {
                bestScore = r.score;
                bestName = r.name;
                bestRect = r.rect;
            }
            if (r.name != null && !"未知".equals(r.name) && r.score >= threshold) matchedOnce = true;
            lastMs = now;
        }
    }

    /** 扫描会话（不可变参数 + 累计可变状态）。 */
    private static final class Job {
        final String jobId;
        final long startMs = System.currentTimeMillis();
        final long durationMs;
        final float threshold;
        final String callback;
        final boolean includeSnapshot;
        final boolean fast;
        final int settleTarget;
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean(false);

        volatile RecognitionState.Listener listener;
        volatile ScheduledFuture<?> timeoutTask;

        /** 多人轨迹聚合表 */
        final Map<String, Track> tracks = new HashMap<>();
        /** 本帧已被认领的轨迹 id：防止同帧多张脸被合并进同一条轨迹（并排多人必须是独立人）。 */
        final Set<String> claimed = new HashSet<>();
        volatile int frameW = 0;
        volatile int lastCount = -1;
        volatile int settleStableCount = 0;

        volatile float bestScore = 0f;
        volatile String bestName = null;
        volatile Rect bestRect = null;
        volatile int bestFaces = 0;
        volatile boolean sawFace = false;
        volatile int framesSampled = 0;

        volatile String resultJson = null;

        Job(long durationMs, float threshold, String callback, boolean includeSnapshot,
            boolean fast, int settleFrames) {
            this.jobId = UUID.randomUUID().toString();
            this.durationMs = durationMs;
            this.threshold = threshold;
            this.callback = callback;
            this.includeSnapshot = includeSnapshot;
            this.fast = fast;
            this.settleTarget = settleFrames > 0 ? settleFrames : 0;
        }
    }
}
