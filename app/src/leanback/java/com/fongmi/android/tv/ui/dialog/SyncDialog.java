package com.fongmi.android.tv.ui.dialog;

import android.content.res.TypedArray;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.DialogDeviceBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.DeviceAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.ScanTask;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class SyncDialog extends BaseAlertDialog implements DeviceAdapter.OnClickListener, ScanTask.Listener {

    private final FormBody.Builder body;
    private final OkHttpClient client;
    private final TypedArray mode;

    private DialogDeviceBinding binding;
    private DeviceAdapter adapter;
    private ScanTask scanTask;
    private String type;

    public SyncDialog() {
        body = new FormBody.Builder();
        scanTask = new ScanTask(this);
        client = OkHttp.client(Constant.TIMEOUT_SYNC);
        mode = ResUtil.getTypedArray(R.array.cast_mode);
    }

    public static SyncDialog create() {
        return new SyncDialog();
    }

    public SyncDialog history() {
        body.add("device", Device.get().toString());
        body.add("config", Config.vod().toString());
        body.add("targets", App.gson().toJson(History.get()));
        return type("history");
    }

    public SyncDialog keep() {
        body.add("device", Device.get().toString());
        body.add("targets", App.gson().toJson(Keep.getVod()));
        body.add("configs", App.gson().toJson(Config.findUrls()));
        return type("keep");
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SyncDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    private SyncDialog type(String type) {
        this.type = type;
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogDeviceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.mode.setVisibility(View.VISIBLE);
        // TV remotes rarely have a camera; LAN refresh is enough.
        binding.scan.setVisibility(View.GONE);
        setRecyclerView();
        getDevice();
        setMode();
    }

    @Override
    protected void initEvent() {
        binding.mode.setOnClickListener(v -> onMode());
        binding.refresh.setOnClickListener(v -> onRefresh());
    }

    private void setRecyclerView() {
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter = new DeviceAdapter(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
    }

    private void getDevice() {
        adapter.setItems(Device.getAll(), () -> {
            if (adapter.getItemCount() == 0) onRefresh();
            else {
                binding.recycler.setVisibility(View.VISIBLE);
                binding.status.setVisibility(View.GONE);
            }
        });
    }

    private void setMode() {
        int index = Setting.getSyncMode();
        binding.mode.setImageResource(mode.getResourceId(index, 0));
        binding.mode.setTag(String.valueOf(index));
    }

    private void onMode() {
        int index = Setting.getSyncMode();
        Setting.putSyncMode(index = index == mode.length() - 1 ? 0 : ++index);
        binding.mode.setImageResource(mode.getResourceId(index, 0));
        binding.mode.setTag(String.valueOf(index));
    }

    private boolean found;

    private void onRefresh() {
        found = false;
        adapter.clear(() -> {
            Device.delete();
            scanTask.start();
            binding.recycler.setVisibility(View.GONE);
            binding.status.setText(R.string.device_searching);
            binding.status.setVisibility(View.VISIBLE);
        });
    }

    private void onSuccess() {
        dismiss();
    }

    @Override
    public void onFind(Device device) {
        found = true;
        binding.status.setVisibility(View.GONE);
        binding.recycler.setVisibility(View.VISIBLE);
        adapter.sort(device);
    }

    @Override
    public void onFinished() {
        if (found) return;
        binding.status.setText(R.string.device_not_found);
        binding.status.setVisibility(View.VISIBLE);
        binding.recycler.setVisibility(View.GONE);
    }

    @Override
    public void onItemClick(Device item) {
        OkHttp.newCall(client, String.format(Locale.getDefault(), "%s/action?do=sync&mode=%s&type=%s", item.getIp(), binding.mode.getTag().toString(), type), body.build()).enqueue(getCallback());
    }

    @Override
    public boolean onLongClick(Device item) {
        String mode = binding.mode.getTag().toString();
        if (mode.equals("0")) return false;
        if (mode.equals("2")) deleteLocal();
        String force = mode.equals("1") ? "&force=true" : "";
        OkHttp.newCall(client, String.format(Locale.getDefault(), "%s/action?do=sync&mode=%s&type=%s%s", item.getIp(), mode, type, force), body.build()).enqueue(getCallback());
        return true;
    }

    private void deleteLocal() {
        if (type.equals("keep")) Keep.deleteAll();
        if (type.equals("history")) History.delete(VodConfig.getCid());
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                App.post(() -> onSuccess());
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                App.post(() -> Notify.show(e.getMessage()));
            }
        };
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        scanTask.stop();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.5f);
    }
}
