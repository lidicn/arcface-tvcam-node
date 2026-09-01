package com.example.arcfaceandroid;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 单例帧缓冲：UVC 摄像头线程写入，识别线程 / HTTP 快照线程读取。
 *
 * 设计要点：
 *  - 只保留「最新一帧」，用可复用缓冲避免每帧 new 数组造成 GC 抖动；
 *  - 写、读都加锁，保证多线程可见性与一致性；
 *  - JPEG 快照按需编码（HA 轮询频率低，不会成为热点）。
 */
public final class FrameBuffer implements CameraFrameSource {

    private static final FrameBuffer INSTANCE = new FrameBuffer();

    public static FrameBuffer get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private byte[] reuse;       // 可复用缓冲
    private byte[] latestFrame; // 指向 reuse（单写者）
    private int width;
    private int height;
    private int format;
    private long frameTimeMs;

    private FrameBuffer() {
    }

    /** UVC 帧回调线程调用：拷入可复用缓冲并持有引用 */
    public void pushFrame(byte[] data, int w, int h, int fmt) {
        if (data == null || w <= 0 || h <= 0) return;
        synchronized (lock) {
            int need = data.length;
            if (reuse == null || reuse.length != need) {
                reuse = new byte[need];
            }
            System.arraycopy(data, 0, reuse, 0, need);
            latestFrame = reuse;
            width = w;
            height = h;
            format = fmt;
            frameTimeMs = System.currentTimeMillis();
        }
    }

    @Override
    public int getFrameWidth() {
        synchronized (lock) {
            return width;
        }
    }

    @Override
    public int getFrameHeight() {
        synchronized (lock) {
            return height;
        }
    }

    @Override
    public int getFrameFormat() {
        synchronized (lock) {
            return format;
        }
    }

    @Override
    public byte[] getLatestFrame() {
        synchronized (lock) {
            return latestFrame;
        }
    }

    @Override
    public boolean hasFrame() {
        synchronized (lock) {
            return latestFrame != null && width > 0 && height > 0;
        }
    }

    public long getFrameTimeMs() {
        synchronized (lock) {
            return frameTimeMs;
        }
    }

    /** 把持有的最新一帧复制出来（供识别线程安全使用），无帧返回 null */
    public byte[] takeCopy() {
        synchronized (lock) {
            if (latestFrame == null) return null;
            byte[] out = new byte[latestFrame.length];
            System.arraycopy(latestFrame, 0, out, 0, latestFrame.length);
            return out;
        }
    }

    @Override
    public byte[] getLatestSnapshotJpeg() {
        synchronized (lock) {
            if (latestFrame == null || width <= 0 || height <= 0) return null;
            try {
                if (format == FORMAT_NV21) {
                    YuvImage yuv = new YuvImage(latestFrame, ImageFormat.NV21, width, height, null);
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    yuv.compressToJpeg(new Rect(0, 0, width, height), 82, os);
                    return os.toByteArray();
                } else {
                    Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(latestFrame));
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, os);
                    bmp.recycle();
                    return os.toByteArray();
                }
            } catch (Exception e) {
                return null;
            }
        }
    }
}
