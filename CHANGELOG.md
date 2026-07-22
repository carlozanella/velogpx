# Changelog

## 1.2.0 — 2026-07-22

- Added an always-available interactive distance/elevation profile for the selected track, exact tap cursors, source-point/edge identity, coordinates, elevation, time, and distance from route start.
- Added foreground current-location display and optional 200 m nearest-route projection on both the map and profile; no background location is collected.
- Added an explicit map multi-select mode with bulk delete and a first-class Merge workflow for preserved gaps, direct connectors, or BRouter-planned bicycle connections.
- Added source groups on import, add-to-current/new-project choice, exact-geometry duplicate suppression, automatic imported-route focus, and persisted Layers scroll position.
- Fixed layer-to-map focus around the profile viewport and bounded chart/handle work for large EuroVelo imports.
- Fixed Garmin Connect integration by opening one real GPX document with Android `ACTION_VIEW`, the contract Connect advertises, while retaining an app chooser fallback.
- Added shared continuous track-position and import-deduplication engines with host tests, plus Android tests for Garmin's open-file contract.

## 1.1.0 — 2026-07-22

- Added named, atomic multi-project archives with last-project restore, full editor/map state, autosave status, snapshots, corruption recovery, and legacy migration.
- Added exact layer focus, rendered-line selection with overlap chooser, persistent multi-select, bulk actions, and collapsible groups.
- Added transactional repeated split previews and two-marker span extraction.
- Added guided selected-track joining with optimized order/orientation, gap previews, safe gap/straight/routed connector policies, and source-retention choice.
- Replaced implicit routed drawing with a dedicated BRouter planner for explicit start/end/vias, profiles, up to four alternatives, metrics, and safe new/append/prepend application.
- Added GPX project/track/segment sharing to Garmin Connect with Android chooser fallback.
- Added typed/cancellable routing, stale-result guards, exact connector endpoints, and comprehensive project/routing/join/range/share tests.

## 1.0.0 — 2026-07-22

- Initial VeloGPX Android release.
