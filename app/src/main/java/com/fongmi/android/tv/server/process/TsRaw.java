package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.player.mpv.MpvHlsPngTs;
import com.fongmi.android.tv.player.util.PngTsUnwrap;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.ResponseBody;

/** Local unwrap for PNG-wrapped MPEG-TS HLS segments used by MPV. */
public class TsRaw implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/tsraw/");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            String id = params.get("id");
            MpvHlsPngTs.Session play = MpvHlsPngTs.get(id);
            if (play == null) return Nano.error("session expired");
            if (url.startsWith("/tsraw/m3u8")) {
                return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                        "application/vnd.apple.mpegurl", play.playlist);
            }
            if (url.startsWith("/tsraw/seg")) {
                String target = params.get("u");
                if (TextUtils.isEmpty(target)) return Nano.error("missing url");
                return streamUnwrapped(target, play.headers);
            }
            return Nano.error("unknown tsraw path");
        } catch (Throwable e) {
            return Nano.error(String.valueOf(e.getMessage()));
        }
    }

    private static Response streamUnwrapped(String url, Map<String, String> headers) throws Exception {
        okhttp3.Response upstream = OkHttp.newCall(url, headers).execute();
        ResponseBody body = upstream.body();
        if (!upstream.isSuccessful() || body == null) {
            int code = upstream.code();
            upstream.close();
            return Nano.error("upstream " + code);
        }
        InputStream in = body.byteStream();
        byte[] head = PngTsUnwrap.readProbe(in, PngTsUnwrap.probeSize());
        int offset = PngTsUnwrap.findTsOffset(head);
        InputStream unwrapped = PngTsUnwrap.unwrap(head, in);
        InputStream out = new FilterInputStream(unwrapped) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    upstream.close();
                }
            }
        };
        long sourceLength = body.contentLength();
        long payloadLength = sourceLength >= 0 && offset >= 0
                ? Math.max(0, sourceLength - offset)
                : -1;
        if (payloadLength < 0) {
            // Transparent content decoding can hide the upstream length. Buffer this one segment
            // rather than falling back to chunked framing: correctness is more important here and
            // HLS keeps the allocation bounded to a segment.
            byte[] payload;
            try (InputStream stream = out;
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[32 * 1024];
                int count;
                while ((count = stream.read(bytes)) != -1) buffer.write(bytes, 0, count);
                payload = buffer.toByteArray();
            }
            out = new ByteArrayInputStream(payload);
            payloadLength = payload.length;
        }
        // A chunked response makes FFmpeg consider the normal end of a segment premature
        // (the expected size is unknown). It then reconnects with a Range request; serving
        // the segment again from byte zero duplicates TS packets and corrupts both video and
        // audio. Always expose the exact unwrapped size.
        Response response = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "video/mp2t", out, payloadLength);
        response.addHeader("Accept-Ranges", "none");
        response.addHeader("Cache-Control", "no-store, no-transform");
        return response;
    }
}
