package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterNetworkStorageBinding;
import com.fongmi.android.tv.storage.NetworkStorage;

import java.util.ArrayList;
import java.util.List;

public class NetworkStorageAdapter extends RecyclerView.Adapter<NetworkStorageAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<NetworkStorage> items = new ArrayList<>();

    public NetworkStorageAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<NetworkStorage> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public NetworkStorage get(int position) {
        return items.get(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterNetworkStorageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NetworkStorage item = items.get(position);
        holder.binding.title.setText(item.displayTitle());
        holder.binding.subtitle.setText(item.displaySubtitle());
        holder.binding.type.setText(item.isSmb() ? R.string.network_storage_type_smb : R.string.network_storage_type_webdav);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(v -> {
            listener.onItemLongClick(item);
            return true;
        });
    }

    public interface OnClickListener {

        void onItemClick(NetworkStorage item);

        void onItemLongClick(NetworkStorage item);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterNetworkStorageBinding binding;

        ViewHolder(@NonNull AdapterNetworkStorageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
