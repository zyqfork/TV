package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.palette.graphics.Palette;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewWallBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;

import pl.droidsonroids.gif.GifDrawable;

public class CustomWallView extends FrameLayout implements DefaultLifecycleObserver {

    private static final int[] WALL_PAPERS = {0, R.drawable.wallpaper_1, R.drawable.wallpaper_2, R.drawable.wallpaper_3, R.drawable.wallpaper_4};
    private static final int[] WALL_COLORS = {0, 0xFF40C090, 0xFF4870E0, 0xFF48B0C0, 0xFF404040};
    /**
     * Contrast to keep for the white text that every layout draws straight onto the wallpaper.
     * 6:1 leaves room for the translucent white cards layered on top of it (a 10% white card still
     * lands above 4.5:1) while keeping the scrim as light as possible.
     */
    private static final double TARGET_CONTRAST = 6.0;
    private static final int MAX_SCRIM = 204;
    private static final int TYPE_RES = 0;
    private static final int TYPE_GIF = 1;
    private static final int TYPE_VIDEO = 2;
    private ViewWallBinding binding;
    private GifDrawable drawable;
    private PlayerView video;
    private ExoPlayer player;
    /** Incremented per refresh so a slower earlier decode cannot overwrite a newer wallpaper. */
    private int wallGeneration;

    public CustomWallView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        binding = ViewWallBinding.inflate(LayoutInflater.from(getContext()), this, true);
        ((ComponentActivity) getContext()).getLifecycle().addObserver(this);
        refresh();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) refresh();
    }

    private void refresh() {
        stop();
        // Built-in wallpapers are cheap; custom image/gif decode + Palette must leave
        // the main thread or every activity enter pays for it (high input latency).
        // Wallpaper switches can overlap, so a task may only apply its result while it is still
        // the newest request; otherwise the slower of two switches wins and the wrong wall shows.
        final int generation = ++wallGeneration;
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (isBuiltIn(wall, type)) {
            loadRes(WALL_PAPERS[wall]);
            applyWallColor(getWallColor());
            return;
        }
        Task.execute(() -> {
            if (type == TYPE_VIDEO) {
                int color = getWallColor();
                Drawable poster = cache();
                App.post(() -> {
                    if (binding == null || generation != wallGeneration) return;
                    loadVideo(Path.wall(wall), poster);
                    applyWallColor(color);
                });
                return;
            }
            GifDrawable gifDraw = type == TYPE_GIF ? gif(Path.wall(wall)) : null;
            Drawable decoded = gifDraw != null ? gifDraw : cache();
            int color = getWallColor();
            App.post(() -> {
                if (binding == null || generation != wallGeneration) {
                    // Never leak a decoded GIF that lost the race.
                    if (gifDraw != null) gifDraw.recycle();
                    return;
                }
                if (gifDraw != null) {
                    drawable = gifDraw;
                    binding.image.setImageDrawable(gifDraw);
                } else if (decoded != null) {
                    binding.image.setImageDrawable(decoded);
                } else {
                    binding.image.setImageResource(R.drawable.wallpaper_1);
                }
                applyWallColor(color);
            });
        });
    }

    private void applyWallColor(int color) {
        applyScrim(color);
        applyThemeColor(color);
    }

    /**
     * Darken the wallpaper just enough for the white text on top of it to stay readable. A scrim
     * beats shipping darker art because the wallpaper can be any photo the user picked, and it
     * stays out of the way for the wallpapers that were already dark enough.
     */
    private void applyScrim(int color) {
        binding.scrim.setBackgroundColor(Color.argb(scrimFor(color), 0, 0, 0));
    }

    private int scrimFor(int color) {
        double target = 1.05 / TARGET_CONTRAST - 0.05;
        int alpha = 0;
        while (alpha < MAX_SCRIM && luminance(darken(color, alpha)) > target) alpha += 4;
        return alpha;
    }

    private static int darken(int color, int alpha) {
        int keep = 255 - alpha;
        return Color.rgb(Color.red(color) * keep / 255, Color.green(color) * keep / 255, Color.blue(color) * keep / 255);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int value) {
        double v = value / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private void applyThemeColor(int newColor) {
        int oldColor = Setting.getWallColor();
        if (newColor == oldColor) return;
        Setting.putWallColor(newColor);
        if (Setting.getThemeColor() == 0) RefreshEvent.theme();
    }

    private void stop() {
        if (player != null && player.isPlaying()) {
            player.stop();
            player.clearMediaItems();
        }
        if (video != null) {
            video.setPlayer(null);
            video.setVisibility(GONE);
        }
        if (drawable != null) {
            drawable.stop();
            drawable.recycle();
            drawable = null;
        }
    }

    private void loadRes(int resId) {
        binding.image.setImageResource(resId);
    }

    private void loadVideo(File file, Drawable poster) {
        ensurePlayer();
        ensureVideoView();
        video.setPlayer(player);
        video.setVisibility(VISIBLE);
        binding.image.setImageDrawable(poster);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
    }

    private Drawable cache() {
        File file = Path.wallCache();
        return file.exists() ? Drawable.createFromPath(file.getAbsolutePath()) : null;
    }

    private GifDrawable gif(File file) {
        try {
            return new GifDrawable(file);
        } catch (IOException e) {
            return null;
        }
    }

    private void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(getContext()).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setPlayWhenReady(true);
        player.mute();
    }

    private void ensureVideoView() {
        if (video != null) return;
        video = (PlayerView) LayoutInflater.from(getContext()).inflate(R.layout.view_wall_video, this, false);
        // Insert below the scrim so a video wallpaper gets darkened like any other wallpaper.
        addView(video, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private boolean hasVideo() {
        return player != null && video != null && video.getVisibility() == VISIBLE && player.getMediaItemCount() > 0;
    }

    private int getWallColor() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (isBuiltIn(wall, type)) return WALL_COLORS[wall];
        File file = Path.wallCache();
        return file.exists() ? paletteColor(file) : WALL_COLORS[1];
    }

    private int paletteColor(File file) {
        Bitmap bitmap = decodeBitmap(file);
        if (bitmap == null) return WALL_COLORS[1];
        Palette palette = Palette.from(bitmap).maximumColorCount(8).generate();
        bitmap.recycle();
        return swatchColor(palette);
    }

    private Bitmap decodeBitmap(File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 8;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private int swatchColor(Palette palette) {
        Palette.Swatch swatch = palette.getVibrantSwatch();
        if (swatch == null) swatch = palette.getDominantSwatch();
        return swatch != null ? swatch.getRgb() : WALL_COLORS[1];
    }

    private boolean isBuiltIn(int wall, int type) {
        return type == TYPE_RES && wall > 0 && wall < WALL_PAPERS.length;
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (drawable != null) drawable.start();
        if (!hasVideo()) return;
        video.setPlayer(player);
        player.play();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        if (drawable != null) drawable.pause();
        if (!hasVideo()) return;
        video.setPlayer(null);
        player.pause();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().unregister(this);
        if (drawable != null) drawable.recycle();
        if (video != null) removeView(video);
        if (player != null) player.release();
        drawable = null;
        binding = null;
        player = null;
        video = null;
    }
}
