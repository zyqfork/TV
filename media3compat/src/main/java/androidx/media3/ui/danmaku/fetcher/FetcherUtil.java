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
package androidx.media3.ui.danmaku.fetcher;

import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class FetcherUtil {

  static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

  static ImmutableMap<String, String> headers(String origin, String referer) {
    return ImmutableMap.of("Origin", origin, "Referer", referer, "User-Agent", UA);
  }

  static String md5Hex(String input) throws IOException {
    try {
      return Util.toHexString(MessageDigest.getInstance("MD5").digest(Util.getUtf8Bytes(input)));
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("MD5 not available", e);
    }
  }

  static String fetchString(@Nullable OkHttpClient client, String url, Map<String, String> headers) throws IOException {
    return Util.fromUtf8Bytes(fetchBytes(client, url, headers));
  }

  static byte[] fetchBytes(@Nullable OkHttpClient client, String url, Map<String, String> headers) throws IOException {
    if (client == null) {
      throw new IOException("OkHttpClient not configured; call DanmakuController.setOkHttpClient()");
    }
    Request.Builder rb = new Request.Builder().url(url);
    for (Map.Entry<String, String> e : headers.entrySet()) {
      rb.header(e.getKey(), e.getValue());
    }
    try (Response response = client.newCall(rb.build()).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("HTTP " + response.code() + " for " + url);
      }
      return response.body().bytes();
    }
  }
}

