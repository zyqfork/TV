package com.fongmi.android.tv.ui.activity;

import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.core.splashscreen.SplashScreen;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.databinding.ViewHomeToolbarBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.dlna.CastNetworkWatcher;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.AirPlayServer;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.setting.DlnaSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.storage.NetworkStorage;
import com.fongmi.android.tv.storage.NetworkStorageStore;
import com.fongmi.android.tv.ui.adapter.HomeAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.home.HomeFuncs;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener, HeaderPresenter.OnClickListener {

    private ActivityHomeBinding mBinding;
    private ViewHomeToolbarBinding mToolbar;
    private HomeAdapter mAdapter;
    private List<History> mHistory = new ArrayList<>();
    private ViewTreeObserver.OnGlobalFocusChangeListener mFocusListener;
    private int actionPosition = -1;
    private int actionItemPosition = -1;
    private boolean initialFocus = true;
    private HistoryPresenter mPresenter;
    private SiteViewModel mViewModel;
    private Result mResult;
    private Clock mClock;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mResult = Result.empty();
        mToolbar = ViewHomeToolbarBinding.inflate(getLayoutInflater());
        mToolbar.title.setFlickerEnabled(false);
        mClock = Clock.create(mToolbar.clock);
        mBinding.progressLayout.showProgress();
        PermissionUtil.requestNotify(this);
        DlnaSetting.ensureDefaultInterface();
        AirPlaySetting.ensureDefaultInterface();
        DLNARendererService.start(this);
        AirPlayServer.start(this);
        CastNetworkWatcher.register(this);
        Updater.create().start(this);
        setRecyclerView();
        setViewModel();
        setAdapter();
        initConfig();
        setTitle();
        setLogo();
    }

    @Override
    protected void initEvent() {
        mToolbar.title.setListener(this);
        mFocusListener = (oldFocus, newFocus) -> {
            if (newFocus != null && mBinding.recycler.hasFocus() && mPresenter.isDelete()
                    && !mAdapter.isHistoryPosition(mBinding.recycler.getFocusedPosition())) setHistoryDelete(false);
        };
        mBinding.recycler.getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusListener);
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            FileChooser.getUri(intent, uri -> loadLive(UrlUtil.toLocalUrl(uri)));
        } else {
            FileChooser.getUri(intent, uri -> VideoActivity.file(this, uri));
        }
    }

    private void setRecyclerView() {
        mPresenter = new HistoryPresenter(this);
        mAdapter = new HomeAdapter(mToolbar.getRoot(), this, this, this);
        GridLayoutManager layout = new GridLayoutManager(this, mAdapter.getColumns()) {
            @Override public boolean onRequestChildFocus(RecyclerView parent, RecyclerView.State state, View child, View focused) {
                return mBinding.recycler.isRemoteViewport() || super.onRequestChildFocus(parent, state, child, focused);
            }
        };
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return mAdapter.isFullSpan(position) ? layout.getSpanCount() : 1;
            }
        });
        mBinding.recycler.setRemoteScrollEnabled(true);
        mBinding.recycler.setLayoutManager(layout);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter);
        mBinding.recycler.addItemDecoration(mAdapter.spacing());
        mAdapter.setOnCommitted(() -> {
            if (layout.getSpanCount() != mAdapter.getColumns()) layout.setSpanCount(mAdapter.getColumns());
            restoreActionPosition();
        });
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            addVideo(mResult = result);
            Cache.clear().put(result);
        });
        mViewModel.getAction().observe(this, this::onActionResult);
    }

    private void onActionResult(Result result) {
        if (result == null) return;
        mViewModel.clearAction();
        Notify.show(result.getMsg());
        if (!result.shouldRefreshAction()) {
            actionItemPosition = -1;
            return;
        }
        actionPosition = actionItemPosition >= 0 ? actionItemPosition : mBinding.recycler.getFocusedPosition();
        actionItemPosition = -1;
        getVideo();
    }

    private void restoreActionPosition() {
        if (actionPosition < 0 || mAdapter.isLoading()) return;
        int position = Math.min(actionPosition, Math.max(0, mAdapter.getItemCount() - 1));
        mBinding.recycler.scrollToPosition(position);
        mBinding.recycler.afterNextLayout(() -> mBinding.recycler.focusPosition(position));
        actionPosition = -1;
    }

    private void setAdapter() {
        setFunc();
        getHistory();
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mToolbar.title.setText(s));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        };
    }

    private void showContent() {
        mBinding.progressLayout.showContent();
        checkAction(getIntent());
        setFocus();
    }

    private void loadLive(String url) {
        if (isFinishing() || isDestroyed()) return;
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setFocus() {
        mToolbar.title.setSelected(true);
        mBinding.recycler.afterNextLayout(() -> {
            if (initialFocus || !mBinding.recycler.hasFocus()) {
                focusFunctions();
                initialFocus = false;
            }
        });
    }

    private void focusFunctions() {
        if (!mBinding.recycler.focusPosition(mAdapter.getFunctionsPosition())) mBinding.recycler.focusVisibleItem();
    }

    private void getVideo() {
        mResult = Result.empty();
        mAdapter.setLoading();
        mViewModel.homeContent();
    }

    private void addVideo(Result result) {
        mAdapter.setRecommendations(result.getList(), result.getStyle(getHome().getStyle()));
    }

    private void setFunc() {
        // Shared with the phone flavour so the two entry rows cannot drift apart.
        mAdapter.setFunctions(HomeFuncs.create());
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        if (renew) mPresenter = new HistoryPresenter(this);
        mHistory = new ArrayList<>(History.get());
        mAdapter.setHistory(mHistory, mPresenter);
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mAdapter.setHistory(mHistory, mPresenter);
    }

    private void clearHistory() {
        History.clear(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistory.clear();
        mAdapter.setHistory(mHistory, mPresenter);
    }

    private void setLogo() {
        ImgUtil.logo(mToolbar.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                getVideo();
                setTitle();
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history());
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onHeaderClick(int resId) {
        if (mPresenter.isDelete()) setHistoryDelete(false);
        if (resId == R.string.home_history) HistoryActivity.start(this);
        else if (resId == R.string.home_recommend) RecommendActivity.start(this);
    }

    @Override
    public void onItemClick(Func item) {
        if (mPresenter.isDelete()) setHistoryDelete(false);
        if (item.getResId() == R.string.home_vod) VodActivity.start(this, mResult);
        else if (item.getResId() == R.string.home_live) LiveActivity.start(this);
        else if (item.getResId() == R.string.home_keep) KeepActivity.start(this);
        else if (item.getResId() == R.string.home_push) PushActivity.start(this);
        else if (item.getResId() == R.string.home_search) SearchActivity.start(this);
        else if (item.getResId() == R.string.home_network_storage) NetworkBrowseActivity.start(this, item.getId());
        else if (item.getResId() == R.string.home_media_library) DlnaServerActivity.start(this);
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);
    }

    @Override
    public void onItemClick(Vod item) {
        if (mPresenter.isDelete()) setHistoryDelete(false);
        if (item.isAction()) {
            // Mouse clicks need not move keyboard focus. Restore the actual action card.
            actionItemPosition = mAdapter.positionOf(item);
            mViewModel.action(getHome().getKey(), item.getAction());
        } else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else VideoActivity.start(this, getHome().getKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (mPresenter.isDelete()) setHistoryDelete(false);
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        mHistory.remove(item.delete());
        if (mHistory.isEmpty()) mPresenter.setDelete(false);
        mAdapter.setHistory(mHistory, mPresenter);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void showDialog() {
        SiteDialog.create().show(this);
    }

    @Override
    public void onRefresh() {
        getVideo();
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isMenuKey(event)) showDialog();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (mBinding.progressLayout.isProgress()) {
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (!mBinding.recycler.isAtTop() || mBinding.recycler.getFocusedPosition() > mAdapter.getFunctionsPosition()) {
            mBinding.recycler.scrollToTop();
            mBinding.recycler.afterNextLayout(this::focusFunctions);
        } else {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mFocusListener != null && mBinding.recycler.getViewTreeObserver().isAlive()) {
            mBinding.recycler.getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusListener);
        }
        CastNetworkWatcher.unregisterIfUnused(this);
        // Keep DLNARendererService running so the TV stays discoverable after the user
        // leaves HomeActivity. Stopping it here meant: cast once → exit/finish home →
        // second scan finds nothing. Settings toggle still stops the service via apply().
        if (!DlnaSetting.isEnabled()) DLNARendererService.stop(this);
        LiveConfig.get().clear();
        VodConfig.get().clear();
        BackupManager.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
