# InputBridge

InputBridge is an Android Studio plugin for entering Unicode text on an ADB-connected Android device and viewing device clipboard changes, with their formatting, in real time.

The device-side component is a small DEX/JAR pushed to `/data/local/tmp` and launched with `app_process`. It is not installed as an APK and does not change the active input method or system settings.

## Behavior

- Text is pasted into the currently focused device control through a single Unicode-safe path.
- Selecting the first online device prepares the temporary server before the first input.
- While the dialog is open, the device list follows ADB attach, detach, and authorization changes without a manual refresh. An idle dialog connects to a device as soon as it comes online, and another online device can be selected at any time to switch to it.
- If the connected device goes offline, the dialog switches to another online device. When no other device is available, it waits for ADB to report the device again and reconnects immediately.
- Device clipboard synchronization into the editor is disabled by default. It can be enabled continuously or requested once with `Get current`.
- Device clipboard formatting is shown in the editor: bold, italic, underline, strikethrough, colors, relative size, monospace, links, and super/subscript. Colors that would be illegible against the IDE theme are skipped.
- Formatting is display only. Text sent to the device, saved as a favorite, or recorded in history is always the plain text, so styling never changes what is delivered.
- Clips that carry no plain-text item, such as a copied link or HTML-only content, are still imported. Every clip item is read, device-side HTML is parsed on the device, and a bare URI or intent falls back to its text form.
- A clipboard holding only blank characters is named in the status line instead of being imported, so an invisible value is never mistaken for a failed synchronization.
- Clipboard writes used internally for Unicode paste are not echoed back as device clipboard updates.
- Device clipboard text never changes the host operating system clipboard automatically.
- Editor text or the current selection can be saved as a titled reusable favorite. Existing content-only favorites receive unique generated titles automatically.
- Selecting a favorite inserts it at the current caret, replacing only selected text, and never sends it automatically.
- Favorites are shared across IDE projects and stored locally in the IDE configuration. Titles and content can be searched, edited, reordered, imported, and exported from the favorites manager.
- Successfully delivered input is retained in a local, most-recently-used history. Failed, rejected, and timed-out input is never recorded.
- Selecting a history entry restores the complete previous input to the editor without sending it. Exact duplicates are promoted instead of stored twice.
- Input history is enabled by default with a 50-entry limit. Recording, retention, and clearing are available under `Settings | Tools | InputBridge`.
- The server session uses an ADB forward to a randomly named localabstract socket.
- Closing the dialog keeps the live connection for a 30-second grace period. After that, the server process and ADB forward stop while the prepared JAR remains available for a fast resume.
- Reopening the dialog starts a fresh server process from the retained JAR and immediately reads the device's current clipboard.
- Disconnecting, switching devices, or closing the project stops the runtime and removes the exact temporary JAR.

## Build

The current release targets Android Studio Meerkat 2024.3.1 or newer
(IntelliJ Platform build 243).

Configure Android Studio and Android SDK paths in `local.properties`:

```properties
studioPath=D:/Android/Android Studio
sdk.dir=C:\\Users\\user\\AppData\\Local\\Android\\Sdk
```

Then run:

```powershell
.\gradlew.bat buildPlugin
```

The installable archive is generated as `dist/InputBridge-<version>.zip`. The build task removes older InputBridge archives from this directory before staging the current package.

Favorites and successful input history are stored as plain text in `InputBridgeFavorites.xml` and `InputBridgeHistory.xml` under the IDE configuration directory. They are not written to the current project or synchronized through the operating-system clipboard. Avoid storing or sending passwords, tokens, or private keys when local retention is enabled.

## Third-party code

The device server contains a deliberately small, refactored subset of techniques and compatibility workarounds derived from scrcpy. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `LICENSES/Apache-2.0.txt`.
