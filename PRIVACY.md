# Privacy

VeloGPX stores projects only in its private Android app storage and in files you explicitly choose for export. It has no account system, advertising, analytics, crash-reporting SDK, or proprietary backend.

The app makes network requests only for:

- the OpenFreeMap vector basemap displayed by MapLibre; and
- optional bicycle routing explicitly requested in the route planner or for a join connector through the public BRouter service.

Route anchors included in an explicit BRouter request are necessarily visible to that service. Straight-line editing, GPX transforms, statistics, autosave, recovery, and export are on-device. Android's Storage Access Framework is used instead of broad filesystem access.

VeloGPX requests foreground location permission only after you tap the location button. While the editor is in the foreground it can show the latest Android-provided position and calculate the nearest point on the selected route locally. Tracking stops when the app leaves the foreground; location is not stored in the project, uploaded, or used for analytics.
