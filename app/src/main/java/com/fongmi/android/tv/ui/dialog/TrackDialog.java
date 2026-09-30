package com.fongmi.android.tv.ui.dialog;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Tracks;
import androidx.media3.ui.DefaultTrackNameProvider;
import androidx.media3.ui.TrackNameProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.databinding.DialogTrackBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.ui.adapter.TrackAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TrackDialog extends BaseBottomSheetDialog implements TrackAdapter.OnClickListener {

    private final TrackNameProvider provider;
    private final TrackAdapter adapter;
    private DialogTrackBinding binding;
    private PlayerManager player;
    private int type;

    public static TrackDialog create() {
        return new TrackDialog();
    }

    public TrackDialog() {
        this.adapter = new TrackAdapter(this);
        this.provider = new DefaultTrackNameProvider(App.get().getResources());
    }

    public TrackDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public TrackDialog type(int type) {
        this.type = type;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof TrackDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    private boolean hasChoose() {
        return type == C.TRACK_TYPE_TEXT && player.isVod();
    }

    private boolean hasSearch() {
        // Search is useful for both live and VOD text tracks.
        return type == C.TRACK_TYPE_TEXT && player != null;
    }

    private boolean hasText() {
        return type == C.TRACK_TYPE_TEXT && player.haveTrack(type);
    }

    private boolean hasSecondarySubtitle() {
        return type == C.TRACK_TYPE_TEXT && player != null && player.getCapabilities().secondarySubtitle();
    }

    private boolean hasAudio() {
        return type == C.TRACK_TYPE_AUDIO && player.haveTrack(type);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogTrackBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setAdapter(adapter.addAll(getTrack()));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.title.setText(ResUtil.getStringArray(R.array.select_track)[type - 1]);
        binding.recycler.post(() -> binding.recycler.scrollToSelection(adapter.getSelected()));
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
        boolean bitmapSubtitle = type == C.TRACK_TYPE_TEXT && TrackUtil.hasSelectedBitmapSubtitle(player.getCurrentTracks());
        binding.offset.setVisibility((hasText() && !bitmapSubtitle) || hasAudio() ? View.VISIBLE : View.GONE);
        binding.choose.setVisibility(hasChoose() ? View.VISIBLE : View.GONE);
        binding.search.setVisibility(hasSearch() ? View.VISIBLE : View.GONE);
        binding.secondary.setVisibility(hasSecondarySubtitle() ? View.VISIBLE : View.GONE);
        binding.effect.setVisibility(type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO ? View.VISIBLE : View.GONE);
        binding.effect.setContentDescription(getString(type == C.TRACK_TYPE_VIDEO ? R.string.player_video_effect : R.string.player_audio_effect));
        binding.secondary.setSelected(player != null && player.hasSecondarySubtitle());
        if (player != null && player.getSecondarySub() != null) {
            binding.secondary.setContentDescription(getString(R.string.subtitle_secondary_active,
                    player.getSecondarySub().getName(), player.getSecondarySubtitleOffsetMs() / 1000.0));
        } else if (player != null && player.hasSecondarySubtitle()) {
            binding.secondary.setContentDescription(getString(R.string.subtitle_secondary_embedded));
        }
        binding.subtitle.setVisibility(hasText() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void initEvent() {
        binding.offset.setOnClickListener(this::onOffset);
        binding.choose.setOnClickListener(this::onChoose);
        binding.search.setOnClickListener(this::onSearch);
        binding.secondary.setOnClickListener(this::onSecondarySubtitle);
        binding.secondary.setOnLongClickListener(this::onClearSecondarySubtitle);
        binding.effect.setOnClickListener(this::onEffectSetting);
        binding.subtitle.setOnClickListener(this::onSubtitle);
        binding.title.setOnLongClickListener(this::onEffectSetting);
    }

    private boolean onEffectSetting(View view) {
        if (type == C.TRACK_TYPE_AUDIO) {
            int error = player.getAudioSettingError();
            if (player.canSetAudioSetting() || error == R.string.error_audio_effect_passthrough) EffectSettingDialog.showAudio(requireActivity(), player);
            else Notify.show(error != 0 ? error : R.string.error_audio_effect_unsupported);
        } else if (type == C.TRACK_TYPE_VIDEO) {
            if (!player.canSetVideoSetting()) Notify.show(player.getVideoSettingError() != 0 ? player.getVideoSettingError() : R.string.error_video_effect_unsupported);
            else EffectSettingDialog.showVideo(requireActivity(), player);
        } else return false;
        return true;
    }

    private void onOffset(View view) {
        OffsetDialog.create().player(player).type(type).show(requireActivity());
        dismiss();
    }

    private void onChoose(View view) {
        FileChooser.from(launcher).show(new String[]{MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML, "audio/*", "text/*", "application/octet-stream"});
        player.pause();
    }

    private void onSearch(View view) {
        FragmentActivity activity = requireActivity();
        dismissNow();
        SubtitleSearchDialog.create().player(player).show(activity);
    }

    private void onSecondarySubtitle(View view) {
        List<Integer> actionIds = new ArrayList<>();
        actionIds.add(R.string.subtitle_secondary_search);
        actionIds.add(R.string.subtitle_secondary_file);
        if (!player.getEmbeddedSecondarySubtitleOptions().isEmpty()) actionIds.add(R.string.subtitle_secondary_embedded);
        actionIds.add(R.string.subtitle_secondary_offset);
        actionIds.add(R.string.subtitle_secondary_clear);
        String[] actions = actionIds.stream().map(id -> getString(id)).toArray(String[]::new);
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.subtitle_secondary)
                .setItems(actions, (dialog, which) -> handleSecondaryAction(actionIds.get(which)))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void handleSecondaryAction(int action) {
        if (action == R.string.subtitle_secondary_search) openSecondarySearch();
        else if (action == R.string.subtitle_secondary_file) chooseSecondaryFile();
        else if (action == R.string.subtitle_secondary_embedded) chooseEmbeddedSecondary();
        else if (action == R.string.subtitle_secondary_offset) SecondarySubtitleOffsetDialog.show(requireActivity(), player);
        else if (action == R.string.subtitle_secondary_clear) {
            player.clearSecondarySub();
            player.setEmbeddedSecondarySubtitle(null);
        }
    }

    private void chooseEmbeddedSecondary() {
        List<PlayerManager.SecondaryTrackOption> options = player.getEmbeddedSecondarySubtitleOptions();
        String[] labels = new String[options.size() + 1];
        labels[0] = getString(R.string.none);
        int selected = 0;
        for (int i = 0; i < options.size(); i++) {
            labels[i + 1] = provider.getTrackName(options.get(i).format());
            if (options.get(i).selected()) selected = i + 1;
        }
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.subtitle_secondary_embedded)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    player.setEmbeddedSecondarySubtitle(which == 0 ? null : options.get(which - 1).selection());
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void openSecondarySearch() {
        FragmentActivity activity = requireActivity();
        dismissNow();
        SubtitleSearchDialog.create().player(player).secondary(true).show(activity);
    }

    private void chooseSecondaryFile() {
        FileChooser.from(secondaryLauncher).show(new String[]{MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML, "text/*", "application/octet-stream"});
        player.pause();
    }

    private boolean onClearSecondarySubtitle(View view) {
        player.clearSecondarySub();
        player.setEmbeddedSecondarySubtitle(null);
        dismiss();
        return true;
    }

    private void onSubtitle(View view) {
        // VOD + text: open ASSRT search. Live text: also allow search.
        if (type == C.TRACK_TYPE_TEXT && player != null) {
            onSearch(view);
            return;
        }
        if (!(requireActivity() instanceof Listener listener)) {
            dismiss();
            return;
        }
        App.post(listener::onSubtitleClick, 100);
        dismiss();
    }

    private List<Track> getTrack() {
        List<Track> items = new ArrayList<>();
        addTrack(items);
        return items;
    }

    private void addTrack(List<Track> items) {
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        for (int i = 0; i < groups.size(); i++) {
            Tracks.Group trackGroup = groups.get(i);
            if (trackGroup.getType() != type) continue;
            for (int j = 0; j < trackGroup.length; j++) {
                Format format = trackGroup.getTrackFormat(j);
                String name = provider.getTrackName(format);
                Track item = new Track(type, name, PlayerHelper.describeFormat(format));
                item.setSelected(trackGroup.isTrackSelected(j));
                items.add(item);
            }
        }
    }

    @Override
    public void onItemClick(Track item) {
        player.setTrack(Arrays.asList(item.key(player.getKey()).save()));
        dismiss();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (player != null) player.play();
        FileChooser.getUri(result, this::setSubtitle);
    });
    private final ActivityResultLauncher<Intent> secondaryLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (player != null) player.play();
        FileChooser.getUri(result, this::setSecondarySubtitle);
    });

    private void setSubtitle(Uri uri) {
        if (!isAdded()) return;
        persistReadPermission(uri);
        player.setSub(Sub.from(FileUtil.getDisplayName(uri), uri.toString()));
        dismiss();
    }

    private void setSecondarySubtitle(Uri uri) {
        if (!isAdded()) return;
        persistReadPermission(uri);
        player.setSecondarySub(Sub.from(FileUtil.getDisplayName(uri), uri.toString()));
        player.play();
        dismiss();
    }

    private void persistReadPermission(Uri uri) {
        if (!"content".equalsIgnoreCase(uri.getScheme())) return;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers grant only a transient permission; playback can still proceed.
        }
    }

    public interface Listener {

        void onSubtitleClick();
    }
}
