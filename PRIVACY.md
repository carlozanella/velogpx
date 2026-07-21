# Privacy

VeloGPX stores projects only in its private Android app storage and in files you explicitly choose for export. It has no account system, advertising, analytics, crash-reporting SDK, or proprietary backend.

The app makes network requests only for:

- the OpenFreeMap vector basemap displayed by MapLibre; and
- optional bicycle routing requested in Route mode through the public BRouter service.

Route anchors included in an explicit BRouter request are necessarily visible to that service. Straight-line editing, GPX transforms, statistics, autosave, recovery, and export are on-device. Android's Storage Access Framework is used instead of broad filesystem access.

