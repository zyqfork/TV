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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TxtParser implements Parser {

  public static final TxtParser INSTANCE = new TxtParser();
  private static final Pattern LINE_PATTERN = Pattern.compile("\\[([^\\]]+)\\](.*)");

  @Nullable
  public static Danmaku parseLine(String line) {
    Matcher matcher = LINE_PATTERN.matcher(line);
    if (!matcher.find()) {
      return null;
    }
    String pAttr = matcher.group(1);
    String text = matcher.group(2);
    if (pAttr == null || text == null || text.isEmpty()) {
      return null;
    }
    return ParserUtil.parsePAttr(pAttr, text);
  }

  @Override
  public boolean sniff(InputStream is, int sniffLength) throws IOException {
    String header = ParserUtil.readSniffHeader(is, sniffLength);
    for (String line : header.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (trimmed.startsWith("<")) {
        return false;
      }
      return LINE_PATTERN.matcher(trimmed).find();
    }
    return false;
  }

  @Override
  public List<Danmaku> parse(InputStream inputStream) throws IOException {
    List<Danmaku> result = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        Danmaku danmaku = parseLine(line);
        if (danmaku != null) {
          result.add(danmaku);
        }
      }
    }
    Collections.sort(result, Danmaku.BY_TIME);
    return result;
  }
}

