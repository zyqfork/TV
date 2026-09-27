package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.ui.custom.CustomWallView;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import me.jessyan.autosize.AutoSizeCompat;

public abstract class BaseActivity extends AppCompatActivity {

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        Util.hideSystemUI(this);
        setBackCallback();
        initEvent();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (!customWall()) return;
        ((ViewGroup) findViewById(android.R.id.content)).addView(new CustomWallView(this, null), 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected boolean customWall() {
        return true;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyDataSetChanged());
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    // MUST match <meta-data android:name="design_width_in_dp" android:value="960"/> in
    // app/src/leanback/AndroidManifest.xml, which is the value AutoSizeCompat converts to.
    // If they diverge the drift check below is always true: AutoSize keeps being re-applied and
    // this optimization silently becomes a no-op (slow, not broken). Change both together.
    private static final float DESIGN_WIDTH_DP = 960f;

    private Resources hackResources(Resources resources) {
        // Re-apply AutoSize only when density drifts (system reset / early call).
        // Doing it on every getResources() floods the main thread (high input latency);
        // doing it once leaves the UI at system density (icons look shrunken).
        try {
            DisplayMetrics dm = resources.getDisplayMetrics();
            float expected = dm.widthPixels / DESIGN_WIDTH_DP;
            if (expected > 0 && Math.abs(dm.density - expected) >= 0.05f) {
                AutoSizeCompat.autoConvertDensityOfGlobal(resources);
            }
        } catch (Exception ignored) {
        }
        return resources;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    @Override
    public Resources getResources() {
        return hackResources(super.getResources());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // hideSystemUI is already applied in onCreate; repeating it on every focus
        // bounce (dialogs, IME, multi-window) adds main-thread work right after clicks.
        if (hasFocus && getWindow() != null && (getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) == 0) {
            Util.hideSystemUI(this);
        }
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
