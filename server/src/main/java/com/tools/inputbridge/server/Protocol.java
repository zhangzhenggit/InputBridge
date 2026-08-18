package com.tools.inputbridge.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class Protocol {
    static final int VERSION = 5;
    private static final int FLAG_APPEND_ENTER = 1;
    private static final int FLAG_REPLACE_EXISTING = 1 << 1;
    static final int MAX_INPUT_BYTES = 256 * 1024;
    static final int MAX_CLIPBOARD_BYTES = 1024 * 1024;
    static final int MAX_CLIPBOARD_SPANS = 4096;
    static final int SPAN_BYTES = 13;
    private static final int MAX_FRAME_BYTES =
            MAX_CLIPBOARD_BYTES + MAX_CLIPBOARD_SPANS * SPAN_BYTES + 64;

    static final int CLIENT_PASTE_TEXT = 1;
    static final int CLIENT_PING = 2;
    static final int CLIENT_CLOSE = 3;
    static final int CLIENT_GET_CLIPBOARD = 4;

    static final int SERVER_HELLO = 16;
    static final int SERVER_CLIPBOARD = 17;
    static final int SERVER_ACK = 18;
    static final int SERVER_ERROR = 19;
    static final int SERVER_PONG = 20;

    private Protocol() {
    }

    static ClientMessage readClientMessage(DataInputStream input) throws IOException {
        int frameLength;
        try {
            frameLength = input.readInt();
        } catch (EOFException eof) {
            return null;
        }
        if (frameLength < 1 || frameLength > MAX_FRAME_BYTES) {
            throw new IOException("Invalid frame length: " + frameLength);
        }

        byte[] frame = new byte[frameLength];
        input.readFully(frame);
        try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(frame))) {
            int type = body.readUnsignedByte();
            switch (type) {
                case CLIENT_PASTE_TEXT: {
                    if (frameLength < 10) {
                        throw new IOException("Invalid paste frame");
                    }
                    long requestId = body.readLong();
                    int flags = body.readUnsignedByte();
                    byte[] textBytes = new byte[frameLength - 10];
                    body.readFully(textBytes);
                    if (textBytes.length > MAX_INPUT_BYTES) {
                        throw new IOException("Input text exceeds the size limit");
                    }
                    return ClientMessage.paste(
                            requestId,
                            (flags & FLAG_APPEND_ENTER) != 0,
                            (flags & FLAG_REPLACE_EXISTING) != 0,
                            new String(textBytes, StandardCharsets.UTF_8));
                }
                case CLIENT_PING:
                    if (frameLength != 9) {
                        throw new IOException("Invalid ping frame");
                    }
                    return ClientMessage.ping(body.readLong());
                case CLIENT_CLOSE:
                    if (frameLength != 1) {
                        throw new IOException("Invalid close frame");
                    }
                    return ClientMessage.close();
                case CLIENT_GET_CLIPBOARD:
                    if (frameLength != 1) {
                        throw new IOException("Invalid clipboard request frame");
                    }
                    return ClientMessage.getClipboard();
                default:
                    throw new IOException("Unsupported message type: " + type);
            }
        }
    }

    static void writeHello(DataOutputStream output, int sdk, String model) throws IOException {
        writeFrame(output, SERVER_HELLO, new BodyWriter() {
            @Override
            public void write(DataOutputStream body) throws IOException {
                body.writeInt(VERSION);
                body.writeInt(sdk);
                body.write(model.getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    static void writeClipboard(DataOutputStream output, long sequence, String text, int[] spans) throws IOException {
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CLIPBOARD_BYTES) {
            throw new IOException("Clipboard text exceeds the size limit");
        }
        int[] runs = spans == null ? StyleSpans.NONE : spans;
        int count = Math.min(runs.length / StyleSpans.VALUES_PER_SPAN, MAX_CLIPBOARD_SPANS);
        writeFrame(output, SERVER_CLIPBOARD, new BodyWriter() {
            @Override
            public void write(DataOutputStream body) throws IOException {
                body.writeLong(sequence);
                body.writeBoolean(text != null);
                body.writeInt(bytes.length);
                body.write(bytes);
                body.writeInt(count);
                for (int index = 0; index < count * StyleSpans.VALUES_PER_SPAN; index += StyleSpans.VALUES_PER_SPAN) {
                    body.writeInt(runs[index]);
                    body.writeInt(runs[index + 1]);
                    body.writeByte(runs[index + 2]);
                    body.writeInt(runs[index + 3]);
                }
            }
        });
    }

    static boolean clipboardFits(String text) {
        return text == null || text.getBytes(StandardCharsets.UTF_8).length <= MAX_CLIPBOARD_BYTES;
    }

    static void writeAck(DataOutputStream output, long requestId, boolean success, String message) throws IOException {
        writeFrame(output, SERVER_ACK, new BodyWriter() {
            @Override
            public void write(DataOutputStream body) throws IOException {
                body.writeLong(requestId);
                body.writeBoolean(success);
                body.write(message.getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    static void writeError(DataOutputStream output, String message) throws IOException {
        writeFrame(output, SERVER_ERROR, new BodyWriter() {
            @Override
            public void write(DataOutputStream body) throws IOException {
                body.write(message.getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    static void writePong(DataOutputStream output, long value) throws IOException {
        writeFrame(output, SERVER_PONG, new BodyWriter() {
            @Override
            public void write(DataOutputStream body) throws IOException {
                body.writeLong(value);
            }
        });
    }

    private static void writeFrame(DataOutputStream output, int type, BodyWriter writer) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream body = new DataOutputStream(buffer)) {
            body.writeByte(type);
            writer.write(body);
        }
        byte[] frame = buffer.toByteArray();
        if (frame.length > MAX_FRAME_BYTES) {
            throw new IOException("Frame exceeds the size limit");
        }
        synchronized (output) {
            output.writeInt(frame.length);
            output.write(frame);
            output.flush();
        }
    }

    private interface BodyWriter {
        void write(DataOutputStream output) throws IOException;
    }

    static final class ClientMessage {
        final int type;
        final long requestId;
        final boolean appendEnter;
        final boolean replaceExisting;
        final String text;

        private ClientMessage(int type, long requestId, boolean appendEnter, boolean replaceExisting, String text) {
            this.type = type;
            this.requestId = requestId;
            this.appendEnter = appendEnter;
            this.replaceExisting = replaceExisting;
            this.text = text;
        }

        static ClientMessage paste(long requestId, boolean appendEnter, boolean replaceExisting, String text) {
            return new ClientMessage(CLIENT_PASTE_TEXT, requestId, appendEnter, replaceExisting, text);
        }

        static ClientMessage ping(long value) {
            return new ClientMessage(CLIENT_PING, value, false, false, null);
        }

        static ClientMessage close() {
            return new ClientMessage(CLIENT_CLOSE, 0, false, false, null);
        }

        static ClientMessage getClipboard() {
            return new ClientMessage(CLIENT_GET_CLIPBOARD, 0, false, false, null);
        }
    }
}
