package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DlnaEntry;
import com.fongmi.android.tv.databinding.AdapterFileBinding;

import java.util.ArrayList;
import java.util.List;

public class DlnaEntryAdapter extends RecyclerView.Adapter<DlnaEntryAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<DlnaEntry> items = new ArrayList<>();

    public DlnaEntryAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<DlnaEntry> list) {
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
        DlnaEntry item = items.get(position);
        holder.binding.name.setText(item.getTitle());
        holder.binding.image.setImageResource(item.isContainer() ? R.drawable.ic_folder : R.drawable.ic_file);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(v -> {
            listener.onItemLongClick(item);
            return true;
        });
    }

    public interface OnClickListener {

        void onItemClick(DlnaEntry item);

        void onItemLongClick(DlnaEntry item);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFileBinding binding;

        ViewHolder(@NonNull AdapterFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
