package com.example.arcfaceandroid;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.arcsoft.face.FaceInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 电视端引导注册 Activity（第一阶段：单张正脸 + 实时质量反馈 + 自动采集）。
 *
 * 功能：
 * 1. 全屏摄像头预览，中央椭圆引导区域
 * 2. 实时人脸检测，绘制人脸框（绿色=合格，红色=不合格）
 * 3. 实时质量检测：人脸大小(>80px)、角度(宽高比0.7-1.3)、亮度(50-200)、单人
 * 4. 质量全部合格并稳定 1 秒后，自动倒计时 3-2-1 采集
 * 5. 采集后调用 FaceServer.registerFromNv21 注册
 * 6. 注册成功后显示结果，提供"完成"和"重新注册"
 *
 * 设计要点：
 * - 直接使用 Camera2CaptureSource（不通过 Service），生命周期与 Activity 绑定
 * - 质量检测在帧回调线程执行，UI 更新在主线程
 * - 自动采集：质量合格稳定后自动触发，不需要用户按按钮
 * - 姓名输入：TV 端用遥控器输入不方便，默认填"用户+时间戳"，用户可修改
 */
public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";
    private static final int REQ_CAMERA = 1001;

    // 质量阈值
    private static final int MIN_FACE_PX = 80;          // 人脸最小边长（比识别用的30px严格）
    private static final float MIN_ASPECT_RATIO = 0.7f;  // 宽高比下限（粗略判断正脸）
    private static final float MAX_ASPECT_RATIO = 1.3f;  // 宽高比上限
    private static final int MIN_BRIGHTNESS = 50;         // 人脸区域最小平均亮度
    private static final int MAX_BRIGHTNESS = 200;        // 人脸区域最大平均亮度
    private static final int STABLE_FRAMES_REQUIRED = 25; // 质量合格稳定帧数（约1秒，25fps）
    private static final int COUNTDOWN_SECONDS = 3;       // 倒计时秒数

    // UI 组件
    private TextureView previewView;
    private RegisterOverlayView overlayView;
    private TextView tvStatus, tvSize, tvAngle, tvBrightness, tvPersons, tvCountdown;
    private EditText etName;
    private Button btnCancel, btnAction, btnDone, btnReregister;
    private View nameInputLayout, successLayout;

    // 摄像头
    private Camera2CaptureSource cameraSource;
    private boolean cameraStarted = false;

    // 状态
    private enum State { IDLE, WAITING_STABLE, COUNTDOWN, CAPTURING, SUCCESS, FAILED }
    private volatile State currentState = State.IDLE;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);

    // 质量统计
    private int stableFrameCount = 0;
    private byte[] lastFrame;
    private int lastFrameW, lastFrameH;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 倒计时 Runnable
    private final Runnable countdownRunnable = new Runnable() {
        private int remaining = COUNTDOWN_SECONDS;
        @Override
        public void run() {
            if (currentState != State.COUNTDOWN) return;
            if (remaining > 0) {
                tvCountdown.setText(String.valueOf(remaining));
                remaining--;
                mainHandler.postDelayed(this, 1000);
            } else {
                tvCountdown.setVisibility(View.GONE);
                doCapture();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setContentView(R.layout.activity_register);

        initViews();
        initCameraSource();

        // 默认姓名：用户+时间戳
        String defaultName = "用户_" + new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date());
        etName.setText(defaultName);
        etName.setSelection(etName.getText().length());

        btnCancel.setOnClickListener(v -> finish());
        btnAction.setOnClickListener(v -> startRegistration());
        btnDone.setOnClickListener(v -> finish());
        btnReregister.setOnClickListener(v -> resetToIdle());
    }

    private void initViews() {
        previewView = findViewById(R.id.preview);
        overlayView = findViewById(R.id.overlay);
        tvStatus = findViewById(R.id.tv_status);
        tvSize = findViewById(R.id.tv_size);
        tvAngle = findViewById(R.id.tv_angle);
        tvBrightness = findViewById(R.id.tv_brightness);
        tvPersons = findViewById(R.id.tv_persons);
        tvCountdown = findViewById(R.id.tv_countdown);
        etName = findViewById(R.id.et_name);
        btnCancel = findViewById(R.id.btn_cancel);
        btnAction = findViewById(R.id.btn_action);
        btnDone = findViewById(R.id.btn_done);
        btnReregister = findViewById(R.id.btn_reregister);
        nameInputLayout = findViewById(R.id.name_input_layout);
        successLayout = findViewById(R.id.success_layout);

        tvCountdown.setVisibility(View.GONE);
        successLayout.setVisibility(View.GONE);
    }

    private void initCameraSource() {
        cameraSource = new Camera2CaptureSource(this);
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "SurfaceTexture available, starting camera");
                cameraSource.setPreviewTexture(surface);
                startCameraIfPermission();
            }
            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                stopCamera();
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });
    }

    private void startCameraIfPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        startCamera();
    }

    private void startCamera() {
        if (cameraStarted || cameraSource == null) return;
        try {
            cameraSource.start(new CameraCaptureSource.FrameListener() {
                @Override
                public void onFrame(byte[] nv21, int width, int height) {
                    handleFrame(nv21, width, height);
                }
            });
            cameraStarted = true;
            Log.i(TAG, "Camera started");
        } catch (Exception e) {
            Log.e(TAG, "Camera start failed", e);
            runOnUiThread(() -> tvStatus.setText("摄像头启动失败：" + e.getMessage()));
        }
    }

    private void stopCamera() {
        if (cameraSource != null) {
            cameraSource.stop();
            cameraStarted = false;
        }
    }

    /** 帧处理：检测人脸 + 质量评估 + 状态更新。 */
    private void handleFrame(byte[] nv21, int width, int height) {
        if (currentState == State.CAPTURING || currentState == State.SUCCESS) return;

        lastFrame = nv21;
        lastFrameW = width;
        lastFrameH = height;

        // 检测人脸
        List<FaceInfo> faces = FaceServer.getInstance().detectFacesOnly(nv21, width, height, null);

        // 质量评估
        boolean sizeOk = false, angleOk = false, brightnessOk = false, singlePerson = false;
        int faceSize = 0;
        float aspectRatio = 0;
        int brightness = 0;

        if (faces != null && !faces.isEmpty()) {
            FaceInfo mainFace = faces.get(0); // 最大脸（detectFacesOnly 按面积排序）
            Rect r = mainFace.getRect();
            if (r != null) {
                faceSize = Math.max(r.width(), r.height());
                aspectRatio = (float) r.width() / Math.max(r.height(), 1);
                brightness = calcFaceBrightness(nv21, width, height, r);

                sizeOk = faceSize >= MIN_FACE_PX;
                angleOk = aspectRatio >= MIN_ASPECT_RATIO && aspectRatio <= MAX_ASPECT_RATIO;
                brightnessOk = brightness >= MIN_BRIGHTNESS && brightness <= MAX_BRIGHTNESS;
                singlePerson = faces.size() == 1;
            }
        }

        boolean allPassed = sizeOk && angleOk && brightnessOk && singlePerson;

        // 更新 UI（主线程）
        final boolean finalAllPassed = allPassed;
        final boolean finalSizeOk = sizeOk;
        final boolean finalAngleOk = angleOk;
        final boolean finalBrightnessOk = brightnessOk;
        final boolean finalSinglePerson = singlePerson;
        final int finalFaceSize = faceSize;
        final float finalAspect = aspectRatio;
        final int finalBrightness = brightness;
        final int finalPersonCount = faces != null ? faces.size() : 0;
        final List<FaceInfo> finalFaces = faces;

        runOnUiThread(() -> {
            overlayView.setFaces(finalFaces, width, height, finalAllPassed);
            tvSize.setText(String.format(Locale.getDefault(), "人脸: %dpx %s", finalFaceSize, finalSizeOk ? "✓" : "✗"));
            tvSize.setTextColor(finalSizeOk ? 0xFF4CAF50 : 0xFFF44336);
            tvAngle.setText(String.format(Locale.getDefault(), "角度: %.2f %s", finalAspect, finalAngleOk ? "✓" : "✗"));
            tvAngle.setTextColor(finalAngleOk ? 0xFF4CAF50 : 0xFFF44336);
            tvBrightness.setText(String.format(Locale.getDefault(), "亮度: %d %s", finalBrightness, finalBrightnessOk ? "✓" : "✗"));
            tvBrightness.setTextColor(finalBrightnessOk ? 0xFF4CAF50 : 0xFFF44336);
            tvPersons.setText(String.format(Locale.getDefault(), "人数: %d %s", finalPersonCount, finalSinglePerson ? "✓" : "✗"));
            tvPersons.setTextColor(finalSinglePerson ? 0xFF4CAF50 : 0xFFF44336);

            // 状态提示
            if (currentState == State.IDLE) {
                if (finalAllPassed) {
                    tvStatus.setText("质量合格，点击「开始注册」");
                    btnAction.setEnabled(true);
                    btnAction.setBackgroundColor(0xFF1A237E);
                } else {
                    tvStatus.setText(getAdjustHint(finalSizeOk, finalAngleOk, finalBrightnessOk, finalSinglePerson));
                    btnAction.setEnabled(false);
                    btnAction.setBackgroundColor(0xFF455A64);
                }
            } else if (currentState == State.WAITING_STABLE) {
                tvStatus.setText("请保持不动，正在确认稳定性...");
            }
        });

        // 状态机：等待稳定
        if (currentState == State.WAITING_STABLE) {
            if (allPassed) {
                stableFrameCount++;
                if (stableFrameCount >= STABLE_FRAMES_REQUIRED) {
                    startCountdown();
                }
            } else {
                stableFrameCount = 0; // 不合格则重置
            }
        }
    }

    /** 获取调整提示（告诉用户哪项不合格）。 */
    private String getAdjustHint(boolean sizeOk, boolean angleOk, boolean brightnessOk, boolean singlePerson) {
        if (!singlePerson) return "请确保画面中只有您一人";
        if (!sizeOk) return "请靠近摄像头，人脸需要 > 80px";
        if (!angleOk) return "请正对摄像头，不要侧脸或低头";
        if (!brightnessOk) return "请调整光线（太暗或太亮）";
        return "请站在椭圆区域内";
    }

    /** 计算人脸区域平均亮度（NV21 Y 平面）。 */
    private int calcFaceBrightness(byte[] nv21, int width, int height, Rect faceRect) {
        try {
            int cx = faceRect.centerX();
            int cy = faceRect.centerY();
            int rw = faceRect.width() / 3; // 取人脸中心 1/3 区域
            int rh = faceRect.height() / 3;
            long sum = 0;
            int count = 0;
            for (int y = Math.max(0, cy - rh); y < Math.min(height, cy + rh); y++) {
                for (int x = Math.max(0, cx - rw); x < Math.min(width, cx + rw); x++) {
                    int v = nv21[y * width + x] & 0xFF;
                    sum += v;
                    count++;
                }
            }
            return count > 0 ? (int) (sum / count) : 0;
        } catch (Exception e) {
            return 128;
        }
    }

    /** 开始注册（用户点击按钮）。 */
    private void startRegistration() {
        String name = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show();
            return;
        }
        currentState = State.WAITING_STABLE;
        stableFrameCount = 0;
        btnAction.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);
        nameInputLayout.setVisibility(View.GONE);
        tvStatus.setText("请保持不动，正在确认稳定性...");
    }

    /** 开始倒计时。 */
    private void startCountdown() {
        currentState = State.COUNTDOWN;
        runOnUiThread(() -> {
            tvCountdown.setVisibility(View.VISIBLE);
            tvStatus.setText("保持不动，即将采集...");
        });
        mainHandler.postDelayed(countdownRunnable, 100);
    }

    /** 执行采集和注册。 */
    private void doCapture() {
        if (isCapturing.get()) return;
        isCapturing.set(true);
        currentState = State.CAPTURING;

        runOnUiThread(() -> tvStatus.setText("正在注册，请稍候..."));

        // 在后台线程执行注册（避免阻塞帧回调）
        new Thread(() -> {
            try {
                if (lastFrame == null) {
                    onRegisterFailed("无帧数据");
                    return;
                }
                String name = etName.getText().toString().trim();
                boolean ok = FaceServer.getInstance().registerFromNv21(
                        this, lastFrame, lastFrameW, lastFrameH, name, 0);
                if (ok) {
                    onRegisterSuccess(name);
                } else {
                    onRegisterFailed("特征提取失败，请重试");
                }
            } catch (Exception e) {
                Log.e(TAG, "Register error", e);
                onRegisterFailed("注册异常：" + e.getMessage());
            } finally {
                isCapturing.set(false);
            }
        }, "register-worker").start();
    }

    private void onRegisterSuccess(String name) {
        currentState = State.SUCCESS;
        runOnUiThread(() -> {
            tvStatus.setText("注册成功！");
            successLayout.setVisibility(View.VISIBLE);
            TextView tvSuccessName = findViewById(R.id.tv_success_name);
            tvSuccessName.setText("姓名：" + name + "（可在 WebUI 修改）");
            overlayView.setStatusText("注册成功 ✓");
        });
    }

    private void onRegisterFailed(String reason) {
        currentState = State.FAILED;
        runOnUiThread(() -> {
            tvStatus.setText("注册失败：" + reason);
            Toast.makeText(this, "注册失败：" + reason, Toast.LENGTH_LONG).show();
            resetToIdle();
        });
    }

    /** 重置到初始状态。 */
    private void resetToIdle() {
        currentState = State.IDLE;
        stableFrameCount = 0;
        mainHandler.removeCallbacks(countdownRunnable);
        runOnUiThread(() -> {
            successLayout.setVisibility(View.GONE);
            tvCountdown.setVisibility(View.GONE);
            btnAction.setVisibility(View.VISIBLE);
            btnCancel.setVisibility(View.VISIBLE);
            nameInputLayout.setVisibility(View.VISIBLE);
            tvStatus.setText("正在检测人脸...");
            overlayView.setStatusText("");
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                tvStatus.setText("需要摄像头权限才能注册");
                Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewView.isAvailable() && !cameraStarted) {
            cameraSource.setPreviewTexture(previewView.getSurfaceTexture());
            startCameraIfPermission();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
        mainHandler.removeCallbacks(countdownRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
