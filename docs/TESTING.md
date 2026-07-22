# Validation strategy

The host test suite covers:

- WGS84 equatorial, antimeridian, antipodal, interpolation, and wrapped-bounds cases.
- Segment-gap-safe analysis, exact split invariants, reverse-twice identity, chronological reverse, protected simplification, and time generation.
- Daily-stage exact coverage and boundary continuity.
- GPX 1.1 hierarchy/Unicode/extension round trips and GPX 1.0 metadata/course/speed/direct-extension round trips.
- Invalid coordinates and external-entity rejection.
- Atomic project archive round trips, catalog CRUD, multi-selection/editor-state persistence, autosave timing, snapshots, corruption recovery, and legacy migration.
- Exact/heuristic join ordering, orientations and constraints, gap policies, descendant-ID uniqueness, and distance/segment preservation.
- Stable projected locations, repeated cuts, cut coalescing, span extraction, gaps, reversed ranges, and distance conservation.
- Typed BRouter requests, profiles/alternative indices, metrics, anchor snaps, HTTP/network/malformed/oversize responses, and cancellation.
- Garmin GPX subsetting, safe filenames, FileProvider grants, chooser fallback, direct Connect preference, and multiple-file shares.
- Continuous profile distance, edge interpolation, exact-point identity, nearest-route projection, segment-gap handling, and single-point segments.
- Exact import geometry identity, repeated-batch suppression, direction and segment-boundary distinction, and persisted layer-scroll state.
- Combined-selection profiles, merge-order/orientation mapping, excluded endpoint gaps, and freehand-lasso containment/intersection geometry.

The v1.2.1 release gate runs 85 host tests and 10 Android-runtime tests. Runtime coverage verifies the freehand lasso gesture overlay, Android XML security, readable GPX 1.1 track/segment materialization, URI grants, send and open-file chooser fallbacks, and Garmin Connect `ACTION_VIEW` targeting.

Run all automated checks with:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The emulator smoke procedure uses an Android 17/API 37.1 16-KB-page x86_64 image:

1. Install the release APK.
2. Clear emulator app data, launch, draw geometry, wait for autosave, force-stop, relaunch, and verify the last project and exact track/point counts restore.
3. Open the explicit planner and verify it begins with no preselected start; place start/end anchors and exercise the BRouter response path.
4. Duplicate a track, enter multi-select, select all, preview a merge, apply it, and undo back to the original counts.
5. Enter split mode on overlapping geometry and verify the track chooser appears before any cut is placed.
6. Open Projects and validate the current project summary, snapshot action, and persistent counts.
7. Tap the distance profile and verify its labelled cursor, exact distance/elevation/coordinate details, and matching prominent map marker.
8. Grant foreground location, inject an emulator fix near and far from the route, and verify the 200 m profile projection threshold.
9. Exercise layer/profile navigation, import source grouping/deduplication, Garmin open-file instrumentation, and confirm no fatal process logs.
10. Select one or several tracks, tap empty map space, and verify the highlight/profile/cursor clear while Multi mode remains active when applicable.
11. Long-press a Layers row to enter Multi, lasso several visible tracks on the map, and verify the combined profile/total follows the same order and directions as Merge.

The v1.2.1 release gate covers 95 tests, Android lint, debug/release APK assembly, API 37 installation/version verification, selection persistence, import grouping, interactive profile/point detail, lasso and long-press multi-select, merge preview, and Garmin open-file validation. This repository's GitHub Actions workflow repeats unit tests, lint, and APK assembly on each push.
