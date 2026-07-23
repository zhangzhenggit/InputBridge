package com.tools.inputbridge.server;

final class Log {
    private static final String PREFIX = "[InputBridge] ";

    private Log() {
    }

    static void info(String message) {
        System.err.println(PREFIX + message);
    }

    static void error(String message, Throwable error) {
        System.err.println(PREFIX + message + ": " + error.getClass().getSimpleName());
    }
}
