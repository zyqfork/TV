package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.databinding.AdapterNetworkStorageBinding;
import com.fongmi.android.tv.dlna.DlnaPin;

import java.util.ArrayList;
import java.util.List;

public class DlnaServerAdapter extends RecyclerView.Adapter<DlnaServerAdapter.ViewHolder> {

    private static final int TYPE_PIN = 1;
    private static final int TYPE_SERVER = 2;

    private final OnClickListener listener;
    private final List<Object> items = new ArrayList<>();

    public DlnaServerAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<DlnaPin> pins, List<Device> servers) {
        items.clear();
        if (pins != null) items.addAll(pins);
        if (servers != null) items.addAll(servers);
        notifyDataSetChanged();
    }

    public void setServers(List<Device> servers) {
        List<DlnaPin> pins = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof DlnaPin) pins.add((DlnaPin) item);
        }
        setItems(pins, servers);
    }

    public void addServer(Device item) {
        if (item == null) return;
        for (int i = 0; i < items.size(); i++) {
            Object cur = items.get(i);
            if (cur instanceof Device device && device.getUuid().equals(item.getUuid())) {
                items.set(i, item);
                notifyItemChanged(i);
                return;
            }
        }
        items.add(item);
        notifyItemInserted(items.size() - 1);
    }

    public void removeServer(Device item) {
        if (item == null) return;
        for (int i = 0; i < items.size(); i++) {
            Object cur = items.get(i);
            if (cur instanceof Device device && device.getUuid().equals(item.getUuid())) {
                items.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public int getServerCount() {
        int count = 0;
        for (Object item : items) if (item instanceof Device) count++;
        return count;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof DlnaPin ? TYPE_PIN : TYPE_SERVER;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterNetworkStorageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Object raw = items.get(position);
        if (raw instanceof DlnaPin pin) {
            holder.binding.title.setText(pin.getTitle());
            String subtitle = pin.getServerName();
            if (subtitle.isEmpty()) subtitle = pin.getUuid();
            holder.binding.subtitle.setText(subtitle);
            holder.binding.type.setText(pin.isContainer() ? R.string.dlna_library_folder : R.string.dlna_library_item);
            holder.binding.getRoot().setOnClickListener(v -> listener.onPinClick(pin));
            holder.binding.getRoot().setOnLongClickListener(v -> {
                listener.onPinLongClick(pin);
                return true;
            });
            return;
        }
        Device item = (Device) raw;
        holder.binding.title.setText(item.getName());
        holder.binding.subtitle.setText(item.getUuid());
        holder.binding.type.setText(R.string.dlna_library_type);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(null);
    }

    public interface OnClickListener {

        void onItemClick(Device item);

        void onPinClick(DlnaPin pin);

        void onPinLongClick(DlnaPin pin);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterNetworkStorageBinding binding;

        ViewHolder(@NonNull AdapterNetworkStorageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
