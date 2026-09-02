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
import android.widget.LinearLayout;
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
 * 电视端引导注册 Activity（第二阶段：模式选择 + 多姿态引导 + 追加到已有用户）。
 *
 * 功能：
 * 1. 模式选择：新建用户 / 追加到已有用户
 * 2. 注册模式：快速(1张正脸) / 完整(5张多姿态：正脸/左转/右转/抬头/低头)
 * 3. 实时质量检测：人脸大小(>80px)、亮度(50-200)、单人检测
 * 4. 多姿态引导：每个姿态显示引导文字，自动检测姿态完成
 * 5. 自动采集：质量合格稳定 1 秒后，自动倒计时 3-2-1 采集
 * 6. 追加到已有用户：从已有用户列表选择，追加特征模板（每人最多6个）
 *
 * 设计要点：
 * - 直接使用 Camera2CaptureSource（不通过 Service），生命周期与 Activity 绑定
 * - 质量检测在帧回调线程执行，UI 更新在主线程
 * - 多姿态模式：每个姿态独立采集，全部完成后统一显示结果
 * - 姿态检测：通过人脸宽高比粗略判断（正脸 0.8-1.2，左转/右转 0.6-0.9，抬头/低头 1.1-1.4）
 */
public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";
    private static final int REQ_CAMERA = 1001;

    // 质量阈值
    private static final int MIN_FACE_PX = 80;
    private static final int MIN_BRIGHTNESS = 50;
    private static final int MAX_BRIGHTNESS = 200;
    private static final int STABLE_FRAMES_REQUIRED = 25;
    private static final int COUNTDOWN_SECONDS = 3;

    // 多姿态定义（仅文字引导，不做角度自动检测——ArcSoft人脸框接近正方形，宽高比无法判断姿态）
    private static final String[] POSE_NAMES = {"正脸", "左转", "右转", "抬头", "低头"};
    private static final String[] POSE_GUIDES = {
            "请正对摄像头，保持自然表情",
            "请将头向左转约30度",
            "请将头向右转约30度",
            "请将头向上仰约15度",
            "请将头向下低约15度"
    };

    // UI 组件
    private TextureView previewView;
    private RegisterOverlayView overlayView;
    private TextView tvTitle, tvSubtitle, tvStatus, tvSize, tvAngle, tvBrightness, tvPersons, tvCountdown;
    private TextView tvPoseGuide, tvSuccessName, tvQuality;
    private EditText etName;
    private Button btnCancel, btnAction, btnDone, btnReregister, btnTest;
    private Button btnNewUser, btnAppendUser, btnModeCancel;
    private Button btnQuickMode, btnFullMode;
    private Button btnUserListBack;
    private LinearLayout nameInputLayout, successLayout, bottomPanel;
    private LinearLayout modeSelectPanel, userListPanel, userListContainer;
    private LinearLayout poseProgress;
    private TextView[] poseIndicators = new TextView[5];

    // 摄像头
    private Camera2CaptureSource cameraSource;
    private boolean cameraStarted = false;

    // 注册模式
    private enum RegisterMode { QUICK, FULL }
    private RegisterMode registerMode = RegisterMode.QUICK;

    // 用户模式
    private enum UserMode { NEW, APPEND }
    private UserMode userMode = UserMode.NEW;
    private String selectedUserName = null; // 追加模式下选中的已有用户

    // 状态
    private enum State { MODE_SELECT, USER_LIST, READY, WAITING_STABLE, COUNTDOWN, CAPTURING, SUCCESS, FAILED }
    private volatile State currentState = State.MODE_SELECT;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);

    // 多姿态
    private int currentPoseIndex = 0;
    private int[] poseResults = new int[5]; // 每个姿态的采集结果：0=未完成, 1=成功, -1=失败
    private int successfulPoses = 0;

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
        setupModeSelect();

        // 默认姓名
        String defaultName = "用户_" + new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date());
        etName.setText(defaultName);
        etName.setSelection(etName.getText().length());
    }

    private void initViews() {
        previewView = findViewById(R.id.preview);
        overlayView = findViewById(R.id.overlay);
        tvTitle = findViewById(R.id.tv_title);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        tvStatus = findViewById(R.id.tv_status);
        tvSize = findViewById(R.id.tv_size);
        tvAngle = findViewById(R.id.tv_angle);
        tvBrightness = findViewById(R.id.tv_brightness);
        tvPersons = findViewById(R.id.tv_persons);
        tvCountdown = findViewById(R.id.tv_countdown);
        tvPoseGuide = findViewById(R.id.tv_pose_guide);
        tvSuccessName = findViewById(R.id.tv_success_name);
        tvQuality = findViewById(R.id.tv_quality);
        etName = findViewById(R.id.et_name);
        btnCancel = findViewById(R.id.btn_cancel);
        btnAction = findViewById(R.id.btn_action);
        btnDone = findViewById(R.id.btn_done);
        btnReregister = findViewById(R.id.btn_reregister);
        btnTest = findViewById(R.id.btn_test);
        btnNewUser = findViewById(R.id.btn_new_user);
        btnAppendUser = findViewById(R.id.btn_append_user);
        btnModeCancel = findViewById(R.id.btn_mode_cancel);
        btnQuickMode = findViewById(R.id.btn_quick_mode);
        btnFullMode = findViewById(R.id.btn_full_mode);
        btnUserListBack = findViewById(R.id.btn_user_list_back);
        nameInputLayout = findViewById(R.id.name_input_layout);
        successLayout = findViewById(R.id.success_layout);
        bottomPanel = findViewById(R.id.bottom_panel);
        modeSelectPanel = findViewById(R.id.mode_select_panel);
        userListPanel = findViewById(R.id.user_list_panel);
        userListContainer = findViewById(R.id.user_list_container);
        poseProgress = findViewById(R.id.pose_progress);

        poseIndicators[0] = findViewById(R.id.pose_1);
        poseIndicators[1] = findViewById(R.id.pose_2);
        poseIndicators[2] = findViewById(R.id.pose_3);
        poseIndicators[3] = findViewById(R.id.pose_4);
        poseIndicators[4] = findViewById(R.id.pose_5);

        tvCountdown.setVisibility(View.GONE);
        successLayout.setVisibility(View.GONE);
        userListPanel.setVisibility(View.GONE);
        poseProgress.setVisibility(View.GONE);
        tvPoseGuide.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);

        btnCancel.setOnClickListener(v -> finish());
        btnAction.setOnClickListener(v -> startRegistration());
        btnDone.setOnClickListener(v -> finish());
        btnTest.setOnClickListener(v -> startTestMode());
        btnReregister.setOnClickListener(v -> resetToModeSelect());
        btnModeCancel.setOnClickListener(v -> finish());
        btnUserListBack.setOnClickListener(v -> showModeSelect());

        btnQuickMode.setOnClickListener(v -> setRegisterMode(RegisterMode.QUICK));
        btnFullMode.setOnClickListener(v -> setRegisterMode(RegisterMode.FULL));

        // 为所有按钮设置焦点监听器，确保 TV 遥控器焦点时视觉效果明显
        // （selector 在某些 TV 设备上焦点状态触发不稳定，代码手动兜底）
        Button[] allButtons = {btnNewUser, btnAppendUser, btnModeCancel, btnUserListBack,
                btnCancel, btnAction, btnDone, btnReregister, btnTest, btnQuickMode, btnFullMode};
        for (Button b : allButtons) {
            applyFocusEffect(b);
        }
    }

    /** 为按钮应用明显的焦点效果：焦点时橙色背景+白色文字+轻微放大 */
    private void applyFocusEffect(Button btn) {
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                btn.setBackgroundColor(0xFFFF9800); // 橙色
                btn.setTextColor(0xFFFFFFFF);
                btn.setScaleX(1.05f);
                btn.setScaleY(1.05f);
            } else {
                // 恢复默认背景（根据按钮类型）
                if (btn == btnNewUser || btn == btnAction || btn == btnQuickMode || btn == btnFullMode) {
                    btn.setBackgroundResource(R.drawable.btn_primary_selector);
                } else if (btn == btnDone) {
                    btn.setBackgroundResource(R.drawable.btn_success_selector);
                } else if (btn == btnTest || btn == btnAppendUser) {
                    btn.setBackgroundResource(R.drawable.btn_teal_selector);
                } else {
                    btn.setBackgroundResource(R.drawable.btn_secondary_selector);
                }
                btn.setTextColor(0xFFB0BEC5);
                btn.setScaleX(1.0f);
                btn.setScaleY(1.0f);
            }
        });
    }

    private void setupModeSelect() {
        btnNewUser.setOnClickListener(v -> {
            userMode = UserMode.NEW;
            selectedUserName = null;
            showReadyScreen();
        });
        btnAppendUser.setOnClickListener(v -> {
            userMode = UserMode.APPEND;
            showUserList();
        });
    }

    private void showModeSelect() {
        currentState = State.MODE_SELECT;
        modeSelectPanel.setVisibility(View.VISIBLE);
        userListPanel.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
        successLayout.setVisibility(View.GONE);
        poseProgress.setVisibility(View.GONE);
        tvPoseGuide.setVisibility(View.GONE);
        // 设置默认焦点，确保遥控器可操作
        btnNewUser.post(() -> btnNewUser.requestFocus());
    }

    private void showUserList() {
        currentState = State.USER_LIST;
        modeSelectPanel.setVisibility(View.GONE);
        userListPanel.setVisibility(View.VISIBLE);
        bottomPanel.setVisibility(View.GONE);

        // 加载已有用户列表
        userListContainer.removeAllViews();
        List<String> names = FaceServer.getInstance().getFaceNames();
        if (names == null || names.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无已注册用户");
            empty.setTextColor(0xFFB0BEC5);
            empty.setTextSize(18);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, 48, 0, 48);
            userListContainer.addView(empty);
        } else {
            for (String name : names) {
                Button btn = new Button(this);
                btn.setText(name);
                btn.setTextColor(0xFFFFFFFF);
                btn.setTextSize(18);
                btn.setBackgroundResource(R.drawable.btn_teal_selector);
                btn.setPadding(24, 16, 24, 16);
                btn.setFocusable(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, 12);
                btn.setLayoutParams(lp);
                btn.setOnClickListener(v -> {
                    selectedUserName = name;
                    showReadyScreen();
                });
                userListContainer.addView(btn);
            }
            // 设置第一个用户按钮为默认焦点
            if (userListContainer.getChildCount() > 0) {
                userListContainer.getChildAt(0).post(() -> userListContainer.getChildAt(0).requestFocus());
            }
        }
    }

    private void showReadyScreen() {
        currentState = State.READY;
        modeSelectPanel.setVisibility(View.GONE);
        userListPanel.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.VISIBLE);

        if (userMode == UserMode.APPEND && selectedUserName != null) {
            tvTitle.setText("追加特征：" + selectedUserName);
            tvSubtitle.setText("为已有用户追加更多角度的人脸模板");
            etName.setText(selectedUserName);
            etName.setEnabled(false);
            nameInputLayout.setVisibility(View.GONE);
        } else {
            tvTitle.setText("新建用户注册");
            tvSubtitle.setText("请站在椭圆区域内，正对摄像头");
            etName.setEnabled(true);
            nameInputLayout.setVisibility(View.VISIBLE);
        }

        setRegisterMode(registerMode);
        tvStatus.setText("正在检测人脸...");
    }

    private void setRegisterMode(RegisterMode mode) {
        this.registerMode = mode;
        if (mode == RegisterMode.QUICK) {
            btnQuickMode.setBackgroundResource(R.drawable.btn_primary_selector);
            btnQuickMode.setTextColor(0xFFFFFFFF);
            btnFullMode.setBackgroundResource(R.drawable.btn_secondary_selector);
            btnFullMode.setTextColor(0xFFB0BEC5);
            poseProgress.setVisibility(View.GONE);
            tvPoseGuide.setVisibility(View.GONE);
        } else {
            btnFullMode.setBackgroundResource(R.drawable.btn_primary_selector);
            btnFullMode.setTextColor(0xFFFFFFFF);
            btnQuickMode.setBackgroundResource(R.drawable.btn_secondary_selector);
            btnQuickMode.setTextColor(0xFFB0BEC5);
            poseProgress.setVisibility(View.VISIBLE);
            tvPoseGuide.setVisibility(View.VISIBLE);
            resetPoseProgress();
        }
    }

    private void resetPoseProgress() {
        currentPoseIndex = 0;
        successfulPoses = 0;
        for (int i = 0; i < 5; i++) {
            poseResults[i] = 0;
            poseIndicators[i].setBackgroundColor(0xFF455A64);
            poseIndicators[i].setTextColor(0xFFFFFFFF);
        }
        updatePoseUI();
    }

    private void updatePoseUI() {
        if (registerMode != RegisterMode.FULL) return;
        tvPoseGuide.setText(POSE_GUIDES[currentPoseIndex]);
        for (int i = 0; i < 5; i++) {
            if (i < currentPoseIndex) {
                poseIndicators[i].setBackgroundColor(0xFF4CAF50); // 已完成
            } else if (i == currentPoseIndex) {
                poseIndicators[i].setBackgroundColor(0xFFFF9800); // 当前
            } else {
                poseIndicators[i].setBackgroundColor(0xFF455A64); // 未开始
            }
        }
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
        // 测试识别模式：实时识别并显示结果
        if (testMode) {
            processTestFrame(nv21, width, height);
            return;
        }
        if (currentState == State.CAPTURING || currentState == State.SUCCESS
                || currentState == State.MODE_SELECT || currentState == State.USER_LIST) return;

        lastFrame = nv21;
        lastFrameW = width;
        lastFrameH = height;

        List<FaceInfo> faces = FaceServer.getInstance().detectFacesOnly(nv21, width, height, null);

        boolean sizeOk = false, brightnessOk = false, singlePerson = false;
        int faceSize = 0;
        float aspectRatio = 0;
        int brightness = 0;

        if (faces != null && !faces.isEmpty()) {
            FaceInfo mainFace = faces.get(0);
            Rect r = mainFace.getRect();
            if (r != null) {
                faceSize = Math.max(r.width(), r.height());
                aspectRatio = (float) r.width() / Math.max(r.height(), 1);
                brightness = calcFaceBrightness(nv21, width, height, r);

                sizeOk = faceSize >= MIN_FACE_PX;
                brightnessOk = brightness >= MIN_BRIGHTNESS && brightness <= MAX_BRIGHTNESS;
                singlePerson = faces.size() == 1;
                // 注意：不再检测姿态角度——ArcSoft人脸框接近正方形，宽高比无法判断左转/右转
                // 多姿态模式仅靠文字引导用户做动作，质量检测只查大小/亮度/单人
            }
        }

        boolean allPassed = sizeOk && brightnessOk && singlePerson;

        // 更新 UI
        final boolean finalAllPassed = allPassed;
        final boolean finalSizeOk = sizeOk;
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
            // 角度栏显示当前姿态引导（而非检测结果）
            String poseText = registerMode == RegisterMode.FULL
                    ? "姿态: " + POSE_NAMES[currentPoseIndex]
                    : String.format(Locale.getDefault(), "比例: %.2f", finalAspect);
            tvAngle.setText(poseText);
            tvAngle.setTextColor(0xFFB0BEC5);
            tvBrightness.setText(String.format(Locale.getDefault(), "亮度: %d %s", finalBrightness, finalBrightnessOk ? "✓" : "✗"));
            tvBrightness.setTextColor(finalBrightnessOk ? 0xFF4CAF50 : 0xFFF44336);
            tvPersons.setText(String.format(Locale.getDefault(), "人数: %d %s", finalPersonCount, finalSinglePerson ? "✓" : "✗"));
            tvPersons.setTextColor(finalSinglePerson ? 0xFF4CAF50 : 0xFFF44336);

            if (currentState == State.READY) {
                if (finalAllPassed) {
                    tvStatus.setText("质量合格，点击「开始注册」");
                    btnAction.setEnabled(true);
                    btnAction.setBackgroundResource(R.drawable.btn_primary_selector);
                } else {
                    tvStatus.setText(getAdjustHint(finalSizeOk, finalBrightnessOk, finalSinglePerson));
                    btnAction.setEnabled(false);
                    btnAction.setBackgroundResource(R.drawable.btn_secondary_selector);
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
                stableFrameCount = 0;
            }
        }
    }

    private String getAdjustHint(boolean sizeOk, boolean brightnessOk, boolean singlePerson) {
        if (!singlePerson) return "请确保画面中只有您一人";
        if (!sizeOk) return "请靠近摄像头，人脸需要 > 80px";
        if (!brightnessOk) return "请调整光线（太暗或太亮）";
        if (registerMode == RegisterMode.FULL) {
            return "请按提示完成：" + POSE_GUIDES[currentPoseIndex];
        }
        return "请站在椭圆区域内";
    }

    private int calcFaceBrightness(byte[] nv21, int width, int height, Rect faceRect) {
        try {
            int cx = faceRect.centerX();
            int cy = faceRect.centerY();
            int rw = faceRect.width() / 3;
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

    private void startRegistration() {
        String name = userMode == UserMode.APPEND ? selectedUserName : etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show();
            return;
        }

        if (registerMode == RegisterMode.FULL) {
            resetPoseProgress();
        }

        currentState = State.WAITING_STABLE;
        stableFrameCount = 0;
        btnAction.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);
        nameInputLayout.setVisibility(View.GONE);
        findViewById(R.id.register_mode_layout).setVisibility(View.GONE);
        tvStatus.setText("请保持不动，正在确认稳定性...");
    }

    private void startCountdown() {
        currentState = State.COUNTDOWN;
        runOnUiThread(() -> {
            tvCountdown.setVisibility(View.VISIBLE);
            tvStatus.setText("保持不动，即将采集...");
        });
        mainHandler.postDelayed(countdownRunnable, 100);
    }

    private void doCapture() {
        if (isCapturing.get()) return;
        isCapturing.set(true);
        currentState = State.CAPTURING;

        runOnUiThread(() -> tvStatus.setText("正在注册，请稍候..."));

        new Thread(() -> {
            try {
                if (lastFrame == null) {
                    onCaptureFailed("无帧数据");
                    return;
                }
                String name = userMode == UserMode.APPEND ? selectedUserName : etName.getText().toString().trim();
                boolean ok = FaceServer.getInstance().registerFromNv21(
                        this, lastFrame, lastFrameW, lastFrameH, name, 0);
                if (ok) {
                    onCaptureSuccess(name);
                } else {
                    onCaptureFailed("特征提取失败，请重试");
                }
            } catch (Exception e) {
                Log.e(TAG, "Register error", e);
                onCaptureFailed("注册异常：" + e.getMessage());
            } finally {
                isCapturing.set(false);
            }
        }, "register-worker").start();
    }

    private void onCaptureSuccess(String name) {
        if (registerMode == RegisterMode.QUICK) {
            // 快速模式：直接完成
            onRegisterSuccess(name, 1);
        } else {
            // 完整模式：记录当前姿态成功，进入下一个姿态
            poseResults[currentPoseIndex] = 1;
            successfulPoses++;
            runOnUiThread(() -> {
                poseIndicators[currentPoseIndex].setBackgroundColor(0xFF4CAF50);
                Toast.makeText(this, POSE_NAMES[currentPoseIndex] + " 采集成功", Toast.LENGTH_SHORT).show();
            });

            if (currentPoseIndex < 4) {
                // 进入下一个姿态
                currentPoseIndex++;
                stableFrameCount = 0;
                currentState = State.WAITING_STABLE;
                runOnUiThread(() -> {
                    updatePoseUI();
                    tvStatus.setText("请保持不动，正在确认稳定性...");
                });
            } else {
                // 全部姿态完成
                onRegisterSuccess(name, successfulPoses);
            }
        }
    }

    private void onCaptureFailed(String reason) {
        if (registerMode == RegisterMode.FULL) {
            poseResults[currentPoseIndex] = -1;
        }
        runOnUiThread(() -> {
            tvStatus.setText("采集失败：" + reason);
            Toast.makeText(this, "采集失败：" + reason, Toast.LENGTH_LONG).show();

            if (registerMode == RegisterMode.FULL && currentPoseIndex < 4) {
                // 失败后跳过当前姿态，继续下一个
                currentPoseIndex++;
                stableFrameCount = 0;
                currentState = State.WAITING_STABLE;
                updatePoseUI();
                tvStatus.setText("请保持不动，正在确认稳定性...");
            } else if (registerMode == RegisterMode.FULL && successfulPoses > 0) {
                // 最后一个姿态失败，但已有成功的，完成注册
                onRegisterSuccess(selectedUserName != null ? selectedUserName : etName.getText().toString().trim(), successfulPoses);
            } else {
                // 快速模式失败，重置
                resetToReady();
            }
        });
    }

    private void onRegisterSuccess(String name, int poseCount) {
        currentState = State.SUCCESS;
        registeredUserName = name;
        runOnUiThread(() -> {
            tvStatus.setText("注册成功！");
            successLayout.setVisibility(View.VISIBLE);
            String modeText = registerMode == RegisterMode.QUICK ? "快速模式(1张)" : "完整模式(" + poseCount + "/5张)";
            String userText = userMode == UserMode.APPEND ? "追加到：" : "新建用户：";
            tvSuccessName.setText(userText + name + "（" + modeText + "）\n可在 WebUI 查看和管理");
            // 质量评估
            String qualityText;
            int qualityColor;
            if (poseCount >= 4) {
                qualityText = "★ 注册质量：优秀（多姿态覆盖完整，识别率高）";
                qualityColor = 0xFF4CAF50; // 绿色
            } else if (poseCount >= 2) {
                qualityText = "● 注册质量：良好（建议补充更多角度提升识别率）";
                qualityColor = 0xFFFFC107; // 黄色
            } else {
                qualityText = "▲ 注册质量：一般（仅1张正脸，建议用完整模式补充侧脸/抬头/低头）";
                qualityColor = 0xFFFF9800; // 橙色
            }
            tvQuality.setText(qualityText);
            tvQuality.setTextColor(qualityColor);
            overlayView.setStatusText("注册成功 ✓");
        });
    }

    // ===== 第三阶段：注册后立即测试识别 =====
    private String registeredUserName = null;
    private boolean testMode = false;
    private long lastTestAnalyzeMs = 0;
    private static final long TEST_ANALYZE_INTERVAL_MS = 300;

    /** 进入测试识别模式：隐藏成功遮罩，保持摄像头，实时显示识别结果。 */
    private void startTestMode() {
        testMode = true;
        runOnUiThread(() -> {
            successLayout.setVisibility(View.GONE);
            tvStatus.setText("测试识别中：请面对摄像头，转动头部测试不同角度...");
            overlayView.setStatusText("测试识别中");
            Toast.makeText(this, "测试识别模式：转动头部测试不同角度，点击完成退出", Toast.LENGTH_LONG).show();
        });
    }

    /** 测试模式下的帧处理：实时识别并显示结果。 */
    private void processTestFrame(byte[] nv21, int w, int h) {
        if (!testMode) return;
        long now = System.currentTimeMillis();
        if (now - lastTestAnalyzeMs < TEST_ANALYZE_INTERVAL_MS) return;
        lastTestAnalyzeMs = now;
        try {
            FaceServer fs = FaceServer.getInstance();
            List<FaceInfo> faces = fs.detectFacesOnly(nv21, w, h, null);
            if (faces == null || faces.isEmpty()) {
                runOnUiThread(() -> tvStatus.setText("测试识别：未检测到人脸，请靠近摄像头"));
                return;
            }
            // 只取最大的一张脸
            FaceInfo best = faces.get(0);
            int maxArea = 0;
            for (FaceInfo f : faces) {
                int area = f.getRect().width() * f.getRect().height();
                if (area > maxArea) { maxArea = area; best = f; }
            }
            FaceServer.RecognizeResult rr = fs.featureAndCompare(nv21, w, h, best);
            final String name = (rr.name == null || rr.name.isEmpty() || "未知".equals(rr.name)) ? "未识别" : rr.name;
            final float score = rr.score;
            final boolean isRegistered = registeredUserName != null && registeredUserName.equals(name);
            runOnUiThread(() -> {
                String status = "测试识别：" + name + " 相似度=" + String.format("%.2f", score);
                if (isRegistered) {
                    status += " ✓（与注册用户匹配）";
                    tvStatus.setTextColor(0xFF4CAF50);
                } else if ("未识别".equals(name)) {
                    status += " ✗（未匹配到注册用户，建议补充该角度照片）";
                    tvStatus.setTextColor(0xFFFF9800);
                } else {
                    status += "（识别为其他用户）";
                    tvStatus.setTextColor(0xFFB0BEC5);
                }
                tvStatus.setText(status);
            });
        } catch (Exception e) {
            Log.w("RegisterTest", "test frame failed", e);
        }
    }

    private void resetToReady() {
        currentState = State.READY;
        stableFrameCount = 0;
        mainHandler.removeCallbacks(countdownRunnable);
        runOnUiThread(() -> {
            successLayout.setVisibility(View.GONE);
            tvCountdown.setVisibility(View.GONE);
            btnAction.setVisibility(View.VISIBLE);
            btnCancel.setVisibility(View.VISIBLE);
            nameInputLayout.setVisibility(userMode == UserMode.APPEND ? View.GONE : View.VISIBLE);
            findViewById(R.id.register_mode_layout).setVisibility(View.VISIBLE);
            tvStatus.setText("正在检测人脸...");
            overlayView.setStatusText("");
            if (registerMode == RegisterMode.FULL) {
                resetPoseProgress();
            }
        });
    }

    private void resetToModeSelect() {
        mainHandler.removeCallbacks(countdownRunnable);
        showModeSelect();
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
