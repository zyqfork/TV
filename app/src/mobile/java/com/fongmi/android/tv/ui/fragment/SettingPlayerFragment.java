package com.fongmi.android.tv.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingPlayerBinding;
import com.fongmi.android.tv.impl.SpeedListener;
import com.fongmi.android.tv.impl.SubtitleListener;
import com.fongmi.android.tv.player.mpv.MpvUtil;
import com.fongmi.android.tv.player.subtitle.ExternalFont;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.EffectSettingDialog;
import com.fongmi.android.tv.ui.dialog.ExternalFontDialog;
import com.fongmi.android.tv.ui.dialog.MpvConfDialog;
import com.fongmi.android.tv.ui.dialog.SpeedDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleApiDialog;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.DecimalFormat;

public class SettingPlayerFragment extends BaseFragment implements SpeedListener, SubtitleListener, ExternalFontDialog.Listener {

    private FragmentSettingPlayerBinding mBinding;
    private DecimalFormat format;
    private String[] background;
    private String[] caption;
    private String[] render;
    private String[] scale;
    private String[] engine;
    private String[] http;
    private String[] latency;

    private final ActivityResultLauncher<Intent> fontLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> FileChooser.getUri(result, this::importFont));

    public static SettingPlayerFragment newInstance() {
        return new SettingPlayerFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPlayerBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setVisible();
        setPlaybackModeText();
        format = new DecimalFormat("0.#");
        mBinding.speedText.setText(format.format(PlayerSetting.getSpeed()));
        mBinding.adblockText.setText(Setting.getSwitch(Setting.isAdblock()));
        mBinding.mpvVulkanText.setText(Setting.getSwitch(PlayerSetting.isMpvVulkan()));
        mBinding.mpvGpuNextText.setText(Setting.getSwitch(PlayerSetting.isMpvGpuNext()));
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);
        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSetting.isCaption() ? 1 : 0]);
        mBinding.backgroundText.setText((background = ResUtil.getStringArray(R.array.select_background))[PlayerSetting.getBackground()]);
        mBinding.bufferText.setText(getString(R.string.player_buffer_value, PlayerSetting.getBuffer()));
        mBinding.httpText.setText((http = ResUtil.getStringArray(R.array.select_exo_http))[PlayerSetting.getHttp()]);
        mBinding.liveLatencyText.setText((latency = ResUtil.getStringArray(R.array.select_live_latency))[PlayerSetting.getLiveLatency()]);
        bindSubtitleLabels();
    }

    private void bindSubtitleLabels() {
        String token = SubtitleSetting.getSearchToken();
        if (TextUtils.isEmpty(token)) token = SubtitleSetting.getEffectiveToken();
        mBinding.subtitleAssrtText.setText(TextUtils.isEmpty(token) ? ResUtil.getString(R.string.subtitle_font_none) : maskToken(token));
        String font = SubtitleSetting.getFontPath();
        if (TextUtils.isEmpty(font)) {
            mBinding.subtitleFontText.setText(ResUtil.getString(R.string.subtitle_font_default));
        } else {
            ExternalFont.Entry entry = SubtitleSetting.getFontEntry();
            mBinding.subtitleFontText.setText(entry == null ? new File(font).getName() : entry.name());
        }
    }

    private static String maskToken(String token) {
        if (token.length() <= 8) return token;
        return token.substring(0, 4) + "…" + token.substring(token.length() - 4);
    }

    @Override
    protected void initEvent() {
        mBinding.engine.setOnClickListener(this::setEngine);
        mBinding.mpvConf.setOnClickListener(this::onMpvConf);
        mBinding.mpvGpuNext.setOnClickListener(this::setMpvGpuNext);
        mBinding.mpvVulkan.setOnClickListener(this::setMpvVulkan);
        mBinding.render.setOnClickListener(this::setRender);
        mBinding.scale.setOnClickListener(this::onScale);
        mBinding.caption.setOnClickListener(this::setCaption);
        mBinding.caption.setOnLongClickListener(this::onCaption);
        mBinding.speed.setOnClickListener(this::onSpeed);
        mBinding.audioEffect.setOnClickListener(view -> EffectSettingDialog.showAudio(requireActivity()));
        mBinding.videoEffect.setOnClickListener(view -> EffectSettingDialog.showVideo(requireActivity()));
        mBinding.background.setOnClickListener(this::onBackground);
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.buffer.setOnClickListener(this::setBuffer);
        mBinding.http.setOnClickListener(this::setHttp);
        mBinding.liveLatency.setOnClickListener(this::setLiveLatency);
        mBinding.preload.setOnClickListener(this::onPreload);
        mBinding.decode.setOnClickListener(this::onDecode);
        mBinding.subtitleAssrt.setOnClickListener(this::onSubtitleAssrt);
        mBinding.subtitleFont.setOnClickListener(this::onSubtitleFont);
        mBinding.speed.setOnLongClickListener(this::onVolumeGain);
    }

    private boolean onVolumeGain(View view) {
        float next = PlayerSetting.getVolumeGain() >= 1.5f ? 1f : 1.5f;
        PlayerSetting.putVolumeGain(next);
        mBinding.speedText.setText(format.format(PlayerSetting.getSpeed()));
        com.fongmi.android.tv.utils.Notify.show(next == 1f ? R.string.player_volume_gain_off : R.string.player_volume_gain_on);
        return true;
    }

    private void setVisible() {
        boolean mpv = PlayerSetting.isMpv();
        boolean exo = !mpv;
        boolean vulkan = mpv && MpvUtil.isVulkanSupported();
        mBinding.mpvConf.setVisibility(mpv ? View.VISIBLE : View.GONE);
        mBinding.mpvVulkan.setVisibility(vulkan ? View.VISIBLE : View.GONE);
        mBinding.mpvGpuNext.setVisibility(mpv ? View.VISIBLE : View.GONE);
        mBinding.decode.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.adblock.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.buffer.setVisibility(View.VISIBLE);
        mBinding.http.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.caption.setVisibility(PlayerSetting.hasCaption() ? View.VISIBLE : View.GONE);
    }

    private void setBuffer(View view) {
        int next = PlayerSetting.getBuffer() >= 15 ? 1 : PlayerSetting.getBuffer() + 1;
        PlayerSetting.putBuffer(next);
        mBinding.bufferText.setText(getString(R.string.player_buffer_value, next));
    }

    private void setHttp(View view) {
        int index = (PlayerSetting.getHttp() + 1) % http.length;
        PlayerSetting.putHttp(index);
        mBinding.httpText.setText(http[index]);
    }

    private void setLiveLatency(View view) {
        int index = (PlayerSetting.getLiveLatency() + 1) % latency.length;
        PlayerSetting.putLiveLatency(index);
        mBinding.liveLatencyText.setText(latency[index]);
    }

    private void setEngine(View view) {
        int index = (PlayerSetting.getEngine() + 1) % engine.length;
        PlayerSetting.putEngine(index);
        setPlaybackModeText();
        setVisible();
    }

    private void onSubtitleAssrt(View view) {
        SubtitleApiDialog.show(this);
    }

    private void onSubtitleFont(View view) {
        ExternalFontDialog.show(this);
    }

    private void importFont(Uri uri) {
        if (uri == null || getContext() == null) return;
        Task.execute(() -> {
            String path = ExternalFont.importFrom(App.get(), uri);
            if (!TextUtils.isEmpty(path)) SubtitleSetting.putFontPath(path);
            App.post(() -> {
                if (isAdded()) bindSubtitleLabels();
            });
        });
    }

    @Override
    public void setSubtitleToken(String token) {
        SubtitleSetting.putSearchToken(token);
        bindSubtitleLabels();
    }

    @Override
    public void onFontSelected(@Nullable ExternalFont.Entry entry) {
        SubtitleSetting.putFontSelection(entry);
        bindSubtitleLabels();
    }

    @Override
    public void onFontImportRequested() {
        FileChooser.from(fontLauncher).show(new String[]{"font/*", "application/octet-stream", "*/*"});
    }

    private void onMpvConf(View view) {
        MpvConfDialog.show(this);
    }

    private void setMpvGpuNext(View view) {
        PlayerSetting.putMpvGpuNext(!PlayerSetting.isMpvGpuNext());
        mBinding.mpvGpuNextText.setText(Setting.getSwitch(PlayerSetting.isMpvGpuNext()));
    }

    private void setMpvVulkan(View view) {
        PlayerSetting.putMpvVulkan(!PlayerSetting.isMpvVulkan());
        mBinding.mpvVulkanText.setText(Setting.getSwitch(PlayerSetting.isMpvVulkan()));
    }

    private void setRender(View view) {
        int index = (PlayerSetting.getRender() + 1) % render.length;
        if (!PlayerSetting.putRender(index)) {
            com.fongmi.android.tv.utils.Notify.show(R.string.error_tunnel_requires_surface);
            return;
        }
        setPlaybackModeText();
    }

    private void setPlaybackModeText() {
        engine = ResUtil.getStringArray(R.array.select_engine);
        render = ResUtil.getStringArray(R.array.select_render);
        mBinding.engineText.setText(engine[PlayerSetting.getEngine()]);
        mBinding.renderText.setText(render[PlayerSetting.getRender()]);
    }

    private void onScale(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_scale).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(scale, PlayerSetting.getScale(), (dialog, which) -> {
            mBinding.scaleText.setText(scale[which]);
            PlayerSetting.putScale(which);
            dialog.dismiss();
        }).show();
    }

    private void setCaption(View view) {
        PlayerSetting.putCaption(!PlayerSetting.isCaption());
        mBinding.captionText.setText(caption[PlayerSetting.isCaption() ? 1 : 0]);
    }

    private boolean onCaption(View view) {
        if (PlayerSetting.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        return PlayerSetting.isCaption();
    }

    private void onSpeed(View view) {
        SpeedDialog.show(this);
    }

    @Override
    public void setSpeed(float speed) {
        mBinding.speedText.setText(format.format(speed));
        PlayerSetting.putSpeed(speed);
    }

    private void onBackground(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_background).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(background, PlayerSetting.getBackground(), (dialog, which) -> {
            mBinding.backgroundText.setText(background[which]);
            PlayerSetting.putBackground(which);
            dialog.dismiss();
        }).show();
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(Setting.getSwitch(Setting.isAdblock()));
    }

    private void onPreload(View view) {
        ((HomeActivity) requireActivity()).change(4);
    }

    private void onDecode(View view) {
        ((HomeActivity) requireActivity()).change(5);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}
