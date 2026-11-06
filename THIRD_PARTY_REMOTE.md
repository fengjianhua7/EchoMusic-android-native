# EchoRemote attribution

The browser interface in `app/src/main/assets/remote/index.html` is adapted from the `buildPage()` function of **EchoRemote** by `yuanqian, lm379`:

- Repository: https://github.com/yuanqian108/echo-remote
- Retrieved: 2026-10-27
- Upstream `index.js` Git blob: `8cf003496966df5d16e680fecb8dbbfd6215ebcd`
- Upstream README declares **MIT**. Original README, manifest and entry source are retained in `../remote-reference/`.

Changes: extract static HTML at build time; use native Android HTTP adapter instead of the desktop plugin/Go bridge; use the upstream HTTP fallback without WebSocket; pair with a process-random session token and remove it from the displayed URL after pairing. No upstream platform bridge executables are bundled.

The Android adapter and native TV UI are part of the EchoMusic Android source distribution. Original project/dependency notices remain included.


0.10.3 Android adapter additions: IP:port bootstrap and session renewal, authenticated cloud playlist browsing/pagination/playback, original/accompaniment state and command, queue auto-dismiss. The underlying native private-peer, Host/Origin and protected-cookie checks are retained.
