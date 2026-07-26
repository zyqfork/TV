/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package androidx.media3.ui.danmaku.parser;

import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import androidx.media3.ui.danmaku.Danmaku;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;

final class ParserUtil {

  static final float SMALL_SP = 12f;
  static final float DEFAULT_SP = 0f;
  static final float LARGE_SP = 18f;
  private static final int BILI_SIZE_SMALL = 18;
  private static final int BILI_SIZE_LARGE = 36;

  static String readSniffHeader(InputStream is, int sniffLength) throws IOException {
    byte[] buf = new byte[sniffLength];
    int read = 0;
    int n;
    while (read < sniffLength && (n = is.read(buf, read, sniffLength - read)) != -1) {
      read += n;
    }
    int start = 0;
    if (read >= 3
        && (buf[0] & 0xFF) == 0xEF
        && (buf[1] & 0xFF) == 0xBB
        && (buf[2] & 0xFF) == 0xBF) {
      start = 3;
    }
    return new String(buf, start, read - start, StandardCharsets.UTF_8);
  }

  static String skipXmlDeclaration(String header) {
    String trimmed = header.trim();
    if (trimmed.startsWith("<?xml")) {
      int close = trimmed.indexOf("?>");
      if (close >= 0) {
        trimmed = trimmed.substring(close + 2).trim();
      }
    }
    return trimmed;
  }

  @Nullable
  static Danmaku parsePAttr(String pAttr, String text) {
    String[] parts = pAttr.split(",");
    if (parts.length < 4) {
      return null;
    }
    float timeSec;
    int mode;
    int rawSize;
    long rawColor;
    try {
      timeSec = Float.parseFloat(parts[0]);
      mode = Integer.parseInt(parts[1]);
      rawSize = Integer.parseInt(parts[2]);
      rawColor = Long.parseLong(parts[3]);
    } catch (NumberFormatException e) {
      return null;
    }
    long timeMs = (long) (timeSec * 1000);
    int color = 0xFF000000 | (int) (rawColor & 0xFFFFFF);
    float textSizeSp = mapBiliTextSize(rawSize);
    if (mode == 7) {
      return parsePositionedDanmaku(text, timeMs, color, textSizeSp);
    }
    int type = mapBiliMode(mode);
    if (type < 0) {
      return null;
    }
    Danmaku danmaku = new Danmaku(text, timeMs, type, color, textSizeSp);
    if (parts.length >= 9 && !parts[8].isEmpty()) {
      danmaku.sourceGradientColors = parseHexColorStrings(parts[8].split(":"));
    }
    return danmaku;
  }

  static int mapBiliMode(int mode) {
    switch (mode) {
      case 1:
      case 2:
      case 3:
        return Danmaku.TYPE_SCROLL;
      case 4:
        return Danmaku.TYPE_BOTTOM;
      case 5:
        return Danmaku.TYPE_TOP;
      case 6:
        return Danmaku.TYPE_REVERSE;
      default:
        return -1;
    }
  }

  static float mapBiliTextSize(int rawSize) {
    if (rawSize <= BILI_SIZE_SMALL) {
      return SMALL_SP;
    }
    if (rawSize >= BILI_SIZE_LARGE) {
      return LARGE_SP;
    }
    return DEFAULT_SP;
  }

  @Nullable
  static Danmaku parsePositionedDanmaku(String json, long timeMs, int color, float textSizeSp) {
    try {
      JSONArray arr = new JSONArray(json);
      if (arr.length() < 7) {
        return null;
      }
      String posText = arr.optString(6, "");
      if (posText.isEmpty()) {
        return null;
      }
      float x = (float) (arr.optDouble(0, 336) / 672.0);
      float y = (float) (arr.optDouble(1, 219) / 438.0);
      double rawDuration = arr.optDouble(3, 0);
      long durationMs = rawDuration > 0 ? (long) (rawDuration * 1000) : 0;
      double rawFontSize = arr.optDouble(4, 0);
      if (rawFontSize > 0) {
        textSizeSp = mapBiliTextSize((int) rawFontSize);
      }
      return new Danmaku(posText, timeMs, x, y, color, textSizeSp, durationMs, Danmaku.POOL_SPECIAL, "", 0);
    } catch (JSONException e) {
      return null;
    }
  }

  static int parseHexColor(@Nullable String hex, int defaultColor) {
    if (hex == null || hex.isEmpty()) {
      return defaultColor;
    }
    String s = hex.trim();
    if (s.startsWith("#")) {
      s = s.substring(1);
    }
    if (s.length() != 6) {
      return defaultColor;
    }
    try {
      return 0xFF000000 | Integer.parseInt(s, 16);
    } catch (NumberFormatException e) {
      return defaultColor;
    }
  }

  @Nullable
  static int[] parseHexColorStrings(@Nullable String[] hexStrings) {
    if (hexStrings == null || hexStrings.length < 2) {
      return null;
    }
    int[] tmp = new int[hexStrings.length];
    int valid = 0;
    for (String hex : hexStrings) {
      int parsed = parseHexColor(hex, 0);
      if (parsed != 0) {
        tmp[valid++] = parsed;
      }
    }
    return valid >= 2 ? Arrays.copyOf(tmp, valid) : null;
  }

  @Nullable
  static int[] parseGradientHexColors(@Nullable JSONArray arr) {
    if (arr == null || arr.length() < 2) {
      return null;
    }
    String[] hexStrings = new String[arr.length()];
    for (int i = 0; i < arr.length(); i++) {
      hexStrings[i] = arr.optString(i, "");
    }
    return parseHexColorStrings(hexStrings);
  }

  @Nullable
  static int[] parseGradientDecimalColors(@Nullable JSONArray arr) {
    if (arr == null || arr.length() < 2) {
      return null;
    }
    int[] tmp = new int[arr.length()];
    int valid = 0;
    for (int i = 0; i < arr.length(); i++) {
      try {
        long v = Math.round(arr.getDouble(i));
        tmp[valid++] = 0xFF000000 | (int) (v & 0xFFFFFF);
      } catch (JSONException ignored) {
      }
    }
    return valid >= 2 ? Arrays.copyOf(tmp, valid) : null;
  }

  static String readToString(InputStream is) throws IOException {
    return Util.fromUtf8Bytes(ByteStreams.toByteArray(is));
  }
}

