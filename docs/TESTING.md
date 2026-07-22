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

The v1.1 release gate runs 67 host tests and 7 Android-runtime tests. Runtime coverage verifies Android XML security plus readable GPX 1.1 track/segment materialization, URI grants, multi-file sharing, chooser fallback, and Garmin Connect targeting.

Run all automated checks with:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The emulator smoke procedure uses an Android 17/API 37.1 16-KB-page x86_64 image:

1. Install the release APK.
2. Clear emulator app data, launch, draw geometry, wait for autosave, force-stop, relaunch, and verify the last project and exact track/point counts restore.
3. Open the explicit planner and verify it begins with no preselected start; place start/end anchors and exercise the BRouter response path.
4. Duplicate a track, enter multi-select, select all, preview a join, apply it, and undo back to the original counts.
5. Enter split mode on overlapping geometry and verify the track chooser appears before any cut is placed.
6. Open Projects and validate the current project summary, snapshot action, and persistent counts.
7. Exercise layer/profile navigation, Garmin/share instrumentation, and confirm no fatal process logs.

The v1.1 release candidate passed all 74 tests, Android lint with no issues, debug/release APK assembly, API 37 installation/version verification, project autosave across process death, planner smoke, multi-select, overlap disambiguation, join preview/apply/undo, and project-browser validation. This repository's GitHub Actions workflow repeats unit tests, lint, and APK assembly on each push.
