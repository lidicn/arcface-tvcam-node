# ArcFace 人脸识别门禁 — HTTP / WebUI 接口文档

人脸服务（TV 端 App 的前台 `FaceServerService`）内置一个轻量 HTTP 服务器，
监听 `Constants.SERVER_PORT`（默认 **8080**），绑定到所有网卡（`0.0.0.0`）。

在同一个局域网内，用浏览器或任意 HTTP 客户端访问：

```
http://<电视局域网IP>:8080/
```

即可打开 **人脸注册管理 WebUI**（实时画面、上传注册、列表、单条删除、清空全部）。

> 已开启 CORS（`Access-Control-Allow-Origin: *`），可直接跨域调用，方便 Node-RED / Home Assistant 等集成。

---

## 1. 服务信息

| 项 | 值 |
| --- | --- |
| 监听端口 | `8080`（`Constants.SERVER_PORT`，可改） |
| 绑定地址 | 所有网卡（局域网可访问） |
| 数据格式 | 请求/响应均为 `application/json`（除二进制接口外） |
| 识别阈值 | `MATCH_THRESHOLD = 0.80`（相似度 ≥ 此值才判定为同人） |

### 1.1 健康检查

`GET /api/health`

```bash
curl http://<IP>:8080/api/health
```

响应：

```json
{
  "status": "ok",
  "engine": 2,
  "registered": 3,
  "port": 8080
}
```

| 字段 | 说明 |
| --- | --- |
| `status` | `ok` 表示服务存活 |
| `engine` | 当前 ArcSoft 引擎版本号 |
| `registered` | 已注册人脸数量 |
| `port` | 实际监听端口 |

---

## 2. 人脸列表

`GET /api/faces`

```bash
curl http://<IP>:8080/api/faces
```

响应：

```json
{
  "count": 2,
  "faces": [
    { "name": "张三", "image": "/api/face_image?name=%E5%BC%A0%E4%B8%89" },
    { "name": "李四", "image": "/api/face_image?name=%E6%9D%8E%E5%9B%9B" }
  ]
}
```

- `image` 字段为可直接用于 `<img src="...">` 的缩略图地址（已 URL 编码）。

---

## 3. 人脸注册（新增/覆盖）

`POST /api/register`

**方式一：二进制图片直接上传（推荐，最简单）**

```bash
curl -X POST "http://<IP>:8080/api/register?name=张三" \
     --data-binary @face.jpg \
     -H "Content-Type: image/jpeg"
```

**方式二：JSON + Base64**

```bash
curl -X POST "http://<IP>:8080/api/register" \
     -H "Content-Type: application/json" \
     -d '{"name":"张三","image":"<BASE64_NO_PREFIX>"}'
```

| 参数 | 位置 | 说明 |
| --- | --- | --- |
| `name` | query 或 JSON | 姓名，**建议唯一**；为空时自动用时间戳 |
| `image` | 请求体（二进制）或 JSON 字段（base64，不含 `data:image/...` 前缀） | 单人清晰正脸照效果最佳 |

响应：

```json
{ "success": true, "name": "张三", "registered": 3 }
```

- `success=false` 表示图片无效或未检测到人脸（不会写入库）。
- 同名注册会**覆盖**该人原有特征。

---

## 4. 取回已注册人脸照片

`GET /api/face_image?name=<姓名>`

```bash
curl "http://<IP>:8080/api/face_image?name=%E5%BC%A0%E4%B8%89" -o zhangsan.jpg
```

- 返回 `image/jpeg` 二进制；姓名含中文/特殊字符需 **URL 编码**。
- 404 表示该姓名不存在。

---

## 5. 删除单条人脸

`DELETE /api/face?name=<姓名>`

```bash
curl -X DELETE "http://<IP>:8080/api/face?name=%E5%BC%A0%E4%B8%89"
```

响应：

```json
{ "deleted": true, "registered": 2 }
```

- `deleted=true` 表示已从内存特征与磁盘（特征+照片）中移除；当前识别不会再匹配到该人。

---

## 6. 清空全部人脸

`POST /api/clear`

```bash
curl -X POST "http://<IP>:8080/api/clear"
```

响应：

```json
{ "success": true, "deleted": 3 }
```

---

## 7. 实时识别（查询式）

`GET /api/recognize?image=<BASE64>` 或 `POST /api/recognize`

**GET（image 放 query，base64 需 URL 编码）**

```bash
curl "http://<IP>:8080/api/recognize?image=<BASE64_URLENCODED>"
```

**POST（JSON 或原始 base64 文本）**

```bash
curl -X POST "http://<IP>:8080/api/recognize" \
     -H "Content-Type: application/json" \
     -d '{"image":"<BASE64_NO_PREFIX>"}'
```

响应：

```json
{
  "success": true,
  "name": "张三",
  "similarity": 0.91,
  "threshold": 0.8,
  "rect": { "left": 120, "top": 80, "right": 280, "bottom": 320 },
  "faces": 1
}
```

| 字段 | 说明 |
| --- | --- |
| `success` | 是否成功识别到人脸 |
| `name` | 命中姓名；无人脸或无匹配时为 `"unknown"` |
| `similarity` | 最高相似度 |
| `threshold` | 判定阈值 |
| `rect` | 人脸框（像素坐标），无人脸时为空 |
| `faces` | 图中检测到的人脸数 |

---

## 8. 实时画面快照

`GET /api/snapshot`

```bash
curl "http://<IP>:8080/api/snapshot" -o live.jpg
```

- 返回当前摄像头最新一帧的 `image/jpeg`，可用于 WebUI 实时预览或外部监控。

---

## 9. WebUI 使用说明

浏览器打开 `http://<IP>:8080/`：

1. **实时画面**：页面顶部每秒刷新一次摄像头画面，便于对准被注册者。
2. **注册**：填写「姓名」→ 选择照片（或直接对着摄像头截一帧上传）→ 点「注册」。
3. **已注册列表**：卡片展示头像与姓名，点「删除」单条移除。
4. **清空全部**：一键清空整个库（有二次确认）。

> 与 App 内「人脸库管理」页共享同一份人脸库（磁盘 `face` 目录下 `feature` + `imgs`），
> 任意一端增删都会即时反映到另一端。

---

## 10. 在智能家居中集成（示例）

### Home Assistant — 用 curl 通知「谁回家了」

```bash
# 摄像头抓拍后调用识别，命中则触发自动化
RESULT=$(curl -s "http://<IP>:8080/api/recognize?image=$(base64 -w0 snap.jpg | jq -sRr @uri)")
NAME=$(echo "$RESULT" | jq -r .name)
```

### Node-RED — 注册新成员

```
[http request]  POST  http://<IP>:8080/api/register?name={{payload.name}}
Body: binary / 上传文件
```

---

## 11. 扫描式识别（多帧 / 主动触发）

> 适用于「电视自己有摄像头、想直接让人脸识别、不传图」的场景（门铃、回家识别等）。
> 与第 7 节「查询式识别」不同：本接口由 TV 端连续多帧识别、命中即停，解决人脸移动时单帧扑空的问题。

### 11.1 触发扫描（异步 + 回传）

`POST /api/scan`（兼容 `GET`）

```bash
curl -X POST "http://<IP>:8080/api/scan?duration=4000&threshold=0.80&callback=http://192.168.2.200:1880/face/result"
```

| 参数 | 位置 | 默认 | 说明 |
|------|------|------|------|
| `duration` | query / JSON | `4000`(ms) | 扫描窗口时长，上限 `10000`；窗口内持续采样，命中即提前结束 |
| `threshold` | query / JSON | `0.80` | 判定为熟人的相似度阈值（低于则判为陌生人） |
| `callback` | query / JSON | 空 | 扫描完成后结果回传的 Webhook URL（Node-RED `http in` 节点） |
| `wait` | query / JSON | `false` | `=1`/`true` 时同步等待，HTTP 响应直接返回完整结果（不再回传） |
| `includeSnapshot` | query / JSON | `false` | `=1`/`true` 时结果附带命中帧 JPEG(base64)，便于微信推送附图 |

立即返回（异步）：

```json
{ "jobId": "<uuid>", "status": "running" }
```

扫描完成后 POST 到 `callback` 的结果体（同步 `wait=1` 时即为 HTTP 响应）：

```json
{
  "jobId": "<uuid>",
  "success": true,
  "state": "matched",
  "matched": true,
  "name": "Kevin",
  "similarity": 0.86,
  "threshold": 0.8,
  "faces": 1,
  "rect": { "left": 120, "top": 80, "right": 280, "bottom": 320 },
  "framesSampled": 9,
  "durationMs": 3981,
  "ts": 1700000000000,
  "snapshot": "<BASE64_JPEG 可选>"
}
```

`state` 取值：

- `matched`：命中熟人（`similarity >= threshold` 且非未知）
- `unknown`：检测到人脸但相似度低于阈值（陌生人）
- `no_face`：窗口内始终未检测到人脸

### 11.2 查询结果

`GET /api/scan/result?jobId=<uuid>` → `{jobId,status,result?}`（result 为 11.1 的完整结果）

`GET /api/scan/latest` → 最近一次扫描结果；从未扫描时回落为 `RecognitionState` 当前实时结果 + `freshMs` 新鲜度（无需传图，直接查「当前是谁」）

### 11.3 Node-RED 集成示例（替换 go2rtc 抓拍）

```
[领普按钮事件] → [http request] POST http://<IP>:8080/api/scan?callback=http://<NR>:1880/face/result&duration=4000
[http in] POST /face/result → [function 结果路由] → 熟人:TTS+微信 / 陌生人:告警+微信 / 无人脸:微信
```

> 调试：`adb logcat -s FaceHttpServer ScanManager FaceServerService`

---

## 附：错误码约定

| HTTP 状态 | 含义 |
| --- | --- |
| 200 | 成功（JSON 内 `success` 字段表示业务是否成功） |
| 400 | 缺少必要参数（如 `name`） |
| 404 | 资源不存在（人脸/图片/页面） |
| 405 | 方法不允许（如对非写接口用了 POST） |
| 500 | 服务器内部错误 |
| 503 | 摄像头未就绪 / 暂无帧 |

> 调试技巧：在电视上 `adb logcat -s FaceHttpServer` 可看到每条请求的方法、URI 与耗时。
