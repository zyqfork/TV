package com.fongmi.android.tv.player.subtitle;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Request;
import okhttp3.Response;

/** Renders an independently parsed external subtitle above the player's primary subtitle. */
public final class SecondarySubtitleOverlay {

    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final long UPDATE_INTERVAL_MS = 100L;

    private final AtomicInteger generation = new AtomicInteger();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlayerView playerView;
    private final SubtitleView subtitleView;
    private final Runnable updater = this::update;

    private SecondarySubtitleTimeline timeline;
    // Set on the main thread by bind()/release(), read on a Task thread by load() for the request
    // headers, so it needs to be volatile.
    private volatile PlayerManager player;
    private String loadedUrl = "";
    private boolean active;
    private boolean hadCues;
    private boolean released;

    public SecondarySubtitleOverlay(PlayerView playerView) {
        this.playerView = playerView;
        this.subtitleView = new SubtitleView(playerView.getContext());
        subtitleView.setApplyEmbeddedStyles(true);
        subtitleView.setApplyEmbeddedFontSizes(false);
        subtitleView.setBottomPaddingFraction(0.20f);
        subtitleView.setVisibility(SubtitleView.GONE);
        FrameLayout overlay = playerView.getOverlayFrameLayout();
        if (overlay != null) overlay.addView(subtitleView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyStyle();
    }

    public void bind(@Nullable PlayerManager player) {
        this.player = player;
        setSubtitle(player == null ? null : player.getSecondarySub());
    }

    public void applyStyle() {
        SubtitleSetting.applyStyle(subtitleView);
    }

    public void setActive(boolean active) {
        if (released || this.active == active) return;
        this.active = active;
        handler.removeCallbacks(updater);
        if (active) update();
        else show(Collections.emptyList());
    }

    public void setSubtitle(@Nullable Sub sub) {
        if (released) return;
        String url = sub == null ? "" : sub.getUrl();
        if (TextUtils.equals(loadedUrl, url)) return;
        loadedUrl = url;
        int requestGeneration = generation.incrementAndGet();
        timeline = null;
        hadCues = false;
        show(Collections.emptyList());
        handler.removeCallbacks(updater);
        if (sub == null || sub.isEmpty()) return;
        Task.execute(() -> load(sub, requestGeneration));
    }

    public void release() {
        released = true;
        generation.incrementAndGet();
        handler.removeCallbacks(updater);
        FrameLayout overlay = playerView.getOverlayFrameLayout();
        if (overlay != null) overlay.removeView(subtitleView);
        timeline = null;
        player = null;
    }

    private void load(Sub sub, int requestGeneration) {
        try {
            Map<String, String> headers = player == null ? Collections.emptyMap() : player.getHeaders();
            byte[] data = read(sub.getUri(), headers);
            String mime = getMimeType(sub, data);
            SecondarySubtitleTimeline parsed = SecondarySubtitleTimeline.parse(data, mime);
            handler.post(() -> applyLoaded(requestGeneration, parsed, null));
        } catch (Throwable error) {
            handler.post(() -> applyLoaded(requestGeneration, null, error));
        }
    }

    private void applyLoaded(int requestGeneration, @Nullable SecondarySubtitleTimeline parsed, @Nullable Throwable error) {
        if (released || generation.get() != requestGeneration) return;
        timeline = parsed;
        if (parsed == null) {
            loadedUrl = "";
            SecondarySubtitleDiagnostics.onFailed(error);
            Notify.show(R.string.subtitle_secondary_failed);
            show(Collections.emptyList());
            return;
        }
        SecondarySubtitleDiagnostics.onLoaded();
        if (active) update();
    }

    private void update() {
        handler.removeCallbacks(updater);
        if (released || !active || timeline == null || player == null || player.isReleased()) return;
        long positionMs = player.getPosition() + player.getSecondarySubtitleOffsetMs();
        show(timeline.cuesAt(positionMs));
        handler.postDelayed(updater, UPDATE_INTERVAL_MS);
    }

    private void show(List<Cue> cues) {
        boolean hasCues = !cues.isEmpty();
        if (hasCues && !hadCues) SecondarySubtitleDiagnostics.onRendered();
        hadCues = hasCues;
        subtitleView.setCues(cues);
        subtitleView.setVisibility(hasCues ? SubtitleView.VISIBLE : SubtitleView.GONE);
    }

    private static String getMimeType(Sub sub, byte[] data) {
        String detected = detectMimeType(data);
        if (!TextUtils.isEmpty(detected)) return detected;
        String mime = sub.getFormat();
        if (TextUtils.isEmpty(mime)) mime = PlayerHelper.getSubtitleMimeType(sub.getName());
        if (TextUtils.isEmpty(mime)) mime = PlayerHelper.getSubtitleMimeType(sub.getUrl());
        return TextUtils.isEmpty(mime) ? MimeTypes.APPLICATION_SUBRIP : mime;
    }

    private static String detectMimeType(byte[] data) {
        if (data.length == 0) return "";
        int length = Math.min(data.length, 4096);
        String sample = new String(data, 0, length, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        if (sample.startsWith("WEBVTT")) return MimeTypes.TEXT_VTT;
        if (sample.startsWith("[Script Info]") || sample.contains("\nDialogue:")) return MimeTypes.TEXT_SSA;
        if (sample.startsWith("<?xml") || sample.startsWith("<tt") || sample.contains("<tt ")) return MimeTypes.APPLICATION_TTML;
        return "";
    }

    private static byte[] read(@Nullable Uri uri, Map<String, String> headers) throws IOException {
        if (uri == null) throw new IOException("Missing subtitle URI");
        String scheme = uri.getScheme();
        if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            InputStream input = App.get().getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Unable to open subtitle");
            try (input) {
                return readLimited(input);
            }
        }
        if (ContentResolver.SCHEME_FILE.equals(scheme) || TextUtils.isEmpty(scheme)) {
            try (InputStream input = new FileInputStream(ContentResolver.SCHEME_FILE.equals(scheme) ? uri.getPath() : uri.toString())) {
                return readLimited(input);
            }
        }
        Request.Builder request = new Request.Builder().url(uri.toString());
        headers.forEach((key, value) -> {
            if (!TextUtils.isEmpty(key) && value != null) request.header(key, value);
        });
        try (Response response = OkHttp.player().newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Subtitle HTTP " + response.code());
            try (InputStream input = response.body().byteStream()) {
                return readLimited(input);
            }
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_BYTES) throw new IOException("Subtitle too large");
            output.write(buffer, 0, count);
        }
        if (total == 0) throw new IOException("Empty subtitle");
        return output.toByteArray();
    }
}
