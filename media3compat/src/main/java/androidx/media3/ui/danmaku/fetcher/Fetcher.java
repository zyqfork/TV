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

import android.net.Uri;
import androidx.media3.ui.danmaku.Danmaku;
import java.io.IOException;
import java.util.List;
import okhttp3.OkHttpClient;

public interface Fetcher {

  boolean accepts(Uri uri);

  Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException;

  interface Session {

    int segmentCount();

    default int segmentDurationMs() {
      return 60_000;
    }

    List<Danmaku> fetchSegment(int segNum) throws IOException;

    default void release() {
    }
  }
}
