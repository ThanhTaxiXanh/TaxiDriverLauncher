# Vietnam OSM driving data (schema 2)

This Android Auto integration uses **only tagged OpenStreetMap data** extracted from the Vietnam Geofabrik PBF. It does not infer a legal speed limit from road classification, lane count, built-up area, province, GPS speed, or a nearby unrelated sign.

## GitHub Actions pipeline

- `.github/workflows/vietnam-osm-data.yml`: downloads the current Geofabrik Vietnam extract and runs `.github/scripts/build-vietnam-osm.py`.
- Release `vietnam-osm-data`: `vietnam_speed_limits.json`, `vietnam_traffic_signs.json`, compressed equivalents, `vietnam_road_data.db.gz`, `manifest.json`.
- `.github/workflows/android-auto-conversion.yml`: requires schema 2 SQLite, validates nonempty tables and packages a gzip database as `assets/vietnam_road_data.osmcache` in the debug APK. Data are attributable to © OpenStreetMap contributors (ODbL).

## Safety boundaries

- Road speed limits originate only from explicit numeric `maxspeed`, `maxspeed:forward` or `maxspeed:backward` tags on a matched OSM highway way. Conditional or nonnumeric values are never guessed.
- A fallback GPS geometry match requires a recent accurate GPS fix, correct road direction, and a unique nearby tagged way. A contradictory reliable Mapbox road name blocks fallback.
- Traffic sign nodes may have `traffic_sign`, `highway=stop`, `highway=give_way`, or `railway=level_crossing`. A numeric speed sign requires an explicitly recognized speed-limit sign type **and** a numeric speed tag. A bare country sign code is not enough to identify a numeric limit.
- Within the 500m search radius, OSM sign coordinates display as pins when actually on screen. Offscreen sign HUD information is labeled as reference only: distance is straight-line and direction is relative to GPS, **not** a turn instruction, route prediction, or regulatory warning.
- Speed limits missing or ambiguous on a given road remain unknown (`—`). Actual posted road signs and applicable laws always take precedence.

## iPhone workflow

Open GitHub Actions and download the `taxi-driver-android-auto-debug` artifact from a successful Android Auto conversion run. Unzip it in iOS Files; move the APK to the Android device that runs Android Auto for installation and validation. The GitHub release provides raw/compressed JSON exports for auditing.
