package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.content.res.TypedArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.fongmi.android.tv.db.SyncSnapshot;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.databinding.DialogDeviceBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.ui.adapter.DeviceAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.ScanTask;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class SyncDialog extends BaseBottomSheetDialog implements DeviceAdapter.OnClickListener, ScanTask.Listener {

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
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDeviceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.mode.setVisibility(View.VISIBLE);
        setRecyclerView();
        getDevice();
        setMode();
    }

    @Override
    protected void initEvent() {
        binding.mode.setOnClickListener(v -> onMode());
        binding.scan.setOnClickListener(v -> onScan());
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

    private void onScan() {
        launcher.launch(new Intent(requireActivity(), ScanActivity.class));
    }

    private boolean found;
    private final AtomicInteger requestGeneration = new AtomicInteger();
    private Call syncCall;

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
        send(item, binding.mode.getTag().toString(), false);
    }

    @Override
    public boolean onLongClick(Device item) {
        String mode = binding.mode.getTag().toString();
        if (mode.equals("0")) return false;
        send(item, mode, true);
        return true;
    }

    private void send(Device item, String mode, boolean force) {
        int gen = requestGeneration.incrementAndGet();
        if (syncCall != null) syncCall.cancel();
        boolean replace = force && mode.equals("2");
        int cid = VodConfig.getCid();
        String sourceUrl = VodConfig.getUrl();
        String url = replace
                ? item.getIp() + "/action?do=syncSnapshot&type=" + type
                : String.format(Locale.getDefault(), "%s/action?do=sync&mode=%s&type=%s%s",
                        item.getIp(), mode, type, force ? "&force=true" : "");
        FormBody requestBody = replace
                ? new FormBody.Builder().add("config", App.gson().toJson(VodConfig.get().getConfig())).build()
                : body.build();
        syncCall = OkHttp.newCall(client, url, requestBody);
        syncCall.enqueue(getCallback(gen, replace, cid, sourceUrl));
    }

    private Callback getCallback(int gen, boolean replace, int cid, String sourceUrl) {
        return new Callback() {
            private boolean active(Call call) {
                return gen == requestGeneration.get() && !call.isCanceled();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (!active(call)) return;
                    if (replace) SyncSnapshot.receive(response, type, cid, sourceUrl, () -> active(call));
                    else if (!response.isSuccessful()) throw new IOException("Sync HTTP " + response.code());
                    App.post(() -> {
                        if (!active(call)) return;
                        if (replace) {
                            if (type.equals("history")) RefreshEvent.history();
                            else RefreshEvent.keep();
                        }
                        onSuccess();
                    });
                } catch (IOException | RuntimeException e) {
                    showFailure(call, e);
                }
            }

            private void showFailure(Call call, Exception e) {
                App.post(() -> {
                    if (!active(call)) return;
                    if (e instanceof SyncSnapshot.ProtocolException) Notify.show(R.string.error_sync_snapshot);
                    else Notify.show(e.getMessage());
                });
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                showFailure(call, e);
            }
        };
    }

    @Override
    public void onDestroyView() {
        requestGeneration.incrementAndGet();
        if (syncCall != null) syncCall.cancel();
        super.onDestroyView();
        scanTask.stop();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) scanTask.start(result.getData().getStringExtra("address"));
    });
}
