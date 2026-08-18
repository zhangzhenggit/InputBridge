package com.tools.inputbridge.server;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
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
    private int[] lastPublishedSpans = StyleSpans.NONE;

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
            // The exception message can quote clipboard content, so only its type is reported.
            Log.error("Unable to read the device clipboard", error);
            try {
                Protocol.writeError(output, "Unable to read the device clipboard: "
                        + error.getClass().getSimpleName());
            } catch (IOException ignored) {
                closed = true;
            }
        }
    }

    private void publishCurrentClipboard(boolean force) throws IOException {
        ClipData clip = clipboard.getPrimaryClip();
        CharSequence content = readClipboardContent(clip);
        String text = content == null ? null : content.toString();
        int[] spans = StyleSpans.extract(content, Protocol.MAX_CLIPBOARD_SPANS);
        if (force && content == null && clip != null) {
            Protocol.writeError(output, describeUnreadableClip(clip));
        }
        if (!shouldPublishClipboard(clip, text, spans, force)) {
            return;
        }
        if (!Protocol.clipboardFits(text)) {
            Protocol.writeError(output, "Device clipboard text exceeds 1 MB and was not transferred");
            return;
        }
        Protocol.writeClipboard(output, clipboardSequence.incrementAndGet(), text, spans);
        lastPublishedClipboard = text;
        lastPublishedSpans = spans;
        hasPublishedClipboard = true;
    }

    private boolean shouldPublishClipboard(ClipData clip, String text, int[] spans, boolean force) {
        if (force) {
            return true;
        }
        if (isInternalClipboard(clip)) {
            return false;
        }
        if (!hasPublishedClipboard) {
            return true;
        }
        return !Objects.equals(lastPublishedClipboard, text) || !Arrays.equals(lastPublishedSpans, spans);
    }

    /** Concatenates every clip item, keeping the styling Android preserved across the binder call. */
    private CharSequence readClipboardContent(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence single = null;
        SpannableStringBuilder combined = null;
        for (int index = 0; index < clip.getItemCount(); index++) {
            CharSequence piece = readItemContent(clip.getItemAt(index));
            if (piece == null || piece.length() == 0) {
                continue;
            }
            if (single == null && combined == null) {
                single = piece;
            } else {
                if (combined == null) {
                    combined = new SpannableStringBuilder(single);
                    single = null;
                }
                combined.append('\n').append(piece);
            }
        }
        return combined != null ? combined : single;
    }

    /**
     * Styled text wins over the plain fallback, so clips created with
     * {@code ClipData.newHtmlText} keep their formatting instead of arriving unstyled.
     */
    private CharSequence readItemContent(ClipData.Item item) {
        CharSequence text = item.getText();
        if (text instanceof Spanned && text.length() > 0) {
            return text;
        }
        String html = item.getHtmlText();
        if (html != null && !html.isEmpty()) {
            CharSequence parsed = trimTrailingBreaks(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
            if (parsed != null && parsed.length() > 0) {
                return parsed;
            }
        }
        if (text != null && text.length() > 0) {
            return text;
        }
        Uri uri = item.getUri();
        if (uri != null) {
            return uri.toString();
        }
        Intent intent = item.getIntent();
        return intent == null ? null : intent.toUri(Intent.URI_INTENT_SCHEME);
    }

    /**
     * Describes the shape of a clip that produced no text. Types, counts, and lengths are
     * metadata, so this never exposes clipboard content.
     */
    private String describeUnreadableClip(ClipData clip) {
        ClipDescription description = clip.getDescription();
        StringBuilder detail = new StringBuilder("Device clipboard has no readable text: ");
        detail.append(clip.getItemCount()).append(" item(s), types [");
        for (int index = 0; index < description.getMimeTypeCount(); index++) {
            if (index > 0) {
                detail.append(' ');
            }
            detail.append(description.getMimeType(index));
        }
        detail.append(']');
        for (int index = 0; index < clip.getItemCount(); index++) {
            ClipData.Item item = clip.getItemAt(index);
            CharSequence text = item.getText();
            String html = item.getHtmlText();
            detail.append(", item").append(index)
                    .append(" text=").append(text == null ? "none" : String.valueOf(text.length()))
                    .append(text instanceof Spanned ? "/spanned" : "")
                    .append(" html=").append(html == null ? "none" : String.valueOf(html.length()))
                    .append(" uri=").append(item.getUri() == null ? "none" : "yes")
                    .append(" intent=").append(item.getIntent() == null ? "none" : "yes");
        }
        return detail.toString();
    }

    /** Block-level HTML makes Android append trailing newlines that were never copied. */
    private CharSequence trimTrailingBreaks(CharSequence parsed) {
        if (parsed == null) {
            return null;
        }
        int end = parsed.length();
        while (end > 0 && parsed.charAt(end - 1) == '\n') {
            end--;
        }
        return end == parsed.length() ? parsed : parsed.subSequence(0, end);
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
