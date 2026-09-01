package com.example.arcfaceandroid;

/**
 * 统一帧源抽象：解耦「取流层（UVC 摄像头）」与「识别层 / HTTP 接口」。
 *
 * {@link FrameBuffer} 是本接口的实现（持有最新帧 + 快照 JPEG），被识别线程与
 * {@code /api/snapshot} 共用。取流本身由 {@link CameraCaptureSource} 完成，
 * 取到的 NV21 帧通过 {@link FrameBuffer#pushFrame} 写入。
 */
public interface CameraFrameSource {

    /** 像素格式：NV21（YUV420SP） */
    int FORMAT_NV21 = 0;
    /** 像素格式：RGBA */
    int FORMAT_RGBA = 1;

    int getFrameWidth();

    int getFrameHeight();

    int getFrameFormat();

    /** 最新一帧原始数据（NV21 或 RGBA），可能为 null */
    byte[] getLatestFrame();

    boolean hasFrame();

    /** 最新帧编码后的 JPEG（供 /api/snapshot 与画中画），无帧时返回 null */
    byte[] getLatestSnapshotJpeg();
}
