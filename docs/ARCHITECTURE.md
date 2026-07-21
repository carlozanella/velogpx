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
EditorViewModel ─── undo/redo snapshots ─── ProjectStore (atomic private autosave)
        │
        ├── GPX codec (1.0/1.1 + structured extension XML)
        ├── edit engine (split/trim/reverse/merge/clean/time/elevation/stages)
        ├── analysis engine (geodesic distance/elevation/time/speed)
        └── RoutingProvider ─── BRouter HTTP implementation
```

The source `GpxDocument` remains the interchange truth. Tracks retain segment boundaries, routes remain routes until an explicit conversion, and waypoints remain independent. UI visibility/color is session presentation state rather than being silently written into GPX extensions.

Every geometry edit creates a new immutable model revision, adds the prior revision to bounded history, clears redo after divergence, atomically autosaves, and leaves external source files untouched. GPX export streams to the user-selected SAF URI.

Unknown extension nodes retain namespace URI, local name, prefix hint, ordered attributes, namespace declarations, and ordered text/CDATA/comment/element children. This is semantic XML preservation rather than byte-for-byte whitespace preservation.

## Map and network policy

MapLibre renders per-track GeoJSON sources; only sampled edit handles are displayed for large tracks while full geometry remains in the model/export. OpenFreeMap avoids an SDK key and explicitly supports public use. VeloGPX never bulk-downloads the OpenStreetMap standard tile service.

All editing functions are local. `RoutingProvider` is replaceable: the first release uses BRouter's public HTTPS endpoint only after a Route-mode tap. A bundled/offline BRouter provider can be added without changing the editor engine. No helo backend is deployed because it would add operational and privacy cost without enabling the core.

## Safety

- XML secure processing, DTD/external entity rejection, a conservative 32 MB input cap, and a two-million-point semantic limit.
- Finite WGS84 coordinate validation; invalid values are rejected rather than clamped.
- Segment gaps are excluded from statistics unless an explicit stitch creates an edge.
- Split duplicates its boundary point; stage splitting inserts geodesically interpolated boundaries so coverage and distance are invariant.
- Source files are never overwritten and internal autosave uses temporary-file replacement.
