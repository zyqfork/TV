package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.databinding.AdapterHomeFuncBinding;

import java.util.ArrayList;
import java.util.List;

/** The phone home screen's row of app-level destinations (see {@code HomeFuncs}). */
public class HomeFuncAdapter extends RecyclerView.Adapter<HomeFuncAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Func> items = new ArrayList<>();

    public HomeFuncAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<Func> list) {
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
        return new ViewHolder(AdapterHomeFuncBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Func item = items.get(position);
        holder.binding.icon.setImageResource(item.getDrawable());
        holder.binding.text.setText(item.getText());
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    public interface OnClickListener {

        void onItemClick(Func item);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterHomeFuncBinding binding;

        ViewHolder(@NonNull AdapterHomeFuncBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
