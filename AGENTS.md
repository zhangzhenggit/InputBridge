# InputBridge development notes

InputBridge is an Android Studio plugin with a small Android-side server. The plugin sends Unicode text to the focused control on an ADB device and displays text clipboard changes from that device.

## Modules

- `plugin`: IntelliJ Platform plugin, modeless dialog UI, and host-side session management.
- `server`: temporary DEX/JAR launched with `app_process`; it is never installed as an APK.

## Build prerequisites

- Java 21 or newer.
- Android SDK platform 36 and a build-tools installation containing `d8`.
- `studioPath` in `local.properties`, the `INPUT_BRIDGE_STUDIO_PATH` environment variable, or the `studioPath` Gradle property.
- `sdk.dir` in `local.properties`, `ANDROID_SDK_ROOT`, or `ANDROID_HOME`.

Build the distributable plugin:

```powershell
.\gradlew.bat buildPlugin
```

Run compilation and checks:

```powershell
.\gradlew.bat verifyProject
```

## Releases

- The plugin ID is `io.zheng.inputbridge` and must never change; Kotlin and Java packages keep `com.tools.inputbridge`.
- Every `pluginVersion` needs a matching `## <version>` section in `CHANGELOG.md`; the build fails without it.
- `dist/` is build output and is not committed. Publish archives through JetBrains Marketplace and GitHub Releases.
- Signing keys and the Marketplace token are read from `INPUT_BRIDGE_*` environment variables. Never commit them or place them inside the repository.

## Constraints

- Never log clipboard or input text.
- Never synchronize the device clipboard to the operating-system clipboard automatically.
- Keep every ADB operation off the EDT and every Swing update on the EDT.
- Do not add fallback input paths. Input requires a ready device server session.
- Remote cleanup must target only the exact InputBridge server path and forward rule.
