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
- concatenate every clip item, preferring styled text over the plain fallback and falling back to a URI string, so clips without a plain-text item are no longer reported as empty;
- flatten the Android text spans that survived the clipboard binder call into `(start, end, kind, value)` records and send them beside the text;
- identify and suppress clipboard writes created internally for Unicode paste;
- coalesce repeated Android callbacks and publish a clipboard state only when its text or spans change;
- cache the most recent value per device in the project service;
- leave the editor unchanged while synchronization is disabled;
- apply clipboard text only when automatic updates are enabled or the current value is requested;
- append fetched text on a new line by default, or replace the editor when that option is selected;
- treat a value that is only blank characters as nothing to import and name it in the status line, because an invisible import cannot be told apart from a broken synchronization;
- report a requested snapshot whose clip yields no text, and a clipboard read that throws, to the plugin instead of failing silently. Both messages carry only metadata: item counts, MIME types, field lengths, and the failure type.

Device clipboard events never update the host operating system clipboard automatically. The editor remains a normal editable text component, so fetched text can be revised before sending.

## Device text formatting

The editor is a styled Swing text surface, so device clipboard formatting is visible instead of being flattened away:

- styling is transmitted as structured span records rather than HTML, so the character content is exactly the plain-text value the device published and the offsets need no remapping;
- the editor renders formatting for presentation only. Input, favorites, and history all read the document's plain text, so what InputBridge sends is unchanged by any styling;
- because nothing parses or renders untrusted markup, device clipboard content cannot reference remote resources or reach a HTML renderer;
- device colors are authored for the application they were copied from, so a foreground is applied only when it keeps a 3:1 contrast ratio against the surface behind it. Foregrounds resolve after backgrounds so each is measured against what ends up under it;
- relative sizes scale the editor font and are clamped to a legible range;
- text typed next to styled content inherits the adjacent styling, which is standard styled-editor behavior and cannot change what is sent.

Clips created with `ClipData.newHtmlText` are parsed on the device with `Html.fromHtml`, so their text comes from the parsed markup rather than the application's plain fallback.

## Favorites

Favorites are deliberately independent from the device session:

- an application-level persistent service stores favorites in the IDE configuration file `InputBridgeFavorites.xml`;
- roaming is disabled, so favorite text is not uploaded through IDE settings synchronization;
- favorites store a required, case-insensitively unique title with their content;
- the editor exposes one native favorite action; its lightweight popup provides selection, direct saving, and management without adding permanent toolbar clutter;
- selecting a favorite inserts its content at the editor caret, replacing only the current selection, and never sends it automatically;
- `Save` captures the current selection or full editor text and requests only a short title, preselected from the first non-empty content line; `Manage` opens the full collection editor;
- the manager edits a versioned private draft and commits additions, updates, deletions, and ordering only when `Save` is selected; a stale draft cannot overwrite changes from another IDE window;
- version 1 and version 2 XML state is migrated to the titled version 3 model on load; generated title collisions receive a numeric suffix;
- JSON import accepts version 1, version 2, and version 3 backups, merges new content without overwriting the existing collection, and exports titles and content in version 3 format; file reads, encoding, and writes run outside the EDT;
- popup filtering and manager search match both title and content;
- favorite contents are unique, individual content follows the 256 KiB input limit, and the local collection has a 10 MiB aggregate content limit.

Favorite contents, imported data, and exported data are never logged. Storage is local plain text and is not intended for credentials or secret material.

## Input history

Input history is independent from the Android clipboard and the device session lifetime:

- a separate application-level persistent service stores history in `InputBridgeHistory.xml` with roaming disabled;
- the project service holds sent text only until the matching device acknowledgement arrives;
- only a successful acknowledgement commits text to history; rejected, failed, timed-out, disconnected, and locally invalid requests are discarded;
- history is newest-first and globally deduplicated, so sending identical text promotes its existing entry;
- the history popup is a lightweight, filterable list with no management footer; selecting an entry restores the complete text to the editor and never sends it automatically;
- history recording is enabled by default with a 50-entry limit, configurable to 20, 50, 100, or 200 entries under `Settings | Tools | InputBridge`;
- reducing the limit removes the oldest entries, and clearing history is an explicit confirmed action;
- individual entries use the 256 KiB input limit and the retained collection has a 10 MiB aggregate cap.

History content is never logged or copied to the operating-system clipboard. It is stored locally as plain text and should not be used for passwords, tokens, or private keys.

## Reliability boundaries

- One selected device lease and at most one active runtime per IDE project.
- Each device discovery request returns directly to its originating dialog on the IDE event thread; stale results are discarded.
- UTF-8 throughout; 256 KiB maximum input, 1 MiB maximum clipboard event, and 4096 maximum clipboard style spans.
- Frame lengths and message shapes are validated before allocation/use, including the declared clipboard text length and span count.
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
