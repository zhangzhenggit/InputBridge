# InputBridge design

InputBridge has two focused modules:

- `plugin`: Android Studio modeless dialog, ADB process management, transport, reconnect logic, and UI state.
- `server`: a small Android DEX/JAR launched temporarily through `app_process`.

## Runtime flow

1. The plugin finds `adb` from the Android SDK configured by Android Studio or the environment.
2. When the first online device is selected, it extracts and uploads a uniquely named server JAR to `/data/local/tmp` before the first input.
3. It creates `adb forward tcp:0 localabstract:inputbridge_<random>` and launches the server.
4. The server drops root to shell UID when needed and accepts exactly one connection. The JAR remains available for the lifetime of the logical device selection.
5. After Hello, the server registers an event-driven clipboard listener and sends the current clipboard snapshot. Clipboard reads and events are serialized on the Android main looper.
6. Both sides exchange bounded binary frames. A low-frequency heartbeat detects broken transports, and the plugin reconnects to the same requested device with bounded exponential backoff.
7. Closing the dialog starts a 30-second grace period. If it remains closed, the plugin closes the socket and process and removes the exact ADB forward, but retains the JAR.
8. Reopening starts a new runtime from that JAR and receives a current clipboard snapshot during startup. No event history is required to produce the latest value.
9. Disconnect, device switch, and project close stop the runtime and remove the exact remote artifact and local extraction.

No APK, service, input method, setting, boot component, or persistent process is installed. The only intentional device state change during text delivery is the device clipboard content, which is required for reliable Unicode paste.

## Text directions

Plugin to device:

- set an Android plain-text clipboard item marked sensitive;
- inject `KEYCODE_PASTE` into the focused control;
- optionally inject Ctrl+A first to replace all text in the focused control;
- optionally inject `KEYCODE_ENTER` after paste;
- return an acknowledgement to the UI.

Device to plugin:

- register an Android primary-clipboard listener;
- send the initial clipboard and subsequent text changes;
- identify and suppress clipboard writes created internally for Unicode paste;
- coalesce repeated Android callbacks and publish a clipboard state only when its text changes;
- cache the most recent value per device in the project service;
- leave the editor unchanged while synchronization is disabled;
- apply clipboard text only when automatic updates are enabled or the current value is requested;
- append fetched text on a new line by default, or replace the editor when that option is selected.

Device clipboard events never update the host operating system clipboard automatically. The editor remains a normal editable text component, so fetched text can be revised before sending.

## Reliability boundaries

- One selected device lease and at most one active runtime per IDE project.
- Each device discovery request returns directly to its originating dialog on the IDE event thread; stale results are discarded.
- UTF-8 throughout; 256 KiB maximum input and 1 MiB maximum clipboard event.
- Frame lengths and message shapes are validated before allocation/use.
- Writes are synchronized so clipboard callbacks and acknowledgements cannot interleave frames.
- Hello always precedes clipboard frames, and clipboard snapshots/events are emitted in serial order.
- Each device-server process marks its own clipboard writes with a unique session identifier. Automatic events ignore those writes, while an explicit current-value request always returns the actual device clipboard.
- Consecutive Android callbacks reporting the same external clipboard text are collapsed because the clipboard is treated as current state rather than event history.
- Enabling automatic updates does not request a snapshot; it starts applying the next clipboard change received from the device.
- Disabling automatic updates stops editor updates but does not pause device-side monitoring while the dialog is open.
- Closing the dialog pauses monitoring after 30 seconds; reopening always fetches the device's current clipboard before reporting live updates.
- Runtime generations reject callbacks arriving late from a server process that has already been stopped or replaced.
- The plugin never stops the shared host ADB server or the device's `adbd` process.
- User text and device clipboard contents are never logged.
- There is no alternate `adb shell input text` path because it is not Unicode-safe.

## scrcpy-derived scope

Only the application-context workaround, shell attribution, clipboard access pattern, root-to-shell transition, and reflective input injection approach are adapted from scrcpy 4.1. The implementation is reorganized for this protocol and contains none of scrcpy's media, display, touch, UHID, camera, or desktop-client systems.
