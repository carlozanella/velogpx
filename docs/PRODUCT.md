# Product research and scope

Research was checked on 21 July 2026 against the live products and primary documentation.

## Design target

The central workflow is non-destructive assembly of long bicycle tours: overlay independent EuroVelo/Komoot/other sources, inspect direction and gaps, trim or split exactly, bridge selected gaps, retain source hierarchy until the user chooses to stitch, plan days, and export a deterministic master GPX.

The baseline reviewed was [gpx.studio](https://gpx.studio/help/toolbar): multi-file editing, route drawing, POIs, crop/split, timestamp tools, merging/extraction, elevation, minification, rectangular cleaning, profiles, and map controls. Competitive references included:

- [OsmAnd Plan a Route](https://osmand.net/docs/user/plan-route/create-route/) for Android/offline and per-span routing.
- [QMapShack](https://github.com/Maproom/qmapshack) for persistent projects and deep desktop transforms.
- [Ride with GPS](https://support.ridewithgps.com/hc/en-us/articles/4419016103835-Split-Combine-or-Change-an-Existing-Route) for bicycle-specific split/combine/cue workflows.
- [Komoot advanced planning](https://support.komoot.com/hc/en-us/articles/10268757747738-Advanced-route-planning) and its documented external [multi-tour GPX merge workflow](https://support.komoot.com/hc/en-us/articles/10844763435674-Merge-multiple-komoot-tours-into-one-using-the-GPX-Merger).
- Garmin BaseCamp's [track editing](https://www8.garmin.com/manuals/webhelp/basecamppc/EN-US/GUID-CAB01C01-DC90-419F-883F-5B862FA5DCFB.html).
- EuroVelo's [GPX downloads](https://en.eurovelo.com/news/2022-07-25_you-can-now-download-eurovelo-routes-and-stages-as-gpx-tracks), including the warning that full routes may contain developing sections.

## Implemented release requirements

1. Data-safe GPX 1.0/1.1 import/export with unknown extension preservation and explicit version choice.
2. Multi-source layers, route/track/segment distinction, colored overlays, ordering, selection, and non-destructive autosave.
3. Point selection/move/delete, exact split, both-sided trim, reverse, duplicate, and 75-step undo/redo.
4. Gap-preserving combine versus explicit stitch, plus endpoint-based source auto-ordering/orientation.
5. Bicycle route drawing with configurable BRouter profile and a straight-line offline fallback.
6. Simplification, duplicate/spike removal, elevation smoothing/interpolation, and timestamp generation/shift/clear.
7. WGS84 geodesic statistics, segment-gap-safe distance, elevation profile, and daily-stage generation.
8. Android Storage Access Framework/share-intent integration, no broad storage permission, and crash recovery.

## Longer-term depth

The architecture deliberately leaves clean provider boundaries for bundled BRouter `.rd5` regions, imported local PMTiles, DEM elevation lookup, OSM surface/access auditing, selected-span road matching, overlap removal, alternative comparison, stage optimization around overnight POIs, KML/TCX/FIT, and cue sheets. Those require substantial map datasets or additional format engines and are not disguised as backend-dependent features.

No backend is justified for the editing core. Sync, collaboration, and public share links would be the first features that warrant one.

