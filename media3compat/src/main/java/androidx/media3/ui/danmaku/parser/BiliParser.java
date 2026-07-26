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
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

public final class BiliParser implements Parser {

  public static final BiliParser INSTANCE = new BiliParser();

  @Override
  public boolean sniff(InputStream is, int sniffLength) throws IOException {
    String trimmed = ParserUtil.skipXmlDeclaration(ParserUtil.readSniffHeader(is, sniffLength));
    return trimmed.startsWith("<i>") || trimmed.startsWith("<i ");
  }

  @Override
  public List<Danmaku> parse(InputStream inputStream) throws IOException {
    List<Danmaku> result = new ArrayList<>();
    try {
      XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
      factory.setNamespaceAware(false);
      XmlPullParser parser = factory.newPullParser();
      parser.setInput(inputStream, "UTF-8");
      int eventType = parser.getEventType();
      while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG && "d".equals(parser.getName())) {
          String p = parser.getAttributeValue(null, "p");
          String text = parser.nextText();
          if (p != null && !text.isEmpty()) {
            @Nullable Danmaku danmaku = ParserUtil.parsePAttr(p, text);
            if (danmaku != null) {
              result.add(danmaku);
            }
          }
        }
        eventType = parser.next();
      }
    } catch (XmlPullParserException e) {
      throw new IOException("Failed to parse Bilibili XML", e);
    }
    Collections.sort(result, Danmaku.BY_TIME);
    return result;
  }
}

