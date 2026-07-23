package com.tools.inputbridge.server;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

import java.lang.reflect.Field;

/**
 * Presents the app_process instance as com.android.shell to clipboard AppOps.
 * Adapted and reduced from scrcpy 4.1 FakeContext.java (Apache-2.0).
 */
final class ShellContext extends ContextWrapper {
    static final String PACKAGE_NAME = "com.android.shell";
    private static final ShellContext INSTANCE = new ShellContext();

    static ShellContext get() {
        return INSTANCE;
    }

    private ShellContext() {
        super(AndroidWorkarounds.getSystemContext());
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @TargetApi(31)
    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE_NAME).build();
    }

    @SuppressWarnings("unused")
    public int getDeviceId() {
        return 0;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }

    @SuppressLint("SoonBlockedPrivateApi")
    @Override
    public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        if (service == null) {
            return null;
        }
        if (Context.CLIPBOARD_SERVICE.equals(name) || "semclipboard".equals(name)) {
            try {
                Field context = service.getClass().getDeclaredField("mContext");
                context.setAccessible(true);
                context.set(service, this);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unable to attach shell context to clipboard service", error);
            }
        }
        return service;
    }
}
