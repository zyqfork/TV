package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.databinding.AdapterHomeFuncBinding;

import java.util.ArrayList;
import java.util.List;

/** The phone home screen's row of app-level destinations (see {@code HomeFuncs}). */
public class HomeFuncAdapter extends ListAdapter<Func, HomeFuncAdapter.ViewHolder> {

    private final OnClickListener listener;

    public HomeFuncAdapter(OnClickListener listener) {
        super(new DiffUtil.ItemCallback<>() {
            @Override public boolean areItemsTheSame(@NonNull Func a, @NonNull Func b) { return a.isSameItem(b); }
            @Override public boolean areContentsTheSame(@NonNull Func a, @NonNull Func b) { return a.isSameContent(b); }
        });
        this.listener = listener;
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    public void setItems(List<Func> list) {
        submitList(list == null ? List.of() : new ArrayList<>(list));
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterHomeFuncBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Func item = getItem(position);
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
