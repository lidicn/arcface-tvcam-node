package com.example.arcfaceandroid;

import android.content.Context;

/**
 * 取流源抽象（解耦具体 UVC 实现）。
 *
 * 设计目标：让 TV 版在不依赖某个特定 UVC 库（AUSBC / serenegiant / 原生 USB Host）的前提下
 * 仍能编译与运行。当前默认实现 {@link StubCameraCaptureSource} 生成测试帧，便于在无摄像头
 * 的开发机上验证 UI / 识别 / HTTP / 保活全链路；回家接入真 USB 摄像头时，只需新增一个实现本
 * 接口的 {@code UvcCameraCaptureSource} 并在 {@link TvCameraFragment} 中替换即可。
 */
public interface CameraCaptureSource {

    /** 启动取流。每产生一帧（NV21）就回调 listener.onFrame，由调用方推入 FrameBuffer。 */
    void start(FrameListener listener);

    /** 停止取流并释放资源 */
    void stop();

    /** 摄像头是否就绪（USB 已连接 / 测试源已启动） */
    boolean isOpened();

    interface FrameListener {
        /**
         * @param nv21  NV21 格式帧数据
         * @param width 帧宽
         * @param height 帧高
         */
        void onFrame(byte[] nv21, int width, int height);
    }
}
