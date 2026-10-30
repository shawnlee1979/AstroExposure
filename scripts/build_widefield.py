#!/usr/bin/env python3
"""Builds data/src/main/resources/catalog/widefield.json: the built-in
wide-field viewpoint list (design §6.1, ui-design §7.8).

Admission rule (fixed): Milky-Way segments only, anchored on an
in-segment landmark, at 20–40° major-axis scale. Single deep-sky
objects and small asterisms are never viewpoints. The table below IS
the source of truth; the JSON is its product. Anchors and extents
are editorial approximations with the same status as the
constellation table's centres and sizes. The first entry's anchor is
the galactic centre itself and must stay identical to the domain's
GalacticCenter constants (a contract test asserts the equality).
"""

import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "data/src/main/resources/catalog/widefield.json"
DATA_VERSION = "Editorial wide-field viewpoint list v1 (2026-10-29); anchors and extents are approximations"
SOURCE = "Editorial list (this repository)"

# id, nameZh, nameEn, anchorRaHours, anchorDecDeg, sizeMajorDeg, sizeMinorDeg, noteZh, noteEn
ROWS = [
    ("galactic-centre",
     "天蝎–人马银河段（银心）", "Scorpius–Sagittarius Milky Way (Galactic Centre)",
     17.75, -29.0, 35, 12,
     "银河最亮核心区，礁湖、三裂星云同段；入秋后只剩黄昏西南短窗。",
     "The brightest core of the Milky Way, with the Lagoon and Trifid in the same stretch; by late autumn only a short dusk window in the southwest remains."),
    ("scutum-star-cloud",
     "盾牌座银河亮云", "Scutum Star Cloud",
     18.85, -8.5, 20, 10,
     "夏季银河的一团亮云，野鸭星团 M11 在其北缘。",
     "A bright cloud of the summer Milky Way; the Wild Duck Cluster M11 sits at its northern edge."),
    ("cygnus-milky-way",
     "天鹅座银河段", "Cygnus Milky Way",
     20.37, 40.3, 30, 12,
     "北美洲星云与面纱星云同段，夏秋前半夜高悬。",
     "The North America and Veil Nebulae share this stretch, high in the first half of summer and autumn nights."),
    ("cassiopeia-milky-way",
     "仙后座银河段", "Cassiopeia Milky Way",
     0.90, 60.0, 25, 10,
     "心脏、灵魂星云所在，秋冬北天高纬银河。",
     "Home to the Heart and Soul Nebulae — the high northern Milky Way of autumn and winter."),
    ("winter-milky-way",
     "冬季银河（猎户–金牛）", "Winter Milky Way (Orion–Taurus)",
     5.00, 25.0, 40, 12,
     "昴星团、加州星云一线，冬夜后半夜至黎明的主景。",
     "The Pleiades and California Nebula line — the main scene of winter nights after midnight."),
    ("monoceros-milky-way",
     "麒麟座银河段", "Monoceros Milky Way",
     6.80, 0.0, 30, 12,
     "玫瑰星云所在，冬季银河中段。",
     "The middle stretch of the winter Milky Way, holding the Rosette Nebula."),
    ("centaurus-crux-milky-way",
     "半人马–南十字银河段", "Centaurus–Crux Milky Way",
     12.90, -60.0, 30, 12,
     "南天银河最亮段，煤袋与南十字在其内；北纬中纬度不可见。",
     "The brightest southern stretch, with the Coalsack and the Cross; invisible from mid-northern latitudes."),
]


def main() -> None:
    entries = []
    for vid, name_zh, name_en, ra, dec, major, minor, note_zh, note_en in ROWS:
        assert 0.0 <= ra < 24.0, vid
        assert -90.0 <= dec <= 90.0, vid
        assert 20 <= major <= 40 and major >= minor > 0, vid
        entries.append({
            "id": vid,
            "nameZh": name_zh,
            "nameEn": name_en,
            "anchorRaHours": ra,
            "anchorDecDeg": dec,
            "sizeMajorDeg": major,
            "sizeMinorDeg": minor,
            "noteZh": note_zh,
            "noteEn": note_en,
        })
    doc = {
        "schemaVersion": 1,
        "dataVersion": DATA_VERSION,
        "source": SOURCE,
        "viewpoints": entries,
    }
    OUT.write_text(
        json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=False) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {OUT} ({len(entries)} viewpoints)")


if __name__ == "__main__":
    main()
