package com.example.arcfaceandroid;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 电视端人脸录入：直接取 UVC 帧缓冲里的当前帧作为注册照片（不依赖系统 camera）。
 *
 * 遥控器流程：方向键聚焦 EditText 输入姓名（接键盘或用 TV IME）→ 聚焦「拍照录入」确认。
 */
public class RegisterFragment extends Fragment {

    private static final String TAG = "RegisterFragment";
    private ImageView preview;
    private EditText etName;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable previewTask;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_register, container, false);
        preview = v.findViewById(R.id.preview);
        etName = v.findViewById(R.id.et_name);
        Button btn = v.findViewById(R.id.btn_capture);
        btn.setOnClickListener(view -> doRegister());
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        previewTask = new Runnable() {
            @Override
            public void run() {
                byte[] jpg = FrameBuffer.get().getLatestSnapshotJpeg();
                if (jpg != null) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                    if (bmp != null) preview.setImageBitmap(bmp);
                }
                ui.postDelayed(this, 400);
            }
        };
        ui.postDelayed(previewTask, 200);
    }

    @Override
    public void onPause() {
        super.onPause();
        ui.removeCallbacks(previewTask);
    }

    private void doRegister() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(getContext(), R.string.register_no_name, Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] jpg = FrameBuffer.get().getLatestSnapshotJpeg();
        if (jpg == null) {
            Toast.makeText(getContext(), R.string.tv_no_usb, Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
        if (bmp == null) {
            Toast.makeText(getContext(), R.string.register_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        // 保证宽高为 4 的倍数（ArcSoft 引擎要求）
        if (bmp.getWidth() % 4 != 0 || bmp.getHeight() % 4 != 0) {
            int nw = (bmp.getWidth() / 4) * 4;
            int nh = (bmp.getHeight() / 4) * 4;
            Bitmap s = Bitmap.createScaledBitmap(bmp, nw, nh, true);
            if (s != bmp) bmp.recycle();
            bmp = s;
        }
        try {
            FaceServer.getInstance().register(getContext(), bmp, name);
            Toast.makeText(getContext(),
                    getString(R.string.tv_reg_ok, name), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "register", e);
            Toast.makeText(getContext(), R.string.tv_reg_fail, Toast.LENGTH_SHORT).show();
        }
    }
}
