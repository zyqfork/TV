package com.fongmi.android.tv.setting;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.accessibility.CaptioningManager;

import androidx.annotation.Nullable;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.player.subtitle.ExternalFont;
import com.github.catvod.utils.Prefers;

import java.io.File;

public class SubtitleSetting {

    public static String getSearchToken() {
        return Prefers.getString("subtitle_search_token", "");
    }

    public static String getEffectiveToken() {
        String userToken = getSearchToken();
        if (!TextUtils.isEmpty(userToken)) return userToken;
        try {
            return VodConfig.get().getConfig().getAssrt();
        } catch (Exception e) {
            return "";
        }
    }

    public static void putSearchToken(String token) {
        Prefers.put("subtitle_search_token", token == null ? "" : token.trim());
    }

    /** Optional custom font path for libass / MPV subtitle rendering. */
    public static String getFontPath() {
        return Prefers.getString("subtitle_font_path", "");
    }

    public static void putFontPath(String path) {
        String value = path == null ? "" : path;
        ExternalFont.Entry entry = TextUtils.isEmpty(value) ? null : ExternalFont.getEntry(new File(value));
        putFontSelection(entry);
    }

    public static void putFontSelection(@Nullable ExternalFont.Entry entry) {
        Prefers.put("subtitle_font_path", entry == null ? "" : entry.path());
        Prefers.put("subtitle_font_family", entry == null ? "" : entry.family());
        Prefers.put("subtitle_font_face", entry == null ? 0 : entry.faceIndex());
    }

    /**
     * Family name recorded at selection time. Reading it back instead of re-parsing the file keeps
     * the selected TTC face stable: re-parsing would always report the collection's first face.
     */
    public static String getFontFamily() {
        String family = Prefers.getString("subtitle_font_family", "");
        if (!TextUtils.isEmpty(family)) return family;
        ExternalFont.Entry entry = getFontEntry();
        return entry == null ? "" : entry.family();
    }

    public static int getFontFaceIndex() {
        return Prefers.getInt("subtitle_font_face", 0);
    }

    /** Rebuilds the persisted selection, including the TTC face index. */
    @Nullable
    public static ExternalFont.Entry getFontEntry() {
        String path = getFontPath();
        if (TextUtils.isEmpty(path)) return null;
        int face = getFontFaceIndex();
        for (ExternalFont.Entry entry : ExternalFont.getEntries(new File(path))) {
            if (entry.faceIndex() == face) return entry;
        }
        return ExternalFont.getEntry(new File(path));
    }

    public static boolean hasCustomFont() {
        return !TextUtils.isEmpty(getFontPath());
    }

    /**
     * Typeface for the selected font face, or {@code null} when the user kept the system default.
     * Resolving through the face index is what makes TTC sub-fonts selectable.
     */
    @Nullable
    public static Typeface getTypeface() {
        ExternalFont.Entry entry = getFontEntry();
        return entry == null ? null : entry.typeface();
    }

    /** Applies the selected custom typeface on top of a base caption style. */
    public static CaptionStyleCompat withTypeface(@Nullable CaptionStyleCompat base) {
        CaptionStyleCompat source = base == null ? CaptionStyleCompat.DEFAULT : base;
        Typeface typeface = getTypeface();
        if (typeface == null) return source;
        return new CaptionStyleCompat(source.foregroundColor, source.backgroundColor, source.windowColor, source.edgeType, source.edgeColor, typeface);
    }

    /**
     * Single source of truth for the subtitle caption style. Honors the system caption style when
     * the user enabled it, otherwise uses the app's white-on-outline default, and always layers the
     * selected external font on top.
     */
    public static CaptionStyleCompat captionStyle(@Nullable Context context) {
        CaptioningManager manager = context == null ? null : (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
        CaptionStyleCompat base = PlayerSetting.isCaption() && manager != null
                ? CaptionStyleCompat.createFromCaptionStyle(manager.getUserStyle())
                : new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
        return withTypeface(base);
    }

    /** Apply persisted subtitle metrics (and font) to a Media3 SubtitleView (Exo and shared UI). */
    public static void applyStyle(SubtitleView view) {
        if (view == null) return;
        view.setStyle(captionStyle(view.getContext()));
        float position = PlayerSetting.getSubtitlePosition();
        float textSize = PlayerSetting.getSubtitleTextSize();
        // Keep authored ASS/SSA sizes unless the user explicitly overrides them.
        view.setApplyEmbeddedFontSizes(textSize == 0f && !PlayerSetting.isCaption());
        view.setBottomPosition(position);
        view.setFractionalTextSize(textSize != 0f ? textSize : SubtitleView.DEFAULT_TEXT_SIZE_FRACTION);
    }
}
