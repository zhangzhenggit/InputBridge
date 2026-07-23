package com.tools.inputbridge.server;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Creates the small amount of application context expected by Android system-service wrappers.
 * Adapted and reduced from scrcpy 4.1 Workarounds.java (Apache-2.0).
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi"})
final class AndroidWorkarounds {
    private static final Class<?> ACTIVITY_THREAD_CLASS;
    private static final Object ACTIVITY_THREAD;

    static {
        try {
            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
            constructor.setAccessible(true);
            ACTIVITY_THREAD = constructor.newInstance();

            Field currentThread = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
            currentThread.setAccessible(true);
            currentThread.set(null, ACTIVITY_THREAD);

            Field systemThread = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread");
            systemThread.setAccessible(true);
            systemThread.setBoolean(ACTIVITY_THREAD, true);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private AndroidWorkarounds() {
    }

    static void apply() {
        if (Build.VERSION.SDK_INT >= 31) {
            fillConfigurationController();
        }
        fillApplicationInfo();
        fillApplicationContext();
    }

    static Context getSystemContext() {
        try {
            Method method = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
            return (Context) method.invoke(ACTIVITY_THREAD);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unable to obtain Android system context", error);
        }
    }

    private static void fillApplicationInfo() {
        try {
            Class<?> bindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> constructor = bindDataClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object bindData = constructor.newInstance();

            ApplicationInfo info = new ApplicationInfo();
            info.packageName = ShellContext.PACKAGE_NAME;
            Field appInfo = bindDataClass.getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            appInfo.set(bindData, info);

            Field boundApplication = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
            boundApplication.setAccessible(true);
            boundApplication.set(ACTIVITY_THREAD, bindData);
        } catch (Throwable error) {
            Log.error("Unable to fill application info", error);
        }
    }

    private static void fillApplicationContext() {
        try {
            Application application = Instrumentation.newApplication(Application.class, ShellContext.get());
            Field initialApplication = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
            initialApplication.setAccessible(true);
            initialApplication.set(ACTIVITY_THREAD, application);
        } catch (Throwable error) {
            Log.error("Unable to fill application context", error);
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> threadInternalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> constructor = controllerClass.getDeclaredConstructor(threadInternalClass);
            constructor.setAccessible(true);
            Object controller = constructor.newInstance(ACTIVITY_THREAD);

            Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(ACTIVITY_THREAD, controller);
        } catch (Throwable error) {
            Log.error("Unable to fill configuration controller", error);
        }
    }
}
