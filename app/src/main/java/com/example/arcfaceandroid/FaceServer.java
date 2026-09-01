package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.FaceSimilar;
import com.arcsoft.face.enums.DetectFaceOrientPriority;
import com.arcsoft.face.enums.DetectMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 人脸库操作（注册 / 搜索）。基于虹软官方 ArcfaceDemo 的 FaceServer 改写：
 *  - 激活用 activeOnline(context, APP_ID, SDK_KEY)
 *  - init 用 6 参形式：init(context, DetectMode, OrientPriority, scaleVal, maxNum, functionMask)
 *  - 以 Bitmap 入参（HTTP 上传的图片）做检测/特征提取，特征持久化到 app 私有目录
 */
public class FaceServer {
    private static final String TAG = "FaceServer";
    public static final String IMG_SUFFIX = ".jpg";
    private static FaceEngine faceEngine = null;
    private static FaceServer faceServer = null;
    private static List<FaceRegisterInfo> faceRegisterInfoList;
    public static String ROOT_PATH;
    /** 应用 Context（init 时记录），供 addFaceFeature 在 ROOT_PATH 未设时兜底。 */
    private static Context appCtx;

    public static final String SAVE_IMG_DIR = "register" + File.separator + "imgs";
    public static final String SAVE_FEATURE_DIR = "register" + File.separator + "features";

    /** 小于该像素尺寸的人脸先上采样再提特征，提升远距离小脸识别质量 */
    private static final int MIN_QUALITY_FACE_PX = 112;

    /** 每人最多保存的注册模板数（多姿态/远近模板，匹配时取每人最高分）。超出则淘汰最旧一条。 */
    private static final int MAX_TEMPLATES_PER_PERSON = 6;

    /** 最近一次 init 失败的详细信息（供 UI / 日志诊断）。 */
    public static String lastInitError = "";

    /** 当前引擎检测模式（VIDEO=自带跟踪，IMAGE=单帧检测）。用于诊断和自动回退。 */
    public static volatile DetectMode currentDetectMode = DetectMode.ASF_DETECT_MODE_IMAGE;

    /** VIDEO 模式连续检测失败的帧数。超过阈值则自动回退到 IMAGE 模式。 */
    private static int videoModeFailCount = 0;

    /** VIDEO 模式自动回退阈值：连续 N 帧 detectFaces 返回 0（且画面非空）则回退。 */
    private static final int VIDEO_MODE_FAIL_THRESHOLD = 10;

    /** 最近一次识别/注册的诊断信息（供 HTTP 接口返回，便于排查）。 */
    public int lastDetectCode = 0;
    public int lastFaceCount = 0;
    public boolean lastDecodeOk = true;
    /** 最近一次识别的分段耗时（毫秒），供性能埋点。 */
    public long lastDetMs = 0;
    public long lastFeatMs = 0;
    public long lastCmpMs = 0;
    public long lastRecMs = 0;
    public int lastRecFaces = 0;

    private boolean isProcessing = false;

    public static FaceServer getInstance() {
        if (faceServer == null) {
            synchronized (FaceServer.class) {
                if (faceServer == null) faceServer = new FaceServer();
            }
        }
        return faceServer;
    }

    /** 激活并初始化引擎。返回 true 表示成功。
     *  注意：activeOnline/init 可能抛出 native 层 Throwable（含 Error，如 UnsatisfiedLinkError），
     *  必须整体兜底，否则会让调用方进程（Activity/Service）直接崩溃、连 UI 都起不来。 */
    public boolean init(Context context) {
        synchronized (this) {
            if (faceEngine != null) return true;
            if (context == null) return false;
            appCtx = context.getApplicationContext();
            try {
                faceEngine = new FaceEngine();

                // 激活（首次需联网；之后可离线）。Android 3.0/5.0 SDK 均为 activeOnline
                // 密钥从 AppConfig（SharedPreferences）读取，开源版默认留空需用户配置
                AppConfig cfg = AppConfig.get(context);
                String appId = cfg.getArcsoftAppId();
                String sdkKey = cfg.getArcsoftSdkKey();
                if (appId == null || appId.isEmpty() || sdkKey == null || sdkKey.isEmpty()) {
                    lastInitError = "ArcSoft 密钥未配置：请在 WebUI 设置页填写 APP_ID / SDK_KEY";
                    Log.e(TAG, "ArcSoft keys not configured, engine activation skipped");
                    faceEngine = null;
                    return false;
                }
                int activeCode = faceEngine.activeOnline(context, appId, sdkKey);
                if (activeCode != ErrorInfo.MOK && activeCode != ErrorInfo.MERR_ASF_ALREADY_ACTIVATED) {
                    lastInitError = "激活失败 code=" + activeCode + " " + errorName(activeCode);
                    Log.e(TAG, "activeOnline failed, " + lastInitError);
                    faceEngine = null;
                    return false;
                }

                // 注意：本机 ArcSoft SDK（ArcFace 3.0 Android）在 ASF_DETECT_MODE_VIDEO 下
                // detectFaces 持续返回 0（已实测验证：VIDEO 模式初始化成功，但连续 10+ 帧检测为空，
                // 自动回退到 IMAGE 模式后检测正常）。故默认使用 IMAGE 模式。
                // VIDEO 模式的自带人脸跟踪（faceId 连续）不可用，跨帧关联由 RecognitionState
                // 的位置距离 + 特征余弦相似度加权匹配实现（已实现，效果足够稳定）。
                // 如未来升级 SDK 版本验证 VIDEO 模式可用，可改回 ASF_DETECT_MODE_VIDEO。
                int engineCode = faceEngine.init(context,
                        DetectMode.ASF_DETECT_MODE_IMAGE,
                        DetectFaceOrientPriority.ASF_OP_ALL_OUT,
                        4, 10,
                        FaceEngine.ASF_FACE_RECOGNITION | FaceEngine.ASF_FACE_DETECT);
                if (engineCode == ErrorInfo.MOK) {
                    lastInitError = "";
                    currentDetectMode = DetectMode.ASF_DETECT_MODE_IMAGE;
                    videoModeFailCount = 0;
                    Log.i(TAG, "Engine initialized in IMAGE mode (VIDEO mode unsupported on this SDK version)");
                    initFaceList(context);
                    return true;
                } else {
                    lastInitError = "引擎初始化失败 code=" + engineCode + " " + errorName(engineCode);
                    Log.e(TAG, "init failed, " + lastInitError);
                    faceEngine.unInit();
                    faceEngine = null;
                    return false;
                }
            } catch (Throwable t) {
                // 捕获所有异常/错误（含 native UnsatisfiedLinkError），避免顶掉调用进程
                lastInitError = "引擎初始化异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
                Log.e(TAG, "init crashed", t);
                faceEngine = null;
                return false;
            }
        }
    }

    public void unInit() {
        synchronized (this) {
            if (faceRegisterInfoList != null) {
                faceRegisterInfoList.clear();
                faceRegisterInfoList = null;
            }
            if (faceEngine != null) {
                faceEngine.unInit();
                faceEngine = null;
            }
        }
    }

    /** VIDEO 模式检测失效时自动回退到 IMAGE 模式。在 doRecognize 中被调用。
     *  注意：unInit 会清空内存中的人脸库，必须重新调用 initFaceList 加载。 */
    private void fallbackToImageMode() {
        if (faceEngine != null) {
            faceEngine.unInit();
            faceEngine = null;
        }
        if (faceRegisterInfoList != null) {
            faceRegisterInfoList.clear();
            faceRegisterInfoList = null;
        }
        faceEngine = new FaceEngine();
        int code = faceEngine.init(appCtx,
                DetectMode.ASF_DETECT_MODE_IMAGE,
                DetectFaceOrientPriority.ASF_OP_ALL_OUT,
                4, 10,
                FaceEngine.ASF_FACE_RECOGNITION | FaceEngine.ASF_FACE_DETECT);
        if (code == ErrorInfo.MOK) {
            currentDetectMode = DetectMode.ASF_DETECT_MODE_IMAGE;
            videoModeFailCount = 0;
            initFaceList(appCtx);
            Log.i(TAG, "Fallback to IMAGE mode successful, face library reloaded");
        } else {
            Log.e(TAG, "Fallback to IMAGE mode failed, code=" + code);
            faceEngine.unInit();
            faceEngine = null;
        }
    }

    /** 引擎是否已就绪（激活 + 初始化均成功）。Fragment 调用前应先判此值。 */
    public boolean isEngineReady() {
        synchronized (this) {
            return faceEngine != null;
        }
    }

    /** 将 ArcSoft 错误码翻译为可读含义（直接映射官方错误码表，避免误导）。 */
    private static String errorName(int code) {
        switch (code) {
            case 0:      return "成功";
            case 90113:  return "激活失败(请授予读写/电话权限)";
            case 90114:  return "SDK已激活";
            case 90115:  return "SDK未激活";
            case 90116:  return "detectFaceScaleVal不支持";
            case 90117:  return "激活文件与SDK类型不匹配(确认SDK版本)";
            case 90118:  return "设备不匹配";
            case 90121:  return "SDK已过期(免费版一年期限,需重新下载替换)";
            case 90122:  return "Android版本不支持(仅4.4-10)";
            case 90128:  return "READ_PHONE_STATE权限被拒绝";
            case 90129:  return "激活数据被破坏(删除激活文件后重激活)";
            case 90131:  return "INTERNET权限被拒绝";
            case 90135:  return "数据校验异常(APP_ID/SDK_KEY或包名不匹配)";
            case 90136:  return "APP_ID/AppKey与SDK版本不一致";
            case 90138:  return "激活文件不存在";
            case 90139:  return "当前设备时间不正确";
            default:     return "错误码 " + code;
        }
    }

    /** 启动时从磁盘加载已注册特征。 */
    private void initFaceList(Context context) {
        synchronized (this) {
            if (ROOT_PATH == null) ROOT_PATH = context.getFilesDir().getAbsolutePath();
            File featureDir = new File(ROOT_PATH + File.separator + SAVE_FEATURE_DIR);
            if (!featureDir.exists() || !featureDir.isDirectory()) return;
            File[] featureFiles = featureDir.listFiles();
            if (featureFiles == null || featureFiles.length == 0) return;
            faceRegisterInfoList = new ArrayList<>();
            for (File f : featureFiles) {
                try {
                    FileInputStream fis = new FileInputStream(f);
                    byte[] feature = new byte[FaceFeature.FEATURE_SIZE];
                    int read = fis.read(feature);
                    fis.close();
                    if (read == FaceFeature.FEATURE_SIZE) {
                        faceRegisterInfoList.add(new FaceRegisterInfo(feature, displayNameOf(f.getName())));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public int getFaceNumber() {
        synchronized (this) {
            return faceRegisterInfoList == null ? 0 : faceRegisterInfoList.size();
        }
    }

    public List<String> getFaceNames() {
        synchronized (this) {
            List<String> names = new ArrayList<>();
            if (faceRegisterInfoList != null)
                for (FaceRegisterInfo i : faceRegisterInfoList)
                    if (!names.contains(i.getName())) names.add(i.getName());
            return names;
        }
    }

    /** 导出当前人脸库（姓名 + 特征）的拷贝，供节点下行同步给 memory-agent 中央库。 */
    public List<FaceRegisterInfo> exportFaceLib() {
        synchronized (this) {
            List<FaceRegisterInfo> copy = new ArrayList<>();
            if (faceRegisterInfoList != null) {
                for (FaceRegisterInfo i : faceRegisterInfoList) {
                    byte[] f = i.getFeatureData();
                    copy.add(new FaceRegisterInfo(f != null ? f.clone() : null, i.getName()));
                }
            }
            return copy;
        }
    }

    /** 直接注册一条人脸特征（来自 memory-agent 下行同步），不重新提特征。 */
    public boolean addFaceFeature(byte[] feature, String name) {
        if (feature == null || feature.length != FaceFeature.FEATURE_SIZE) return false;
        if (name == null || name.isEmpty()) return false;
        if (ROOT_PATH == null && appCtx != null) ROOT_PATH = appCtx.getFilesDir().getAbsolutePath();
        if (ROOT_PATH == null) return false;
        FaceFeature ff = new FaceFeature();
        ff.setFeatureData(feature);
        return persistFeature(ff, null, name);
    }

    public int clearAllFaces(Context context) {
        synchronized (this) {
            if (faceRegisterInfoList != null) faceRegisterInfoList.clear();
            if (ROOT_PATH == null) ROOT_PATH = context.getFilesDir().getAbsolutePath();
            int deleted = 0;
            File featureDir = new File(ROOT_PATH + File.separator + SAVE_FEATURE_DIR);
            if (featureDir.exists() && featureDir.isDirectory() && featureDir.listFiles() != null) {
                for (File f : featureDir.listFiles()) if (f.delete()) deleted++;
            }
            File imgDir = new File(ROOT_PATH + File.separator + SAVE_IMG_DIR);
            if (imgDir.exists() && imgDir.isDirectory() && imgDir.listFiles() != null) {
                for (File f : imgDir.listFiles()) f.delete();
            }
            return deleted;
        }
    }

    /** 删除某人全部模板：同时移除内存特征与磁盘文件（特征+照片）。供 WebUI / App 管理页调用。 */
    public boolean removeFace(Context context, String name) {
        synchronized (this) {
            if (name == null || name.isEmpty()) return false;
            if (ROOT_PATH == null) ROOT_PATH = context.getFilesDir().getAbsoluteFile().getAbsolutePath();
            boolean removed = false;
            if (faceRegisterInfoList != null) {
                for (int i = faceRegisterInfoList.size() - 1; i >= 0; i--) {
                    if (name.equals(faceRegisterInfoList.get(i).getName())) {
                        faceRegisterInfoList.remove(i);
                        removed = true;
                    }
                }
            }
            File featureDir = new File(ROOT_PATH + File.separator + SAVE_FEATURE_DIR);
            if (featureDir.exists() && featureDir.isDirectory() && featureDir.listFiles() != null) {
                for (File f : featureDir.listFiles())
                    if (name.equals(displayNameOf(f.getName())) && f.delete()) removed = true;
            }
            File imgDir = new File(ROOT_PATH + File.separator + SAVE_IMG_DIR);
            if (imgDir.exists() && imgDir.isDirectory() && imgDir.listFiles() != null) {
                for (File f : imgDir.listFiles())
                    if (name.equals(displayNameOf(f.getName()))) f.delete();
            }
            return removed;
        }
    }

    /** 返回已注册人脸的照片文件（不存在返回 null），供 HTTP 接口回传缩略图。多模板时返回最新一张。 */
    public File getRegisteredImageFile(String name) {
        if (ROOT_PATH == null || name == null || name.isEmpty()) return null;
        File imgDir = new File(ROOT_PATH + File.separator + SAVE_IMG_DIR);
        if (imgDir.exists() && imgDir.isDirectory()) {
            File best = null; long bestT = -1;
            File[] files = imgDir.listFiles();
            if (files != null) for (File f : files) {
                if (name.equals(displayNameOf(f.getName())) && f.lastModified() > bestT) {
                    bestT = f.lastModified(); best = f;
                }
            }
            if (best != null) return best;
        }
        return new File(ROOT_PATH + File.separator + SAVE_IMG_DIR + File.separator + name + IMG_SUFFIX);
    }

    /** 注册：从一张（单人）Bitmap 提取特征并持久化。name 为空则用时间戳。 */
    public boolean register(Context context, Bitmap srcBitmap, String name) {
        if (faceEngine == null || context == null || srcBitmap == null) return false;
        if (ROOT_PATH == null) ROOT_PATH = context.getFilesDir().getAbsolutePath();
        return registerFromBitmap(srcBitmap, name == null ? "" : name, 0);
    }

    /** 就地注册：用当前摄像头实时帧（真实座位距离）检测并注册，使模板贴合房间内小脸特征分布。
     *
     *  实现要点（踩过的坑）：必须<b>在 NV21 上直接检测</b>。原实现先把整帧经 JPEG 转成 Bitmap
     *  再转 BGR24 检测，而 YuvImage 的 JPEG 编码是有损的，暗光噪点会被涂抹，
     *  70~80px 的远距离小脸经这一转就检不出 —— 表现为 <code>/api/register?live=1</code>
     *  恒返回 success=false。检出后再裁剪（无损转换）+ 按人脸尺寸上采样 + 重新提特征。
     *
     *  @param faceIndex 按面积降序的脸下标：0=最大脸；多人同框时指定具体某人（如远处小孩）。 */
    public boolean registerFromNv21(Context context, byte[] nv21, int w, int h, String name, int faceIndex) {
        if (faceEngine == null || context == null || nv21 == null) return false;
        if (ROOT_PATH == null) ROOT_PATH = context.getFilesDir().getAbsolutePath();

        // 1) 直接在 NV21 上检测：与识别同一路径，避开 JPEG 转换导致的小脸丢失
        List<FaceInfo> list = new ArrayList<>();
        synchronized (this) {
            int code = faceEngine.detectFaces(nv21, w, h, FaceEngine.CP_PAF_NV21, list);
            if (code != ErrorInfo.MOK || list.isEmpty()) {
                Log.e(TAG, "registerFromNv21: no face detected, code=" + code);
                return false;
            }
        }
        // 按面积降序，faceIndex=0 取最大脸（最清晰）
        list.sort((a, b) -> Integer.compare(
                b.getRect().width() * b.getRect().height(),
                a.getRect().width() * a.getRect().height()));
        int idx = (faceIndex >= 0 && faceIndex < list.size()) ? faceIndex : 0;
        Rect r = list.get(idx).getRect();

        // 2) 裁剪人脸区域（含 padding，无损转换）作为注册照；小脸再上采样后重新提特征
        FaceFeature feature = new FaceFeature();
        boolean ok = false;
        Bitmap disp = null;
        boolean small = r != null
                && (r.width() < MIN_QUALITY_FACE_PX || r.height() < MIN_QUALITY_FACE_PX);
        if (r != null) {
            int pad = (int) (0.3f * Math.max(r.width(), r.height()));
            int cx0 = Math.max(0, r.left - pad) & ~1;
            int cy0 = Math.max(0, r.top - pad) & ~1;
            int cw = (Math.min(w, r.right + pad) - cx0) & ~1;
            int ch = (Math.min(h, r.bottom + pad) - cy0) & ~1;
            if (cw > 4 && ch > 4) {
                byte[] sub = cropNv21(nv21, w, h, cx0, cy0, cw, ch);
                Bitmap cropBmp = nv21ToBitmapLossless(sub, cw, ch);
                if (cropBmp != null) {
                    if (small) {
                        // 放大倍数按“人脸尺寸”算，不能按含 padding 的裁剪框（否则小脸不放大）
                        int faceLong = Math.max(r.width(), r.height());
                        int scale = (int) Math.ceil((double) MIN_QUALITY_FACE_PX / Math.max(faceLong, 1));
                        if (scale > 1) {
                            Bitmap up = Bitmap.createScaledBitmap(cropBmp,
                                    cropBmp.getWidth() * scale, cropBmp.getHeight() * scale, true);
                            cropBmp.recycle();
                            cropBmp = up;
                        }
                        FaceFeature upFeature = extractFeatureFromBitmap(cropBmp);
                        if (upFeature != null) {
                            feature = upFeature;
                            ok = true;
                        }
                    }
                    disp = cropBmp;   // 注册照（小脸时为放大后的图）
                }
            }
        }

        // 3) 大脸、或上采样失败：回落到原始 NV21 直接提特征
        if (!ok) {
            synchronized (this) {
                if (faceEngine.extractFaceFeature(nv21, w, h, FaceEngine.CP_PAF_NV21,
                        list.get(idx), feature) == ErrorInfo.MOK) {
                    ok = true;
                }
            }
        }
        if (!ok) {
            Log.e(TAG, "registerFromNv21: extract feature failed");
            if (disp != null) disp.recycle();
            return false;
        }

        String userName = (name == null || name.isEmpty())
                ? String.valueOf(System.currentTimeMillis()) : name;
        boolean saved = persistFeature(feature, disp, userName);
        if (disp != null) disp.recycle();
        return saved;
    }

    /** 统一注册逻辑（synchronized）：在 BGR24 上检测，小脸(<112px)/原生提取失败则裁剪+上采样重提取。
     *  @param faceIndex 按面积降序的脸下标：0=最大脸。 */
    private synchronized boolean registerFromBitmap(Bitmap srcBitmap, String name, int faceIndex) {
        if (faceEngine == null || srcBitmap == null) return false;
        Bitmap bmp = prepareBitmap(srcBitmap);
        int w = bmp.getWidth(), h = bmp.getHeight();
        byte[] bgr24 = bitmapToBgr24(bmp);

        List<FaceInfo> faceInfoList = new ArrayList<>();
        int code = faceEngine.detectFaces(bgr24, w, h, FaceEngine.CP_PAF_BGR24, faceInfoList);
        if (code != ErrorInfo.MOK || faceInfoList.isEmpty()) {
            Log.e(TAG, "register: no face detected, code=" + code);
            bmp.recycle();
            return false;
        }
        // 按面积降序，faceIndex=0 取最大脸（最清晰）；多人同框时指定具体某人
        faceInfoList.sort((a, b) -> Integer.compare(
                b.getRect().width() * b.getRect().height(),
                a.getRect().width() * a.getRect().height()));
        int idx = (faceIndex >= 0 && faceIndex < faceInfoList.size()) ? faceIndex : 0;
        FaceInfo fi = faceInfoList.get(idx);
        Rect r = fi.getRect();

        FaceFeature faceFeature = new FaceFeature();
        code = faceEngine.extractFaceFeature(bgr24, w, h, FaceEngine.CP_PAF_BGR24, fi, faceFeature);
        Bitmap disp = bmp;
        if (code != ErrorInfo.MOK || r.width() < MIN_QUALITY_FACE_PX || r.height() < MIN_QUALITY_FACE_PX) {
            // 小脸或原生提取失败：裁剪含 30% 边距 -> 上采样至 ≥112px -> 重检测+提特征
            int pad = (int) (0.3f * Math.max(r.width(), r.height()));
            int cx0 = Math.max(0, r.left - pad) & ~1;
            int cy0 = Math.max(0, r.top - pad) & ~1;
            int cw = (Math.min(w, r.right + pad) - cx0) & ~1;
            int ch = (Math.min(h, r.bottom + pad) - cy0) & ~1;
            if (cw > 4 && ch > 4) {
                Bitmap cropBmp = Bitmap.createBitmap(bmp, cx0, cy0, cw, ch);
                // 关键：放大倍数必须按“人脸尺寸”算，不能按含 padding 的裁剪框算。
                // 裁剪框 ≈ 1.6×人脸，若按其计算则 scale=ceil(112/1.6×face)=ceil(70/face)，
                // 导致 70~112px 的人脸算出 scale=1 而完全不放大，远距离小脸特征质量极差。
                int faceLong = Math.max(r.width(), r.height());
                int scale = (int) Math.ceil((double) MIN_QUALITY_FACE_PX / Math.max(faceLong, 1));
                if (scale > 1) {
                    Bitmap up = Bitmap.createScaledBitmap(cropBmp,
                            cropBmp.getWidth() * scale, cropBmp.getHeight() * scale, true);
                    cropBmp.recycle();
                    cropBmp = up;
                }
                FaceFeature upFeature = extractFeatureFromBitmap(cropBmp);
                if (upFeature != null) { faceFeature = upFeature; code = ErrorInfo.MOK; }
                disp = cropBmp;
            }
        }
        if (code != ErrorInfo.MOK) {
            Log.e(TAG, "register: extract failed, code=" + code);
            if (disp != bmp && disp != null) disp.recycle();
            bmp.recycle();
            return false;
        }
        String userName = (name == null || name.isEmpty()) ? String.valueOf(System.currentTimeMillis()) : name;
        boolean ok = persistFeature(faceFeature, disp, userName);
        if (disp != null && disp != bmp) disp.recycle();
        bmp.recycle();
        return ok;
    }

    /** 从 Bitmap 上检测并提取首张脸特征（已 prepareBitmap），供小脸上采样注册复用。 */
    private FaceFeature extractFeatureFromBitmap(Bitmap bmp) {
        if (faceEngine == null || bmp == null) return null;
        Bitmap prepared = prepareBitmap(bmp);
        int w = prepared.getWidth(), h = prepared.getHeight();
        byte[] bgr24 = bitmapToBgr24(prepared);
        List<FaceInfo> list = new ArrayList<>();
        int code = faceEngine.detectFaces(bgr24, w, h, FaceEngine.CP_PAF_BGR24, list);
        FaceFeature ff = null;
        if (code == ErrorInfo.MOK && !list.isEmpty()) {
            ff = new FaceFeature();
            code = faceEngine.extractFaceFeature(bgr24, w, h, FaceEngine.CP_PAF_BGR24, list.get(0), ff);
            if (code != ErrorInfo.MOK) ff = null;
        }
        // 仅回收内部副本：若 prepareBitmap 返回的是同一实例（已满足 ARGB_8888 且 4 倍数），
        // 调用方仍持有该 bitmap，回收会连带损坏调用方的数据（已踩过 recycled-bitmap 坑）。
        if (prepared != bmp) prepared.recycle();
        return ff;
    }

    /** 对已知人脸框（bitmap 坐标）直接提特征并比对，不重新检测，避免破坏视频模式 faceId 跟踪。 */
    private FaceFeature extractFeatureFromBitmapAt(Bitmap bmp, Rect rect) {
        if (faceEngine == null || bmp == null || rect == null) return null;
        Bitmap prepared = prepareBitmap(bmp);
        int w = prepared.getWidth(), h = prepared.getHeight();
        byte[] bgr24 = bitmapToBgr24(prepared);
        FaceInfo fi = new FaceInfo();
        fi.setRect(new Rect(rect.left, rect.top, rect.right, rect.bottom));
        FaceFeature ff = new FaceFeature();
        int code = faceEngine.extractFaceFeature(bgr24, w, h, FaceEngine.CP_PAF_BGR24, fi, ff);
        if (prepared != bmp) prepared.recycle();
        return (code == ErrorInfo.MOK) ? ff : null;
    }

    /** 持久化特征与注册照（提取/检测已在外完成）。displayBmp 可为 null（仅存特征）。 */
    private boolean persistFeature(FaceFeature feature, Bitmap displayBmp, String userName) {
        if (ROOT_PATH == null) return false;
        try {
            File featureDir = new File(ROOT_PATH + File.separator + SAVE_FEATURE_DIR);
            if (!featureDir.exists() && !featureDir.mkdirs()) return false;
            File imgDir = new File(ROOT_PATH + File.separator + SAVE_IMG_DIR);
            if (!imgDir.exists() && !imgDir.mkdirs()) return false;

            // 唯一文件名（展示名#时间戳），支持同名多次注册（多姿态/远近模板），避免互相覆盖
            String entryId = userName + "#" + System.currentTimeMillis();
            FileOutputStream fosFeature = new FileOutputStream(featureDir + File.separator + entryId);
            fosFeature.write(feature.getFeatureData());
            fosFeature.close();

            if (displayBmp != null) {
                FileOutputStream fosImage = new FileOutputStream(imgDir + File.separator + entryId + IMG_SUFFIX);
                displayBmp.compress(Bitmap.CompressFormat.JPEG, 90, fosImage);
                fosImage.close();
            }

            if (faceRegisterInfoList == null) faceRegisterInfoList = new ArrayList<>();
            faceRegisterInfoList.add(new FaceRegisterInfo(feature.getFeatureData(), userName));
            // 每人模板数上限：超出则淘汰最旧一条，避免无限增长
            if (faceRegisterInfoList.size() > 1) {
                int oldestIdx = -1;
                int count = 0;
                for (int i = 0; i < faceRegisterInfoList.size(); i++) {
                    if (userName.equals(faceRegisterInfoList.get(i).getName())) {
                        if (oldestIdx == -1) oldestIdx = i;
                        count++;
                    }
                }
                if (count > MAX_TEMPLATES_PER_PERSON && oldestIdx != -1) {
                    faceRegisterInfoList.remove(oldestIdx);
                }
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * NV21 -> ARGB_8888 Bitmap（无损直转，不经过 JPEG）。
     *
     * 小脸上采样/注册路径必须用本方法：原先的 YuvImage 压缩再解码，
     * 而 JPEG 是有损的，暗光噪点会被涂抹掉 —— 实测 70~80px 的远距离小脸经这一转就
     * 检不出来（就地注册恒返回 false 的根因），换成本方法后即可稳定检出。
     */
    private static Bitmap nv21ToBitmapLossless(byte[] nv21, int w, int h) {
        if (nv21 == null || w <= 0 || h <= 0 || nv21.length < w * h * 3 / 2) return null;
        try {
            int[] argb = new int[w * h];
            final int frameSize = w * h;
            int yp = 0;
            for (int j = 0; j < h; j++) {
                int uvp = frameSize + (j >> 1) * w;   // NV21：VU 交错，V 在前
                int u = 0, v = 0;
                for (int i = 0; i < w; i++, yp++) {
                    int y = (0xff & nv21[yp]) - 16;
                    if (y < 0) y = 0;
                    if ((i & 1) == 0) {
                        v = (0xff & nv21[uvp++]) - 128;
                        u = (0xff & nv21[uvp++]) - 128;
                    }
                    int y1192 = 1192 * y;
                    int r = y1192 + 1634 * v;
                    int g = y1192 - 833 * v - 400 * u;
                    int b = y1192 + 2066 * u;
                    r = r < 0 ? 0 : (r > 262143 ? 262143 : r);
                    g = g < 0 ? 0 : (g > 262143 ? 262143 : g);
                    b = b < 0 ? 0 : (b > 262143 ? 262143 : b);
                    argb[yp] = 0xff000000 | ((r << 6) & 0x00ff0000)
                            | ((g >> 2) & 0x0000ff00) | ((b >> 10) & 0x000000ff);
                }
            }
            return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从整图裁剪人脸区域（夹紧边界）为 Bitmap，用于保存注册照。 */
    private static Bitmap cropFaceBitmap(Bitmap src, Rect r) {
        if (src == null || r == null) return src;
        int x = Math.max(0, r.left), y = Math.max(0, r.top);
        int cw = Math.min(src.getWidth() - x, r.width());
        int ch = Math.min(src.getHeight() - y, r.height());
        if (cw <= 0 || ch <= 0) return src;
        return Bitmap.createBitmap(src, x, y, cw, ch);
    }

    /** 识别（Bitmap 入参，供 HTTP 上传图片）。内部转 BGR24 后走 {@link #doRecognize}。 */
    public List<RecognizeResult> recognize(Bitmap srcBitmap) {
        if (faceEngine == null || srcBitmap == null || faceRegisterInfoList == null || faceRegisterInfoList.isEmpty())
            return new ArrayList<>();
        Bitmap bmp = prepareBitmap(srcBitmap);
        int w = bmp.getWidth(), h = bmp.getHeight();
        byte[] bgr24 = bitmapToBgr24(bmp);
        return doRecognize(bgr24, w, h, FaceEngine.CP_PAF_BGR24);
    }

    /** 识别（NV21 直通入参，供摄像头实时流）。跳过 YUV->JPEG->Bitmap->BGR24 三重转换，显著提速。 */
    public List<RecognizeResult> recognizeNv21(byte[] nv21, int w, int h) {
        return doRecognize(nv21, w, h, FaceEngine.CP_PAF_NV21);
    }

    /**
     * 核心识别：检测所有人脸 -> 逐张提特征 -> 比对人脸库。
     * format 用 {@link FaceEngine#CP_PAF_BGR24}（上传图）或 {@link FaceEngine#CP_PAF_NV21}（实时流）。
     * 视频检测模式下 FaceInfo.getFaceId() 有效（阶段 M5 启用），用于多人跨帧聚合。
     */
    private List<RecognizeResult> doRecognize(byte[] data, int w, int h, int format) {
        List<RecognizeResult> results = new ArrayList<>();
        synchronized (this) {
            lastDetectCode = -1;
            lastFaceCount = 0;
            lastDetMs = lastFeatMs = lastCmpMs = lastRecMs = 0;
            lastRecFaces = 0;
            if (faceEngine == null || data == null || w <= 0 || h <= 0
                    || faceRegisterInfoList == null || faceRegisterInfoList.isEmpty())
                return results;

            long t0 = System.currentTimeMillis();
            List<FaceInfo> faceInfoList = new ArrayList<>();
            int code = faceEngine.detectFaces(data, w, h, format, faceInfoList);
            lastDetMs = System.currentTimeMillis() - t0;
            lastDetectCode = code;
            lastFaceCount = faceInfoList.size();

            // VIDEO 模式自动回退：连续 N 帧 detectFaces 返回空（code=MOK 但 list 为空），
            // 说明 VIDEO 模式在当前设备/SDK 上检测失效，自动回退到 IMAGE 模式。
            // 注意：VIDEO 模式前几帧可能返回空（需积累上下文），故设阈值为 10 帧。
            if (currentDetectMode == DetectMode.ASF_DETECT_MODE_VIDEO
                    && code == ErrorInfo.MOK && faceInfoList.isEmpty()) {
                videoModeFailCount++;
                Log.w(TAG, "VIDEO mode detect empty frame #" + videoModeFailCount
                        + "/" + VIDEO_MODE_FAIL_THRESHOLD);
                if (videoModeFailCount >= VIDEO_MODE_FAIL_THRESHOLD) {
                    Log.e(TAG, "VIDEO mode consistently returns empty, falling back to IMAGE mode");
                    fallbackToImageMode();
                    // 回退后用 IMAGE 模式重新检测当前帧
                    faceInfoList = new ArrayList<>();
                    code = faceEngine.detectFaces(data, w, h, format, faceInfoList);
                    lastDetectCode = code;
                    lastFaceCount = faceInfoList.size();
                }
            } else if (!faceInfoList.isEmpty()) {
                // 检测到人脸，重置失败计数
                videoModeFailCount = 0;
            }

            if (code != ErrorInfo.MOK || faceInfoList.isEmpty()) return results;

            for (FaceInfo fi : faceInfoList) {
                // 质量门控：过小的人脸（噪点/远处误检）直接判“未知”，不浪费提特征
                Rect fr0 = fi.getRect();
                if (fr0 != null && Math.min(fr0.width(), fr0.height()) < Constants.MIN_FACE_PX) {
                    RecognizeResult skip = new RecognizeResult();
                    skip.rect = fr0; skip.faceId = fi.getFaceId();
                    skip.name = "未知"; skip.score = 0f;
                    results.add(skip);
                    continue;
                }
                FaceFeature feature = new FaceFeature();
                long tf = System.currentTimeMillis();
                int ex = faceEngine.extractFaceFeature(data, w, h, format, fi, feature);
                lastFeatMs = System.currentTimeMillis() - tf;
                RecognizeResult r = new RecognizeResult();
                r.rect = fi.getRect();
                r.faceId = fi.getFaceId();
                if (ex == ErrorInfo.MOK) {
                    // 填充特征向量（用于跨帧 ReID 关联）
                    byte[] featData = feature.getFeatureData();
                    if (featData != null) r.feature = featureToFloatArray(featData);
                    long tc = System.currentTimeMillis();
                    CompareResult top = getTopOfFaceLib(feature);
                    lastCmpMs = System.currentTimeMillis() - tc;
                    if (top != null) {
                        r.name = top.userName;
                        r.score = top.similar;
                    } else {
                        r.name = "未知";
                        r.score = 0f;
                    }
                } else {
                    r.name = "未知";
                    r.score = 0f;
                }
                results.add(r);
            }
            lastRecMs = System.currentTimeMillis() - t0;
            lastRecFaces = results.size();
            Log.i(TAG, "recognize cost total=" + lastRecMs + " det=" + lastDetMs
                    + " feat=" + lastFeatMs + " cmp=" + lastCmpMs + " faces=" + results.size());
        }
        return results;
    }

    // ===== hotspot / ROI 提速接口 =====

    /**
     * 在整帧 NV21 上检测人脸，返回整图坐标的 FaceInfo 列表。
     *
     * 注意：视频检测模式下必须始终对整帧检测，保证 FaceInfo.getFaceId() 跨帧稳定；
     * 若对某帧裁剪子图再 detectFaces，会向引擎插入一帧“异图”，破坏跨帧跟踪上下文，
     * 导致 faceId 跳变、多人聚合失效。故 roi 参数此处不再用于裁剪（热点 ROI 仍用于
     * 布局上报，不参与检测裁剪）。上传识别（BGR24）与注册走各自路径，均对整图/整图位图检测。
     */
    public List<FaceInfo> detectFacesOnly(byte[] nv21, int w, int h, Rect roi) {
        List<FaceInfo> out = new ArrayList<>();
        synchronized (this) {
            if (faceEngine == null || nv21 == null || w <= 0 || h <= 0) return out;
            faceEngine.detectFaces(nv21, w, h, FaceEngine.CP_PAF_NV21, out);
            return out;
        }
    }

    /** 对整图中某张人脸提特征并比对人脸库（不再重新 detect），faceInfo.rect 须为整图坐标。 */
    public RecognizeResult featureAndCompare(byte[] nv21, int w, int h, FaceInfo fi) {
        RecognizeResult r = new RecognizeResult();
        r.rect = fi != null ? fi.getRect() : null;
        r.faceId = fi != null ? fi.getFaceId() : -1;
        if (fi == null || fi.getRect() == null) {
            r.name = "未知"; r.score = 0f; return r;
        }
        Rect fr = fi.getRect();
        int fw = fr.width(), fh = fr.height();
        // 远距离小脸：裁剪含 30% 边距区域 -> 无损转换 -> 按人脸尺寸上采样 -> 直接提特征+比对。
        // 关键：上采样后【直接】对已知人脸框提特征，不再对裁剪图重新 detectFaces，
        // 否则会向视频检测引擎插入一帧“异图”，破坏 faceId 跨帧跟踪，多人聚合失效。
        if (fw < MIN_QUALITY_FACE_PX || fh < MIN_QUALITY_FACE_PX) {
            int pad = (int) (0.3f * Math.max(fw, fh));
            int cx0 = Math.max(0, fr.left - pad) & ~1;
            int cy0 = Math.max(0, fr.top - pad) & ~1;
            int cw = (Math.min(w, fr.right + pad) - cx0) & ~1;
            int ch = (Math.min(h, fr.bottom + pad) - cy0) & ~1;
            if (cw > 4 && ch > 4) {
                byte[] sub = cropNv21(nv21, w, h, cx0, cy0, cw, ch);
                Bitmap cropBmp = nv21ToBitmapLossless(sub, cw, ch);   // 无损转换，保留小脸细节
                if (cropBmp != null) {
                    // 按“人脸尺寸”算放大倍数（不能按含 padding 的裁剪框，否则小脸不放大）
                    int faceLong = Math.max(fw, fh);
                    int scale = (int) Math.ceil((double) MIN_QUALITY_FACE_PX / Math.max(faceLong, 1));
                    Bitmap up = (scale > 1)
                            ? Bitmap.createScaledBitmap(cropBmp, cropBmp.getWidth() * scale,
                                cropBmp.getHeight() * scale, true)
                            : cropBmp;
                    // 人脸在裁剪图里的坐标，按 scale 映射到上采样图，避免再次检测
                    int us = (scale > 1) ? scale : 1;
                    Rect faceUp = new Rect((fr.left - cx0) * us, (fr.top - cy0) * us,
                            (fr.left - cx0 + fw) * us, (fr.top - cy0 + fh) * us);
                    FaceFeature uf = extractFeatureFromBitmapAt(up, faceUp);
                    if (up != cropBmp) up.recycle();
                    cropBmp.recycle();
                    if (uf != null) {
                        // 填充特征向量（用于跨帧 ReID 关联）
                        byte[] ufData = uf.getFeatureData();
                        if (ufData != null) r.feature = featureToFloatArray(ufData);
                        synchronized (this) {
                            CompareResult top = getTopOfFaceLib(uf);
                            if (top != null) { r.name = top.userName; r.score = top.similar; }
                            else { r.name = "未知"; r.score = 0f; }
                        }
                        return r;
                    }
                }
            }
        }
        synchronized (this) {
            if (faceEngine == null || nv21 == null) {
                r.name = "未知"; r.score = 0f; return r;
            }
            FaceFeature feature = new FaceFeature();
            int ex = faceEngine.extractFaceFeature(nv21, w, h, FaceEngine.CP_PAF_NV21, fi, feature);
            if (ex == ErrorInfo.MOK) {
                // 填充特征向量（用于跨帧 ReID 关联）
                byte[] featData = feature.getFeatureData();
                if (featData != null) r.feature = featureToFloatArray(featData);
                CompareResult top = getTopOfFaceLib(feature);
                if (top != null) { r.name = top.userName; r.score = top.similar; }
                else { r.name = "未知"; r.score = 0f; }
            } else {
                r.name = "未知"; r.score = 0f;
            }
        }
        return r;
    }

    /** 裁剪 NV21 子图（调用方保证偶数对齐、宽高4倍数）。 */
    private static byte[] cropNv21(byte[] src, int w, int h, int x, int y, int rw, int rh) {
        byte[] sub = new byte[rw * rh * 3 / 2];
        for (int row = 0; row < rh; row++) {
            System.arraycopy(src, (y + row) * w + x, sub, row * rw, rw);
        }
        int srcUvBase = w * h;
        int subUvBase = rw * rh;
        int uvRows = rh / 2;
        for (int row = 0; row < uvRows; row++) {
            System.arraycopy(src, srcUvBase + (y / 2 + row) * w, sub, subUvBase + row * rw, rw);
        }
        return sub;
    }

    /** 从特征/照片文件名解析展示名（去掉末尾的 #<时间戳> 后缀）；无后缀则原样返回（兼容旧单模板）。 */
    private static String displayNameOf(String fileName) {
        if (fileName == null) return "";
        int h = fileName.lastIndexOf('#');
        return h > 0 ? fileName.substring(0, h) : fileName;
    }

    /** 在人脸库找出最相似的一条（多模板时即为该人最佳姿态/距离模板，姓名仍按展示名）。 */
    private CompareResult getTopOfFaceLib(FaceFeature faceFeature) {
        if (faceEngine == null || isProcessing || faceRegisterInfoList == null || faceRegisterInfoList.isEmpty())
            return null;
        FaceFeature temp = new FaceFeature();
        FaceSimilar similar = new FaceSimilar();
        float maxSimilar = 0;
        int maxIdx = -1;
        isProcessing = true;
        for (int i = 0; i < faceRegisterInfoList.size(); i++) {
            temp.setFeatureData(faceRegisterInfoList.get(i).getFeatureData());
            faceEngine.compareFaceFeature(faceFeature, temp, similar);
            if (similar.getScore() > maxSimilar) {
                maxSimilar = similar.getScore();
                maxIdx = i;
            }
        }
        isProcessing = false;
        return maxIdx != -1 ? new CompareResult(faceRegisterInfoList.get(maxIdx).getName(), maxSimilar) : null;
    }

    // ---- 工具 ----

    /** 识别结果（单张脸）。faceId 在视频检测模式下有效，用于多人跨帧聚合。
     *  feature 为 ArcSoft 特征向量（float[]，用于 ReID 相似度匹配）。 */
    public static class RecognizeResult {
        public Rect rect;
        public String name;
        public float score;
        public int faceId = -1;
        /** 特征向量（512维 float），用于跨帧 ReID 关联。可能为 null（提取失败时）。 */
        public float[] feature = null;
    }

    /** 将 ArcSoft FaceFeature 的 byte[] 特征转换为 float[]（用于余弦相似度计算）。 */
    public static float[] featureToFloatArray(byte[] data) {
        if (data == null || data.length < 4) return null;
        int len = data.length / 4;
        float[] arr = new float[len];
        for (int i = 0; i < len; i++) {
            int bits = (data[i*4] & 0xFF)
                    | ((data[i*4+1] & 0xFF) << 8)
                    | ((data[i*4+2] & 0xFF) << 16)
                    | ((data[i*4+3] & 0xFF) << 24);
            arr[i] = Float.intBitsToFloat(bits);
        }
        return arr;
    }

    /** 比对结果（最相似） */
    private static class CompareResult {
        String userName;
        float similar;
        CompareResult(String userName, float similar) {
            this.userName = userName;
            this.similar = similar;
        }
    }

    /** 保证宽高为 4 的倍数（ArcSoft 要求），必要时缩放。 */
    private Bitmap prepareBitmap(Bitmap src) {
        Bitmap argb = (src.getConfig() == Bitmap.Config.ARGB_8888)
                ? src : src.copy(Bitmap.Config.ARGB_8888, false);
        int w = argb.getWidth(), h = argb.getHeight();
        int nw = (w % 4 == 0) ? w : (w - (w % 4));
        int nh = (h % 4 == 0) ? h : (h - (h % 4));
        if (nw == w && nh == h) return argb;
        return Bitmap.createScaledBitmap(argb, nw, nh, true);
    }

    /** ARGB_8888 -> BGR24 字节数组。 */
    private static byte[] bitmapToBgr24(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        byte[] bgr = new byte[w * h * 3];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            bgr[i * 3] = (byte) (c & 0xff);
            bgr[i * 3 + 1] = (byte) ((c >> 8) & 0xff);
            bgr[i * 3 + 2] = (byte) ((c >> 16) & 0xff);
        }
        return bgr;
    }
}
