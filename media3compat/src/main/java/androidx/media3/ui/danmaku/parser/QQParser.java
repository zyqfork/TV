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
import androidx.media3.ui.danmaku.Danmaku;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class QQParser implements Parser {

  public static final QQParser INSTANCE = new QQParser();

  @Nullable
  private static Danmaku parseItem(JSONObject item) {
    try {
      long timeMs = Long.parseLong(item.getString("time_offset"));
      String text = item.optString("content", "");
      if (text.isEmpty()) {
        return null;
      }
      int color = 0xFFFFFFFF;
      int[] sourceGradientColors = null;
      String styleJson = item.optString("content_style", "");
      if (!styleJson.isEmpty()) {
        try {
          JSONObject style = new JSONObject(styleJson);
          color = ParserUtil.parseHexColor(style.optString("color", ""), color);
          sourceGradientColors = ParserUtil.parseGradientHexColors(style.optJSONArray("gradient_colors"));
          if (sourceGradientColors != null) {
            color = sourceGradientColors[0];
          }
        } catch (JSONException | NumberFormatException ignored) {
        }
      }
      Danmaku danmaku = new Danmaku(text, timeMs, Danmaku.TYPE_SCROLL, color, 0f);
      danmaku.sourceGradientColors = sourceGradientColors;
      return danmaku;
    } catch (JSONException | NumberFormatException e) {
      return null;
    }
  }

  @Override
  public boolean sniff(InputStream is, int sniffLength) throws IOException {
    String header = ParserUtil.readSniffHeader(is, sniffLength);
    return header.contains("\"barrage_list\"");
  }

  @Override
  public List<Danmaku> parse(InputStream inputStream) throws IOException {
    String json = ParserUtil.readToString(inputStream);
    List<Danmaku> result = new ArrayList<>();
    try {
      JSONArray list = new JSONObject(json).getJSONArray("barrage_list");
      for (int i = 0; i < list.length(); i++) {
        @Nullable Danmaku danmaku = parseItem(list.getJSONObject(i));
        if (danmaku != null) {
          result.add(danmaku);
        }
      }
    } catch (JSONException e) {
      throw new IOException("Failed to parse QQ danmaku JSON", e);
    }
    Collections.sort(result, Danmaku.BY_TIME);
    return result;
  }
}

