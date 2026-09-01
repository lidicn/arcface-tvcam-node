package com.example.arcfaceandroid;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.example.arcfaceandroid.SmartZoomController.ZoomTransform;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;

public class TvMainActivity extends FragmentActivity {

    private static final String TAG = "TvMainActivity";
    private TvCameraFragment cameraFragment;
    private FaceOverlayView overlay;
    private TextView tvStatus;
    private TextView tvCount;
    private Button btnService;
    private Button btnSnapshot;
    private Button btnZoomMode;

    private FaceServerService mService;
    private FaceServerService.FaceServerBinder mBinder;
    private boolean mFaceServiceBound = false;
    private RoomStatusView roomStatus;

    private final RecognitionState.Listener recognitionListener = () -> runOnUiThread(() -> {
        if (isFinishing()) return;
        RecognitionState s = RecognitionState.get();
        overlay.setResults(s.getResults(), s.getFrameW(), s.getFrameH());
        ZoomTransform t = s.getZoom();
        if (t != null) {
            applyTransform(cameraFragment.getCameraTextureView(), t);
            applyTransform(overlay, t);
        }
        tvStatus.setText(s.isCameraOpened() ? "摄像头：已开启" : "摄像头：" + s.getCameraMsg());
        // 双路融合：TV 路 + 米家全景路按名取最高分，刷新房间状态面板
        roomStatus.update(s.getFusedPeople(), s.isPanoOnline(), s.getPanoMsg());
    });

    private final ServiceConnection mFaceServiceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = (FaceServerService.FaceServerBinder) service;
            mService = mBinder.getService();
            mFaceServiceBound = true;
            cameraFragment.attachService(mBinder);
            Log.i(TAG, "FaceServerService 已连接");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mBinder = null;
            mFaceServiceBound = false;
            Log.i(TAG, "FaceServerService 已断开");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setContentView(R.layout.activity_tv_main);

        cameraFragment = new TvCameraFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.camera_host, cameraFragment)
                .commit();

        overlay = findViewById(R.id.overlay);
        roomStatus = findViewById(R.id.room_status);
        tvStatus = findViewById(R.id.tv_status);
        tvCount = findViewById(R.id.tv_count);
        btnService = findViewById(R.id.btn_service);
        btnSnapshot = findViewById(R.id.btn_snapshot);
        btnZoomMode = findViewById(R.id.btn_zoom_mode);

        FaceServer.getInstance().init(this);

        buildListeners();

        startFaceService();
        refreshFaceCount();
    }

    private void buildListeners() {
        btnService.setOnClickListener(v -> toggleFaceService());
        btnSnapshot.setOnClickListener(v -> {
            if (mFaceServiceBound && mService != null) {
                mService.takePhoto();
                Toast.makeText(this, R.string.tv_snapshot_taken, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.tv_service_off, Toast.LENGTH_SHORT).show();
            }
        });
        btnZoomMode.setOnClickListener(v -> {
            if (mService != null) {
                boolean on = !mService.isZoomEnabled();
                mService.setZoomEnabled(on);
                Toast.makeText(this, on ? R.string.tv_zoom_on : R.string.tv_zoom_off, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.tv_service_off, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startFaceService() {
        Intent intent = new Intent(this, FaceServerService.class);
        startForegroundService(intent);
        bindService(intent, mFaceServiceConn, BIND_AUTO_CREATE);
    }

    private void stopFaceService() {
        FaceServerService.userRequestedRunning = false;
        if (mFaceServiceBound) {
            unbindService(mFaceServiceConn);
            mFaceServiceBound = false;
        }
        stopService(new Intent(this, FaceServerService.class));
        mService = null;
        mBinder = null;
    }

    private void toggleFaceService() {
        if (mFaceServiceBound) {
            stopFaceService();
            btnService.setText(R.string.tv_start_service);
            tvStatus.setText(R.string.tv_service_off);
        } else {
            startFaceService();
            btnService.setText(R.string.tv_stop_service);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        RecognitionState.get().register(recognitionListener);
        refreshFaceCount();
        // 重新打开 App（如从熄屏恢复）时主动拉起相机，避免依赖 SCREEN_ON 广播
        if (mFaceServiceBound && mBinder != null) mBinder.startCamera();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        // 菜单键启动引导注册（TV 端遥控器最方便的入口）
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, RegisterActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RecognitionState.get().unregister(recognitionListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mFaceServiceBound) {
            unbindService(mFaceServiceConn);
            mFaceServiceBound = false;
        }
    }

    private void refreshFaceCount() {
        int n = FaceServer.getInstance().getFaceNumber();
        tvCount.setText(String.format(Locale.getDefault(), "已注册人脸：%d", n));
    }

    private void applyTransform(View view, ZoomTransform t) {
        if (view == null || t == null) return;
        view.setScaleX(t.scale);
        view.setScaleY(t.scale);
        // pivot 是画面中的相对位置(0..1)，View 的 setPivotX/Y 接收像素，需换算
        if (view.getWidth() > 0 && view.getHeight() > 0) {
            view.setPivotX(t.pivotX * view.getWidth());
            view.setPivotY(t.pivotY * view.getHeight());
        }
    }

    /** 返回局域网访问地址，用于 Home Assistant 等调用 */
    private String getLanUrl() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface nif = nets.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return "http://" + addr.getHostAddress() + ":" + Constants.SERVER_PORT;
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "getLanUrl failed", e);
        }
        return "http://<本机IP>:" + Constants.SERVER_PORT;
    }
}
