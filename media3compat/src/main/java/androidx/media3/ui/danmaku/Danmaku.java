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
package androidx.media3.ui.danmaku;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.graphics.Color;
import android.graphics.Shader;
import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Comparator;

public final class Danmaku {

  public static final Comparator<Danmaku> BY_TIME = (a, b) -> Long.compare(a.timeMs, b.timeMs);
  public static final int TYPE_SCROLL = 1;
  public static final int TYPE_BOTTOM = 4;
  public static final int TYPE_TOP = 5;
  public static final int TYPE_REVERSE = 6;
  public static final int TYPE_POSITIONED = 7;
  public static final int POOL_NORMAL = 0;
  public static final int POOL_SUBTITLE = 1;
  public static final int POOL_SPECIAL = 2;
  public final String text;
  public final long timeMs;
  public final @Type int type;
  public final @ColorInt int color;
  public final float textSizeSp;
  public final int pool;
  public final String userId;
  public final long rowId;
  public final float x;
  public final float y;
  public final long durationMs;
  @Nullable
  public int[] sourceGradientColors;
  float measuredWidth;
  int trackIndex = -1;
  long activatedTimeMs = -1;
  boolean active;
  Shader gradientShader;
  float gradientShaderWidth;
  int gradientShaderColorMode = -1;
  int cachedStrokeColor;

  public Danmaku(String text, long timeMs, @Type int type, @ColorInt int color, float textSizeSp, int pool, String userId, long rowId) {
    this.text = text;
    this.timeMs = timeMs;
    this.type = type;
    this.color = color;
    this.textSizeSp = textSizeSp;
    this.pool = pool;
    this.userId = userId;
    this.rowId = rowId;
    this.x = 0f;
    this.y = 0f;
    this.durationMs = 0;
  }

  public Danmaku(String text, long timeMs, float x, float y, @ColorInt int color, float textSizeSp, long durationMs, int pool, String userId, long rowId) {
    this.text = text;
    this.timeMs = timeMs;
    this.type = TYPE_POSITIONED;
    this.color = color;
    this.textSizeSp = textSizeSp;
    this.pool = pool;
    this.userId = userId;
    this.rowId = rowId;
    this.x = x;
    this.y = y;
    this.durationMs = durationMs;
  }

  public Danmaku(String text, long timeMs, @Type int type, @ColorInt int color, float textSizeSp) {
    this(text, timeMs, type, color, textSizeSp, POOL_NORMAL, "", 0);
  }

  public Danmaku(String text, long timeMs, @Type int type, @ColorInt int color) {
    this(text, timeMs, type, color, 0);
  }

  public Danmaku(String text, long timeMs) {
    this(text, timeMs, TYPE_SCROLL, Color.WHITE, 0);
  }

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({TYPE_SCROLL, TYPE_TOP, TYPE_BOTTOM, TYPE_REVERSE, TYPE_POSITIONED})
  public @interface Type {

  }
}

