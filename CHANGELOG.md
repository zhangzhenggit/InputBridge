# Changelog

The build copies the section for the current `pluginVersion` into the plugin's change notes, so each release needs a matching `## <version>` heading with `- ` bullet items.

## 1.5.0

- First public release on JetBrains Marketplace.
- The device list follows ADB attach, detach, and authorization changes while the dialog is open, without a manual refresh.
- Another online device can be selected at any time to switch the connection to it.
- When the connected device goes offline, InputBridge switches to another online device, or reconnects as soon as the device returns.
- The status line no longer stays on "Detecting ADB devices" after a refresh, and names unauthorized devices.
- The plugin ID is now `io.zheng.inputbridge`. Uninstall earlier InputBridge builds before installing this version; favorites and history are kept.
