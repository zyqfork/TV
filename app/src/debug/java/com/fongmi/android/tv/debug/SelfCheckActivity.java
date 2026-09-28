package com.fongmi.android.tv.debug;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.LineQualityStore;
import com.fongmi.android.tv.player.effect.audio.AudioChannelMode;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.effect.audio.AudioEffectConfig;
import com.fongmi.android.tv.player.effect.audio.AudioEffectPreset;
import com.fongmi.android.tv.player.effect.audio.MpvAudioEffectFilter;
import com.fongmi.android.tv.player.effect.video.VideoEffectPreset;
import com.fongmi.android.tv.player.effect.video.VideoEffectProfile;
import com.fongmi.android.tv.player.engine.PlaybackCapabilities;
import com.fongmi.android.tv.player.engine.PlaybackRecoveryPolicy;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngineFactory;
import com.fongmi.android.tv.player.exo.MediaSourceFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.subtitle.ExternalFont;
import com.fongmi.android.tv.player.subtitle.SecondarySubtitleTimeline;
import com.fongmi.android.tv.player.util.PngTsUnwrap;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.setting.VideoSetting;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ADB driven self-check for the parts of this round that cannot be exercised by the preload
 * end-to-end test: the recovery decision table, the external-font selection round-trip, and the
 * effect of the selected font on actual subtitle rendering.
 *
 * <p>All three are pure logic with no UI surface, which is exactly why they are easy to get subtly
 * wrong and hard to notice at runtime. The font checks in particular pin the multi-face (TTC) bug:
 * the selection used to persist only the file path, so every reload reported face 0.
 *
 * <p>Run: {@code adb shell am start -n com.fongmi.android.tv/.debug.SelfCheckActivity}
 * then read {@code logcat -s SelfCheck:V}. Exits with a {@code RESULT passed=.. failed=..} line.
 */
@UnstableApi
public final class SelfCheckActivity extends Activity {

    public static final String TAG = "SelfCheck";
    private static final int RENDER_WIDTH = 480;
    private static final int RENDER_HEIGHT = 120;
    private static final String RENDER_TEXT = "Hamburgefonstiv 0123456789";
    private final List<String> failures = new ArrayList<>();
    private int passed;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        // Several checks deliberately clear the selection, so snapshot it up front and put it back
        // at the end. A test must never leave the device reconfigured.
        ExternalFont.Entry saved = SubtitleSetting.getFontEntry();
        checkRecoveryPolicy();
        checkRetryBackoff();
        checkPlaybackCapabilities();
        checkEngineRoutingAndCacheIsolation();
        checkPlayerSettingBounds();
        checkSafeLocalPath();
        checkActionResult();
        checkLineQuality();
        checkPngTsUnwrap();
        checkSecondarySubtitleTimeline();
        checkMpvAudioFilter();
        checkVideoEffectProfile();
        checkFontRoundTrip();
        checkCaptionStyle();
        checkFontRendering();
        SubtitleSetting.putFontSelection(saved);
        Log.i(TAG, "RESTORED font=" + (saved == null ? "<none>" : saved.path() + " face=" + saved.faceIndex()));
        // Escape hatch for repairing a selection damaged by an earlier run.
        String setFontPath = getIntent().getStringExtra("setFontPath");
        if (setFontPath != null) {
            SubtitleSetting.putFontPath(setFontPath);
            Log.i(TAG, "SET font=" + SubtitleSetting.getFontPath() + " face=" + SubtitleSetting.getFontFaceIndex() + " family=" + SubtitleSetting.getFontFamily());
        }
        Log.i(TAG, "RESULT passed=" + passed + " failed=" + failures.size());
        for (String failure : failures) Log.e(TAG, "FAILED " + failure);
        String playUrl = getIntent().getStringExtra("playUrl");
        if (playUrl != null && failures.isEmpty()) VideoActivity.start(this, playUrl);
        finish();
    }

    private void expect(String name, Object actual, Object expected) {
        if (expected.equals(actual)) {
            passed++;
            Log.i(TAG, "PASS " + name + "=" + actual);
        } else {
            failures.add(name + " expected=" + expected + " actual=" + actual);
        }
    }

    /** Every retryable class must exhaust its budget and then stop, instead of spinning forever. */
    private void checkRecoveryPolicy() {
        PlaybackRecoveryPolicy.Action seek = PlaybackRecoveryPolicy.Action.SEEK_DEFAULT;
        PlaybackRecoveryPolicy.Action decode = PlaybackRecoveryPolicy.Action.SWITCH_DECODE;
        PlaybackRecoveryPolicy.Action transientRetry = PlaybackRecoveryPolicy.Action.RETRY_TRANSIENT;
        PlaybackRecoveryPolicy.Action formatRetry = PlaybackRecoveryPolicy.Action.RETRY_FORMAT;
        PlaybackRecoveryPolicy.Action fatal = PlaybackRecoveryPolicy.Action.FATAL;
        int budget = PlaybackRecoveryPolicy.MAX_ATTEMPTS;

        expect("live_window", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, 0), seek);
        expect("decode_init", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, 0), decode);
        expect("decode_query", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, 0), decode);
        expect("decoding", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_DECODING_FAILED, 0), decode);
        expect("decode_exceeds", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, 0), decode);

        // Transient transport failures used to fall through to FATAL; they must now retry, but only
        // while budget remains.
        expect("net_failed_first", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, 0), transientRetry);
        expect("net_timeout_first", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, 0), transientRetry);
        expect("bad_http_first", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 0), transientRetry);
        expect("timeout_first", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_TIMEOUT, 0), transientRetry);
        expect("net_timeout_exhausted", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, budget), fatal);
        expect("net_failed_exhausted", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, budget + 5), fatal);

        expect("container_malformed_first", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, 0), formatRetry);
        expect("manifest_malformed_first", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, 0), formatRetry);
        expect("container_unsupported_first", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, 0), formatRetry);
        expect("io_unspecified_first", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, 0), formatRetry);
        // This is the regression the old code had: an unbounded parse retry loop.
        expect("container_malformed_exhausted", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, budget), fatal);

        expect("unspecified_is_fatal", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_UNSPECIFIED, 0), fatal);
        expect("drm_is_fatal", PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, 0), fatal);
    }

    private void checkRetryBackoff() {
        long first = PlaybackRecoveryPolicy.retryDelayMs(0);
        long second = PlaybackRecoveryPolicy.retryDelayMs(1);
        long capped = PlaybackRecoveryPolicy.retryDelayMs(99);
        expect("backoff_first", first, 500L);
        expect("backoff_second", second, 1000L);
        if (second > first) passed++;
        else failures.add("backoff_not_increasing first=" + first + " second=" + second);
        // Must saturate rather than overflow into a negative delay.
        if (capped > 0 && capped <= 3000L) passed++;
        else failures.add("backoff_not_capped value=" + capped);
    }

    private void checkPlaybackCapabilities() {
        PlaybackCapabilities exo = PlaybackCapabilities.forEngine(PlayerEngine.Type.EXO);
        PlaybackCapabilities mpv = PlaybackCapabilities.forEngine(PlayerEngine.Type.MPV);
        expect("cap_exo_gain", exo.volumeGain(), true);
        expect("cap_mpv_gain", mpv.volumeGain(), true);
        expect("cap_exo_secondary", exo.secondarySubtitle(), true);
        expect("cap_mpv_secondary", mpv.secondarySubtitle(), true);
        expect("cap_exo_eq", exo.audioEqualizer(), true);
        expect("cap_mpv_eq", mpv.audioEqualizer(), true);
        expect("cap_exo_dynamics", exo.audioDynamics(), true);
        expect("cap_mpv_dynamics", mpv.audioDynamics(), true);
        expect("cap_exo_video_color", exo.videoColorAdjustments(), true);
        expect("cap_exo_video_detail", exo.videoDetailEnhancement(), true);
        expect("cap_exo_video_shader", exo.videoShaders(), true);
        expect("cap_mpv_video_color", mpv.videoColorAdjustments(), true);
        expect("cap_mpv_video_detail", mpv.videoDetailEnhancement(), true);
        expect("cap_mpv_video_shader", mpv.videoShaders(), false);
    }

    private void checkEngineRoutingAndCacheIsolation() {
        PlaySpec dashByUrl = PlaySpec.from("dash", "https://example.test/video.mpd", Map.of(), null);
        PlaySpec dashByFormat = PlaySpec.from("dash-format", "https://example.test/video", Map.of(), null);
        dashByFormat.setFormat(MimeTypes.APPLICATION_MPD);
        PlaySpec hls = PlaySpec.from("hls", "https://example.test/live.m3u8", Map.of(), null);
        expect("route_dash_url_exo", PlayerEngineFactory.requiresExo(dashByUrl), true);
        expect("route_dash_format_exo", PlayerEngineFactory.requiresExo(dashByFormat), true);
        expect("route_hls_not_forced", PlayerEngineFactory.requiresExo(hls), false);
        expect("cache_authorization_sensitive", MediaSourceFactory.hasSensitiveHeaders(Map.of("Authorization", "Bearer test")), true);
        expect("cache_cookie_sensitive", MediaSourceFactory.hasSensitiveHeaders(Map.of("Cookie", "sid=test")), true);
        expect("cache_referer_allowed", MediaSourceFactory.hasSensitiveHeaders(Map.of("Referer", "https://example.test/")), false);
    }

    private void checkPlayerSettingBounds() {
        int buffer = PlayerSetting.getBuffer();
        int http = PlayerSetting.getHttp();
        int latency = PlayerSetting.getLiveLatency();
        try {
            PlayerSetting.putBuffer(-10);
            expect("setting_buffer_min", PlayerSetting.getBuffer(), 1);
            PlayerSetting.putBuffer(100);
            expect("setting_buffer_max", PlayerSetting.getBuffer(), 15);
            PlayerSetting.putHttp(-1);
            expect("setting_http_min", PlayerSetting.getHttp(), 0);
            PlayerSetting.putHttp(9);
            expect("setting_http_max", PlayerSetting.getHttp(), 1);
            PlayerSetting.putLiveLatency(-1);
            expect("setting_latency_min", PlayerSetting.getLiveLatency(), PlayerSetting.LIVE_LATENCY_SMOOTH);
            PlayerSetting.putLiveLatency(9);
            expect("setting_latency_max", PlayerSetting.getLiveLatency(), PlayerSetting.LIVE_LATENCY_LOW);
        } finally {
            PlayerSetting.putBuffer(buffer);
            PlayerSetting.putHttp(http);
            PlayerSetting.putLiveLatency(latency);
        }
    }

    private void checkSafeLocalPath() {
        File inside = new File(Path.root(), "TV/self-check.txt");
        File escaped = Path.resolveUnderRoot("../self-check-escape.txt");
        File absoluteInside = Path.resolveUnderRoot(inside.getAbsolutePath());
        expect("path_traversal_rejected", escaped == null, true);
        expect("path_absolute_inside_allowed", absoluteInside != null, true);
    }

    private void checkActionResult() {
        Result explicit = Result.fromJson("{\"code\":1,\"refresh\":true,\"position\":7}");
        expect("action_refresh_explicit", explicit.shouldRefreshAction(), true);
        expect("action_position_present", explicit.hasPosition(), true);
        expect("action_position_value", explicit.getPosition(), 7L);
        Result legacy = Result.fromJson("{\"code\":0,\"msg\":\"updated\"}");
        expect("action_refresh_legacy", legacy.shouldRefreshAction(), true);
        expect("action_no_position", legacy.hasPosition(), false);
    }

    private void checkLineQuality() {
        final String key = "live_line_quality_v2";
        boolean existed = Prefers.getPrefers().contains(key);
        String saved = Prefers.getString(key, "");
        try {
            Prefers.remove(key);
            List<String> urls = List.of("https://self-check.invalid/a", "https://self-check.invalid/b");
            expect("line_quality_fallback", LineQualityStore.bestIndex(urls, 1), 1);
            LineQualityStore.recordFailure(urls.get(0));
            LineQualityStore.recordSuccess(urls.get(1) + "$User-Agent=test", 400);
            expect("line_quality_learned", LineQualityStore.bestIndex(urls, 0), 1);
            String stored = Prefers.getString(key, "");
            expect("line_quality_no_url", stored.contains("self-check.invalid"), false);
        } finally {
            if (existed) Prefers.put(key, saved);
            else Prefers.remove(key);
        }
    }

    private void checkPngTsUnwrap() {
        try {
            byte[] wrapped = new byte[32 + 188 * 3];
            byte[] prefix = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                    'x', 'T', 'S', '_', 'R', 'A', 'W', 0};
            System.arraycopy(prefix, 0, wrapped, 0, prefix.length);
            int offset = 32;
            wrapped[offset] = 0x47;
            wrapped[offset + 188] = 0x47;
            wrapped[offset + 376] = 0x47;
            expect("png_ts_detect", PngTsUnwrap.findTsOffset(wrapped), offset);
            byte[] payload = readAll(PngTsUnwrap.unwrap(wrapped, new ByteArrayInputStream(new byte[0])));
            expect("png_ts_unwrap_sync", payload[0] & 0xff, 0x47);
            expect("png_ts_unwrap_length", payload.length, wrapped.length - offset);
            expect("png_ts_passthrough", PngTsUnwrap.findTsOffset(new byte[]{0x47, 0, 1}), 0);
            // TS beginning exactly at length - 376: only two packets fit, so the three-point
            // probe cannot be confirmed and the call must fall back to pass-through. The loop
            // used to read one byte past the end here and throw instead of returning -1.
            byte[] boundary = new byte[496];
            System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, 0, boundary, 0, 8);
            boundary[120] = 0x47;
            boundary[120 + 188] = 0x47;
            expect("png_ts_boundary_no_throw", PngTsUnwrap.findTsOffset(boundary), -1);
        } catch (Throwable e) {
            failures.add("png_ts_unwrap_threw " + e);
            Log.e(TAG, "PNG-TS unwrap check threw", e);
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    /** Verifies the public Media3 parser used by the dual-subtitle prototype. */
    private void checkSecondarySubtitleTimeline() {
        try {
            String srt = "1\n00:00:01,000 --> 00:00:02,500\nFirst line\n\n"
                    + "2\n00:00:03,000 --> 00:00:04,000\nSecond line\n\n";
            SecondarySubtitleTimeline timeline = SecondarySubtitleTimeline.parse(
                    srt.getBytes(StandardCharsets.UTF_8), MimeTypes.APPLICATION_SUBRIP);
            expect("secondary_sample_count", timeline.sampleCount(), 2);
            expect("secondary_before_empty", timeline.cuesAt(500).isEmpty(), true);
            expect("secondary_first_count", timeline.cuesAt(1500).size(), 1);
            expect("secondary_first_text", String.valueOf(timeline.cuesAt(1500).get(0).text), "First line");
            expect("secondary_gap_empty", timeline.cuesAt(2750).isEmpty(), true);
            expect("secondary_second_text", String.valueOf(timeline.cuesAt(3500).get(0).text), "Second line");
            expect("secondary_after_empty", timeline.cuesAt(4500).isEmpty(), true);
        } catch (Throwable e) {
            failures.add("secondary_timeline_threw " + e);
            Log.e(TAG, "secondary subtitle timeline check threw", e);
        }
    }

    /**
     * The MPV audio chain is emitted as one lavfi string, so every ordering and formatting rule is
     * load-bearing: a filter placed after a downmix makes the graph invalid, and a band emitted at
     * zero gain burns CPU for nothing. None of this was covered before.
     */
    private void checkMpvAudioFilter() {
        int preset = AudioSetting.getPreset();
        int stability = AudioSetting.getStability();
        int dialogue = AudioSetting.getDialogue();
        int boost = AudioSetting.getBoost();
        int preamp = AudioSetting.getPreamp();
        boolean loudness = AudioSetting.isLoudnessEnabled();
        int centerGain = AudioSetting.getCenterGain();
        int balance = AudioSetting.getBalance();
        int channelMode = AudioSetting.getChannelMode();
        short[] custom = AudioSetting.getCustomLevels(AudioEffectBands.STANDARD);
        try {
            neutralAudio();
            expect("af_all_off_empty", MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2), "");
            expect("af_disabled_has_no_effect", AudioEffectConfig.disabled().hasEffect(), false);

            // Gain only: no bands, so exactly one volume filter and nothing else.
            AudioSetting.putPreamp(-600);
            expect("af_gain_only", MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2), "lavfi=[volume=-6.00dB]");

            neutralAudio();
            AudioSetting.putLoudness(true);
            String loud = MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2);
            expect("af_loudness", loud.contains("loudnorm=I=-18:LRA=11:TP=-1.5"), true);
            expect("af_single_wrapper", countOf(loud, "lavfi=["), 1);

            neutralAudio();
            AudioSetting.putStability(100);
            expect("af_stability_compressor", MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2).contains("acompressor="), true);
            neutralAudio();
            expect("af_stability_off_no_compressor", MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2).contains("acompressor"), false);

            // A custom curve with one non-zero band must emit exactly one equalizer, at that band's
            // own centre frequency (32 kHz / 1000 = 32.0, 600 / 100 = 6.00 dB).
            neutralAudio();
            AudioSetting.putPreset(AudioEffectPreset.CUSTOM);
            short[] levels = new short[AudioEffectBands.STANDARD.getCount()];
            levels[0] = 600;
            AudioSetting.putCustomLevels(AudioEffectBands.STANDARD, levels);
            String eq = MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2);
            expect("af_eq_single_band", eq.contains("equalizer=f=32.0:t=o:w=1:g=6.00"), true);
            expect("af_eq_zero_bands_skipped", countOf(eq, "equalizer="), 1);
            expect("af_eq_wrapped", eq.startsWith("lavfi=[") && eq.endsWith("]"), true);

            neutralAudio();
            AudioSetting.putChannelMode(AudioChannelMode.MONO);
            expect("af_mono_pan", MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2)
                    .contains("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1"), true);
            neutralAudio();
            AudioSetting.putChannelMode(AudioChannelMode.REVERSE);
            expect("af_reverse_pan", MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2)
                    .contains("pan=stereo|c0=c1|c1=c0"), true);

            // Centre gain must be applied while the 5.1 layout still exists: referencing c2 after a
            // stereo downmix would name a channel that no longer exists.
            neutralAudio();
            AudioSetting.putCenterGain(600);
            AudioSetting.putChannelMode(AudioChannelMode.STEREO);
            String centre = MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 6), 6);
            expect("af_center_layout_51", centre.contains("pan=5.1|"), true);
            expect("af_center_gain_amplitude", centre.contains("c2=1.995*c2"), true);
            expect("af_center_before_downmix", indexOfOrMax(centre, "pan=5.1|") < indexOfOrMax(centre, "pan=stereo"), true);
            expect("af_center_layout_71", MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 8), 8).contains("pan=7.1|"), true);

            // Balance attenuates one side only (50 -> left 0.5, right 1.0).
            neutralAudio();
            AudioSetting.putBalance(50);
            expect("af_balance_right", MpvAudioEffectFilter.create(AudioEffectConfig.from(AudioEffectBands.STANDARD, 2), 2)
                    .contains("pan=stereo|c0=0.500*c0|c1=1.000*c1"), true);
        } catch (Throwable e) {
            failures.add("mpv_af_threw " + e);
            Log.e(TAG, "mpv audio filter check threw", e);
        } finally {
            AudioSetting.putPreset(preset);
            AudioSetting.putStability(stability);
            AudioSetting.putDialogue(dialogue);
            AudioSetting.putBoost(boost);
            AudioSetting.putPreamp(preamp);
            AudioSetting.putLoudness(loudness);
            AudioSetting.putCenterGain(centerGain);
            AudioSetting.putBalance(balance);
            AudioSetting.putChannelMode(channelMode);
            AudioSetting.putCustomLevels(AudioEffectBands.STANDARD, custom);
        }
    }

    /** Neutralise every audio-effect input so each assertion starts from a known state. */
    private void neutralAudio() {
        AudioSetting.putPreset(AudioEffectPreset.OFF);
        AudioSetting.putStability(0);
        AudioSetting.putDialogue(0);
        AudioSetting.putBoost(0);
        AudioSetting.putPreamp(0);
        AudioSetting.putLoudness(false);
        AudioSetting.putCenterGain(0);
        AudioSetting.putBalance(0);
        AudioSetting.putChannelMode(AudioChannelMode.AUTO);
    }

    /**
     * Video presets are pure data, but they are what the sliders edit: a preset outside the slider
     * range would be unrepresentable in the UI, and {@code of(CUSTOM)} silently returning the
     * identity profile is easy to misread as "custom is broken".
     */
    private void checkVideoEffectProfile() {
        VideoEffectProfile off = VideoEffectProfile.off();
        expect("video_off_saturation", off.getSaturation(), 1.0f);
        expect("video_off_contrast", off.getContrast(), 1.0f);
        expect("video_off_brightness", off.getBrightness(), 0.0f);
        expect("video_off_sharpness", off.getSharpness(), 0.0f);
        expect("video_off_shadow", off.getShadowLift(), 0.0f);
        expect("video_off_gamma", off.getGamma(), 1.0f);
        expect("video_off_hue", off.getHue(), 0.0f);
        expect("video_off_temperature", off.getTemperature(), 0.0f);

        // CUSTOM resolves through VideoSetting.getCustomProfile(), not of(); of(CUSTOM) is identity.
        expect("video_of_custom_is_identity", VideoEffectProfile.of(VideoEffectPreset.CUSTOM).getSaturation(), 1.0f);
        expect("video_vivid_saturation_up", VideoEffectProfile.of(VideoEffectPreset.VIVID).getSaturation() > 1.0f, true);
        expect("video_soft_saturation_down", VideoEffectProfile.of(VideoEffectPreset.SOFT).getSaturation() < 1.0f, true);
        expect("video_clear_has_sharpness", VideoEffectProfile.of(VideoEffectPreset.CLEAR).getSharpness() > 0.0f, true);
        expect("video_warm_temperature_up", VideoEffectProfile.of(VideoEffectPreset.WARM).getTemperature() > 0.0f, true);
        expect("video_cool_temperature_down", VideoEffectProfile.of(VideoEffectPreset.COOL).getTemperature() < 0.0f, true);
        expect("video_clamp_high", VideoEffectPreset.clamp(999), VideoEffectPreset.CUSTOM);
        expect("video_clamp_low", VideoEffectPreset.clamp(-5), VideoEffectPreset.OFF);

        // Custom profile round-trips through the settings layer with the slider bounds intact.
        VideoEffectProfile applied = VideoSetting.getAppliedProfile();
        expect("video_applied_profile_nonnull", applied != null, true);
        expect("video_applied_in_range", inSliderRange(applied), true);

        int outOfRange = 0;
        for (int preset = VideoEffectPreset.OFF; preset <= VideoEffectPreset.CUSTOM; preset++) {
            if (!inSliderRange(VideoEffectProfile.of(preset))) outOfRange++;
        }
        expect("video_all_presets_in_slider_range", outOfRange, 0);
    }

    private static boolean inSliderRange(VideoEffectProfile profile) {
        return inRange(profile.getSaturation(), VideoSetting.MIN_SATURATION, VideoSetting.MAX_SATURATION)
                && inRange(profile.getContrast(), VideoSetting.MIN_CONTRAST, VideoSetting.MAX_CONTRAST)
                && inRange(profile.getBrightness(), VideoSetting.MIN_BRIGHTNESS, VideoSetting.MAX_BRIGHTNESS)
                && inRange(profile.getSharpness(), VideoSetting.MIN_SHARPNESS, VideoSetting.MAX_SHARPNESS)
                && inRange(profile.getShadowLift(), VideoSetting.MIN_SHADOW, VideoSetting.MAX_SHADOW)
                && inRange(profile.getGamma(), VideoSetting.MIN_GAMMA, VideoSetting.MAX_GAMMA)
                && inRange(profile.getHue(), VideoSetting.MIN_HUE, VideoSetting.MAX_HUE)
                && inRange(profile.getTemperature(), VideoSetting.MIN_TEMPERATURE, VideoSetting.MAX_TEMPERATURE);
    }

    private static boolean inRange(float value, float min, float max) {
        return !Float.isNaN(value) && value >= min && value <= max;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /** Index of {@code needle}, or Integer.MAX_VALUE so "absent" fails an ordering comparison. */
    private static int indexOfOrMax(String text, String needle) {
        int index = text.indexOf(needle);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * Round-trips a real multi-face font (if one exists on the device) through the settings layer.
     * The family and face index must survive, which is the whole point of the fix.
     */
    private void checkFontRoundTrip() {
        ExternalFont.Entry original = SubtitleSetting.getFontEntry();
        File ttc = findMultiFaceFont();
        if (ttc == null) {
            Log.w(TAG, "SKIP font_round_trip: no multi-face font found under /system/fonts");
        } else {
            List<ExternalFont.Entry> entries = ExternalFont.getEntries(ttc);
            expect("ttc_face_count", entries.size() > 1, true);
            ExternalFont.Entry chosen = entries.get(entries.size() - 1);
            Log.i(TAG, "chosen face index=" + chosen.faceIndex() + " family=" + chosen.family() + " file=" + ttc.getName());
            SubtitleSetting.putFontSelection(chosen);
            expect("roundtrip_path", SubtitleSetting.getFontPath(), chosen.path());
            expect("roundtrip_face", SubtitleSetting.getFontFaceIndex(), chosen.faceIndex());
            // The regression: this used to report the collection's first family, not the selected one.
            expect("roundtrip_family", SubtitleSetting.getFontFamily(), chosen.family());
            ExternalFont.Entry reloaded = SubtitleSetting.getFontEntry();
            expect("roundtrip_reloaded_face", reloaded == null ? -1 : reloaded.faceIndex(), chosen.faceIndex());
            expect("roundtrip_has_custom", SubtitleSetting.hasCustomFont(), true);
            expect("roundtrip_typeface_nonnull", SubtitleSetting.getTypeface() != null, true);
        }
        // Never leave the user's own selection overwritten by a test.
        SubtitleSetting.putFontSelection(original);
        expect("restored_path", SubtitleSetting.getFontPath(), original == null ? "" : original.path());
        expect("restored_face", SubtitleSetting.getFontFaceIndex(), original == null ? 0 : original.faceIndex());
    }

    @Nullable
    private File findMultiFaceFont() {
        File dir = new File("/system/fonts");
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (!file.getName().endsWith(".ttc")) continue;
            if (ExternalFont.getEntries(file).size() > 1) return file;
        }
        return null;
    }

    /** captionStyle()/applyStyle() run on every playback start, so they must not throw. */
    private void checkCaptionStyle() {
        try {
            CaptionStyleCompat style = SubtitleSetting.captionStyle(this);
            expect("caption_style_nonnull", style != null, true);
            CaptionStyleCompat layered = SubtitleSetting.withTypeface(CaptionStyleCompat.DEFAULT);
            expect("with_typeface_nonnull", layered != null, true);
            // With no font selected the base style must pass through untouched.
            SubtitleSetting.putFontSelection(null);
            expect("with_typeface_passthrough", SubtitleSetting.withTypeface(CaptionStyleCompat.DEFAULT) == CaptionStyleCompat.DEFAULT, true);
            SubtitleView view = new SubtitleView(this);
            SubtitleSetting.applyStyle(view);
            expect("apply_style_ok", true, true);
        } catch (Throwable e) {
            failures.add("caption_style_threw " + e);
            Log.e(TAG, "caption style check threw", e);
        }
    }

    /**
     * Renders the same cue through the real {@link SubtitleView} twice — once with the default
     * style and once with a custom font layered on — and compares the resulting pixels. This is the
     * only check that proves the selected font reaches the screen; a non-throwing
     * {@code captionStyle()} would still pass if the typeface were silently dropped.
     */
    private void checkFontRendering() {
        CaptionStyleCompat base = new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
        long[] baseline = renderSignature(base);
        if (baseline[INK] == 0) {
            // Drawing nothing would make every comparison vacuously equal, so report rather than pass.
            Log.w(TAG, "SKIP font_rendering: SubtitleView produced no ink while detached from a window");
            return;
        }
        expect("render_baseline_has_ink", true, true);

        ExternalFont.Entry original = SubtitleSetting.getFontEntry();
        ExternalFont.Entry candidate = pickRenderFont();
        if (candidate == null) {
            Log.w(TAG, "SKIP font_rendering: no usable font file on device");
            return;
        }

        SubtitleSetting.putFontSelection(candidate);
        long[] styled = renderSignature(SubtitleSetting.withTypeface(base));
        SubtitleSetting.putFontSelection(original);

        Log.i(TAG, "render baseline ink=" + baseline[INK] + " right=" + baseline[RIGHT]
                + " | custom(" + candidate.family() + ") ink=" + styled[INK] + " right=" + styled[RIGHT]);
        expect("render_typeface_changes_pixels", styled[HASH] != baseline[HASH], true);
        // Glyph advance widths must move too; otherwise only antialiasing changed.
        expect("render_typeface_changes_ink_width", styled[RIGHT] != baseline[RIGHT], true);

        long[] restored = renderSignature(base);
        expect("render_restored_matches_baseline", restored[HASH], baseline[HASH]);
    }

    /** Prefer the user's own installed font, falling back to a system collection face. */
    @Nullable
    private ExternalFont.Entry pickRenderFont() {
        for (ExternalFont.Entry entry : ExternalFont.getAll()) return entry;
        File ttc = findMultiFaceFont();
        if (ttc == null) return null;
        List<ExternalFont.Entry> entries = ExternalFont.getEntries(ttc);
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    private static final int HASH = 0;
    private static final int RIGHT = 1;
    private static final int INK = 2;

    /** @return {pixel hash, rightmost inked column, inked pixel count} */
    private long[] renderSignature(CaptionStyleCompat style) {
        SubtitleView view = new SubtitleView(this);
        view.setApplyEmbeddedStyles(false);
        view.setApplyEmbeddedFontSizes(false);
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION);
        view.setStyle(style);
        view.setCues(Collections.singletonList(new Cue.Builder().setText(RENDER_TEXT).build()));
        Bitmap bitmap = Bitmap.createBitmap(RENDER_WIDTH, RENDER_HEIGHT, Bitmap.Config.ARGB_8888);
        view.measure(View.MeasureSpec.makeMeasureSpec(RENDER_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(RENDER_HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, RENDER_WIDTH, RENDER_HEIGHT);
        view.draw(new Canvas(bitmap));
        long hash = 1125899906842597L;
        int right = -1;
        int ink = 0;
        for (int y = 0; y < RENDER_HEIGHT; y++) {
            for (int x = 0; x < RENDER_WIDTH; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) == 0) continue;
                ink++;
                if (x > right) right = x;
                hash = hash * 31 + pixel;
            }
        }
        bitmap.recycle();
        return new long[]{hash, right, ink};
    }
}
