package com.example.arcfaceandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

/**
 * 应用配置管理：所有原先硬编码在 Constants.java 中的密钥、内网地址、米家参数、节点池参数
 * 统一通过 SharedPreferences 持久化，运行时可通过 /api/config 端点热更新。
 *
 * 设计原则：
 *  - Constants.java 只保留"真正的常量"（阈值、超时、端口等不涉及隐私的数值），且作为默认值。
 *  - 涉及隐私/部署差异的值（密钥、URL、账号密码、内网IP）全部走 AppConfig。
 *  - 密码字段 GET 时返回掩码，PUT 时支持三态（缺失=不改、非空=更新、__CLEAR__=清空）。
 *  - 配置变更后通过 Listener 通知相关模块热重载（如 MijiaPanoSource 启停）。
 */
public class AppConfig {

    private static final String TAG = "AppConfig";
    private static final String PREFS_NAME = "arcface_config";

    // ===== 配置键名 =====
    public static final String KEY_ARCSOFT_APP_ID = "arcsoft_app_id";
    public static final String KEY_ARCSOFT_SDK_KEY = "arcsoft_sdk_key";

    public static final String KEY_PANO_ENABLED = "pano_enabled";
    public static final String KEY_PANO_URL = "pano_url";
    public static final String KEY_PANO_USER = "pano_user";
    public static final String KEY_PANO_PASS = "pano_pass";
    public static final String KEY_PANO_POLL_MS = "pano_poll_ms";
    public static final String KEY_PANO_FETCH_TIMEOUT_MS = "pano_fetch_timeout_ms";
    public static final String KEY_PANO_MAX_W = "pano_max_w";

    public static final String KEY_MEMORY_AGENT_BASE = "memory_agent_base";
    public static final String KEY_NODE_ID = "node_id";
    public static final String KEY_NODE_TYPE = "node_type";
    public static final String KEY_NODE_ENDPOINT = "node_endpoint";

    public static final String KEY_NAS_BASE_URL = "nas_base_url";
    public static final String KEY_AUTO_IMPORT_FROM_NAS = "auto_import_from_nas";

    public static final String KEY_MATCH_THRESHOLD = "match_threshold";
    public static final String KEY_HOTSPOT_ENABLED = "hotspot_enabled";

    /** 密码掩码哨兵值 */
    public static final String PASSWORD_MASK = "********";
    public static final String PASSWORD_CLEAR_SENTINEL = "__CLEAR__";

    private static AppConfig instance;
    private final SharedPreferences prefs;
    private ConfigChangeListener listener;

    public interface ConfigChangeListener {
        void onConfigChanged(Set<String> changedKeys);
    }

    private AppConfig(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AppConfig get(Context context) {
        if (instance == null) {
            instance = new AppConfig(context);
        }
        return instance;
    }

    public void setListener(ConfigChangeListener l) {
        this.listener = l;
    }

    // ===== 通用读写 =====

    private String getString(String key, String def) {
        return prefs.getString(key, def);
    }

    private boolean getBoolean(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    private int getInt(String key, int def) {
        return prefs.getInt(key, def);
    }

    private long getLong(String key, long def) {
        return prefs.getLong(key, def);
    }

    private float getFloat(String key, float def) {
        return prefs.getFloat(key, def);
    }

    // ===== ArcSoft 密钥 =====

    public String getArcsoftAppId() {
        return getString(KEY_ARCSOFT_APP_ID, "");
    }

    public String getArcsoftSdkKey() {
        return getString(KEY_ARCSOFT_SDK_KEY, "");
    }

    // ===== 米家全景摄像头 =====

    public boolean isPanoEnabled() {
        return getBoolean(KEY_PANO_ENABLED, false); // 开源默认关闭，用户配置后开启
    }

    public String getPanoUrl() {
        return getString(KEY_PANO_URL, "");
    }

    public String getPanoUser() {
        return getString(KEY_PANO_USER, "");
    }

    public String getPanoPass() {
        return getString(KEY_PANO_PASS, "");
    }

    public long getPanoPollMs() {
        return getLong(KEY_PANO_POLL_MS, Constants.PANORAMA_POLL_MS);
    }

    public int getPanoFetchTimeoutMs() {
        return getInt(KEY_PANO_FETCH_TIMEOUT_MS, Constants.PANORAMA_FETCH_TIMEOUT_MS);
    }

    public int getPanoMaxW() {
        return getInt(KEY_PANO_MAX_W, Constants.PANORAMA_MAX_W);
    }

    // ===== memory-agent 节点池 =====

    public String getMemoryAgentBase() {
        return getString(KEY_MEMORY_AGENT_BASE, "");
    }

    public String getNodeId() {
        return getString(KEY_NODE_ID, "tv-livingroom");
    }

    public String getNodeType() {
        return getString(KEY_NODE_TYPE, "tv");
    }

    public String getNodeEndpoint() {
        return getString(KEY_NODE_ENDPOINT, "");
    }

    // ===== NAS 人脸库导入 =====

    public String getNasBaseUrl() {
        return getString(KEY_NAS_BASE_URL, "");
    }

    public boolean isAutoImportFromNas() {
        return getBoolean(KEY_AUTO_IMPORT_FROM_NAS, false);
    }

    // ===== 识别参数 =====

    public float getMatchThreshold() {
        return getFloat(KEY_MATCH_THRESHOLD, Constants.MATCH_THRESHOLD);
    }

    public boolean isHotspotEnabled() {
        return getBoolean(KEY_HOTSPOT_ENABLED, true);
    }

    // ===== 批量更新（来自 /api/config PUT）=====

    /**
     * 从 JSON 对象批量更新配置。
     * 密码字段支持三态：字段缺失/null → 不改；非空新值 → 更新；等于 __CLEAR__ → 清空。
     * 返回实际变更的键名集合。
     */
    public Set<String> updateFromJson(JSONObject json) {
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> changed = new java.util.HashSet<>();

        // ArcSoft 密钥
        if (json.has(KEY_ARCSOFT_APP_ID)) {
            String v = json.optString(KEY_ARCSOFT_APP_ID, null);
            if (v != null) { editor.putString(KEY_ARCSOFT_APP_ID, v); changed.add(KEY_ARCSOFT_APP_ID); }
        }
        if (json.has(KEY_ARCSOFT_SDK_KEY)) {
            String v = json.optString(KEY_ARCSOFT_SDK_KEY, null);
            if (v != null) { editor.putString(KEY_ARCSOFT_SDK_KEY, v); changed.add(KEY_ARCSOFT_SDK_KEY); }
        }

        // 米家
        if (json.has(KEY_PANO_ENABLED)) {
            boolean v = json.optBoolean(KEY_PANO_ENABLED, isPanoEnabled());
            editor.putBoolean(KEY_PANO_ENABLED, v); changed.add(KEY_PANO_ENABLED);
        }
        if (json.has(KEY_PANO_URL)) {
            String v = json.optString(KEY_PANO_URL, null);
            if (v != null) { editor.putString(KEY_PANO_URL, v); changed.add(KEY_PANO_URL); }
        }
        if (json.has(KEY_PANO_USER)) {
            String v = json.optString(KEY_PANO_USER, null);
            if (v != null) { editor.putString(KEY_PANO_USER, v); changed.add(KEY_PANO_USER); }
        }
        // 密码三态
        if (json.has(KEY_PANO_PASS)) {
            String v = json.optString(KEY_PANO_PASS, null);
            if (v != null && !PASSWORD_MASK.equals(v)) {
                if (PASSWORD_CLEAR_SENTINEL.equals(v)) {
                    editor.remove(KEY_PANO_PASS);
                } else {
                    editor.putString(KEY_PANO_PASS, v);
                }
                changed.add(KEY_PANO_PASS);
            }
        }
        if (json.has(KEY_PANO_POLL_MS)) {
            int v = json.optInt(KEY_PANO_POLL_MS, (int) getPanoPollMs());
            editor.putLong(KEY_PANO_POLL_MS, v); changed.add(KEY_PANO_POLL_MS);
        }
        if (json.has(KEY_PANO_FETCH_TIMEOUT_MS)) {
            int v = json.optInt(KEY_PANO_FETCH_TIMEOUT_MS, getPanoFetchTimeoutMs());
            editor.putInt(KEY_PANO_FETCH_TIMEOUT_MS, v); changed.add(KEY_PANO_FETCH_TIMEOUT_MS);
        }
        if (json.has(KEY_PANO_MAX_W)) {
            int v = json.optInt(KEY_PANO_MAX_W, getPanoMaxW());
            editor.putInt(KEY_PANO_MAX_W, v); changed.add(KEY_PANO_MAX_W);
        }

        // 节点池
        if (json.has(KEY_MEMORY_AGENT_BASE)) {
            String v = json.optString(KEY_MEMORY_AGENT_BASE, null);
            if (v != null) { editor.putString(KEY_MEMORY_AGENT_BASE, v); changed.add(KEY_MEMORY_AGENT_BASE); }
        }
        if (json.has(KEY_NODE_ID)) {
            String v = json.optString(KEY_NODE_ID, null);
            if (v != null) { editor.putString(KEY_NODE_ID, v); changed.add(KEY_NODE_ID); }
        }
        if (json.has(KEY_NODE_TYPE)) {
            String v = json.optString(KEY_NODE_TYPE, null);
            if (v != null) { editor.putString(KEY_NODE_TYPE, v); changed.add(KEY_NODE_TYPE); }
        }
        if (json.has(KEY_NODE_ENDPOINT)) {
            String v = json.optString(KEY_NODE_ENDPOINT, null);
            if (v != null) { editor.putString(KEY_NODE_ENDPOINT, v); changed.add(KEY_NODE_ENDPOINT); }
        }

        // NAS
        if (json.has(KEY_NAS_BASE_URL)) {
            String v = json.optString(KEY_NAS_BASE_URL, null);
            if (v != null) { editor.putString(KEY_NAS_BASE_URL, v); changed.add(KEY_NAS_BASE_URL); }
        }
        if (json.has(KEY_AUTO_IMPORT_FROM_NAS)) {
            boolean v = json.optBoolean(KEY_AUTO_IMPORT_FROM_NAS, isAutoImportFromNas());
            editor.putBoolean(KEY_AUTO_IMPORT_FROM_NAS, v); changed.add(KEY_AUTO_IMPORT_FROM_NAS);
        }

        // 识别参数
        if (json.has(KEY_MATCH_THRESHOLD)) {
            float v = (float) json.optDouble(KEY_MATCH_THRESHOLD, getMatchThreshold());
            editor.putFloat(KEY_MATCH_THRESHOLD, v); changed.add(KEY_MATCH_THRESHOLD);
        }
        if (json.has(KEY_HOTSPOT_ENABLED)) {
            boolean v = json.optBoolean(KEY_HOTSPOT_ENABLED, isHotspotEnabled());
            editor.putBoolean(KEY_HOTSPOT_ENABLED, v); changed.add(KEY_HOTSPOT_ENABLED);
        }

        editor.apply();

        if (listener != null && !changed.isEmpty()) {
            try { listener.onConfigChanged(changed); } catch (Exception e) { Log.w(TAG, "listener error", e); }
        }
        return changed;
    }

    // ===== 导出为 JSON（GET /api/config 用，密码掩码）=====

    public JSONObject toJson(boolean includeSecrets) {
        JSONObject o = new JSONObject();
        try {
            o.put(KEY_ARCSOFT_APP_ID, maskIfNeeded(getArcsoftAppId(), includeSecrets));
            o.put(KEY_ARCSOFT_SDK_KEY, maskIfNeeded(getArcsoftSdkKey(), includeSecrets));

            o.put(KEY_PANO_ENABLED, isPanoEnabled());
            o.put(KEY_PANO_URL, getPanoUrl());
            o.put(KEY_PANO_USER, getPanoUser());
            o.put(KEY_PANO_PASS, maskIfNeeded(getPanoPass(), includeSecrets));
            o.put(KEY_PANO_POLL_MS, getPanoPollMs());
            o.put(KEY_PANO_FETCH_TIMEOUT_MS, getPanoFetchTimeoutMs());
            o.put(KEY_PANO_MAX_W, getPanoMaxW());

            o.put(KEY_MEMORY_AGENT_BASE, getMemoryAgentBase());
            o.put(KEY_NODE_ID, getNodeId());
            o.put(KEY_NODE_TYPE, getNodeType());
            o.put(KEY_NODE_ENDPOINT, getNodeEndpoint());

            o.put(KEY_NAS_BASE_URL, getNasBaseUrl());
            o.put(KEY_AUTO_IMPORT_FROM_NAS, isAutoImportFromNas());

            o.put(KEY_MATCH_THRESHOLD, getMatchThreshold());
            o.put(KEY_HOTSPOT_ENABLED, isHotspotEnabled());
        } catch (JSONException e) {
            Log.w(TAG, "toJson error", e);
        }
        return o;
    }

    private static String maskIfNeeded(String value, boolean include) {
        if (include) return value;
        return (value == null || value.isEmpty()) ? "" : PASSWORD_MASK;
    }
}
