package com.example.arcfaceandroid;

/** 单条已注册人脸：特征数据 + 姓名。 */
public class FaceRegisterInfo {
    private byte[] featureData;
    private String name;

    public FaceRegisterInfo(byte[] featureData, String name) {
        this.featureData = featureData;
        this.name = name;
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
}
