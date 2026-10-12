# CLAUDE.md

See [README.md](README.md) for what the app does and how to build it.

- Kotlin + Jetpack Compose (Material 3), application ID `sh.kizer.golive`,
  minSdk 31 (BLUETOOTH_SCAN with `neverForLocation`, no location permission).
- Keep the BTHome decoder (`Bthome.kt`) in step with the firmware's
  `products/go/main/go_bthome.cpp` and kizer.sh's `go-viewer/bthome.js`;
  the tests share the firmware's vectors.
- Pure logic (decoder, filter, buckets, axis ticks, CSV, backup format) has
  JVM unit tests; keep Android types out of it.
- Bump `ReadingStore.VERSION` with an `onUpgrade` migration for any schema
  change: the database holds a year of the user's data.
