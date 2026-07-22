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
        ├── edit engine (range/split/join/trim/reverse/clean/time/elevation/stages)
        ├── analysis engine (geodesic distance/elevation/time/speed)
        └── RoutingProvider ─── BRouter HTTP implementation
```

The source `GpxDocument` remains the interchange truth. Tracks retain segment boundaries, routes remain routes until an explicit conversion, and waypoints remain independent. Presentation state is stored beside GPX in the project archive: stable IDs, order, styles, groups, multi-selection, camera, panel, and routing profile never leak into exported GPX.

Every geometry edit creates a new immutable document revision, adds the prior revision to bounded history, clears redo after divergence, and submits a versioned project state. Autosave is debounced at 750 ms with a five-second maximum delay and is flushed when the app backgrounds. The `.velogpx` ZIP contains a checksummed GPX document and versioned manifest; writes use `AtomicFile`, snapshots rotate by age/size/count, and corrupt current archives recover from a verified snapshot.

Unknown extension nodes retain namespace URI, local name, prefix hint, ordered attributes, namespace declarations, and ordered text/CDATA/comment/element children. This is semantic XML preservation rather than byte-for-byte whitespace preservation.

## Map and network policy

MapLibre renders per-track GeoJSON sources; only sampled edit handles are displayed for large tracks while full geometry remains in the model/export. OpenFreeMap avoids an SDK key and explicitly supports public use. VeloGPX never bulk-downloads the OpenStreetMap standard tile service.

All editing functions are local. `RoutingProvider` is replaceable: v1.1 uses BRouter's public HTTPS endpoint only after the dedicated planner receives explicit start/end anchors and the user requests alternatives. Requests/results are typed, cancellable, size-limited, and revision/token/profile-bound. A bundled/offline BRouter provider can be added without changing the editor engine. No helo backend is deployed.

## Safety

- XML secure processing, DTD/external entity rejection, a conservative 32 MB input cap, and a two-million-point semantic limit.
- Finite WGS84 coordinate validation; invalid values are rejected rather than clamped.
- Segment gaps are excluded from statistics unless an explicit stitch creates an edge.
- Split duplicates its boundary point; repeated cuts and span extraction resolve stable segment/edge/point IDs transactionally before emitting output.
- Join planning preserves source payload, uses exact or deterministic endpoint ordering, keeps gaps by default, and attaches routed connectors to exact source endpoints.
- Route, join, and split drafts validate their source document revision before Apply and each becomes a single undo step.
- Source files are never overwritten and internal autosave uses temporary-file replacement.
