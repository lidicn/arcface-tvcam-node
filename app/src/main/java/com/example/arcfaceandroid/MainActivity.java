package com.example.arcfaceandroid;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 兼容入口：本应用已统一为安卓 TV 形态（见 {@link TvMainActivity}）。
 * 旧手机版入口在此直接跳转 TV 主界面；不在 Manifest 中声明，不会出现在启动器。
 */
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, TvMainActivity.class));
        finish();
    }
}
