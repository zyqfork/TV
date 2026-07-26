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

public final class YoukuParser implements Parser {

  public static final YoukuParser INSTANCE = new YoukuParser();

  @Nullable
  private static Danmaku parseItem(JSONObject item) {
    try {
      long timeMs = (long) item.getDouble("playat");
      String text = item.optString("content", "");
      if (text.isEmpty()) {
        return null;
      }
      int color = 0xFFFFFFFF;
      String propsJson = item.optString("propertis", "");
      int[] sourceGradientColors = null;
      if (!propsJson.isEmpty()) {
        try {
          JSONObject props = new JSONObject(propsJson);
          long rawColor = Math.round(props.optDouble("color", 0xFFFFFF));
          color = 0xFF000000 | (int) (rawColor & 0xFFFFFF);
          sourceGradientColors = ParserUtil.parseGradientDecimalColors(props.optJSONArray("gradientColors"));
          if (sourceGradientColors != null) {
            color = sourceGradientColors[0];
          }
        } catch (JSONException ignored) {
        }
      }
      Danmaku danmaku = new Danmaku(text, timeMs, Danmaku.TYPE_SCROLL, color, 0f);
      danmaku.sourceGradientColors = sourceGradientColors;
      return danmaku;
    } catch (JSONException e) {
      return null;
    }
  }

  @Override
  public boolean sniff(InputStream is, int sniffLength) throws IOException {
    String header = ParserUtil.readSniffHeader(is, sniffLength);
    return header.contains("\"playat\"") || header.contains("\"propertis\"");
  }

  @Override
  public List<Danmaku> parse(InputStream inputStream) throws IOException {
    String json = ParserUtil.readToString(inputStream);
    List<Danmaku> result = new ArrayList<>();
    try {
      JSONArray list = new JSONObject(json).getJSONObject("data").getJSONArray("result");
      for (int i = 0; i < list.length(); i++) {
        @Nullable Danmaku danmaku = parseItem(list.getJSONObject(i));
        if (danmaku != null) {
          result.add(danmaku);
        }
      }
    } catch (JSONException e) {
      throw new IOException("Failed to parse Youku danmaku JSON", e);
    }
    Collections.sort(result, Danmaku.BY_TIME);
    return result;
  }
}

