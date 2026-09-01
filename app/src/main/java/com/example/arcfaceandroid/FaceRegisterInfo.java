package com.example.arcfaceandroid;

/** 单条已注册人脸：特征数据 + 姓名 + 质量分数。
 *  P1-3: 模板质量排序，比对时优先使用高质量模板。 */
public class FaceRegisterInfo {
    private byte[] featureData;
    private String name;
    /** P1-3: 模板质量分数（0-1），根据注册时人脸大小/角度/清晰度计算。
     *  高质量模板（大脸、正面、清晰）在比对时权重更高。默认 0.5（从旧数据加载时）。 */
    private float quality = 0.5f;

    public FaceRegisterInfo(byte[] featureData, String name) {
        this.featureData = featureData;
        this.name = name;
    }

    public FaceRegisterInfo(byte[] featureData, String name, float quality) {
        this.featureData = featureData;
        this.name = name;
        this.quality = Math.max(0f, Math.min(1f, quality));
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public byte[] getFeatureData() {
        return featureData;
    }

    public void setFeatureData(byte[] featureData) {
        this.featureData = featureData;
    }

    public float getQuality() {
        return quality;
    }

    public void setQuality(float quality) {
        this.quality = Math.max(0f, Math.min(1f, quality));
    }
}
