package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.utils.KeyUtil;

public class CustomKeyDownLive extends GestureDetector.SimpleOnGestureListener {

    private final GestureDetector detector;
    private final StringBuilder text;
    private final Listener listener;
    private long holdTime;
    private boolean centerLongPressed;

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            listener.onFind(text.toString());
            text.setLength(0);
        }
    };

    public static CustomKeyDownLive create(Context context) {
        return new CustomKeyDownLive(context);
    }

    private CustomKeyDownLive(Context context) {
        this.text = new StringBuilder();
        this.listener = (Listener) context;
        this.detector = new GestureDetector(context, this);
    }

    public boolean onTouchEvent(MotionEvent e) {
        return detector.onTouchEvent(e);
    }

    public boolean hasEvent(KeyEvent event) {
        return KeyUtil.isEnterKey(event) || KeyUtil.isUpKey(event) || KeyUtil.isDownKey(event) || KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event) || KeyUtil.isDigitKey(event) || KeyUtil.isMenuKey(event) || KeyUtil.isMediaPlayPause(event) || event.isLongPress();
    }

    public boolean onKeyDown(KeyEvent event) {
        if (KeyUtil.isEnterKey(event) && centerLongPressed) {
            if (KeyUtil.isActionUp(event)) centerLongPressed = false;
            return true; // release belongs to the long press, not the newly focused channel
        }
        boolean media = event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY
                || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PAUSE;
        if (!media && !listener.dispatch(true)) return false;
        if (media) {
            if (KeyUtil.isActionUp(event)) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY) listener.onMediaPlay(true);
                else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PAUSE) listener.onMediaPlay(false);
                else listener.onPlayPause();
            }
            return true; // consume both halves: MediaSession must not toggle a second time
        }
        if (KeyUtil.isEnterKey(event)) {
            if (KeyUtil.isActionDown(event) && event.getRepeatCount() == 0) centerLongPressed = false;
            boolean held = event.isLongPress() || (KeyUtil.isActionDown(event)
                    && event.getRepeatCount() > 0 && event.getEventTime() - event.getDownTime() >= 500);
            if (held && !centerLongPressed) {
                centerLongPressed = true;
                listener.onChannelList();
            } else if (KeyUtil.isActionUp(event) && !centerLongPressed) listener.onKeyCenter();
            return true;
        }
        check(event);
        return true;
    }

    private void check(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) {
            listener.onSeeking(subTime());
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            listener.onSeeking(addTime());
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) {
            listener.onKeyUp();
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event)) {
            listener.onKeyDown();
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isLeftKey(event)) {
            listener.onKeyLeft(holdTime);
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isRightKey(event)) {
            listener.onKeyRight(holdTime);
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isDigitKey(event)) {
            onKeyDown(event.getKeyCode());
        } else if (KeyUtil.isMediaPlayPause(event)) {
            listener.onPlayPause();
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            listener.onKeyCenter();
        } else if (KeyUtil.isMenuKey(event) || event.isLongPress() && KeyUtil.isEnterKey(event)) {
            listener.onMenu();
        }
    }

    private void onKeyDown(int keyCode) {
        if (text.length() >= 4) return;
        text.append(getNumber(keyCode));
        listener.onShow(text.toString());
        App.post(runnable, 2000);
    }

    @Override
    public boolean onDoubleTap(@NonNull MotionEvent e) {
        if (listener.dispatch(false)) listener.onDoubleTap();
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        if (listener.dispatch(false)) listener.onSingleTap();
        return true;
    }

    private int getNumber(int keyCode) {
        return keyCode >= 144 ? keyCode - 144 : keyCode - 7;
    }

    private long addTime() {
        return holdTime = holdTime + Constant.INTERVAL_SEEK;
    }

    private long subTime() {
        return holdTime = holdTime - Constant.INTERVAL_SEEK;
    }

    public void reset() {
        holdTime = 0;
    }

    public interface Listener {

        boolean dispatch(boolean check);

        void onShow(String number);

        void onFind(String number);

        void onSeeking(long time);

        void onKeyUp();

        void onKeyDown();

        void onKeyLeft(long time);

        void onKeyRight(long time);

        void onKeyCenter();

        void onPlayPause();

        void onMediaPlay(boolean play);

        void onChannelList();

        void onMenu();

        void onSingleTap();

        void onDoubleTap();
    }
}
