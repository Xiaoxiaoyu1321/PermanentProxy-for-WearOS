# Permanent Proxy for Wear OS @VERSION@

Runs a system-wide HTTP proxy on your watch so geo-restricted apps (Google Pay and friends) work
from unsupported regions — and keeps it enabled across reboots.

## @RANGE_HEADING@

@CHANGELOG@

## About the Shizuku edition

Version 5.0 replaced the "ADB over Bluetooth" implementation with the
[Shizuku](https://shizuku.rikka.app/) API.

The old version obtained the `WRITE_SECURE_SETTINGS` privilege by speaking the ADB protocol over
Bluetooth to `localhost:7272`. That only ever worked on devices whose developer options still expose
*"Debug over Bluetooth"*, which Wear OS 3.5 / 4 / 5 and most other devices no longer do.

All device communication now goes through Shizuku, so Permanent Proxy works on **any Android 6.0+
device that can run Shizuku** — started with wireless debugging, a computer with adb, or root.

- 🔁 **Shizuku instead of Bluetooth ADB** — no "Debug over Bluetooth" developer option needed.
- 📶 **Works on Wear OS 3 / 4 / 5** and on many more watches than before.
- 🛡️ **No reboot needed to disable the proxy** — the app now clears the global proxy through
  `settings put global http_proxy ":0"`, which is the only value that makes system_server drop the
  proxy it caches in memory. Deleting the settings alone would keep it active until a restart,
  which is why 4.x had to reboot. Rebooting stays available as an opt-in checkbox.
- 🔎 **Privileged-access status in the app** — see whether it runs through Shizuku (shell or root)
  or through a direct permission.
- 🧰 **PC fallback kept** — if you would rather not use Shizuku, grant `WRITE_SECURE_SETTINGS` once
  with `adb` and the app works on its own.
- 🏗️ **Modernised build** — Gradle 8.10.2, AGP 8.5.2, Kotlin 1.9.24, AndroidX Wear, compileSdk 34.

### Getting started

1. Install the Wear APK on your watch:
   `adb install -r permanentproxy-wear-@VERSION@.apk`
   (or install the companion app on your phone and use *"Apps on your phone"*.)
2. Install **Shizuku** on the watch and start it
   (wireless debugging on Android 11+, `adb` from a computer, or root).
3. Open Permanent Proxy → **Grant Shizuku permission** → enter your proxy host and port →
   **Set proxy**.

Shizuku has to be started again after every reboot on non-rooted devices; the proxy setting itself
survives reboots.

### Downloads

| File | Device | Notes |
| --- | --- | --- |
| `permanentproxy-wear-@VERSION@.apk` | Wear OS watch | Release build to sideload with `adb install` |
| `permanentproxy-mobile-@VERSION@.apk` | Android phone | Companion app, bundles the Wear app |
| `permanentproxy-wear-@VERSION@-debug.apk` | Wear OS watch | Debug build, for bug reports |

Verify your download with `SHA256SUMS.txt`.

> ⚠️ **A proxy sees all of your watch's traffic.** Only ever use a proxy you control and trust.
> Proxies with authentication are not supported, because Android's global proxy setting does not
> carry credentials.

## Credits

- Original app and design by Jolan Rensen ([@Jolanrensen](https://github.com/Jolanrensen)).
  The donation options in the app belong to the original author and are unchanged.
- Shizuku edition maintained by [@Xiaoxiaoyu1321](https://github.com/Xiaoxiaoyu1321).
- Privileged access provided by [Shizuku](https://github.com/RikkaApps/Shizuku-API) by Rikka.
- The Shizuku integration, the build modernisation and the release automation were developed with
  assistance from **DeepSeek Harness**（由 DeepSeek Harness 辅助生成）.
