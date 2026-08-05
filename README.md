# VeloGPX

VeloGPX is a local-first Android GPX editor built for assembling long bicycle tours from EuroVelo, Komoot, Garmin, and other GPX sources.

## What it does

- Imports several GPX 1.0/1.1 files through Android's file picker, share sheet, or “Open with”.
- Preserves tracks, track segments, routes, waypoints, metadata, timestamps, elevation, legacy course/speed, and namespace-aware extension XML.
- Organizes every track in a color-coded group list with exact map focus, direct line picking, long-press and freehand-lasso multi-selection, combined selection profiles/totals, bulk show/hide/delete/share/merge, movable membership, and collapsible/visible groups.
- Provides an explicit BRouter planner for start, end, and via anchors; touring, road, gravel, low-traffic, and shortest profiles; up to four preview alternatives; and safe new/append/prepend application.
- Selects, moves, deletes, trims, repeatedly splits, and extracts spans directly from the map with transactional previews.
- Guides merging selected tracks through an optimized order/orientation preview, preserve-gap/direct/BRouter connector policies, exact connector endpoints, and keep-or-replace source choice.
- Removes duplicates and likely GPS spikes, simplifies geometry with protected semantic points, loads missing terrain elevation from the Copernicus GLO-90 DEM, smooths/interpolates elevation, and generates/shifts/removes timestamps.
- Splits a master route into exact, consecutively numbered daily stages by target distance.
- Shows WGS84 distance, ascent/descent, time, speed, point counts, and an interactive distance/elevation profile on the map with exact point/edge details.
- Shows current location on request and projects it onto the selected route/profile only when it is within 200 m.
- Exports the whole project, only the selected track, or a ZIP containing one GPX per track/day, and opens projects/tracks/segments as real GPX documents in Garmin Connect. Garmin handoffs use one continuous course so Connect retains every requested segment.
- Stores multiple named projects as checksummed atomic `.velogpx` archives, restores the last project/camera/groups/selection, autosaves every change, rotates recovery snapshots, and provides 75-step undo/redo.
- Uses OpenFreeMap/MapLibre without an API key. GPX editing and export work without a backend.

No account, analytics SDK, broad storage permission, or VeloGPX backend is used. Foreground location permission is requested only when you tap the location button. Online map display, optional public BRouter requests, and the explicit Open-Meteo terrain-elevation wand action require internet; other file editing does not.

## Install

Download `VeloGPX-1.4.3.apk` from the latest GitHub Release and allow installation from your browser/files app. The release APK is signed with a development key for direct personal installation.

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

Published releases use one protected, stable signing key so APK updates install over earlier versions. Local release builds fall back to the standard Android debug key unless `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` are configured. Configure a private production keystore before publishing to an app store.

## Using the editor

1. Tap the folder icon, choose one or several GPX files, then add them to this project or create a new one. Each file becomes a source group and exact reimports are skipped.
2. Use **Tracks** to collapse or hide whole groups. Select a group or individual tracks, then use **Move** to create, move into, or merge groups. Hidden groups stay out of map taps and lasso selection, so you can isolate one import, trim it, and restore the others afterward.
3. On **Map**, use Select, Line, Move, Split, or POI. Tap **Plan** for the dedicated route planner; it never chooses a hidden starting point.
4. For merging, select two or more tracks, choose preserved gaps, direct lines, or bicycle-planned connections, then preview order, direction, output name, and source retention.
5. In Split mode, tap the rendered track, add one or several cut markers, then split once or extract the span between exactly two markers.
6. Open the wand button for cleaning, timestamp/elevation tools, daily-stage planning, and other transforms. **Load terrain elevation** fills only missing values using Copernicus GLO-90 data via Open-Meteo; existing recorded elevations are retained.
7. Tap anywhere on the bottom profile for an exact route cursor. The location button shows your position and, when nearby, its corresponding profile position.
8. Undo/redo as needed, then export or open **Garmin** from Tracks.

Important semantics:

- Merge previews do not modify the project; Merge creates one undoable edit. Preserved gaps do not count discontinuities as travelled distance.
- Split/range and route-planner previews are also transactional and reject stale source geometry.
- Append/prepend never invents a teleport edge: an unmatched route is retained as a separate GPX segment.
- GPX 1.0 is retained by default after import; choose the desired version explicitly on export.
- Source files are never overwritten. Export is always explicit.

## Engineering notes

See [product research](docs/PRODUCT.md), [architecture](docs/ARCHITECTURE.md), and [validation](docs/TESTING.md). The project is Apache-2.0 licensed.
