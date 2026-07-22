# Architecture

## Platform

- Android 17 / API 37 target and compile SDK; minimum API 26.
- Android Gradle Plugin 9.3, Gradle 9.6.1, JDK 17, built-in Kotlin with Compose compiler 2.4.10.
- Jetpack Compose BOM 2026.06.01 and Material 3.
- MapLibre Native 13.4.0 with OpenFreeMap's no-key vector style and a local blank-style fallback.
- GeographicLib Java 2.1 for authoritative WGS84 distance, bearing, interpolation, antimeridian, and antipodal behavior.

## Boundaries

```text
Compose/MapLibre UI
        │ events + immutable state
EditorViewModel ─── undo/redo snapshots ─── ProjectRepository
        │                                  ├── atomic .velogpx ZIP + catalog
        │                                  ├── debounced/max-delay autosave
        │                                  └── rotating recovery snapshots
        │
        ├── GPX codec (1.0/1.1 + structured extension XML)
        ├── edit engine (range/split/merge/trim/reverse/clean/time/elevation/stages)
        ├── track-position engine (distance profile/cursor/location projection)
        ├── import identity engine (source grouping + exact geometry deduplication)
        ├── analysis engine (geodesic distance/elevation/time/speed)
        └── RoutingProvider ─── BRouter HTTP implementation
```

The source `GpxDocument` remains the interchange truth. Tracks retain segment boundaries, routes remain routes until an explicit conversion, and waypoints remain independent. Presentation state is stored beside GPX in the project archive: stable IDs, order, styles, source groups, multi-selection, layer scroll, camera, panel, and routing profile never leak into exported GPX.

`TrackPositionEngine` is the single definition of a continuous position along a multi-segment track. The map cursor, interactive elevation profile, point details, and foreground device-location projection all consume that model. Distances continue across segment boundaries without inventing distance across their gaps. A position can identify an exact recorded point or an interpolated fraction of an edge.

Every geometry edit creates a new immutable document revision, adds the prior revision to bounded history, clears redo after divergence, and submits a versioned project state. Autosave is debounced at 750 ms with a five-second maximum delay and is flushed when the app backgrounds. The `.velogpx` ZIP contains a checksummed GPX document and versioned manifest; writes use `AtomicFile`, snapshots rotate by age/size/count, and corrupt current archives recover from a verified snapshot.

Unknown extension nodes retain namespace URI, local name, prefix hint, ordered attributes, namespace declarations, and ordered text/CDATA/comment/element children. This is semantic XML preservation rather than byte-for-byte whitespace preservation.

## Map and network policy

MapLibre renders per-track GeoJSON sources; only cached sampled edit handles are displayed for large tracks while full geometry remains in the model/export. Elevation drawing is peak-preserving and bounded to 3,000 chart samples, while cursor calculations retain full precision. OpenFreeMap avoids an SDK key and explicitly supports public use. VeloGPX never bulk-downloads the OpenStreetMap standard tile service.

All editing functions are local. `RoutingProvider` is replaceable: VeloGPX uses BRouter's public HTTPS endpoint only after the dedicated planner receives explicit start/end anchors or the user chooses planned merge connections. Requests/results are typed, cancellable, size-limited, and revision/token/profile-bound. A bundled/offline BRouter provider can be added without changing the editor engine. No helo backend is deployed.

Location is foreground-only and opt-in. Android's `LocationManager` supplies the current coordinate after runtime permission; VeloGPX stops updates when the app leaves the foreground and never persists the coordinate. Nearest-route projection is local and appears on the profile only within 200 m.

## Safety

- XML secure processing, DTD/external entity rejection, a conservative 32 MB input cap, and a two-million-point semantic limit.
- Finite WGS84 coordinate validation; invalid values are rejected rather than clamped.
- Segment gaps are excluded from statistics unless an explicit stitch creates an edge.
- Split duplicates its boundary point; repeated cuts and span extraction resolve stable segment/edge/point IDs transactionally before emitting output.
- Merge planning preserves source payload, uses exact or deterministic endpoint ordering, replaces sources by default with an explicit keep-sources option, and attaches routed connectors to exact source endpoints.
- Route, merge, and split drafts validate their source document revision before Apply and each becomes a single undo step.
- Garmin handoff materializes one permission-granted `.gpx` content URI and uses `ACTION_VIEW`, matching Garmin Connect's advertised Android contract.
- Source files are never overwritten and internal autosave uses temporary-file replacement.
