package com.tools.inputbridge.server;

import android.annotation.SuppressLint;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reflective input injection reduced from scrcpy 4.1 InputManager.java (Apache-2.0). */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
final class InputInjector {
    private static final int WAIT_FOR_FINISH = 2;
    private final InputManager manager;
    private final Method injectMethod;

    InputInjector() throws ReflectiveOperationException {
        manager = (InputManager) ShellContext.get().getSystemService(ContextNames.INPUT);
        if (manager == null) {
            throw new IllegalStateException("Android InputManager is unavailable");
        }
        injectMethod = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    boolean paste(boolean replaceExisting, boolean appendEnter) {
        if (replaceExisting && !press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)) {
            return false;
        }
        if (!press(KeyEvent.KEYCODE_PASTE)) {
            return false;
        }
        return !appendEnter || press(KeyEvent.KEYCODE_ENTER);
    }

    private boolean press(int keyCode) {
        return press(keyCode, 0);
    }

    private boolean press(int keyCode, int metaState) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        return inject(down) && inject(up);
    }

    private boolean inject(InputEvent event) {
        try {
            return (boolean) injectMethod.invoke(manager, event, WAIT_FOR_FINISH);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
            Log.error("Input injection failed", cause == null ? error : cause);
            return false;
        }
    }

    private static final class ContextNames {
        private static final String INPUT = "input";
    }
}
