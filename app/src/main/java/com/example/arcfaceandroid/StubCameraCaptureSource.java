package com.example.arcfaceandroid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取流存根实现：生成测试 NV21 帧（渐变背景 + 移动彩色方块），用于无摄像头环境下
 * 验证 UI / 识别 / HTTP / 保活全链路。不会产生真实人脸，识别结果将为空，属预期行为。
 *
 * 回家接真 USB 摄像头时，另写一个 {@code UvcCameraCaptureSource implements CameraCaptureSource}
 * （基于 AUSBC 本地 module 或可用 UVC 库），替换 {@link TvCameraFragment} 中的实例即可。
 */
public class StubCameraCaptureSource implements CameraCaptureSource {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 15;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private HandlerThread thread;
    private Handler handler;
    private FrameListener listener;
    private int phase = 0;

    @Override
    public void start(FrameListener l) {
        this.listener = l;
        if (running.compareAndSet(false, true)) {
            thread = new HandlerThread("stub-camera");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.postDelayed(this::tick, 1000 / FPS);
        }
    }

    private void tick() {
        if (!running.get()) return;
        byte[] nv21 = renderTestFrame(phase);
        phase = (phase + 4) % WIDTH;
        if (listener != null) listener.onFrame(nv21, WIDTH, HEIGHT);
        handler.postDelayed(this::tick, 1000 / FPS);
    }

    /** 生成一张 NV21 测试帧：Y 通道为水平渐变 + 一个移动的亮方块 */
    private byte[] renderTestFrame(int offset) {
        int w = WIDTH, h = HEIGHT;
        int ySize = w * h;
        int uvSize = w * h / 2;
        byte[] nv21 = new byte[ySize + uvSize];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int yVal = (x * 255 / w) & 0xFF;          // 水平渐变
                // 移动方块（亮度提升）
                int bx = (offset + w / 4) % w;
                if (x >= bx && x < bx + 160 && y >= h / 2 - 80 && y < h / 2 + 80) {
                    yVal = Math.min(255, yVal + 120);
                }
                nv21[y * w + x] = (byte) yVal;
            }
        }
        // UV 平面填 128（中性灰），方块区略偏色
        for (int i = 0; i < uvSize; i++) {
            nv21[ySize + i] = (byte) 128;
        }
        return nv21;
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (handler != null) handler.removeCallbacksAndMessages(null);
            if (thread != null) {
                thread.quitSafely();
                thread = null;
            }
        }
    }

    @Override
    public boolean isOpened() {
        return running.get();
    }
}
