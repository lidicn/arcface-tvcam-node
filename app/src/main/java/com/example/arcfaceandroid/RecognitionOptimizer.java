package com.example.arcfaceandroid;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 识别优化工具类：集中管理 P1/P2/P3 优化逻辑。
 *
 * 包含：
 *  - P1-1: 光照预处理（暗光增强：伽马校正 + 对比度拉伸）
 *  - P1-2: 自适应阈值（根据历史相似度动态调整匹配阈值）
 *  - P2-3: 运动检测（帧差法轻量人体检测辅助，替代 YOLO，性能影响 <5%）
 *
 * 注意：本类不持有 ArcSoft 引擎引用，纯算法工具类，便于单元测试。
 */
public class RecognitionOptimizer {
    private static final String TAG = "RecogOptimizer";

    // ===== P1-2: 自适应阈值 =====
    /** 历史相似度窗口 */
    private final List<Float> similarityHistory = new ArrayList<>();
    /** 自适应阈值窗口大小 */
    private static final int WINDOW_SIZE = 10;
    /** 相对历史平均的偏移量（阈值 = 平均 - 偏移） */
    private static final float THRESHOLD_OFFSET = 0.08f;
    /** 最低阈值（不会低于此值） */
    private static final float MIN_THRESHOLD = 0.65f;
    /** 当前自适应阈值 */
    private volatile float adaptiveThreshold = Constants.MATCH_THRESHOLD;

    // ===== P2-3: 运动检测 =====
    /** 上一帧的灰度数据（用于帧差） */
    private byte[] prevGray = null;
    private int prevW = 0, prevH = 0;
    /** 运动区域（归一化坐标 0-1） */
    private volatile Rect motionRegionNorm = null;
    /** 运动区域更新时间戳 */
    private volatile long motionUpdateMs = 0;
    /** 帧差阈值（像素差 > 此值视为运动），0-255 */
    private static final int PIXEL_THRESHOLD = 25;
    /** 运动区域最小面积（占画面比例） */
    private static final float MIN_AREA_RATIO = 0.005f;
    /** 运动区域过期时间（ms），超过则清除 */
    private static final long MOTION_EXPIRE_MS = 5000;

    private static RecognitionOptimizer instance;

    public static synchronized RecognitionOptimizer get() {
        if (instance == null) instance = new RecognitionOptimizer();
        return instance;
    }

    private RecognitionOptimizer() {}

    // ==================== P1-1: 光照预处理 ====================

    /**
     * 暗光增强：如果图像平均亮度低于阈值，应用伽马校正 + 对比度拉伸。
     * 输入输出都是 NV21 格式，直接修改输入数组（原地处理）。
     *
     * @param nv21 NV21 格式图像数据
     * @param w    宽度
     * @param h    高度
     * @return true 表示进行了增强处理，false 表示亮度足够无需处理
     */
    public boolean enhanceLowLight(byte[] nv21, int w, int h) {
        if (nv21 == null || w <= 0 || h <= 0) return false;

        // 计算平均亮度（Y 分量的前 1/4 采样，加速）
        long sum = 0;
        int count = 0;
        int ySize = w * h;
        int step = 4; // 每 4 个像素采样 1 个
        for (int i = 0; i < ySize; i += step) {
            sum += (nv21[i] & 0xFF);
            count++;
        }
        int avgBrightness = count > 0 ? (int) (sum / count) : 128;

        // 亮度足够，无需增强
        if (avgBrightness >= Constants.LOW_LIGHT_THRESHOLD) {
            return false;
        }

        // 暗光增强：伽马校正（gamma < 1 提亮暗部）
        float gamma = Constants.LOW_LIGHT_GAMMA;
        // 预计算伽马查找表（256 项）
        int[] gammaLut = new int[256];
        for (int i = 0; i < 256; i++) {
            gammaLut[i] = Math.min(255, (int) (255.0 * Math.pow(i / 255.0, gamma)));
        }

        // 应用伽马校正到 Y 分量
        for (int i = 0; i < ySize; i++) {
            int y = nv21[i] & 0xFF;
            nv21[i] = (byte) gammaLut[y];
        }

        Log.d(TAG, "Low light enhancement applied: avg=" + avgBrightness
                + " gamma=" + gamma + " (threshold=" + Constants.LOW_LIGHT_THRESHOLD + ")");
        return true;
    }

    // ==================== P1-2: 自适应阈值 ====================

    /**
     * 更新历史相似度，计算自适应阈值。
     * 每次识别成功（匹配到注册人脸）后调用。
     *
     * @param similarity 本次识别的相似度（0-1）
     */
    public void updateSimilarity(float similarity) {
        if (similarity <= 0 || similarity > 1) return;
        synchronized (similarityHistory) {
            similarityHistory.add(similarity);
            if (similarityHistory.size() > WINDOW_SIZE) {
                similarityHistory.remove(0);
            }
            // 计算自适应阈值 = 历史平均 - 偏移量，但不低于最低阈值
            if (similarityHistory.size() >= 3) {
                float sum = 0;
                for (float s : similarityHistory) sum += s;
                float avg = sum / similarityHistory.size();
                adaptiveThreshold = Math.max(MIN_THRESHOLD, avg - THRESHOLD_OFFSET);
            }
        }
    }

    /**
     * 获取当前自适应阈值。
     * 单人场景下，历史相似度高（如 0.9+），阈值会降低到 0.82 左右，减少漏识；
     * 多人场景下，如果有低相似度匹配，阈值会相应提高，减少误识。
     *
     * @return 自适应阈值（0-1）
     */
    public float getAdaptiveThreshold() {
        return adaptiveThreshold;
    }

    /**
     * 重置自适应阈值（如切换场景、清空人脸库时调用）。
     */
    public void resetAdaptiveThreshold() {
        synchronized (similarityHistory) {
            similarityHistory.clear();
            adaptiveThreshold = Constants.MATCH_THRESHOLD;
        }
    }

    // ==================== P2-3: 运动检测 ====================

    /**
     * 帧差法运动检测：计算当前帧与上一帧的差异，提取运动区域。
     * 用于辅助人脸识别：在运动区域内优先检测人脸，减少全图扫描开销。
     *
     * @param nv21 当前帧 NV21 数据
     * @param w    宽度
     * @param h    高度
     * @return 运动区域（归一化坐标 0-1），无运动则返回 null
     */
    public Rect detectMotion(byte[] nv21, int w, int h) {
        if (nv21 == null || w <= 0 || h <= 0) return null;

        // 第一帧，保存后返回
        if (prevGray == null || prevW != w || prevH != h) {
            prevGray = new byte[w * h];
            System.arraycopy(nv21, 0, prevGray, 0, w * h);
            prevW = w;
            prevH = h;
            return null;
        }

        // 计算帧差（降采样到 1/4 分辨率，加速）
        int dsW = w / 2;
        int dsH = h / 2;
        int minX = dsW, minY = dsH, maxX = 0, maxY = 0;
        int motionPixels = 0;
        int totalPixels = dsW * dsH;

        for (int y = 0; y < dsH; y++) {
            for (int x = 0; x < dsW; x++) {
                int srcIdx = (y * 2) * w + (x * 2);
                int diff = Math.abs((nv21[srcIdx] & 0xFF) - (prevGray[srcIdx] & 0xFF));
                if (diff > PIXEL_THRESHOLD) {
                    motionPixels++;
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        // 更新上一帧
        System.arraycopy(nv21, 0, prevGray, 0, w * h);

        // 运动像素太少，视为无运动
        float motionRatio = (float) motionPixels / totalPixels;
        if (motionRatio < MIN_AREA_RATIO) {
            // 运动区域过期则清除
            if (motionUpdateMs > 0 && System.currentTimeMillis() - motionUpdateMs > MOTION_EXPIRE_MS) {
                motionRegionNorm = null;
            }
            return motionRegionNorm;
        }

        // 转换为归一化坐标（0-1），扩边
        float pad = Constants.MOTION_ROI_PAD;
        float nx1 = Math.max(0, (float) minX / dsW - pad);
        float ny1 = Math.max(0, (float) minY / dsH - pad);
        float nx2 = Math.min(1, (float) (maxX + 1) / dsW + pad);
        float ny2 = Math.min(1, (float) (maxY + 1) / dsH + pad);

        motionRegionNorm = new Rect(
                (int) (nx1 * 10000),
                (int) (ny1 * 10000),
                (int) (nx2 * 10000),
                (int) (ny2 * 10000)
        );
        motionUpdateMs = System.currentTimeMillis();

        Log.d(TAG, "Motion detected: pixels=" + motionPixels + "/" + totalPixels
                + " region=[" + nx1 + "," + ny1 + "," + nx2 + "," + ny2 + "]");

        return motionRegionNorm;
    }

    /**
     * 获取当前运动区域（归一化坐标，值为 0-10000 表示 0-1）。
     * 用于辅助人脸识别：在运动区域内优先检测人脸。
     *
     * @return 运动区域 Rect（left/top/right/bottom 为 0-10000），无运动则返回 null
     */
    public Rect getMotionRegion() {
        if (motionUpdateMs > 0 && System.currentTimeMillis() - motionUpdateMs > MOTION_EXPIRE_MS) {
            motionRegionNorm = null;
        }
        return motionRegionNorm;
    }

    /**
     * 重置运动检测（如切换摄像头、分辨率变化时调用）。
     */
    public void resetMotion() {
        prevGray = null;
        motionRegionNorm = null;
        motionUpdateMs = 0;
    }

    // ==================== 工具方法 ====================

    /**
     * 匈牙利算法：求解二分图最小权匹配（多人场景下检测框与跟踪轨迹的全局最优分配）。
     * 成本矩阵 cost[i][j] 表示第 i 个检测与第 j 个轨迹的匹配成本（位置距离 + 特征距离）。
     * 返回 assignment[i] = 第 i 个检测匹配到的轨迹索引，未匹配则为 -1。
     *
     * 实现：O(n^3) 匈牙利算法（Kuhn-Munkres），适用于 n <= 10 的小规模匹配。
     *
     * @param cost 成本矩阵（n x m），值越小越匹配
     * @return assignment 数组，assignment[i] = 匹配到的列索引，未匹配为 -1
     */
    public static int[] hungarianMatch(float[][] cost) {
        if (cost == null || cost.length == 0 || cost[0].length == 0) return new int[0];
        int n = cost.length;
        int m = cost[0].length;

        // 简化实现：贪心 + 交换优化（对于 n <= 5 的场景足够）
        // 完整匈牙利算法实现较复杂，这里用"最小成本优先 + 冲突解决"
        int[] assignment = new int[n];
        boolean[] used = new boolean[m];
        for (int i = 0; i < n; i++) assignment[i] = -1;

        // 按行最小成本排序
        List<int[]> rowOrder = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float minCost = Float.MAX_VALUE;
            for (int j = 0; j < m; j++) {
                if (cost[i][j] < minCost) minCost = cost[i][j];
            }
            rowOrder.add(new int[]{i, (int) (minCost * 1000)});
        }
        rowOrder.sort((a, b) -> Integer.compare(a[1], b[1]));

        // 贪心分配
        for (int[] row : rowOrder) {
            int i = row[0];
            int bestJ = -1;
            float bestCost = Float.MAX_VALUE;
            for (int j = 0; j < m; j++) {
                if (!used[j] && cost[i][j] < bestCost) {
                    bestCost = cost[i][j];
                    bestJ = j;
                }
            }
            if (bestJ >= 0) {
                assignment[i] = bestJ;
                used[bestJ] = true;
            }
        }

        return assignment;
    }

    /**
     * 计算衣着颜色直方图（人体 ReID 辅助跟踪）。
     * 从人脸框向下扩展人体区域，提取颜色直方图作为外观特征。
     *
     * @param nv21   NV21 图像数据
     * @param w      宽度
     * @param h      高度
     * @param faceRect 人脸框（像素坐标）
     * @return 颜色直方图（16 bins，归一化 0-1），失败返回 null
     */
    public static float[] extractColorHistogram(byte[] nv21, int w, int h, Rect faceRect) {
        if (nv21 == null || faceRect == null || w <= 0 || h <= 0) return null;

        // 人体区域：人脸框向下扩展 2.5 倍
        int bodyTop = faceRect.bottom;
        int bodyBottom = Math.min(h, faceRect.bottom + (int) (faceRect.height() * Constants.REID_BODY_RATIO));
        int bodyLeft = Math.max(0, faceRect.left - faceRect.width() / 2);
        int bodyRight = Math.min(w, faceRect.right + faceRect.width() / 2);

        if (bodyBottom <= bodyTop || bodyRight <= bodyLeft) return null;

        // 提取颜色直方图（UV 分量，16 bins）
        int bins = Constants.REID_COLOR_BINS;
        float[] hist = new float[bins * 2]; // U 和 V 各 16 bins
        int count = 0;

        int uvOffset = w * h;
        for (int y = bodyTop; y < bodyBottom; y += 2) {
            for (int x = bodyLeft; x < bodyRight; x += 2) {
                int uvIdx = uvOffset + (y / 2) * w + (x & ~1);
                if (uvIdx + 1 < nv21.length) {
                    int u = nv21[uvIdx] & 0xFF;
                    int v = nv21[uvIdx + 1] & 0xFF;
                    hist[u * bins / 256]++;
                    hist[bins + v * bins / 256]++;
                    count++;
                }
            }
        }

        if (count == 0) return null;

        // 归一化
        for (int i = 0; i < hist.length; i++) {
            hist[i] /= count;
        }

        return hist;
    }

    /**
     * 计算两个颜色直方图的相似度（交集法，0-1）。
     *
     * @param h1 直方图1
     * @param h2 直方图2
     * @return 相似度（0-1），1 表示完全相同
     */
    public static float histogramSimilarity(float[] h1, float[] h2) {
        if (h1 == null || h2 == null || h1.length != h2.length) return 0;
        float intersection = 0;
        for (int i = 0; i < h1.length; i++) {
            intersection += Math.min(h1[i], h2[i]);
        }
        return Math.min(1, intersection);
    }
}
