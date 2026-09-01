package com.example.arcfaceandroid;

import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * 纯预览 Fragment：只负责把摄像头画面显示出来。
 *
 * 摄像头与识别逻辑已移到 {@link FaceServerService}（前台 Service）。本 Fragment 在前景时：
 *   1) 提供一个 TextureView 作为预览 Surface；
 *   2) 把它的 SurfaceTexture 通过 Binder 交给 Service（Service 据此把预览并入取流会话）；
 *   3) 离开本 App / 息屏时 TextureView 的 Surface 被销毁，Fragment 通知 Service 移除预览，
 *      退化为「纯识别流」——识别在 Service 内继续，只是不画框。
 *
 * 识别结果与变焦变换由 Activity 从 {@link RecognitionState} 读取并画到 Overlay 上。
 */
public class TvCameraFragment extends Fragment {

    private static final String TAG = "TvCameraFragment";
    private static final int REQ_CAMERA = 1001;

    private FrameLayout cameraContainer;
    private TextureView previewView;
    private SurfaceTexture pendingSurface;
    private FaceServerService.FaceServerBinder serviceBinder;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_tv_camera, container, false);
        cameraContainer = root.findViewById(R.id.camera_container);
        previewView = new TextureView(requireContext());
        previewView.setSurfaceTextureListener(surfaceTextureListener);
        if (cameraContainer != null) {
            cameraContainer.addView(previewView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }
        return root;
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    pendingSurface = surface;
                    if (serviceBinder != null) serviceBinder.setPreviewSurfaceTexture(surface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    pendingSurface = null;
                    if (serviceBinder != null) serviceBinder.clearPreviewSurfaceTexture();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    /** Activity 绑定到 Service 后注入 Binder；若有待提交的预览 Surface 立即交给 Service */
    public void attachService(FaceServerService.FaceServerBinder binder) {
        this.serviceBinder = binder;
        if (pendingSurface != null) binder.setPreviewSurfaceTexture(pendingSurface);
    }

    public View getCameraTextureView() {
        return previewView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && serviceBinder != null) {
            serviceBinder.startCamera();
        }
    }
}
