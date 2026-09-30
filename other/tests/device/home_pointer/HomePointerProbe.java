import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/** Shell-only mouse injection probe; never packaged into the app. Run via app_process. */
public final class HomePointerProbe {
    private static final Object input;
    private static final Method inject;
    static {
        try {
            Class<?> type;
            try {
                type = Class.forName("android.hardware.input.InputManagerGlobal");
                type.getMethod("getInstance");
            } catch (ClassNotFoundException | NoSuchMethodException legacy) {
                type = Class.forName("android.hardware.input.InputManager");
            }
            input = type.getMethod("getInstance").invoke(null);
            inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void send(long down, int action, float x, float y, int buttons, float wheel) throws Exception {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = buttons != 0 ? 1 : 0;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, wheel);
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{props}, new MotionEvent.PointerCoords[]{coords},
                0, buttons, 1, 1, -1, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            if (!Boolean.TRUE.equals(inject.invoke(input, event, 2))) throw new IllegalStateException("Injection failed");
        } finally {
            event.recycle();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("keyhold")) {
            int code = Integer.parseInt(args[1]);
            int hold = Integer.parseInt(args[2]);
            long down = SystemClock.uptimeMillis();
            inject.invoke(input, new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0,
                    -1, 0, 0, InputDevice.SOURCE_DPAD), 2);
            SystemClock.sleep(hold);
            inject.invoke(input, new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                    code, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD), 2);
        } else if (args[0].equals("wheel")) {
            float axis = Float.parseFloat(args[1]);
            int count = Integer.parseInt(args[2]);
            for (int i = 0; i < count; i++) {
                send(SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL, 960, 550, 0, axis);
                SystemClock.sleep(100);
            }
        } else {
            float x = Float.parseFloat(args[1]);
            float start = Float.parseFloat(args[2]);
            float end = Float.parseFloat(args[3]);
            int steps = Integer.parseInt(args[4]);
            int delay = Integer.parseInt(args[5]);
            int hold = Integer.parseInt(args[6]);
            long down = SystemClock.uptimeMillis();
            send(down, MotionEvent.ACTION_DOWN, x, start, MotionEvent.BUTTON_PRIMARY, 0);
            if (args[0].equals("turn") || args[0].equals("turnleft")) {
                // Leftward movement scrolls a row from its initial (leftmost) position.
                int direction = args[0].equals("turnleft") ? -1 : 1;
                for (int i = 1; i <= 10; i++) {
                    SystemClock.sleep(delay);
                    send(down, MotionEvent.ACTION_MOVE, x + direction * i * 8, start + i, MotionEvent.BUTTON_PRIMARY, 0);
                }
                x += direction * 80;
                start += 10;
            }
            for (int i = 1; i <= steps; i++) {
                SystemClock.sleep(delay);
                send(down, MotionEvent.ACTION_MOVE, x, start + (end - start) * i / steps, MotionEvent.BUTTON_PRIMARY, 0);
            }
            if (args[0].equals("bounce")) {
                // Pause at the edge, then reverse while still holding the same button.
                SystemClock.sleep(500);
                float reverseEnd = end - 200;
                for (int i = 1; i <= 40; i++) {
                    SystemClock.sleep(delay);
                    send(down, MotionEvent.ACTION_MOVE, x, end + (reverseEnd - end) * i / 40, MotionEvent.BUTTON_PRIMARY, 0);
                }
                end = reverseEnd;
            }
            SystemClock.sleep(hold);
            send(down, MotionEvent.ACTION_UP, x, end, 0, 0);
        }
        SystemClock.sleep(700);
    }
}
