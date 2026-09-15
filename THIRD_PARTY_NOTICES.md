# Third-party notices

## scrcpy

InputBridge's device-side server adapts the following narrowly scoped concepts and compatibility workarounds from scrcpy 4.1:

- creation of an `app_process`-compatible Android context;
- shell attribution for clipboard access;
- reflective input-event injection;
- localabstract socket transport;
- dropping a root `app_process` to the shell UID for clipboard compatibility.

All video, audio, capture, encoding, display mirroring, touch, UHID, camera, application management, power management, and desktop client code has been omitted.

Upstream project: https://github.com/Genymobile/scrcpy

Upstream revision: `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0` (v4.1)

Copyright (C) 2018-2026 Romain Vimont

Copyright (C) 2018 Genymobile

Licensed under the Apache License, Version 2.0. A copy is provided in [LICENSE](LICENSE).
