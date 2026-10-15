# PermanentProxy for Wear OS

Simple app to run a system-wide proxy (also on boot) on a Wear OS device and enable geo-restricted
apps like Google Pay in unsupported regions!

Sometimes big companies decide that Geoblocking is a good idea. I don't agree.
That's why this app lets you use your watch as if you were in another country, so you can use
payment apps or otherwise geo-specific apps wherever you are in the world.

The app uses Android's built-in global proxy setting — the same one you would normally have to set
from a computer with `adb shell settings put global http_proxy host:port`. To get that privilege
the app now uses **[Shizuku](https://shizuku.rikka.app/)**.

> ### ⚠️ Changed in 5.0: Shizuku instead of ADB over Bluetooth
>
> Up to version 4.0 this app got its privileges by speaking the ADB protocol over **Bluetooth** to
> `localhost:7272`. That only worked on devices whose developer options still expose
> *"Debug over Bluetooth"* — which is missing on **Wear OS 3.5 / 4 / 5** and on many other watches.
>
> All device communication has been rewritten to use the **Shizuku API**. Permanent Proxy now works
> on **any Android 6.0+ device that can run Shizuku** (started with wireless debugging, with a
> computer over adb, or with root). See [Getting started](#getting-started).

---

## Installation

**Method 1 — from your phone**

Download `permanentproxy-mobile-<version>.apk` from the
[releases page](https://github.com/Xiaoxiaoyu1321/PermanentProxy-for-WearOS/releases) and install it
on your Android phone. Then open the Play Store on your Wear OS watch, go to
*"Apps on your phone"* and install Permanent Proxy from there.

**Method 2 — straight onto the watch**

Download `permanentproxy-wear-<version>.apk` and sideload it:

```sh
adb install -r permanentproxy-wear-<version>.apk
```

Every release also ships `SHA256SUMS.txt` so you can verify your download.

## Getting started

Permanent Proxy needs privileged access to change the global proxy setting. There are two ways to
get it — pick one:

### Option A: Shizuku (recommended, works nearly everywhere)

1. **Install Shizuku** on the watch. Either sideload the Shizuku APK, or install it on your phone
   from the Play Store and push it to the watch through *"Apps on your phone"*.
2. **Start Shizuku.** On non-rooted devices this has to be repeated after every reboot:
   - *Android 11 and newer:* enable Developer options → **Wireless debugging**, then start Shizuku
     from inside the Shizuku app. No computer needed.
   - *Any Android 6.0+:* connect the watch to a computer with adb and run
     ```sh
     adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
     ```
   - *Rooted:* just start Shizuku and pick root.
3. Open Permanent Proxy, tap **Grant Shizuku permission** and accept the dialog.
4. Enter your proxy host and port and tap **Set proxy**.

The app shows which privileges it currently has (*"Shizuku, running as adb (shell)"* or
*"Shizuku, running as root"*).

### Option B: grant the permission once from a PC

If you cannot or do not want to use Shizuku, you can hand the app `WRITE_SECURE_SETTINGS` once and
it will never need Shizuku:

```sh
adb shell pm grant nl.jolanrensen.permanentproxy android.permission.WRITE_SECURE_SETTINGS
```

That permission survives reboots (but not a reinstall). To undo it:

```sh
adb shell pm revoke nl.jolanrensen.permanentproxy android.permission.WRITE_SECURE_SETTINGS
```

The same instructions are available in the app under *"No Shizuku? Set up with a PC instead"*.

### Using it

Enter a proxy address and port, tap **Set proxy**, and you are done — the proxy keeps working after
a reboot. To turn it off, tap **Disable proxy**; it takes effect immediately, no reboot required.

*(4.x had to reboot the watch here, because deleting the stored settings alone does not make
system_server drop the proxy it has cached. The app now writes `http_proxy=:0` first, waits until
system_server confirms that it dropped the cached proxy, and only then deletes the settings. If a
device does not react to that, or the write fails, the app automatically falls back to the plain
"delete the settings" path that 4.x used and tells you a reboot may be needed. The
"Reboot the watch afterwards" checkbox is kept for exactly those devices.)*

Proxy services can be found online and can be from any country. However, make sure you completely
trust the proxy you choose before entering it: all the data of your watch may be sent through that
proxy, including sensitive data. Act at your own risk.

## Tips

- Create your own proxy. Free proxies might work, but they usually stop working within a couple of
  days — if they work at all. A tutorial can be found on XDA Developers.
- Use an obscure port: all IPv4 addresses are regularly scanned for common services, and a
  passwordless proxy stands out like a beacon (see
  <https://www.shodan.io/search?query=squid>).
- **Proxies with a password do not work**, because Android's global proxy setting does not carry
  credentials.
- Don't sideload Google Pay onto your watch. It should enable by itself. You can try to
  disable/enable Google Pay from the Play Store on your watch, or use the Pay Enabler app to open
  the Google Pay page quickly.
- Clearing the Google Pay data in the watch settings also helps sometimes, as does
  "remove updates" / "disable app".
- After enabling the proxy, exit the app, give the watch some time, and check back in Permanent
  Proxy to see whether the external IP changed to the one of the proxy. If so, you're connected.
- You can make the watch refresh its Google Pay availability check by setting the IP/port again in
  the app, or by tapping "Home App" in Settings → System → About → Versions.
- Google Pay works once you see its icon in your quick settings.
- Google Pay stuck while adding a new card? Check the Play Store for updates for Google Pay.
- Only cards that work with Google Pay on your phone can be added to Google Pay on your watch, so
  you need a card from a supported country.
- Permanent Proxy only works for IPv4 networks. On an IPv6-only network Google might still see your
  real country.
- Uninstalling Permanent Proxy does **not** stop the proxy. Disable the proxy first, or run
  `adb shell settings delete global http_proxy` afterwards.
- Shizuku has to be started again after every reboot on non-rooted devices. The proxy setting itself
  survives reboots — only the app's ability to *change* it goes away until Shizuku is running again.

## Note

While I haven't experienced it personally, some users report that the Google Assistant and related
processes might confuse your location with the location of the proxy. This is likely due to internet
traffic being routed to that country. If apps ask for the location of the watch using GPS (or the
location of your phone), it should be fine.

---

## Building from source

Requirements: JDK 17+, Android SDK with `platforms;android-34` and `build-tools;35.0.0`.

```sh
./gradlew :wear:assembleDebug        # Wear OS debug APK
./gradlew :wear:assembleRelease      # Wear OS release APK
./gradlew :mobile:assembleRelease    # Companion phone app
```

The APKs end up in `wear/build/outputs/apk/` and `mobile/build/outputs/apk/`.

Release builds are signed with the public key committed at
`keystore/permanentproxy-release.jks` so that builds are installable and upgradeable in place. For a
real distribution, point the build at your own keystore instead:

```sh
export RELEASE_STORE_FILE=/path/to/your.jks
export RELEASE_STORE_PASSWORD=...
export RELEASE_KEY_ALIAS=...
export RELEASE_KEY_PASSWORD=...
```

### Continuous integration

`.github/workflows/android-build-release.yml` builds all four APKs on every push and pull request,
and uploads them as artifacts.

Pushing a version tag builds **and publishes a GitHub Release** with the APKs, a `SHA256SUMS.txt`
and generated release notes:

```sh
git tag v5.0.1 && git push origin v5.0.1
```

## Project layout

| Path | What it is |
| --- | --- |
| `wear/` | The Wear OS app (the actual proxy controller) |
| `mobile/` | Companion phone app that bundles the Wear app for easy installation |
| `wear/src/main/aidl/.../IShizukuShellService.aidl` | AIDL contract for the privileged Shizuku user service |
| `wear/src/main/java/.../ShizukuManager.kt` | Shizuku binder, permission and user-service handling |
| `wear/src/main/java/.../ShizukuShellService.kt` | Runs shell commands as shell/root |
| `wear/src/main/java/.../ProxyManager.kt` | Reads/writes the system proxy |
| `flag.md` | Requirements and the project's progress log |
| `keystore/permanentproxy-release.jks` | Public signing key used for release builds |

## Credits

- Original app and design by [Jolan Rensen](https://github.com/Jolanrensen). The donation options
  in the app belong to the original author and are unchanged.
- Shizuku edition maintained by [@Xiaoxiaoyu1321](https://github.com/Xiaoxiaoyu1321).
- Privileged access provided by [Shizuku](https://github.com/RikkaApps/Shizuku-API) by Rikka.
- The Shizuku integration, the build modernisation and the release automation were developed with
  assistance from **DeepSeek Harness**（由 DeepSeek Harness 辅助生成）.

The same credits are shown in the app under *About & credits*.

