package com.tools.inputbridge.server;

import android.annotation.SuppressLint;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Looper;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.util.regex.Pattern;

public final class Main {
    private static final Pattern SOCKET_NAME = Pattern.compile("inputbridge_[0-9a-f]{8}");
    private Main() {
    }

    public static void main(String... args) {
        int status = 0;
        try {
            run(args);
        } catch (Throwable error) {
            Log.error("Server stopped", error);
            status = 1;
        } finally {
            System.exit(status);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 1 || !SOCKET_NAME.matcher(args[0]).matches()) {
            throw new IllegalArgumentException("Invalid server arguments");
        }

        dropRootPrivileges();
        prepareMainLooper();
        AndroidWorkarounds.apply();

        String socketName = args[0];
        Looper mainLooper = Looper.myLooper();
        Thread bridgeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve(socketName, mainLooper);
            }
        }, "InputBridge-Control");
        bridgeThread.start();
        Looper.loop();
        bridgeThread.join(2_000L);
    }

    private static void serve(String socketName, Looper mainLooper) {
        try (LocalServerSocket serverSocket = new LocalServerSocket(socketName);
             LocalSocket socket = serverSocket.accept();
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             BridgeController controller = new BridgeController(input, output)) {
            Log.info("Control client connected");
            controller.run();
        } catch (Throwable error) {
            Log.error("Control session failed", error);
        } finally {
            mainLooper.quit();
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private static void prepareMainLooper() throws ReflectiveOperationException {
        Looper.prepare();
        synchronized (Looper.class) {
            Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
            mainLooper.setAccessible(true);
            mainLooper.set(null, Looper.myLooper());
        }
    }

    @SuppressWarnings("deprecation")
    private static void dropRootPrivileges() throws Exception {
        if (Os.getuid() == 0) {
            Os.setuid(2000);
            if (Os.getuid() != 2000) {
                throw new IllegalStateException("Unable to switch app_process to the shell UID");
            }
        }
    }

}
