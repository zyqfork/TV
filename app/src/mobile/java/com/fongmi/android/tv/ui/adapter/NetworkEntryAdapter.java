package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterFileBinding;
import com.fongmi.android.tv.storage.NetworkEntry;

import java.util.ArrayList;
import java.util.List;

public class NetworkEntryAdapter extends RecyclerView.Adapter<NetworkEntryAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<NetworkEntry> items = new ArrayList<>();

    public NetworkEntryAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<NetworkEntry> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NetworkEntry item = items.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.image.setImageResource(item.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    public interface OnClickListener {

        void onItemClick(NetworkEntry item);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFileBinding binding;

        ViewHolder(@NonNull AdapterFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
