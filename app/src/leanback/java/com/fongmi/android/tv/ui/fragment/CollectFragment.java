package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.adapter.VodGridAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.presenter.VodPresenter;

import java.util.List;

public class CollectFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener {
    private FragmentTypeBinding mBinding;
    private VodGridAdapter mAdapter;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private Collect mCollect;

    public static CollectFragment newInstance(String keyword, Collect collect) {
        Bundle args = new Bundle(); args.putString("keyword", keyword);
        CollectFragment fragment = new CollectFragment(); fragment.mCollect = collect; fragment.setArguments(args); return fragment;
    }
    private String getKeyword() { return getArguments().getString("keyword"); }
    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }
    @Override protected void initView() {
        mScroller = new CustomScroller(this);
        mAdapter = new VodGridAdapter(this);
        mAdapter.attach(mBinding.recycler, () -> {
            if (mBinding != null) mBinding.recycler.post(() -> { if (mBinding != null) mScroller.checkMore(mBinding.recycler); });
        });
        mBinding.recycler.addOnScrollListener(mScroller);
        // Aggregate searches are updated by their search observer, not pull-to-refresh.
        mBinding.swipeLayout.setEnabled(false);
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), result -> {
            if (result == null) return;
            mScroller.endLoading(result); addVideo(result.getList());
        });
        if (mCollect != null) addVideo(mCollect.getList());
    }
    public void addVideo(List<Vod> items) {
        if (mBinding == null || getActivity() == null || getActivity().isFinishing()) return;
        mAdapter.addVideos(items, Style.rect());
    }
    public boolean moveToTop() {
        if (mBinding == null || (mBinding.recycler.isAtTop() && mBinding.recycler.getFocusedPosition() < mAdapter.firstRowEnd())) return false;
        mBinding.recycler.scrollToTop();
        mBinding.recycler.afterNextLayout(() -> {
            View header = requireActivity().findViewById(R.id.recycler);
            if (header != null && header != mBinding.recycler) header.requestFocusFromTouch();
        });
        return true;
    }
    @Override public void onItemClick(Vod item) {
        requireActivity().setResult(Activity.RESULT_OK);
        if (item.isFolder()) VodActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else VideoActivity.collect(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), item.getPic());
    }
    @Override public boolean onLongClick(Vod item) { return false; }
    @Override public boolean onLoadMore(String page) {
        if (mCollect == null || "all".equals(mCollect.getSite().getKey())) return false;
        mViewModel.searchContent(mCollect.getSite(), getKeyword(), false, page); return true;
    }
    @Override public void setUserVisibleHint(boolean visible) {
        super.setUserVisibleHint(visible);
        if (!visible && mBinding != null) mBinding.recycler.scrollToTop();
    }
    @Override public void onDestroyView() { super.onDestroyView(); mBinding = null; }
}
