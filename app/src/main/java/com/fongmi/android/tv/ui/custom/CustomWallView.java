package com.fongmi.android.tv.ui.custom;

import android.content.Context;
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

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewWallBinding;
import com.fongmi.android.tv.event.ConfigEvent;
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
    private static final int TYPE_RES = 0;
    private static final int TYPE_GIF = 1;
    private static final int TYPE_VIDEO = 2;
    private ViewWallBinding binding;
    private GifDrawable drawable;
    private PlayerView video;
    private ExoPlayer player;
    private boolean resumed;
    /** Only media loading is asynchronous; a late decode must not replace a newer wallpaper. */
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
        if (binding == null) return;
        stop();
        final int generation = ++wallGeneration;
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (type == TYPE_RES && wall > 0 && wall < WALL_PAPERS.length) {
            // Built-in art needs no colour sampling, cache or background task.
            binding.image.setImageResource(WALL_PAPERS[wall]);
            return;
        }
        Task.execute(() -> {
            if (type == TYPE_VIDEO) {
                Drawable poster = cache();
                App.post(() -> {
                    if (binding == null || generation != wallGeneration) return;
                    loadVideo(Path.wall(wall), poster);
                });
                return;
            }
            GifDrawable gifDraw = type == TYPE_GIF ? gif(Path.wall(wall)) : null;
            Drawable decoded = gifDraw != null ? gifDraw : cache();
            App.post(() -> {
                if (binding == null || generation != wallGeneration) {
                    if (gifDraw != null) gifDraw.recycle();
                    return;
                }
                if (gifDraw != null) {
                    drawable = gifDraw;
                    binding.image.setImageDrawable(gifDraw);
                    if (!resumed) gifDraw.pause();
                } else if (decoded != null) {
                    binding.image.setImageDrawable(decoded);
                } else {
                    binding.image.setImageResource(R.drawable.wallpaper_1);
                }
            });
        });
    }

    private void stop() {
        if (player != null) {
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

    private void loadVideo(File file, Drawable poster) {
        ensurePlayer();
        ensureVideoView();
        video.setPlayer(resumed ? player : null);
        video.setVisibility(VISIBLE);
        binding.image.setImageDrawable(poster);
        player.setPlayWhenReady(resumed);
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
        player.setPlayWhenReady(false);
        player.mute();
    }

    private void ensureVideoView() {
        if (video != null) return;
        video = (PlayerView) LayoutInflater.from(getContext()).inflate(R.layout.view_wall_video, this, false);
        // Video, like image/GIF art, stays below the fixed scrim defined by view_wall.xml.
        addView(video, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private boolean hasVideo() {
        return player != null && video != null && video.getVisibility() == VISIBLE && player.getMediaItemCount() > 0;
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        resumed = true;
        if (drawable != null) drawable.start();
        if (!hasVideo()) return;
        video.setPlayer(player);
        player.play();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        resumed = false;
        if (drawable != null) drawable.pause();
        if (!hasVideo()) return;
        video.setPlayer(null);
        player.pause();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().unregister(this);
        ++wallGeneration;
        resumed = false;
        if (drawable != null) drawable.recycle();
        if (video != null) removeView(video);
        if (player != null) player.release();
        drawable = null;
        binding = null;
        player = null;
        video = null;
    }
}
