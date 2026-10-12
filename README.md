# Go Live

Android app that records an AirGradient Go's readings in the background, for
long-term trends. It listens for the Go's BTHome v2 broadcast and never
connects, so the Go keeps sleeping between measurements in Portable Sleep.

The broadcast needs the custom Go firmware
([jaredkzr/firmware-core](https://github.com/jaredkzr/firmware-core));
stock AirGradient firmware doesn't send it. The web version is
<https://kizer.sh/go-viewer/> (foreground only).

## How it works

- `ScanService` is a foreground service (type `connectedDevice`) with a
  persistent notification showing the latest reading. It runs a low-power BLE
  scan filtered in the Bluetooth controller on the BTHome service data (and,
  once paired, the Go's address), so it keeps scanning with the screen off
  and the phone sleeps between matches. The scan is restarted every 25 min
  so the system doesn't demote it.
- `BootReceiver` resumes collecting after a reboot or an app update.
- `Repository` locks onto the first broadcast that looks like a Go, drops
  repeats (`ReadingFilter`: connect-window rebroadcasts reuse the packet id)
  and stores one row per measurement in SQLite (`ReadingStore`), about one a
  minute: ~525k rows and ~30 MB a year.
- Charts aggregate in SQL per bucket (`TrendRange`: 5 min for 24 h up to a
  day for 1 y) and show the average with a min–max band.
- Cloud backup excludes the database (over the 25 MB limit); `Backup` writes
  a gzipped CSV copy to `files/backup/` once a day, and a fresh install
  restores from it. Settings exports everything as CSV.

## Build & test

```sh
JAVA_HOME=~/android-studio/jbr ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Gradle needs Android Studio's JDK. `adb` is `~/Android/Sdk/platform-tools/adb`;
pass `-s <serial>` because the emulator is often connected too. The emulator
can't hear the Go, so test scanning on a phone.
