#!/usr/bin/env python3
"""Build real Vietnam OSM speed/sign datasets from the Geofabrik PBF.

Never infer an untagged limit. National OSM traffic-sign codes are preserved
verbatim; unrecognized signs are informational, not interpreted regulations.
"""
import argparse
import datetime as dt
import gzip
import json
import math
import os
import re
import sqlite3
from pathlib import Path

import osmium

SOURCE = "https://download.geofabrik.de/asia/vietnam-latest.osm.pbf"
MAX_SPEED = re.compile(r"^\s*(\d{1,3})(?:\s*(km/h|kmh|kph|mph))?\s*$", re.I)
VALID_HIGHWAYS = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary",
    "primary_link", "secondary", "secondary_link", "tertiary",
    "tertiary_link", "residential", "unclassified", "living_street",
    "service", "road",
}


def tagged_limit(text):
    if not text:
        return None
    match = MAX_SPEED.fullmatch(text)
    if not match:
        return None
    speed = int(match.group(1))
    if match.group(2) and match.group(2).lower() == "mph":
        speed = round(speed * 1.609344)
    return speed if 5 <= speed <= 200 else None


def explicit_sign_speed(tags):
    """Only numeric signed speed limits explicitly tagged on a sign node."""
    code = tags.get("traffic_sign", "").lower()
    if not code or not any(marker in code for marker in (
        "maxspeed", "speed_limit", "speed-limit", "p.127",
    )):
        return None
    # A country-code identifier by itself never supplies a numeric limit.
    return tagged_limit(tags.get("traffic_sign:maxspeed") or tags.get("maxspeed"))


def classified_sign(tags):
    if explicit_sign_speed(tags) is not None:
        return "SPEED_LIMIT"
    code = tags.get("traffic_sign", "")
    if tags.get("highway") == "stop":
        return "STOP"
    if tags.get("highway") == "give_way":
        return "GIVE_WAY"
    if tags.get("railway") == "level_crossing":
        return "RAILWAY"
    normalized = code.strip().lower()
    if normalized in ("stop", "vn:r.122"):
        return "STOP"
    if normalized in ("give_way", "yield"):
        return "GIVE_WAY"
    if normalized in ("pedestrian_crossing",):
        return "PEDESTRIAN"
    if normalized in ("hazard", "danger"):
        return "HAZARD"
    if code:
        return "OTHER"
    return None


def valid_point(lat, lon):
    return math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180


def start_database(path):
    con = sqlite3.connect(path)
    con.executescript("""
        PRAGMA journal_mode=DELETE;
        PRAGMA synchronous=OFF;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE signs (
          id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,
          kind TEXT NOT NULL, osm_code TEXT NOT NULL, direction TEXT,
          road_name TEXT, road_heading REAL, road_way_id INTEGER,
          speed_limit_kmh INTEGER
        );
        CREATE INDEX signs_coord ON signs(lat, lon);
        CREATE TABLE speed_segments (
          way_id INTEGER PRIMARY KEY, name TEXT NOT NULL,
          maxspeed_kmh INTEGER, forward_kmh INTEGER, backward_kmh INTEGER,
          minlat REAL NOT NULL, maxlat REAL NOT NULL,
          minlon REAL NOT NULL, maxlon REAL NOT NULL, geometry TEXT NOT NULL
        );
        CREATE INDEX speeds_bbox ON speed_segments(minlat, maxlat, minlon, maxlon);
    """)
    return con


class Signs(osmium.SimpleHandler):
    def __init__(self, con):
        super().__init__()
        self.con = con
        self.count = 0
        self.sign_ids = set()

    def node(self, node):
        tags = {k: v for k, v in node.tags}
        kind = classified_sign(tags)
        if not kind:
            return
        try:
            lat, lon = float(node.location.lat), float(node.location.lon)
        except (ValueError, RuntimeError):
            return
        if not valid_point(lat, lon):
            return
        self.con.execute(
            "INSERT OR REPLACE INTO signs (id,lat,lon,kind,osm_code,direction,speed_limit_kmh) VALUES (?,?,?,?,?,?,?)",
            (node.id, lat, lon, kind, tags.get("traffic_sign", ""),
             tags.get("traffic_sign:direction") or tags.get("direction"),
             explicit_sign_speed(tags)),
        )
        self.sign_ids.add(int(node.id))
        self.count += 1
        if self.count % 5000 == 0:
            self.con.commit()


def segment_heading(coords, pos, backwards=False):
    lo = max(0, pos - 1)
    hi = min(len(coords) - 1, pos + 1)
    if lo == hi:
        return None
    lat1, lon1 = coords[lo]
    lat2, lon2 = coords[hi]
    north = lat2 - lat1
    east = (lon2 - lon1) * math.cos(math.radians((lat1 + lat2) * 0.5))
    if abs(north) + abs(east) < 1e-8:
        return None
    heading = (math.degrees(math.atan2(east, north)) + 360.0) % 360.0
    return (heading + 180.0) % 360.0 if backwards else heading


class Roads(osmium.SimpleHandler):
    def __init__(self, con, sign_ids):
        super().__init__()
        self.con = con
        self.sign_ids = sign_ids
        self.sign_roads = {}
        self.speed_count = 0

    def way(self, way):
        tags = {k: v for k, v in way.tags}
        if tags.get("highway") not in VALID_HIGHWAYS:
            return
        main = tagged_limit(tags.get("maxspeed"))
        forward = tagged_limit(tags.get("maxspeed:forward"))
        backward = tagged_limit(tags.get("maxspeed:backward"))
        # Conditional limits depend on time, weather, vehicle class, etc.
        # Do not guess which condition is active.
        if any(k.startswith("maxspeed:conditional") or k in (
            "maxspeed:forward:conditional", "maxspeed:backward:conditional"
        ) for k in tags):
            main = forward = backward = None
        related = any(n.ref in self.sign_ids for n in way.nodes)
        if main is None and forward is None and backward is None and not related:
            return
        try:
            positions = [[round(n.lat, 7), round(n.lon, 7)] for n in way.nodes]
        except (ValueError, RuntimeError):
            return
        if len(positions) < 2 or any(not valid_point(*p) for p in positions):
            return
        name = tags.get("name:vi") or tags.get("name") or ""
        if main is not None or forward is not None or backward is not None:
            lats = [p[0] for p in positions]
            lons = [p[1] for p in positions]
            self.con.execute(
                "INSERT OR REPLACE INTO speed_segments VALUES (?,?,?,?,?,?,?,?,?,?)",
                (way.id, name, main, forward, backward,
                 min(lats), max(lats), min(lons), max(lons),
                 json.dumps(positions, separators=(",", ":"))),
            )
            self.speed_count += 1
        if related:
            for i, nd in enumerate(way.nodes):
                if nd.ref not in self.sign_ids:
                    continue
                direction = self.con.execute(
                    "SELECT direction FROM signs WHERE id=?", (nd.ref,)
                ).fetchone()
                if not direction or direction[0] not in ("forward", "backward"):
                    continue
                head = segment_heading(positions, i, direction[0] == "backward")
                if head is None or not name:
                    continue
                item = (str(name), head, int(way.id))
                prev = self.sign_roads.get(nd.ref)
                # Intersection nodes can lie on multiple roads: do not
                # attach ambiguous road headings to a driving warning.
                if prev is None:
                    self.sign_roads[nd.ref] = item
                elif prev != item:
                    self.sign_roads[nd.ref] = ("", None, None)
        if self.speed_count and self.speed_count % 2500 == 0:
            self.con.commit()


def write_json(con, sql, output, generated, kind):
    count = 0
    with open(output, "w", encoding="utf-8") as handle:
        handle.write(json.dumps({
            "schema": 2, "region": "VN", "type": kind,
            "generated_at": generated, "source": SOURCE,
            "license": "ODbL; © OpenStreetMap contributors",
        }, ensure_ascii=False)[:-1] + ',"features":[')
        for row in con.execute(sql):
            if count:
                handle.write(",")
            handle.write(json.dumps(dict(row), ensure_ascii=False, separators=(",", ":")))
            count += 1
        handle.write("]}")
    with open(output, "rb") as inp, gzip.open(str(output) + ".gz", "wb", compresslevel=6) as out:
        for block in iter(lambda: inp.read(1024 * 1024), b""):
            out.write(block)
    return count


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pbf")
    parser.add_argument("--output", default="dist/vietnam")
    args = parser.parse_args()
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    db = out / "vietnam_road_data.db"
    if db.exists():
        db.unlink()
    con = start_database(db)
    con.row_factory = sqlite3.Row
    generated = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    signs = Signs(con)
    signs.apply_file(args.pbf)
    con.commit()
    roads = Roads(con, signs.sign_ids)
    roads.apply_file(args.pbf, locations=True, idx="flex_mem")
    con.commit()
    for sid, (name, head, way_id) in roads.sign_roads.items():
        if name and head is not None:
            con.execute(
                "UPDATE signs SET road_name=?,road_heading=?,road_way_id=? WHERE id=?",
                (name, head, way_id, sid),
            )
    for key, value in [
        ("schema", "2"), ("generated_at", generated), ("source", SOURCE),
        ("region", "VN"), ("attribution", "© OpenStreetMap contributors"),
    ]:
        con.execute("INSERT INTO meta VALUES (?,?)", (key, value))
    con.commit()
    n_speed = write_json(con,
        "SELECT * FROM speed_segments ORDER BY way_id",
        out / "vietnam_speed_limits.json", generated, "speed_limits")
    n_sign = write_json(con,
        "SELECT * FROM signs ORDER BY id",
        out / "vietnam_traffic_signs.json", generated, "traffic_signs")
    con.execute("ANALYZE")
    con.commit()
    con.close()
    with open(db, "rb") as inp, gzip.open(str(db) + ".gz", "wb", compresslevel=6) as compressed:
        for block in iter(lambda: inp.read(1024 * 1024), b""):
            compressed.write(block)
    manifest = {
        "schema": 2, "region": "VN", "generated_at": generated,
        "source": SOURCE, "signs": n_sign, "speed_segments": n_speed,
        "attribution": "© OpenStreetMap contributors",
    }
    (out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(manifest))
    if n_sign == 0 and n_speed == 0:
        raise SystemExit("No OSM speed limits or traffic signs extracted")


if __name__ == "__main__":
    main()
