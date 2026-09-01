# arcface-tvcam-node

> Android TV 端人脸识别节点：基于虹软 ArcFace 3.0 的常驻人脸识别服务，对外提供 HTTP API，支持双路摄像头（TV USB 摄像头 + 米家全景/go2rtc）、多人识别、热点 ROI 提速，并可作为 memory-agent 人脸节点池中的 `type=tv` 节点。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

---

## 功能特性

- **实时人脸识别**：TV 端常驻前台服务，USB 摄像头取流，实时画框 + 命名
- **双路融合**：TV 近景摄像头 + 米家全景摄像头（经 go2rtc HTTP 取帧），按姓名融合补漏
- **多人识别**：同帧多脸检测 + 跨帧人员关联（faceId + 特征相似度）+ 姓名投票迟滞
- **热点 ROI 提速**：在线学习人脸常现位置，只在热点区域检测，周期性全图兜底
- **远距离小脸增强**：人脸区域裁剪 + 上采样后提特征，提升远距离识别率
- **WebUI 管理**：浏览器访问 `http://<tv-ip>:8080/` 进行人脸注册、管理、系统配置
- **HTTP API**：原生 `ServerSocket` 实现，零三方依赖，提供识别/注册/扫描/节点契约等端点
- **人脸节点池**：可注册到 memory-agent，统一 `/face/recognize` 入口 + 中央人脸库下行同步
- **多层保活**：前台服务 + WorkManager + AlarmManager + 进程内自愈 + 开机自启，对抗 TV 系统杀进程

---

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Android TV (本项目)                        │
│                                                              │
│  ┌──────────────┐    ┌──────────────┐                      │
│  │ TV USB Camera│    │ 米家/go2rtc   │                      │
│  │ (Camera2)    │    │ (HTTP 取帧)   │                      │
│  └──────┬───────┘    └──────┬───────┘                      │
│         │                     │                               │
│         ▼                     ▼                               │
│  ┌─────────────────────────────────────────┐                │
│  │         FaceServerService (前台服务)      │                │
│  │  ┌───────────┐  ┌────────────────────┐  │                │
│  │  │ FaceServer │  │ RecognitionState    │  │                │
│  │  │ (ArcSoft)  │  │ (共识/融合/缓存)    │  │                │
│  │  └───────────┘  └────────────────────┘  │                │
│  │  ┌───────────┐  ┌────────────────────┐  │                │
│  │  │ HotspotMgr│  │ FaceNodeClient      │  │                │
│  │  │ (ROI提速)  │  │ (memory-agent节点)  │  │                │
│  │  └───────────┘  └────────────────────┘  │                │
│  └──────────────────────┬──────────────────┘                │
│                         │                                    │
│                         ▼                                    │
│  ┌─────────────────────────────────────────┐                │
│  │        FaceHttpServer (端口 8080)        │                │
│  │  /api/*  +  /face/*  +  WebUI           │                │
│  └──────────────────────┬──────────────────┘                │
└─────────────────────────┼───────────────────────────────────┘
                          │ HTTP
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   Home Assistant    Node-RED      memory-agent
   (rest_command)   (http节点)     (人脸节点池/中央库)
```

---

## 快速开始

### 1. 环境要求

- JDK 17+
- Android SDK（platform-34, build-tools）
- NDK（仅在需要编译 UVC 原生库时需要，默认 Camera2 取流无需 NDK）
- ArcSoft ArcFace 3.0 SDK（需自行下载，见下方说明）

### 2. 获取 ArcSoft SDK

> **重要**：本仓库不包含 ArcSoft SDK 文件（`.jar` / `.so`），受 ArcSoft 许可协议禁止再分发。

1. 访问 [虹软视觉开放平台](https://ai.arcsoft.com.cn/)，注册账号
2. 创建应用，包名填写 `com.example.arcfaceandroid`（或你自己的包名）
3. 下载 Android 平台 ArcFace 3.0 SDK
4. 将以下文件放入对应目录：
   - `app/libs/arcsoft_face.jar`
   - `app/src/main/jniLibs/arm64-v8a/libarcsoft_face.so`
   - `app/src/main/jniLibs/arm64-v8a/libarcsoft_face_engine.so`
   - `app/src/main/jniLibs/armeabi-v7a/libarcsoft_face.so`
   - `app/src/main/jniLibs/armeabi-v7a/libarcsoft_face_engine.so`
5. 记录 `APP_ID` 和 `SDK_KEY`，安装后在 WebUI 配置页填写

### 3. 构建

```bash
# 克隆仓库
git clone https://github.com/lidicn/arcface-tvcam-node.git
cd arcface-tvcam-node

# 构建 Debug APK
./gradlew assembleDebug

# 产物路径
# app/build/outputs/apk/debug/app-debug.apk
```

### 4. 安装与配置

```bash
# 安装到 TV（需 ADB 连接）
adb install app/build/outputs/apk/debug/app-debug.apk
```

1. 打开 App，授予摄像头权限
2. 浏览器访问 `http://<tv-ip>:8080/`
3. 进入「设置」页，填写 ArcSoft `APP_ID` / `SDK_KEY`
4. （可选）配置米家全景摄像头：go2rtc URL、账号、密码
5. （可选）配置 memory-agent 节点池地址
6. 回到首页，注册人脸，开始使用

---

## 配置说明

所有配置均可通过 WebUI 设置页或 `PUT /api/config` 端点修改，运行时热生效。

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `arcsoft_app_id` | 虹软 APP_ID | 空（需配置） |
| `arcsoft_sdk_key` | 虹软 SDK_KEY | 空（需配置） |
| `pano_enabled` | 是否启用米家第二路 | false |
| `pano_url` | go2rtc 取帧端点 URL | 空 |
| `pano_user` / `pano_pass` | go2rtc Basic Auth 凭据 | 空 |
| `memory_agent_base` | memory-agent 地址（为空则独立运行） | 空 |
| `node_id` | 节点唯一 ID | `tv-livingroom` |
| `node_endpoint` | 本机对外可达端点 | `http://<tv-ip>:8080` |
| `match_threshold` | 识别相似度阈值 | 0.80 |
| `hotspot_enabled` | 热点 ROI 提速 | true |

详见 [config.sample.json](config.sample.json)。

---

## HTTP API

Base URL：`http://<tv-ip>:8080`

### 人脸管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/faces` | 已注册人脸列表 |
| POST | `/api/register?name=张三` | 注册人脸（图片 body） |
| DELETE | `/api/face?name=张三` | 删除单条人脸 |
| POST | `/api/clear` | 清空人脸库 |
| GET | `/api/snapshot` | 当前摄像头帧 JPEG |

### 识别

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/recognize` | 识别图片中所有人脸 |
| POST | `/api/scan` | 扫描式识别（多帧/主动触发/异步回调） |
| GET | `/api/scan/result?jobId=` | 查询扫描结果 |
| GET | `/api/scan/latest` | 最新识别状态（含双路融合） |

### 节点契约（memory-agent 调用）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/face/recognize` | 统一识别入口 |
| GET | `/face/lib` | 导出本机人脸库 |
| POST | `/face/lib` | 导入人脸特征（下行同步） |

### 配置 & 调试

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/config` | 读取配置（密码掩码） |
| PUT | `/api/config` | 保存配置（热生效） |
| GET | `/api/hotspots` | 查看当前热点 |
| POST | `/api/hotspots/reset` | 重置热点 |
| GET | `/api/pano` | 米家路状态 + 融合结果 |

完整 API 文档见 [docs/http_api.md](docs/http_api.md)。

---

## go2rtc 米家摄像头接入

本项目通过 go2rtc 的 HTTP API 取帧，支持米家全景摄像头作为第二路识别源。

### go2rtc 配置示例

```yaml
# go2rtc.yaml
api:
  listen: ":1984"
  username: "your_user"
  password: "your_pass"

streams:
  living_room: rtsp://user:pass@192.168.1.100:554/stream1  # 米家摄像头 RTSP 地址
```

### App 端配置

在 WebUI 设置页填写：
- `pano_url`: `http://<go2rtc-ip>:1984/api/frame.jpeg?src=living_room`
- `pano_user` / `pano_pass`: go2rtc API 凭据
- 启用 `pano_enabled`

> 注意：frame.jpeg 单帧延迟约 4-13 秒（取决于摄像头关键帧间隔），适用于"谁在房间"判定，不适合实时画框。

---

## memory-agent 人脸节点池

本项目可作为 memory-agent 人脸节点池中的 `type=tv` 节点：

1. 启动后自动向 `memory_agent_base` 注册（`POST /face/node/register`）
2. 每 20 秒心跳（`POST /face/node/heartbeat`）
3. 注册成功后下行同步中央人脸库（`GET /face/lib`）
4. memory-agent 可通过 `POST http://<node_endpoint>/face/recognize` 调用本机识别

`memory_agent_base` 为空时，TV 端独立运行，不接入节点池。

联调细节见 [docs/handoff_memory_agent_integration.md](docs/handoff_memory_agent_integration.md)。

---

## 保活说明

小米 Android TV 系统（PatchWall）会 aggressively 杀后台进程。本项目实现了 6 层保活：

1. **前台服务** + `START_STICKY`（通知栏可见）
2. **WorkManager** 15 分钟看门狗
3. **AlarmManager** 2 分钟看门狗（`setExactAndAllowWhileIdle`）
4. **进程内自愈**：HTTP 端口/唤醒锁丢失时当场恢复
5. **onTaskRemoved**：划掉任务时自动重启
6. **开机自启**：`BOOT_COMPLETED` 广播

**建议**：在 TV 系统设置中，将本 App 设为「自启动允许」+「电池优化不受限制」，可显著提升存活率。

---

## 项目结构

```
arcface-tvcam-node/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/arcfaceandroid/
│   │   │   ├── FaceServerService.java   # 前台服务：识别主循环 + 保活
│   │   │   ├── FaceServer.java           # ArcSoft 引擎封装
│   │   │   ├── FaceHttpServer.java       # 原生 HTTP 服务 + WebUI
│   │   │   ├── AppConfig.java            # 配置管理（SharedPreferences）
│   │   │   ├── RecognitionState.java     # 识别状态：共识/融合/缓存
│   │   │   ├── HotspotManager.java       # 热点 ROI 提速
│   │   │   ├── MijiaPanoSource.java      # 米家/go2rtc 第二路取流
│   │   │   ├── FaceNodeClient.java       # memory-agent 节点客户端
│   │   │   ├── ScanManager.java           # 扫描式识别
│   │   │   ├── Camera2CaptureSource.java # TV USB 摄像头取流（Camera2）
│   │   │   ├── SmartZoomController.java  # 数字变焦
│   │   │   └── ...                        # UI / 保活 / 工具类
│   │   ├── assets/webui.html              # 人脸注册管理 WebUI
│   │   ├── res/                           # 布局/资源
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── libs/                              # ArcSoft SDK（自行放入，不进仓库）
├── docs/
│   ├── http_api.md                        # HTTP API 文档
│   ├── handoff_arcface.md                 # 项目交接文档
│   └── handoff_memory_agent_integration.md # memory-agent 联调交接单
├── config.sample.json                     # 配置示例
├── build.gradle
├── settings.gradle
├── LICENSE
└── README.md
```

---

## 已知限制

- **ArcSoft SDK 许可**：免费版通常限个人/非商用，且禁止再分发 SDK 文件。商用需购买企业版授权。
- **多人跨帧关联**：当前基于 faceId + 位置 + 特征相似度，非真正的行人重识别（re-id），大幅姿态变化/交叉时可能跳名。
- **米家路延迟**：frame.jpeg 单帧延迟 4-13 秒，仅用于"谁在房间"判定，不画预览框。
- **TV 保活**：OEM 强杀无法 100% 阻止，建议配合系统设置提升存活率。

---

## 许可证

[Apache License 2.0](LICENSE)

> 本项目依赖虹软 ArcFace SDK，该 SDK 为专有软件，受其自身许可协议约束。使用者须自行从虹软官方获取 SDK 并遵守其许可条款。
