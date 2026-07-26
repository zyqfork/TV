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
package androidx.media3.ui.danmaku.bean;

import android.graphics.Paint;

public final class ShadowPaintState {

  private int color;
  private float radius = -1f;
  private boolean set;

  public void apply(Paint paint, int color, float radius) {
    if (this.color != color || this.radius != radius) {
      paint.setShadowLayer(radius, 0, 0, color);
      this.color = color;
      this.radius = radius;
      set = true;
    }
  }

  public void clearIfSet(Paint paint) {
    if (set) {
      paint.clearShadowLayer();
      set = false;
      radius = -1f;
    }
  }

  public void invalidate() {
    radius = -1f;
  }
}

