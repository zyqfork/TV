package com.fongmi.android.tv.player.mpv;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.util.PngTsUnwrap;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

/**
 * For MPV only: when HLS segments are PNG-wrapped MPEG-TS (Exo sniffs TS; ffmpeg opens as PNG),
 * rewrite the playlist so segments go through the local {@code /tsraw} unwrap endpoint.
 */
public final class MpvHlsPngTs {

    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");

    private MpvHlsPngTs() {
    }

    public static PlaySpec prepare(PlaySpec spec) {
        if (spec == null || TextUtils.isEmpty(spec.getUrl())) return spec;
        String url = spec.getUrl();
        if (!isHlsUrl(url)) return spec;
        try {
            Map<String, String> headers = spec.checkUa().getHeaders();
            String body = normalizePlaylist(OkHttp.string(url, headers));
            // A cached local copy cannot follow a sliding live playlist. Byte-range playlists
            // also require preserving upstream Range semantics, so leave both to libmpv.
            if (TextUtils.isEmpty(body) || !body.contains("#EXTINF")
                    || !body.contains("#EXT-X-ENDLIST")
                    || body.contains("#EXT-X-BYTERANGE")) return spec;
            String segment = firstSegmentUrl(body, url);
            if (segment == null || !needsUnwrap(segment, headers)) return spec;
            String id = UUID.randomUUID().toString().replace("-", "");
            String rewritten = rewrite(body, url, id);
            Map<String, String> safeHeaders = new HashMap<>(headers);
            safeHeaders.keySet().removeIf(key -> "Host".equalsIgnoreCase(key)
                    || "Range".equalsIgnoreCase(key));
            safeHeaders = Collections.unmodifiableMap(safeHeaders);
            SESSIONS.put(id, new Session(safeHeaders, rewritten));
            purgeExpired();
            String local = Server.get().getAddress(true) + "/tsraw/m3u8?id=" + id;
            return spec.copyWithSource(local, safeHeaders);
        } catch (Throwable ignored) {
            return spec;
        }
    }

    public static Session get(String id) {
        if (id == null) return null;
        Session session = SESSIONS.get(id);
        if (session == null) return null;
        session.touch();
        return session;
    }

    private static boolean needsUnwrap(String segmentUrl, Map<String, String> headers) throws Exception {
        try (Response res = OkHttp.newCall(segmentUrl, headers).execute()) {
            if (res.body() == null) return false;
            try (InputStream in = res.body().byteStream()) {
                byte[] head = PngTsUnwrap.readProbe(in, PngTsUnwrap.probeSize());
                return PngTsUnwrap.findTsOffset(head) > 0;
            }
        }
    }

    private static String rewrite(String body, String playlistUrl, String id) {
        StringBuilder out = new StringBuilder(body.length() + 256);
        String prefix = Server.get().getAddress(true) + "/tsraw/seg?id=" + id + "&u=";
        for (String raw : body.split("\\r?\\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                out.append(raw).append('\n');
                continue;
            }
            if (line.startsWith("#")) {
                out.append(rewriteUriAttribute(raw, playlistUrl)).append('\n');
                continue;
            }
            String abs = UrlUtil.resolve(playlistUrl, line);
            out.append(prefix).append(Uri.encode(abs)).append('\n');
        }
        return out.toString();
    }

    private static String rewriteUriAttribute(String line, String playlistUrl) {
        Matcher matcher = URI_ATTRIBUTE.matcher(line);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group(1);
            String absolute = value.contains("://") || value.startsWith("data:")
                    ? value
                    : UrlUtil.resolve(playlistUrl, value);
            matcher.appendReplacement(output, "URI=\"" + Matcher.quoteReplacement(absolute) + "\"");
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String firstSegmentUrl(String body, String playlistUrl) {
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            return UrlUtil.resolve(playlistUrl, line);
        }
        return null;
    }

    /**
     * Some parsing services wrap an otherwise valid m3u8 in {@code <pre>}. FFmpeg then treats the
     * HTML tag as a playlist entry. Keep normal playlists untouched and unwrap only this shape.
     */
    private static String normalizePlaylist(String body) {
        if (TextUtils.isEmpty(body)) return body;
        String lower = body.toLowerCase(Locale.US);
        int marker = body.indexOf("#EXTM3U");
        if (marker < 0) return body;
        int preStart = lower.indexOf("<pre");
        if (preStart < 0) return body;
        int openEnd = body.indexOf('>', preStart);
        int preEnd = lower.lastIndexOf("</pre>");
        if (openEnd < 0 || preEnd <= openEnd) return body.substring(marker);
        return body.substring(openEnd + 1, preEnd)
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static boolean isHlsUrl(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.contains(".m3u8") || lower.contains("m3u8?");
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(e -> now - e.getValue().lastAccessMs > 30 * 60_000L);
    }

    public static final class Session {
        public final Map<String, String> headers;
        public final String playlist;
        volatile long lastAccessMs;

        Session(Map<String, String> headers, String playlist) {
            this.headers = headers;
            this.playlist = playlist;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() {
            lastAccessMs = System.currentTimeMillis();
        }
    }
}
