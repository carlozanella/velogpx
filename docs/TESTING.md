# Validation strategy

The host test suite covers:

- WGS84 equatorial, antimeridian, antipodal, interpolation, and wrapped-bounds cases.
- Segment-gap-safe analysis, exact split invariants, reverse-twice identity, chronological reverse, protected simplification, and time generation.
- Daily-stage exact coverage and boundary continuity.
- GPX 1.1 hierarchy/Unicode/extension round trips and GPX 1.0 metadata/course/speed/direct-extension round trips.
- Invalid coordinates and external-entity rejection.

The release gate currently runs 28 host tests and 2 Android-runtime tests. The latter specifically verify parsing and DTD/external-entity rejection under Android's platform XML implementation.

Run all automated checks with:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The emulator smoke procedure uses an Android 17/API 37.1 16-KB-page x86_64 image:

1. Install the release APK.
2. Launch and import `samples/eurovelo-demo.gpx` through an Android VIEW intent.
3. Confirm both tracks and the waypoint render, layer/profile screens open, and there are no process crashes.
4. Exercise a transform, undo/redo, and export.
5. Pull the exported GPX and parse it again with the codec tests.

The release candidate passed all 30 tests, Android lint with no issues, both APK assemblies, import/render/profile/layer navigation, reverse + undo + redo, and a Storage Access Framework export/reparse on the API 37 emulator. This repository's GitHub Actions workflow repeats unit tests, lint, and APK assembly on each push.
