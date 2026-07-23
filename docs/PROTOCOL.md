# InputBridge protocol v4

Transport is a single ADB-forwarded localabstract socket. Every frame is big-endian:

```text
uint32 payload_length
uint8  message_type
byte[] message_body
```

Strings are UTF-8 and consume the remainder of their frame. Booleans are one byte as produced by `DataOutputStream.writeBoolean`.

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
| 17 | Clipboard | `uint64 sequence`, `uint8 has_text`, optional UTF-8 text |
| 18 | Acknowledgement | `uint64 request_id`, `uint8 success`, UTF-8 message |
| 19 | Error | UTF-8 message |
| 20 | Pong | `uint64 value` |

The device sends Hello before registering the clipboard listener, then sends the current clipboard snapshot. Type 4 requests another snapshot without changing the device clipboard. Automatic clipboard frames exclude clipboard writes created by InputBridge itself and collapse consecutive callbacks containing the same text. An explicit type 4 request always returns the current value, including a value previously written by InputBridge for Unicode paste.

Invalid, unknown, or oversized frames terminate the session. Input is limited to 256 KiB; clipboard text is limited to 1 MiB.

Paste flags use bit 0 for `append_enter` and bit 1 for `replace_existing`. Replace mode injects Ctrl+A before the Unicode-safe clipboard paste.
