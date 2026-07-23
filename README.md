# InputBridge

InputBridge is an Android Studio plugin for entering Unicode text on an ADB-connected Android device and viewing device text clipboard changes in real time.

The device-side component is a small DEX/JAR pushed to `/data/local/tmp` and launched with `app_process`. It is not installed as an APK and does not change the active input method or system settings.

## Behavior

- Text is pasted into the currently focused device control through a single Unicode-safe path.
- Selecting the first online device prepares the temporary server before the first input.
- Device clipboard synchronization into the editor is disabled by default. It can be enabled continuously or requested once with `Get current`.
- Clipboard writes used internally for Unicode paste are not echoed back as device clipboard updates.
- Device clipboard text never changes the host operating system clipboard automatically.
- The server session uses an ADB forward to a randomly named localabstract socket.
- Closing the dialog keeps the live connection for a 30-second grace period. After that, the server process and ADB forward stop while the prepared JAR remains available for a fast resume.
- Reopening the dialog starts a fresh server process from the retained JAR and immediately reads the device's current clipboard.
- Disconnecting, switching devices, or closing the project stops the runtime and removes the exact temporary JAR.

## Build

The current release targets Android Studio 2026.1 or newer (IntelliJ Platform build 261).

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

## Third-party code

The device server contains a deliberately small, refactored subset of techniques and compatibility workarounds derived from scrcpy. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `LICENSES/Apache-2.0.txt`.
