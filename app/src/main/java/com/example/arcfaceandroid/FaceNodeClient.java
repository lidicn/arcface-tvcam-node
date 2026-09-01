package com.example.arcfaceandroid;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 节点侧客户端：向 memory-agent 注册自己、定时心跳、并下行同步人脸库。
 *
 * 让 TV 端成为 Face-Node-Pool 中的一个节点（type=tv），使 memory-agent 可经统一接口
 * POST /face/recognize 调本机 ArcFace 做人脸识别，并拿到常驻的人脸特征库（中央集权）。
 *
 * 部署相关参数（memory_agent_base / node_id / node_type / node_endpoint）全部从
 * {@link AppConfig}（SharedPreferences）读取，可通过 /api/config 端点热更新。
 * memory-agent 不可达时仅日志，绝不拖垮本服务。
 */
public class FaceNodeClient {
    private static final String TAG = "FaceNodeClient";
    private final ScheduledExecutorService sched =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "face-node-client");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private volatile boolean registered = false;
    private final Context appContext;

    public FaceNodeClient(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void start() {
        sched.execute(this::registerOnce);
        sched.scheduleWithFixedDelay(this::tick,
                Constants.FACE_NODE_HEARTBEAT_MS, Constants.FACE_NODE_HEARTBEAT_MS,
                TimeUnit.MILLISECONDS);
        Log.i(TAG, "node client started");
    }

    public void stop() {
        sched.shutdownNow();
    }

    /** 配置变更后调用：重置注册状态，下次 tick 重新注册（使用新的 memory_agent_base 等） */
    public void onConfigChanged() {
        registered = false;
    }

    private void tick() {
        if (!registered) registerOnce();
        else heartbeatOnce();
    }

    private void registerOnce() {
        AppConfig cfg = AppConfig.get(appContext);
        String base = cfg.getMemoryAgentBase();
        if (base == null || base.isEmpty()) {
            Log.d(TAG, "memory_agent_base not configured, skip registration");
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("node_id", cfg.getNodeId());
            body.put("type", cfg.getNodeType());
            body.put("capability", "arcface");
            body.put("endpoint", cfg.getNodeEndpoint());
            int code = postJson(base + "/face/node/register", body);
            if (code >= 200 && code < 300) {
                registered = true;
                Log.i(TAG, "registered node " + cfg.getNodeId() + " -> " + cfg.getNodeEndpoint());
                syncLibFromAgent(cfg);
            } else {
                Log.w(TAG, "register failed http=" + code);
            }
        } catch (Throwable t) {
            Log.w(TAG, "register error", t);
        }
    }

    private void heartbeatOnce() {
        AppConfig cfg = AppConfig.get(appContext);
        String base = cfg.getMemoryAgentBase();
        if (base == null || base.isEmpty()) { registered = false; return; }
        try {
            JSONObject body = new JSONObject();
            body.put("node_id", cfg.getNodeId());
            int code = postJson(base + "/face/node/heartbeat", body);
            if (code >= 200 && code < 300) {
                // ok
            } else if (code == 404) {
                registered = false;
            }
        } catch (Throwable t) {
            // 网络不可达等：保持 registered，下次再试
        }
    }

    /** 从 memory-agent 拉取人脸特征库并注册到本地 ArcFace（best-effort）。 */
    private void syncLibFromAgent(AppConfig cfg) {
        String base = cfg.getMemoryAgentBase();
        if (base == null || base.isEmpty()) return;
        try {
            String resp = getJson(base + "/face/lib");
            if (resp == null) return;
            JSONObject o = new JSONObject(resp);
            JSONArray members = o.optJSONArray("members");
            if (members == null) return;
            FaceServer fs = FaceServer.getInstance();
            int imported = 0;
            for (int i = 0; i < members.length(); i++) {
                JSONObject m = members.getJSONObject(i);
                String name = m.optString("name", "");
                String featB64 = m.optString("feature", "");
                if (name.isEmpty() || featB64.isEmpty()) continue;
                try {
                    byte[] feat = Base64.decode(featB64, Base64.NO_WRAP);
                    if (fs.addFaceFeature(feat, name)) imported++;
                } catch (Throwable ignore) {}
            }
            if (imported > 0) Log.i(TAG, "synced " + imported + " face features from memory-agent");
        } catch (Throwable t) {
            Log.w(TAG, "syncLib error", t);
        }
    }

    /** 上行：本机新注册一个人脸，通知 memory-agent 中央库（供其它节点/VLM 对齐）。 */
    public void uploadFeature(String name, byte[] feature) {
        if (name == null || feature == null) return;
        sched.execute(() -> {
            AppConfig cfg = AppConfig.get(appContext);
            String base = cfg.getMemoryAgentBase();
            if (base == null || base.isEmpty()) return;
            try {
                JSONObject body = new JSONObject();
                body.put("name", name);
                body.put("feature", Base64.encodeToString(feature, Base64.NO_WRAP));
                postJson(base + "/face/lib", body);
            } catch (Throwable ignore) {}
        });
    }

    // ---- 简易 HttpURLConnection 客户端（零三方依赖）----

    private int postJson(String urlStr, JSONObject body) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            return conn.getResponseCode();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String getJson(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            try (InputStream in = conn.getInputStream()) {
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return new String(os.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
