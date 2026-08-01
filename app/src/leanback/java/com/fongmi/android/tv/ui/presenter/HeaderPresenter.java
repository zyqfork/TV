package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.animation.Animation;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterHeaderBinding;
import com.fongmi.android.tv.utils.ResUtil;

public class HeaderPresenter extends Presenter {

    private final OnClickListener listener;
    private final Animation flicker;

    public HeaderPresenter() {
        this(null);
    }

    public HeaderPresenter(OnClickListener listener) {
        this.listener = listener;
        this.flicker = ResUtil.getAnim(R.anim.flicker);
    }

    public interface OnClickListener {

        void onHeaderClick(int resId);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new HeaderPresenter.ViewHolder(AdapterHeaderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        HeaderPresenter.ViewHolder holder = (HeaderPresenter.ViewHolder) viewHolder;
        holder.binding.text.clearAnimation();
        if (object instanceof String) {
            holder.binding.text.setText(object.toString());
            holder.binding.text.setOnClickListener(null);
            holder.binding.text.setClickable(false);
            holder.binding.text.setOnFocusChangeListener(null);
            return;
        }
        int resId = (int) object;
        holder.binding.text.setText(ResUtil.getString(resId));
        if (listener == null) {
            holder.binding.text.setOnClickListener(null);
            holder.binding.text.setClickable(false);
            holder.binding.text.setOnFocusChangeListener(null);
            return;
        }
        holder.binding.text.setClickable(true);
        holder.binding.text.setOnClickListener(v -> listener.onHeaderClick(resId));
        holder.binding.text.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) v.startAnimation(flicker);
            else v.clearAnimation();
        });
        if (holder.binding.text.hasFocus()) holder.binding.text.startAnimation(flicker);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        HeaderPresenter.ViewHolder holder = (HeaderPresenter.ViewHolder) viewHolder;
        holder.binding.text.clearAnimation();
        holder.binding.text.setOnFocusChangeListener(null);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterHeaderBinding binding;

        public ViewHolder(@NonNull AdapterHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
