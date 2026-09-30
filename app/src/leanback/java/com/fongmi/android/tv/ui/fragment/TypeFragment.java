package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.VodGridAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Notify;

import java.util.HashMap;
import java.util.List;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {
    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private VodGridAdapter mAdapter;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Filter> mFilters;
    private boolean filterVisible;
    private boolean awaitingFirst;
    private int actionPosition = -1;
    private int actionItemPosition = -1;
    private int folderPosition = -1;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder) {
        Bundle args = new Bundle();
        args.putString("key", key); args.putString("typeId", typeId);
        args.putBoolean("folder", folder); args.putParcelable("style", style); args.putSerializable("extend", extend);
        TypeFragment fragment = new TypeFragment(); fragment.setArguments(args); return fragment;
    }
    private String getKey() { return getArguments().getString("key"); }
    private String getTypeId() { return getArguments().getString("typeId"); }
    private boolean isFolder() { return getArguments().getBoolean("folder"); }
    private Site getSite() { return VodConfig.get().getSite(getKey()); }
    private Style getStyle() { return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style")); }
    private FolderFragment getParent() { return (FolderFragment) getParentFragment(); }

    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }
    @Override @SuppressWarnings("unchecked") protected void initView() {
        mScroller = new CustomScroller(this);
        mExtends = (HashMap<String, String>) getArguments().getSerializable("extend");
        mFilters = Cache.copy(getTypeId());
        for (Filter filter : mFilters) if (mExtends.containsKey(filter.getKey())) filter.setSelected(mExtends.get(filter.getKey()));
        mAdapter = new VodGridAdapter(this);
        mAdapter.attach(mBinding.recycler, this::onCommitted);
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), this::onActionResult);
        getVideo();
    }
    @Override protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.swipeLayout.setOnChildScrollUpCallback((parent, child) ->
                mBinding.recycler.isMouseGesture() || mBinding.recycler.canScrollVertically(-1));
        mBinding.recycler.addOnScrollListener(mScroller);
    }
    private void onActionResult(Result result) {
        if (result == null) return;
        mViewModel.clearAction(); Notify.show(result.getMsg());
        if (!result.shouldRefreshAction()) { actionItemPosition = -1; return; }
        actionPosition = actionItemPosition >= 0 ? actionItemPosition : mBinding.recycler.getFocusedPosition();
        actionItemPosition = -1;
        getVideo();
    }
    private void setClick(String key, Value value) {
        for (Filter filter : mFilters) {
            if (!filter.getKey().equals(key)) continue;
            for (Value option : filter.getValue()) option.setSelected(value);
            if (filter.getValue().stream().anyMatch(Value::isSelected)) mExtends.put(key, value.getV());
            else mExtends.remove(key);
        }
        mAdapter.setFilters(filterVisible ? mFilters : List.of(), this::setClick);
        onRefresh();
    }
    private void getVideo() {
        awaitingFirst = true;
        mScroller.reset(); mScroller.beginLoading();
        boolean empty = mAdapter.getItemCount() == 0;
        mAdapter.clearVideos();
        if (empty) mBinding.progressLayout.showProgress();
        else mBinding.swipeLayout.setRefreshing(true);
        mViewModel.categoryContent(getKey(), getTypeId(), "1", true, mExtends);
    }
    private void setAdapter(Result result) {
        if (result == null) return;
        boolean first = mScroller.first();
        mBinding.progressLayout.showContent(first && mExtends.isEmpty(), result.getList().size());
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        awaitingFirst = false;
        Style style = result.getStyle(getStyle());
        if (first) mAdapter.setVideos(result.getList(), style);
        else mAdapter.addVideos(result.getList(), style);
    }
    private void onCommitted() {
        if (mBinding == null) return;
        if (!awaitingFirst && actionPosition >= 0 && mAdapter.getItemCount() > 0) {
            int target = Math.min(actionPosition, mAdapter.getItemCount() - 1);
            actionPosition = -1;
            mBinding.recycler.scrollToPositionAndFocus(target);
        }
        mBinding.recycler.post(() -> { if (mBinding != null && !awaitingFirst) mScroller.checkMore(mBinding.recycler); });
    }
    public void toggleFilter(boolean visible) {
        if (mFilters.isEmpty() || filterVisible == visible) return;
        filterVisible = visible;
        mAdapter.setFilters(visible ? mFilters : List.of(), this::setClick);
        mBinding.recycler.scrollToTop();
    }
    @Override public void onRefresh() { actionPosition = -1; getVideo(); }
    public boolean moveToTop() {
        if (mBinding == null || (mBinding.recycler.isAtTop()
                && mBinding.recycler.getFocusedPosition() < mAdapter.firstRowEnd())) return false;
        mBinding.recycler.scrollToTop();
        mBinding.recycler.afterNextLayout(() -> {
            View header = requireActivity().findViewById(R.id.recycler);
            if (header != null && header != mBinding.recycler) header.requestFocusFromTouch();
        });
        return true;
    }
    @Override public void onItemClick(Vod item) {
        if (item.isAction()) {
            actionItemPosition = mAdapter.positionOf(item);
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            folderPosition = mAdapter.positionOf(item);
            getParent().openFolder(item.getId(), mExtends);
        } else if (getSite().isIndex()) CollectActivity.start(requireActivity(), item.getName());
        else VideoActivity.start(this.requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
    }
    @Override public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        CollectActivity.start(requireActivity(), item.getName()); return true;
    }
    @Override public boolean onLoadMore(String page) {
        mViewModel.categoryContent(getKey(), getTypeId(), page, true, mExtends); return true;
    }
    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden || mBinding == null) return;
        mBinding.recycler.afterNextLayout(() -> {
            if (folderPosition >= 0 && mBinding.recycler.focusPosition(folderPosition)) return;
            mBinding.recycler.focusVisibleItem();
        });
    }
    @Override public void setUserVisibleHint(boolean visible) {
        super.setUserVisibleHint(visible);
        if (!visible && mBinding != null) mBinding.recycler.scrollToTop();
    }
    @Override public void onDestroyView() { super.onDestroyView(); mBinding = null; }
}
