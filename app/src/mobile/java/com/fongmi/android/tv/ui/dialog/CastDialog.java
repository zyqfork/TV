package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
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
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.DialogDeviceBinding;
import com.fongmi.android.tv.dlna.DLNACast;
import com.fongmi.android.tv.dlna.DLNACastManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.ui.adapter.DeviceAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ScanTask;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class CastDialog extends BaseBottomSheetDialog implements DeviceAdapter.OnClickListener, ScanTask.Listener, DLNACastManager.DeviceListener, Callback {

    private static final long EMPTY_DELAY_MS = 3500;

    private final FormBody.Builder body;
    private final OkHttpClient client;
    private final Runnable showEmpty = this::showEmptyIfNeeded;

    private DialogDeviceBinding binding;
    private DeviceAdapter adapter;
    private ScanTask scanTask;
    private CastVideo video;
    private boolean fm;
    private boolean casting;

    public CastDialog() {
        scanTask = new ScanTask(this);
        body = new FormBody.Builder();
        body.add("device", Device.get().toString());
        body.add("config", Config.vod().toString());
        client = OkHttp.client(Constant.TIMEOUT_SYNC);
    }

    public static CastDialog create() {
        return new CastDialog();
    }

    public CastDialog history(History history) {
        String id = history.getVodId();
        String fd = history.getVodId();
        if (fd.startsWith("/")) fd = Server.get().getAddress() + "/file" + fd.replace(Path.rootPath(), "");
        if (fd.startsWith("file")) fd = Server.get().getAddress() + "/" + fd.replace(Path.rootPath(), "").replace("://", "");
        if (fd.contains("127.0.0.1")) fd = fd.replace("127.0.0.1", Util.getIp());
        body.add("history", history.toString().replace(id, fd));
        return this;
    }

    public CastDialog video(CastVideo video) {
        this.video = video;
        return this;
    }

    public CastDialog fm(boolean fm) {
        this.fm = fm;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof CastDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDeviceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.scan.setVisibility(fm ? View.VISIBLE : View.GONE);
        DLNACastManager.get().init(requireActivity());
        DLNACastManager.get().setDeviceListener(this);
        setRecyclerView();
        getDevice();
    }

    @Override
    protected void initEvent() {
        binding.scan.setOnClickListener(v -> onScan());
        binding.refresh.setOnClickListener(v -> onRefresh());
    }

    private void setRecyclerView() {
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter = new DeviceAdapter(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
    }

    private void updateListState(boolean searching) {
        boolean empty = adapter.getItemCount() == 0;
        binding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            App.removeCallbacks(showEmpty);
            binding.status.setVisibility(View.GONE);
            return;
        }
        if (searching) {
            App.removeCallbacks(showEmpty);
            binding.status.setText(R.string.device_searching);
            binding.status.setVisibility(View.VISIBLE);
            App.post(showEmpty, EMPTY_DELAY_MS);
        } else {
            showEmptyIfNeeded();
        }
    }

    private void showEmptyIfNeeded() {
        if (binding == null || adapter == null || adapter.getItemCount() > 0) return;
        binding.status.setText(R.string.device_empty);
        binding.status.setVisibility(View.VISIBLE);
        binding.recycler.setVisibility(View.GONE);
    }

    private void getDevice() {
        adapter.setItems(Device.getAll(), () -> {
            adapter.sort(DLNACastManager.get().getRegistered(), () -> {
                if (adapter.getItemCount() == 0) onRefresh();
                else {
                    updateListState(false);
                    DLNACastManager.get().search();
                }
            });
        });
    }

    private void onScan() {
        launcher.launch(new Intent(requireActivity(), ScanActivity.class));
    }

    private void onRefresh() {
        if (casting) return;
        adapter.clear(() -> {
            Device.delete();
            if (fm) scanTask.start();
            DLNACastManager.get().search();
            updateListState(true);
        });
    }

    private void onCasted() {
        casting = false;
        ((CastDialog.Listener) requireActivity()).onCasted();
        dismiss();
    }

    private void onCastFailed() {
        casting = false;
    }

    @Override
    public void onDeviceAdded(Device device) {
        adapter.sort(device, () -> updateListState(false));
    }

    @Override
    public void onDeviceRemoved(Device device) {
        adapter.remove(device, () -> updateListState(false));
    }

    @Override
    public void onFind(Device device) {
        adapter.sort(device, () -> updateListState(false));
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
        App.post(() -> {
            onCastFailed();
            Notify.show(e.getMessage());
        });
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try (Response res = response) {
            if (res.body().string().equals("OK")) App.post(this::onCasted);
            else App.post(() -> {
                onCastFailed();
                Notify.show(R.string.device_offline);
            });
        }
    }

    @Override
    public void onItemClick(Device item) {
        if (casting) return;
        if (video == null) {
            Notify.show(R.string.device_offline);
            return;
        }
        casting = true;
        Notify.show(R.string.device_casting);
        if (item.isDLNA()) new DLNACast(video, this::onCasted, this::onCastFailed).cast(item);
        else OkHttp.newCall(client, item.getIp().concat("/action?do=cast"), body.build()).enqueue(this);
    }

    @Override
    public boolean onLongClick(Device item) {
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        App.removeCallbacks(showEmpty);
        DLNACastManager.get().setDeviceListener(null);
        DLNACastManager.get().release(requireActivity());
        scanTask.stop();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) scanTask.start(result.getData().getStringExtra("address"));
    });

    public interface Listener {

        void onCasted();
    }
}
