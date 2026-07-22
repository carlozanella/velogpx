# VeloGPX

VeloGPX is a local-first Android GPX editor built for assembling long bicycle tours from EuroVelo, Komoot, Garmin, and other GPX sources.

## What it does

- Imports several GPX 1.0/1.1 files through Android's file picker, share sheet, or “Open with”.
- Preserves tracks, track segments, routes, waypoints, metadata, timestamps, elevation, legacy course/speed, and namespace-aware extension XML.
- Overlays every track in a color-coded layer list with exact map focus, direct line picking, persistent multi-selection, bulk show/hide/delete/share, and collapsible groups.
- Provides an explicit BRouter planner for start, end, and via anchors; touring, road, gravel, low-traffic, and shortest profiles; up to four preview alternatives; and safe new/append/prepend application.
- Selects, moves, deletes, trims, repeatedly splits, and extracts spans directly from the map with transactional previews.
- Guides joining selected tracks through an optimized order/orientation preview, per-draft keep-gap/straight/routed connector policy, exact connector endpoints, and keep-or-replace source choice.
- Removes duplicates and likely GPS spikes, simplifies geometry with protected semantic points, smooths/fills elevation, and generates/shifts/removes timestamps.
- Splits a master route into exact, consecutively numbered daily stages by target distance.
- Shows WGS84 distance, ascent/descent, time, speed, point counts, and an elevation profile.
- Exports the whole project, only the selected track, or a ZIP containing one GPX per track/day, and shares projects/tracks/segments to Garmin Connect through Android.
- Stores multiple named projects as checksummed atomic `.velogpx` archives, restores the last project/camera/layers/groups/selection, autosaves every change, rotates recovery snapshots, and provides 75-step undo/redo.
- Uses OpenFreeMap/MapLibre without an API key. GPX editing and export work without a backend.

No account, analytics SDK, broad storage permission, location permission, or VeloGPX backend is used. Online map display and the optional public BRouter request require internet; file editing does not.

## Install

Download `VeloGPX-1.1.0.apk` from the latest GitHub Release and allow installation from your browser/files app. The release APK is signed with a development key for direct personal installation.

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
2. Use **Layers** to focus a route, or tap **Select** for multi-track bulk actions, grouping, guided joining, or Garmin sharing.
3. On **Map**, use Select, Line, Move, Split, or POI. Tap **Plan** for the dedicated route planner; it never chooses a hidden starting point.
4. For joining, select two or more layers and preview order, reversed sources, gaps, connector policy, output name, and source retention before Apply.
5. In Split mode, tap the rendered track, add one or several cut markers, then split once or extract the span between exactly two markers.
6. Open the wand button for cleaning, timestamp/elevation tools, daily-stage planning, and other transforms.
7. Inspect **Profile**, undo/redo as needed, then export or open **Garmin** from Layers.

Important semantics:

- Join previews do not modify the project; Apply creates one undoable edit. Keeping gaps does not count discontinuities as travelled distance.
- Split/range and route-planner previews are also transactional and reject stale source geometry.
- Append/prepend never invents a teleport edge: an unmatched route is retained as a separate GPX segment.
- GPX 1.0 is retained by default after import; choose the desired version explicitly on export.
- Source files are never overwritten. Export is always explicit.

## Engineering notes

See [product research](docs/PRODUCT.md), [architecture](docs/ARCHITECTURE.md), and [validation](docs/TESTING.md). The project is Apache-2.0 licensed.
