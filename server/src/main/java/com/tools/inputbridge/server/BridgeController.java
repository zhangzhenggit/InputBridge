package com.tools.inputbridge.server;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class BridgeController implements AutoCloseable {
    private static final String SENSITIVE_CLIP_KEY = "android.content.extra.IS_SENSITIVE";
    private static final String CLIP_SESSION_KEY = "com.tools.inputbridge.extra.SESSION";

    private final ClipboardManager clipboard;
    private final InputInjector inputInjector;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final String clipboardSession = UUID.randomUUID().toString();
    private final Handler clipboardHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong clipboardSequence = new AtomicLong();
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    requestClipboardUpdate();
                }
            };
    private final Runnable clipboardUpdateTask = new Runnable() {
        @Override
        public void run() {
            publishCurrentClipboardSafely(false);
        }
    };
    private final Runnable clipboardSnapshotTask = new Runnable() {
        @Override
        public void run() {
            publishCurrentClipboardSafely(true);
        }
    };
    private volatile boolean closed;
    private boolean monitoring;
    private boolean hasPublishedClipboard;
    private String lastPublishedClipboard;

    BridgeController(DataInputStream input, DataOutputStream output) throws ReflectiveOperationException {
        this.input = input;
        this.output = output;
        clipboard = (ClipboardManager) ShellContext.get().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            throw new IllegalStateException("Android ClipboardManager is unavailable");
        }
        inputInjector = new InputInjector();
    }

    void run() throws IOException {
        Protocol.writeHello(output, Build.VERSION.SDK_INT, Build.MODEL == null ? "Android" : Build.MODEL);
        startClipboardMonitoring();

        Protocol.ClientMessage message;
        while (!closed && (message = Protocol.readClientMessage(input)) != null) {
            switch (message.type) {
                case Protocol.CLIENT_PASTE_TEXT:
                    handlePaste(message);
                    break;
                case Protocol.CLIENT_PING:
                    Protocol.writePong(output, message.requestId);
                    break;
                case Protocol.CLIENT_CLOSE:
                    return;
                case Protocol.CLIENT_GET_CLIPBOARD:
                    requestClipboardSnapshot();
                    break;
                default:
                    throw new IOException("Unsupported message type");
            }
        }
    }

    private void startClipboardMonitoring() {
        monitoring = true;
        clipboard.addPrimaryClipChangedListener(clipboardListener);
        requestClipboardSnapshot();
    }

    private void requestClipboardUpdate() {
        if (!closed) {
            clipboardHandler.removeCallbacks(clipboardUpdateTask);
            clipboardHandler.post(clipboardUpdateTask);
        }
    }

    private void requestClipboardSnapshot() {
        if (!closed) {
            clipboardHandler.removeCallbacks(clipboardSnapshotTask);
            clipboardHandler.post(clipboardSnapshotTask);
        }
    }

    private void handlePaste(Protocol.ClientMessage message) throws IOException {
        if (message.text == null || message.text.isEmpty()) {
            Protocol.writeAck(output, message.requestId, false, "Input text is empty");
            return;
        }

        try {
            ClipData clip = ClipData.newPlainText("InputBridge", message.text);
            PersistableBundle extras = clip.getDescription().getExtras();
            if (extras == null) {
                extras = new PersistableBundle();
            }
            extras.putBoolean(Build.VERSION.SDK_INT >= 33 ? ClipDescription.EXTRA_IS_SENSITIVE : SENSITIVE_CLIP_KEY, true);
            extras.putString(CLIP_SESSION_KEY, clipboardSession);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);

            boolean injected = inputInjector.paste(message.replaceExisting, message.appendEnter);
            Protocol.writeAck(output, message.requestId, injected,
                    injected ? "Text delivered to the focused control" : "Android rejected the paste input event");
        } catch (RuntimeException error) {
            Log.error("Paste request failed", error);
            Protocol.writeAck(output, message.requestId, false, "Unable to set the device clipboard or inject paste");
        }
    }

    private void publishCurrentClipboardSafely(boolean force) {
        try {
            publishCurrentClipboard(force);
        } catch (IOException error) {
            closed = true;
        } catch (RuntimeException error) {
            Log.error("Unable to read the device clipboard", error);
        }
    }

    private void publishCurrentClipboard(boolean force) throws IOException {
        ClipData clip = clipboard.getPrimaryClip();
        String text = readClipboardText(clip);
        if (!shouldPublishClipboard(clip, text, force)) {
            return;
        }
        if (!Protocol.clipboardFits(text)) {
            Protocol.writeError(output, "Device clipboard text exceeds 1 MB and was not transferred");
            return;
        }
        Protocol.writeClipboard(output, clipboardSequence.incrementAndGet(), text);
        lastPublishedClipboard = text;
        hasPublishedClipboard = true;
    }

    private boolean shouldPublishClipboard(ClipData clip, String text, boolean force) {
        if (force) {
            return true;
        }
        if (isInternalClipboard(clip)) {
            return false;
        }
        return !hasPublishedClipboard || !Objects.equals(lastPublishedClipboard, text);
    }

    private String readClipboardText(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence value = clip.getItemAt(0).getText();
        return value == null ? null : value.toString();
    }

    private boolean isInternalClipboard(ClipData clip) {
        if (clip == null) {
            return false;
        }
        PersistableBundle extras = clip.getDescription().getExtras();
        return extras != null && clipboardSession.equals(extras.getString(CLIP_SESSION_KEY));
    }

    @Override
    public void close() {
        closed = true;
        clipboardHandler.removeCallbacks(clipboardUpdateTask);
        clipboardHandler.removeCallbacks(clipboardSnapshotTask);
        if (monitoring) {
            clipboard.removePrimaryClipChangedListener(clipboardListener);
            monitoring = false;
        }
    }
}
