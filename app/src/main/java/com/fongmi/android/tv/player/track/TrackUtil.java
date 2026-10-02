package com.fongmi.android.tv.player.track;

import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.util.PlayerHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TrackUtil {

    public static String getSubtitleMimeType(String path) {
        if (TextUtils.isEmpty(path)) return "";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (lower.endsWith(".ssa") || lower.endsWith(".ass")) return MimeTypes.TEXT_SSA;
        if (lower.endsWith(".ttml") || lower.endsWith(".xml") || lower.endsWith(".dfxp")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    public static boolean isBitmapSubtitle(Format format) {
        if (format == null) return false;
        String value = !TextUtils.isEmpty(format.sampleMimeType) ? format.sampleMimeType : format.codecs;
        if (TextUtils.isEmpty(value)) return false;
        value = value.toLowerCase(Locale.ROOT);
        return value.contains("pgs") || value.contains("vobsub") || value.contains("dvd_subtitle")
                || value.contains("dvbsub") || value.contains("dvb_subtitle");
    }

    public static boolean hasSelectedBitmapSubtitle(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i) && isBitmapSubtitle(group.getTrackFormat(i))) return true;
            }
        }
        return false;
    }

    public static int count(Tracks tracks, int type) {
        return tracks.getGroups().stream().filter(trackGroup -> trackGroup.getType() == type).mapToInt(trackGroup -> trackGroup.length).sum();
    }

    public static void reset(Player player) {
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon().clearOverrides();
        builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false);
        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
        player.setTrackSelectionParameters(builder.build());
    }

    private static TrackInfo find(Player player, Track track) {
        if (track.getFormat() == null) return null;
        Tracks currentTracks = player.getCurrentTracks();
        for (Tracks.Group trackGroup : currentTracks.getGroups()) {
            if (trackGroup.getType() != track.getType()) continue;
            for (int i = 0; i < trackGroup.length; i++) {
                Format format = trackGroup.getTrackFormat(i);
                if (track.getFormat().equals(PlayerHelper.describeFormat(format))) {
                    return new TrackInfo(trackGroup, i);
                }
            }
        }
        return null;
    }

    /** Saved TEXT may arrive after the initial audio/video inventory (e.g. HTTP sub-add). */
    public static boolean canRestoreSelection(Player player, List<Track> tracks) {
        boolean hasSelected = tracks.stream().anyMatch(Track::isSelected);
        return tracks.stream().filter(track -> !hasSelected || track.isSelected())
                .anyMatch(track -> find(player, track) != null);
    }

    public static void setTrackSelection(Player player, List<Track> tracks) {
        Map<Integer, TrackGroup> mediaGroupMapByType = new HashMap<>();
        Map<Integer, Integer> selectedIndexMapByType = new HashMap<>();
        for (Track track : tracks) {
            TrackInfo info = find(player, track);
            if (info == null) continue;
            int type = info.trackGroup.getType();
            mediaGroupMapByType.put(type, info.trackGroup.getMediaTrackGroup());
            if (track.isSelected()) selectedIndexMapByType.put(type, info.trackIndex);
        }
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon();
        mediaGroupMapByType.forEach((type, mediaGroup) -> {
            Integer selectedIndex = selectedIndexMapByType.get(type);
            List<Integer> indices = selectedIndex != null ? List.of(selectedIndex) : List.of();
            if (selectedIndex != null) builder.setTrackTypeDisabled(type, false);
            builder.setOverrideForType(new TrackSelectionOverride(mediaGroup, indices));
        });
        player.setTrackSelectionParameters(builder.build());
    }

    private record TrackInfo(Tracks.Group trackGroup, int trackIndex) {
    }
}
