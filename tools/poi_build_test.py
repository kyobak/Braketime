#!/usr/bin/env python3
"""tools/poi_build.py 시험. 네트워크 없이 작은 표로 돈다. 표준 라이브러리만 쓴다.

    python3 -I -B tools/poi_build_test.py   # -B: tools/ 아래에 캐시 파일을 남기지 않는다

core의 PoiBuildScriptTest가 python3이 있으면 이 파일을 함께 돌린다.
"""
import json
import pathlib
import sys
import unittest

# -I로 돌리면 스크립트 폴더가 경로에 없다. 같은 폴더의 poi_build만 읽도록 직접 넣는다
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import poi_build  # noqa: E402

REST_HEADER = ["휴게소명", "도로노선명", "도로노선방향", "위도", "경도", "휴게소종류", "휴게소전화번호", "데이터기준일자"]


def table(header, rows):
    cells = lambda tag, values: "<tr>" + "".join(f"<{tag}>{v}</{tag}>" for v in values) + "</tr>"
    return "<table>" + cells("th", header) + "".join(cells("td", r) for r in rows) + "</table>"


def rest_row(name, facility, lat="36.0198", lon="128.4285"):
    return [name, "경부선", "부산", lat, lon, facility, "054-000-0000", "2026-09-17"]


class RestAreaFacilityTest(unittest.TestCase):
    def build(self, header, rows):
        parsed = poi_build.read_rows(table(header, rows), poi_build.REST_FIELDS)
        return poi_build.build_rest_areas(parsed, [])

    def test_keeps_facility_value_as_is(self):
        places = self.build(REST_HEADER, [rest_row("칠곡(부산)", "일반휴게소"), rest_row("칠곡화물(부산)", "화물차휴게소")])
        self.assertEqual(["일반휴게소", "화물차휴게소"], [p["facility"] for p in places])
        self.assertEqual([False, True], [poi_build.is_truck_only(p) for p in places])

    def test_blank_facility_is_unknown_not_truck(self):
        places = self.build(REST_HEADER, [rest_row("칠곡(부산)", " ")])
        self.assertIsNone(places[0]["facility"])
        self.assertFalse(poi_build.is_truck_only(places[0]))

    def test_other_column_name_is_found(self):
        header = [("휴게소구분" if h == "휴게소종류" else h) for h in REST_HEADER]
        places = self.build(header, [rest_row("칠곡(부산)", "화물차휴게소")])
        self.assertTrue(poi_build.is_truck_only(places[0]))

    def test_missing_facility_column_stops(self):
        header = [h for h in REST_HEADER if h != "휴게소종류"]
        rows = [[v for h, v in zip(REST_HEADER, rest_row("칠곡(부산)", "x")) if h != "휴게소종류"]]
        with self.assertRaises(SystemExit):
            self.build(header, rows)

    def test_validate_rejects_rest_area_without_facility(self):
        place = {"id": "ra-1", "latitude": 36.0, "longitude": 128.0, "kind": "REST_AREA"}
        with self.assertRaises(SystemExit):
            poi_build.validate([place])
        poi_build.validate([dict(place, facility=None)])

    def test_counts_by_facility(self):
        places = self.build(REST_HEADER, [rest_row("가(부산)", "화물차휴게소"), rest_row("나(부산)", "일반휴게소"), rest_row("다(부산)", "")])
        self.assertEqual({"화물차휴게소": 1, "일반휴게소": 1, "(종류 없음)": 1}, poi_build.facility_counts(places))

    def test_ids_match_bundled_file(self):
        # 2026-11-07에 내장 파일의 종류를 포털 표에서 id로 맞춰 채웠다. id 계산이 바뀌면 그 대조가 틀어지므로 원본 두 행으로 묶어 둔다
        rows = [
            ["칠곡(부산)", "경부선", "상행", "36.019795", "128.428517", "화물차휴게소", "054-971-6600", "2026-09-17"],
            ["매송(서울)", "서해안선", "상행", "37.264769", "126.891795", "화물차휴게소", "031-000-0000", "2026-09-17"],
        ]
        built = self.build(REST_HEADER, rows)
        bundled = {p["id"]: p for p in json.loads(poi_build.OUTPUT.read_text(encoding="utf-8"))["places"]}
        self.assertEqual(["ra-e7ad2b2b63d2", "ra-67b3c251b42a"], [p["id"] for p in built])
        for place in built:
            self.assertEqual(place, bundled[place["id"]])

    def test_bundled_file_has_21_truck_rest_areas(self):
        places = json.loads(poi_build.OUTPUT.read_text(encoding="utf-8"))["places"]
        rest = [p for p in places if p["kind"] == "REST_AREA"]
        self.assertEqual({"일반휴게소": 187, "화물차휴게소": 21, "간이휴게소": 7}, poi_build.facility_counts(rest))
        self.assertEqual(21, sum(poi_build.is_truck_only(p) for p in places))

    def test_truck_mark_matches_app(self):
        # 앱의 Place.TRUCK_MARK와 같은 낱말이어야 스크립트 출력과 앱 판정이 맞는다
        place_kt = poi_build.REPO / "core/src/main/kotlin/com/conditiondrive/core/poi/Place.kt"
        self.assertIn(f'const val TRUCK_MARK = "{poi_build.TRUCK_MARK}"', place_kt.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
