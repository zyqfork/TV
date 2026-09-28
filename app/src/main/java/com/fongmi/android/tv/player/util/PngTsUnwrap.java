package com.fongmi.android.tv.player.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Unwraps MPEG-TS segments hidden behind a small PNG prefix. */
public final class PngTsUnwrap {

    private static final byte[] PNG_SIG = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] TS_RAW = "TS_RAW".getBytes(StandardCharsets.US_ASCII);
    private static final int TS_PACKET = 188;
    private static final int PROBE = 4096;

    private PngTsUnwrap() {
    }

    public static boolean looksLikePng(byte[] data) {
        if (data == null || data.length < PNG_SIG.length) return false;
        for (int i = 0; i < PNG_SIG.length; i++) if (data[i] != PNG_SIG[i]) return false;
        return true;
    }

    /** Returns the TS offset, -1 for an unrecognized PNG, or 0 for pass-through input. */
    public static int findTsOffset(byte[] data) {
        if (data == null || data.length < PNG_SIG.length) return 0;
        if (!looksLikePng(data)) return 0;
        int from = indexOf(data, TS_RAW);
        if (from >= 0) {
            from += TS_RAW.length;
            if (from < data.length && data[from] == 0) from++;
            int sync = findTsSync(data, from);
            return sync >= 0 ? sync : from;
        }
        return findTsSync(data, PNG_SIG.length);
    }

    public static InputStream unwrap(byte[] head, InputStream rest) throws IOException {
        int offset = findTsOffset(head);
        if (offset <= 0) return new SequenceInputStream(new ByteArrayInputStream(head), rest);
        if (offset >= head.length) {
            long skip = offset - head.length;
            while (skip > 0) {
                long count = rest.skip(skip);
                if (count <= 0) break;
                skip -= count;
            }
            return rest;
        }
        return new SequenceInputStream(new ByteArrayInputStream(head, offset, head.length - offset), rest);
    }

    public static byte[] readProbe(InputStream in, int max) throws IOException {
        byte[] data = new byte[max];
        int offset = 0;
        while (offset < max) {
            int count = in.read(data, offset, max - offset);
            if (count < 0) break;
            offset += count;
        }
        return offset == max ? data : Arrays.copyOf(data, offset);
    }

    public static int probeSize() {
        return PROBE;
    }

    private static int findTsSync(byte[] data, int start) {
        // The body reads data[i + TS_PACKET * 2], so the last verifiable index is
        // length - TS_PACKET * 2 - 1. Stopping at length - TS_PACKET * 2 instead lets the final
        // iteration read one byte past the end: on a buffer whose TS begins exactly at
        // length - TS_PACKET * 2 the two shorter probes pass, the third throws
        // ArrayIndexOutOfBoundsException, and -1 is never returned. That made every caller's
        // fallback (the `from` offset below, and the -1 pass-through documented on
        // findTsOffset) unreachable, so a segment that should have streamed through raw
        // instead failed: TsRaw answers "segment unavailable" and MpvHlsPngTs.prepare
        // swallows the throw and leaves the playlist wrapped.
        int limit = Math.min(data.length - TS_PACKET * 2 - 1, start + PROBE);
        for (int i = Math.max(0, start); i <= limit; i++) {
            if (data[i] == 0x47 && data[i + TS_PACKET] == 0x47 && data[i + TS_PACKET * 2] == 0x47) return i;
        }
        return -1;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
