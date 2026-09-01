package com.example.arcfaceandroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 人脸库管理页面。
 * 功能：
 *   - 列表展示已注册人脸（头像缩略图+姓名）
 *   - 单条删除
 *   - 从 NAS 批量导入（manifest.json）
 *   - 清空全部
 */
public class ManageFragment extends Fragment {
    private static final String TAG = "ManageFragment";

    private TextView tvCount, tvEmpty;
    private RecyclerView rvFaces;
    private Button btnImportNas, btnClearAll;
    private FaceAdapter adapter;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manage, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvCount = view.findViewById(R.id.tvManageCount);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        rvFaces = view.findViewById(R.id.rvFaces);
        btnImportNas = view.findViewById(R.id.btnImportNas);
        btnClearAll = view.findViewById(R.id.btnClearAll);

        rvFaces.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FaceAdapter(requireContext());
        rvFaces.setAdapter(adapter);

        btnImportNas.setOnClickListener(v -> importFromNas());
        btnClearAll.setOnClickListener(v -> confirmClearAll());

        loadFaceList();
    }

    /** 从本地注册目录加载已注册人脸列表 */
    private void loadFaceList() {
        List<String> names = FaceServer.getInstance().getFaceNames();
        if (names.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            rvFaces.setVisibility(View.GONE);
            tvCount.setText("0 人");
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        rvFaces.setVisibility(View.VISIBLE);
        tvCount.setText(names.size() + " 人");

        // 构建列表数据：从 imgs 目录加载缩略图
        List<FaceItem> items = new ArrayList<>();
        String imgDir = FaceServer.ROOT_PATH != null
                ? FaceServer.ROOT_PATH + File.separator + FaceServer.SAVE_IMG_DIR : null;
        for (String name : names) {
            FaceItem item = new FaceItem();
            item.name = name;
            item.imgPath = imgDir != null ? imgDir + File.separator + name + FaceServer.IMG_SUFFIX : null;
            items.add(item);
        }
        adapter.setItems(items);
    }

    /** 从 NAS 导入人脸库：下载 manifest.json → 逐个下载图片 → 注册 */
    private void importFromNas() {
        AppConfig cfg = AppConfig.get(requireContext());
        String nasUrl = cfg.getNasBaseUrl();
        if (!cfg.isAutoImportFromNas() && (nasUrl == null || nasUrl.isEmpty())) {
            Toast.makeText(requireContext(), "未配置 NAS 导入地址", Toast.LENGTH_SHORT).show();
            return;
        }

        btnImportNas.setEnabled(false);
        btnImportNas.setText(R.string.manage_importing);

        bgExecutor.execute(() -> {
            int imported = 0;
            StringBuilder errors = new StringBuilder();
            try {
                // 1. 获取 manifest
                String manifestUrl = nasUrl + "/manifest.json";
                String jsonStr = httpGet(manifestUrl);
                if (jsonStr == null || jsonStr.isEmpty()) {
                    postResult("无法连接 NAS：" + manifestUrl, 0);
                    return;
                }

                JSONObject manifest = new JSONObject(jsonStr);
                JSONArray faces = manifest.optJSONArray("faces");
                if (faces == null || faces.length() == 0) {
                    postResult("NAS 人脸库为空", 0);
                    return;
                }

                Context ctx = requireContext();
                // 2. 逐个下载并注册
                for (int i = 0; i < faces.length(); i++) {
                    String name = "";
                    try {
                        JSONObject f = faces.getJSONObject(i);
                        name = f.optString("name", "");
                        String imgUrl = f.optString("image", "");
                        if (name.isEmpty() || imgUrl.isEmpty()) continue;

                        byte[] imgBytes = httpGetBytes(imgUrl);
                        if (imgBytes == null || imgBytes.length == 0) continue;

                        Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                        if (bmp == null) continue;

                        boolean ok = FaceServer.getInstance().register(ctx, bmp, name);
                        bmp.recycle();
                        if (ok) imported++;
                    } catch (Exception e) {
                        errors.append(name).append(":").append(e.getMessage()).append("; ");
                    }
                }

                String resultMsg = imported > 0
                        ? String.format(getString(R.string.manage_import_done), imported)
                        : "未导入任何人脸";
                if (errors.length() > 0) resultMsg += "\n错误: " + errors;
                postResult(resultMsg, imported);

            } catch (Exception e) {
                Log.e(TAG, "NAS import failed", e);
                postResult(String.format(getString(R.string.manage_import_fail), e.getMessage()), 0);
            }
        });
    }

    /** 确认清空全部人脸 */
    private void confirmClearAll() {
        int count = FaceServer.getInstance().getFaceNumber();
        if (count <= 0) return;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.manage_title)
                .setMessage(String.format(getString(R.string.manage_clear_confirm), count))
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    FaceServer.getInstance().clearAllFaces(requireContext());
                    loadFaceList();
                    Toast.makeText(requireContext(), R.string.manage_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    /** 在 UI 线程更新导入结果 */
    private void postResult(String msg, int imported) {
        requireActivity().runOnUiThread(() -> {
            btnImportNas.setEnabled(true);
            btnImportNas.setText(R.string.manage_import_nas);
            if (imported > 0) loadFaceList();
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
        });
    }

    // ===== 网络工具 =====

    private static String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[4096];
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int n;
                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toString("UTF-8");
            }
        } catch (Exception e) {
            Log.e(TAG, "httpGet error", e);
        }
        return null;
    }

    private static byte[] httpGetBytes(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toByteArray();
            }
        } catch (Exception e) {
            Log.e(TAG, "httpGetBytes error", e);
        }
        return null;
    }

    // ===== RecyclerView Adapter =====

    static class FaceAdapter extends RecyclerView.Adapter<FaceAdapter.VH> {
        private final Context context;
        private final List<FaceItem> items = new ArrayList<>();
        private final int thumbSize; // dp → px

        FaceAdapter(Context ctx) {
            this.context = ctx;
            this.thumbSize = (int) (56 * ctx.getResources().getDisplayMetrics().density);
        }

        void setItems(List<FaceItem> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(context).inflate(R.layout.item_face, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            FaceItem item = items.get(position);
            holder.tvName.setText(item.name);
            holder.tvInfo.setText("阈值 ≥ " + (int)(Constants.MATCH_THRESHOLD * 100) + "%");

            // 异步加载缩略图
            if (item.imgPath != null && new File(item.imgPath).exists()) {
                Bitmap thumb = decodeSampledBitmap(item.imgPath, thumbSize, thumbSize);
                if (thumb != null) holder.ivThumb.setImageBitmap(thumb);
            } else {
                holder.ivThumb.setImageResource(android.R.drawable.ic_menu_camera); // 占位
            }

            // 长按删除 / 点击删除按钮
            holder.btnDelete.setOnClickListener(v -> confirmDelete(holder.getAdapterPosition()));
            holder.itemView.setOnLongClickListener(v -> {
                confirmDelete(holder.getAdapterPosition());
                return true;
            });
        }

        private void confirmDelete(int position) {
            if (position < 0 || position >= items.size()) return;
            FaceItem item = items.get(position);
            new AlertDialog.Builder(context)
                    .setTitle(R.string.manage_title)
                    .setMessage(String.format(context.getString(R.string.manage_delete_confirm), item.name))
                    .setPositiveButton(R.string.btn_ok, (d, w) -> {
                        // 从引擎和文件系统中删除
                        deleteFace(item.name);
                        items.remove(position);
                        notifyItemRemoved(position);
                        Toast.makeText(context,
                                String.format(context.getString(R.string.manage_deleted), item.name),
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        }

        /** 删除单条人脸记录 */
        private void deleteFace(String name) {
            // 同步移除内存特征与磁盘文件（特征+照片），后续识别不会再匹配到
            FaceServer.getInstance().removeFace(context, name);
        }

        @Override
        public int getItemCount() { return items.size(); }

        /** 缩放解码 Bitmap 以避免 OOM */
        private static Bitmap decodeSampledBitmap(String path, int reqW, int reqH) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, reqW, reqH);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, opts);
        }

        private static int calculateInSampleSize(int w, int h, int reqW, int reqH) {
            int inSampleSize = 1;
            if (h > reqH || w > reqW) {
                final int halfHeight = h / 2;
                final int halfWidth = w / 2;
                while ((halfHeight / inSampleSize) >= reqH && (halfWidth / inSampleSize) >= reqW) {
                    inSampleSize *= 2;
                }
            }
            return inSampleSize;
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivThumb, btnDelete;
            TextView tvName, tvInfo;
            CardView cardView;

            VH(View v) {
                super(v);
                cardView = (CardView) v;
                ivThumb = v.findViewById(R.id.ivFaceThumb);
                tvName = v.findViewById(R.id.tvFaceName);
                tvInfo = v.findViewById(R.id.tvFaceInfo);
                btnDelete = v.findViewById(R.id.btnDelete);
            }
        }
    }

    static class FaceItem {
        String name;
        String imgPath;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadFaceList();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bgExecutor.shutdown();
    }
}
