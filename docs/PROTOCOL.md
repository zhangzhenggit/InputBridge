# InputBridge protocol v5

Transport is a single ADB-forwarded localabstract socket. Every frame is big-endian:

```text
uint32 payload_length
uint8  message_type
byte[] message_body
```

Strings are UTF-8. Unless a field declares its own length, a string consumes the remainder of its frame. Booleans are one byte as produced by `DataOutputStream.writeBoolean`.

## Plugin to device

| Type | Name | Body |
| ---: | --- | --- |
| 1 | Paste text | `uint64 request_id`, `uint8 flags`, UTF-8 text |
| 2 | Ping | `uint64 value` |
| 3 | Close | empty |
| 4 | Get current clipboard | empty |

## Device to plugin

| Type | Name | Body |
| ---: | --- | --- |
| 16 | Hello | `uint32 version`, `uint32 Android SDK`, UTF-8 model |
| 17 | Clipboard | `uint64 sequence`, `uint8 has_text`, `uint32 text_length`, UTF-8 text, `uint32 span_count`, span records |
| 18 | Acknowledgement | `uint64 request_id`, `uint8 success`, UTF-8 message |
| 19 | Error | UTF-8 message |
| 20 | Pong | `uint64 value` |

Unlike other string fields, clipboard text is length-prefixed rather than consuming the rest of the frame, because the span records follow it. A clipboard frame is rejected unless `text_length` and `span_count` account for the frame exactly.

The device sends Hello before registering the clipboard listener, then sends the current clipboard snapshot. Type 4 requests another snapshot without changing the device clipboard. Automatic clipboard frames exclude clipboard writes created by InputBridge itself and collapse consecutive callbacks whose text and spans are both unchanged. An explicit type 4 request always returns the current value, including a value previously written by InputBridge for Unicode paste.

Invalid, unknown, or oversized frames terminate the session. Input is limited to 256 KiB; clipboard text is limited to 1 MiB and carries at most 4096 style spans.

Paste flags use bit 0 for `append_enter` and bit 1 for `replace_existing`. Replace mode injects Ctrl+A before the Unicode-safe clipboard paste. Paste frames are plain text; formatting travels from the device only.

## Clipboard style spans

Each span record is 13 bytes:

```text
uint32 start
uint32 end
uint8  kind
uint32 value
```

Offsets are UTF-16 code unit indexes into the clipboard text, half open, and identical on both sides because the text round-trips losslessly through UTF-8.

| Kind | Name | Value |
| ---: | --- | --- |
| 1 | Bold | unused |
| 2 | Italic | unused |
| 3 | Underline | unused |
| 4 | Strikethrough | unused |
| 5 | Foreground | ARGB color |
| 6 | Background | ARGB color |
| 7 | Relative size | percent of the editor font size |
| 8 | Monospace | unused |
| 9 | Link | unused |
| 10 | Superscript | unused |
| 11 | Subscript | unused |

Spans are derived from the Android text spans that survive the clipboard binder call, so only `ParcelableSpan` implementations are represented. `AbsoluteSizeSpan` is approximated as a relative size. Spans the plugin does not recognize, and ranges outside the decoded text, are dropped rather than rejected, so a newer device server degrades to plain text instead of ending the session.
