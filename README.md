# VeloGPX

VeloGPX is a local-first Android GPX editor built for assembling long bicycle tours from EuroVelo, Komoot, Garmin, and other GPX sources.

## What it does

- Imports several GPX 1.0/1.1 files through Android's file picker, share sheet, or “Open with”.
- Preserves tracks, track segments, routes, waypoints, metadata, timestamps, elevation, legacy course/speed, and namespace-aware extension XML.
- Overlays every track in a color-coded layer list with selection, visibility, renaming, duplication, deletion, color, and ordering controls.
- Draws straight segments offline or bicycle-routed segments through BRouter's public service with touring, road, gravel, low-traffic, and shortest profiles.
- Selects, moves, deletes, splits, and trims track points directly from the map.
- Reverses tracks with chronological timestamp repair; combines tracks while retaining gaps or explicitly stitches them continuously.
- Auto-orders and orients imported sources to minimize endpoint gaps before merging.
- Removes duplicates and likely GPS spikes, simplifies geometry with protected semantic points, smooths/fills elevation, and generates/shifts/removes timestamps.
- Splits a master route into exact, consecutively numbered daily stages by target distance.
- Shows WGS84 distance, ascent/descent, time, speed, point counts, and an elevation profile.
- Exports the whole project, only the selected track, or a ZIP containing one GPX per track/day.
- Provides 75-step undo/redo, atomic internal autosave, crash recovery, and explicit GPX 1.0 or 1.1 export.
- Uses OpenFreeMap/MapLibre without an API key. GPX editing and export work without a backend.

No account, analytics SDK, broad storage permission, location permission, or VeloGPX backend is used. Online map display and the optional public BRouter request require internet; file editing does not.

## Install

Download `VeloGPX-1.0.0.apk` from the latest GitHub Release and allow installation from your browser/files app. The release APK is signed with a development key for direct personal installation.

Android 8.0 (API 26) or newer is supported. The app targets Android 17/API 37.

## Build

Requirements: JDK 17, Android SDK Platform 37, and Build Tools 37.0.0.

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

The local build produces:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

The release build intentionally uses the standard Android debug signing key for this personal pre-Play release. Configure a private release keystore before publishing to an app store.

## Using the editor

1. Tap the folder icon and import one or several GPX files.
2. Use **Layers** to color, hide, order, duplicate, or select sources.
3. On **Map**, use Select, Line, Route, Move, Split, or POI mode. Routed drawing sends only the selected anchors to BRouter.
4. Open the wand button for transforms, merge choices, timestamp/elevation tools, or daily-stage planning.
5. Inspect **Profile**, undo/redo as needed, then export from the overflow menu.

Important semantics:

- Combining as segments preserves discontinuities and does not count gaps in distance.
- Stitching creates explicit straight connecting edges.
- Auto-order/orient reports the resulting connector-gap total and remains undoable.
- GPX 1.0 is retained by default after import; choose the desired version explicitly on export.
- Source files are never overwritten. Export is always explicit.

## Engineering notes

See [product research](docs/PRODUCT.md), [architecture](docs/ARCHITECTURE.md), and [validation](docs/TESTING.md). The project is Apache-2.0 licensed.
