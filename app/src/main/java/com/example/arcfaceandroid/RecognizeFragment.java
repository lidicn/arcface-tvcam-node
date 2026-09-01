package com.example.arcfaceandroid;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 旧「实时识别」Fragment 的兼容空壳。
 * TV 版的人脸识别已迁移到 {@link TvCameraFragment}（UVC 取流 + 识别 + 智能变焦），
 * 此文件仅保留以满足旧引用、避免编译报错，本身不再承载任何功能。
 */
public class RecognizeFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@Nullable LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return new View(getContext());
    }
}
