package com.example.arcfaceandroid;

/**
 * 全局常量。
 *
 * 注意：本类只保留"真正的常量"——不涉及隐私、不随部署变化的数值阈值和默认值。
 * 涉及密钥、内网地址、账号密码、部署差异的配置已全部迁移到 {@link AppConfig}
 * （SharedPreferences 持久化，可通过 /api/config 端点热更新）。
 *
 * 开源版本：所有密钥/内网地址默认留空，使用者需在 WebUI 或 config.json 中配置。
 */
public final class Constants {

    // ===== ArcSoft 密钥（已迁移至 AppConfig，此处留空占位）=====
    // 请通过 WebUI 设置页面或 /api/config 端点配置 APP_ID / SDK_KEY。
    // 空值时 App 可安装运行，但人脸识别引擎激活失败，识别功能不可用（日志会告警）。

    /** HTTP 服务器监听端口（HA / Node-RED / memory-agent 调用此端口） */
    public static final int SERVER_PORT = 8080;

    /** 识别阈值默认值：相似度 >= 此值视为已识别（生活照 0.80，证件照 0.82）。
     *  实际运行值由 AppConfig.getMatchThreshold() 决定，可在 WebUI 调整。 */
    public static final float MATCH_THRESHOLD = 0.80f;

    // ===== 扫描式识别参数 =====
    public static final long SCAN_DEFAULT_DURATION_MS = 4000;
    public static final long SCAN_MAX_DURATION_MS = 10000;
    public static final long SCAN_JOB_TTL_MS = 300000;
    public static final int SCAN_CALLBACK_TIMEOUT_MS = 3000;
    public static final int SCAN_CALLBACK_RETRIES = 1;
    public static final long SCAN_FRAME_FRESH_MS = 2500;
    public static final long SCAN_CAMERA_WAIT_MS = 4000;

    // ===== 第二路摄像头：米家全景（经 go2rtc HTTP 接入）=====
    // 所有部署相关参数（URL/账号/密码/是否启用）已迁移至 AppConfig。
    // 以下为默认值，仅在 AppConfig 未配置时使用。
    /** 两次取帧最小间隔（ms）默认值 */
    public static final long PANORAMA_POLL_MS = 1500;
    /** 单次 HTTP 取帧超时（ms）默认值 */
    public static final int PANORAMA_FETCH_TIMEOUT_MS = 15000;
    /** 全景帧下采样到的最大宽（px）默认值 */
    public static final int PANORAMA_MAX_W = 1280;
    /** 全景路标签（与 TV 路区分，融合用） */
    public static final String PANORAMA_SOURCE = "pano";
    /** 全景路结果新鲜窗口：超过该时长没出新帧即视为米家离线（ms） */
    public static final long PANORAMA_FRESH_MS = 30000;

    // ===== 识别质量门控 =====
    /** 实时识别（TV 路）最小人脸边长：小于该值视为噪点/远处误检。
     *  单人场景优化：从 40 降到 30，更远距离的小脸也能参与识别。 */
    public static final int MIN_FACE_PX = 30;

    // ===== memory-agent 人脸节点池（Face-Node-Pool）=====
    // 部署相关参数（基址/节点ID/端点）已迁移至 AppConfig。
    // 以下为协议常量，不随部署变化。
    /** 心跳周期（ms）：注册 / 保活频率 */
    public static final long FACE_NODE_HEARTBEAT_MS = 20000;
    /** 服务内自愈巡检周期（ms） */
    public static final long SELF_HEAL_INTERVAL_MS = 30000;
    /** AlarmManager 看门狗周期（ms） */
    public static final long WATCHDOG_ALARM_MS = 120000;

    // ===== 共识 & 缓存 =====
    /** 同一姓名需连续多少帧才提交/切换显示（抑制闪烁） */
    public static final int LIVE_CONSENSUS_FRAMES = 3;
    /** 人脸消失后保留显示时长（ms） */
    public static final long LIVE_HOLD_MS = 1500;
    /** 特征缓存有效期（ms）：同一人复用比对结果，不重复提特征。
     *  单人场景优化：从 800 增加到 1500，减少重复提特征，提升性能和稳定性。 */
    public static final long FEAT_CACHE_MS = 1500;

    // ===== Hotspot 热点算法参数 =====
    public static final double HOTSPOT_DECAY = 0.985;
    public static final double HOTSPOT_ASSOC_DIST = 0.18;
    public static final double HOTSPOT_ACTIVE_W = 1.0;
    public static final double HOTSPOT_REMOVE_BELOW = 0.5;
    public static final int HOTSPOT_MAX_HOTS = 6;
    public static final long HOTSPOT_MAX_AGE_MS = 300000; // 5min
    /** 热点 ROI 扩边比例（相对热点宽高）。
     *  单人场景优化：从 0.35 增加到 0.6，增大 ROI 区域，减少人脸移动出 ROI 导致的漏检。 */
    public static final double HOTSPOT_PAD = 0.6;
    public static final double HOTSPOT_EMA_ALPHA = 0.2;
    public static final int HOTSPOT_FULL_SCAN_EVERY = 6;
    public static final int HOTSPOT_ROI_NOFACE_FULL = 8;
    public static final int HOTSPOT_FULL_BURST_FRAMES = 6;

    // ===== P1 单人识别优化 =====
    /** 光照预处理：暗光阈值（平均亮度 < 此值时触发增强），0-255 */
    public static final int LOW_LIGHT_THRESHOLD = 70;
    /** 光照预处理：伽马校正系数（暗光时用 <1.0 提亮） */
    public static final float LOW_LIGHT_GAMMA = 0.7f;
    /** 自适应阈值：历史相似度窗口大小 */
    public static final int ADAPTIVE_THRESHOLD_WINDOW = 10;
    /** 自适应阈值：相对历史平均的偏移量（阈值 = 平均 - 偏移） */
    public static final float ADAPTIVE_THRESHOLD_OFFSET = 0.08f;
    /** 自适应阈值：最低阈值（不会低于此值） */
    public static final float ADAPTIVE_THRESHOLD_MIN = 0.65f;
    /** 模板质量：注册时最小人脸边长（低于此值拒绝注册） */
    public static final int REGISTER_MIN_FACE_PX = 80;
    /** 模板质量：注册时最大人脸角度（偏转 > 此值拒绝注册），度 */
    public static final float REGISTER_MAX_ANGLE = 30f;

    // ===== P2 多人识别优化 =====
    /** 匈牙利算法：启用人数阈值（>= 此人数时用匈牙利，否则用贪心） */
    public static final int HUNGARIAN_MIN_FACES = 3;
    /** 人体 ReID：衣着颜色直方图 bins 数 */
    public static final int REID_COLOR_BINS = 16;
    /** 人体 ReID：衣着区域占人脸框的比例（向下扩展人体区域） */
    public static final float REID_BODY_RATIO = 2.5f;
    /** 人体 ReID：颜色相似度权重（与位置/特征加权） */
    public static final float REID_COLOR_WEIGHT = 0.15f;
    /** 运动检测：帧差阈值（像素差 > 此值视为运动），0-255 */
    public static final int MOTION_PIXEL_THRESHOLD = 25;
    /** 运动检测：运动区域最小面积（占画面比例） */
    public static final float MOTION_MIN_AREA_RATIO = 0.005f;
    /** 运动检测：ROI 扩边比例（运动区域外扩） */
    public static final float MOTION_ROI_PAD = 0.4f;

    // ===== P3 架构优化 =====
    /** 异步流水线：检测线程池大小 */
    public static final int PIPELINE_THREADS = 2;
    /** 热点 ROI 动态调整：移动速度阈值（px/帧），超过则扩大 ROI */
    public static final float HOTSPOT_SPEED_THRESHOLD = 15f;
    /** 热点 ROI 动态调整：高速移动时的额外扩边 */
    public static final float HOTSPOT_SPEED_PAD = 0.3f;
}
