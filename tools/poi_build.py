#!/usr/bin/env python3
"""공공데이터포털 표준데이터로 차량 앱 내장 POI(vehicle/src/main/assets/poi.json)를 만든다.

원본과 판단 근거는 docs/poi-sources.md에 있다. 표준 라이브러리만 쓴다.

    python3 tools/poi_build.py                         # 포털 공개 페이지를 읽어 생성
    python3 tools/poi_build.py --html-dir DIR          # 미리 받은 DIR/shelter.html, DIR/rest.html 사용
    python3 tools/poi_build.py --check                 # 파일을 쓰지 않고 검증만
    python3 tools/poi_build.py --save-html DIR         # 받은 페이지를 DIR에 보관
"""
import argparse
import hashlib
import html.parser
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
OUTPUT = REPO / "vehicle/src/main/assets/poi.json"

SHELTER_URL = "https://www.data.go.kr/data/15028203/standard.do"
REST_URL = "https://www.data.go.kr/data/15025446/standard.do"
SHELTER_DATASET = "전국졸음쉼터표준데이터(한국도로공사)"
REST_DATASET = "전국휴게소정보표준데이터(한국도로공사)"

NOTICE = "데모용 데이터. 실제 출시 전 원본으로 갱신 필요. 출처는 docs/poi-sources.md"

# 표의 필드가 바뀌면 좌표를 잘못 읽을 수 있으므로 조용히 넘어가지 않고 멈춘다
SHELTER_FIELDS = ("졸음쉼터명", "시도명", "도로노선명", "도로노선방향", "소재지도로명주소", "소재지지번주소", "위도", "경도", "데이터기준일자")
REST_FIELDS = ("휴게소명", "도로노선명", "도로노선방향", "위도", "경도", "휴게소전화번호", "데이터기준일자")
# 휴게소 종류 열. 화물차휴게소를 앱이 고르는 안내에서 빼려면 이 값이 있어야 한다(decisions.md P-15).
# 2026-11-07 원본 표에서 열 이름은 휴게소종류였다. 이름이 바뀔 때를 대비해 후보를 순서대로 찾고, 하나도 없으면 멈춘다
REST_TYPE_FIELDS = ("휴게소종류", "휴게소구분", "휴게소유형", "시설구분")
# 앱(Place.truckRestArea)과 같은 판정이다. 값에 이 낱말이 있으면 화물차휴게소로 본다
TRUCK_MARK = "화물"

# 원본 표의 오탈자. 방향 표시에만 쓰이며 좌표와 무관하다
PLACE_TYPOS = {"양앙": "양양", "회턱": "회덕"}

# 시도별 대략 범위(위도 최소, 위도 최대, 경도 최소, 경도 최대). 경계에서 0.1도 정도 여유를 뒀다.
# 좌표가 주소와 어긋난 행을 걸러 내는 검사용이며 정밀한 경계가 아니다.
PROVINCE_BOX = {
    "서울특별시": (37.40, 37.72, 126.74, 127.20),
    "부산광역시": (34.98, 35.42, 128.72, 129.34),
    "대구광역시": (35.57, 36.05, 128.33, 128.82),
    "인천광역시": (37.30, 37.85, 125.90, 126.82),
    "광주광역시": (35.02, 35.28, 126.62, 127.05),
    "대전광역시": (36.17, 36.52, 127.22, 127.58),
    "울산광역시": (35.30, 35.75, 128.95, 129.50),
    "세종특별자치시": (36.38, 36.75, 127.12, 127.42),
    "경기도": (36.85, 38.32, 126.25, 127.92),
    "강원특별자치도": (37.00, 38.65, 127.05, 129.45),
    "강원도": (37.00, 38.65, 127.05, 129.45),
    "충청북도": (36.00, 37.30, 127.25, 128.70),
    "충청남도": (35.95, 37.10, 125.95, 127.70),
    "전북특별자치도": (35.28, 36.20, 126.30, 127.95),
    "전라북도": (35.28, 36.20, 126.30, 127.95),
    "전라남도": (34.00, 35.55, 125.90, 127.95),
    "경상북도": (35.55, 37.20, 127.75, 129.65),
    "경상남도": (34.55, 35.95, 127.50, 129.30),
}

# 휴게소 전화 지역번호로 좌표를 한 번 더 대조한다. 표에 시도가 없어서 쓰는 교차 검사다
AREA_CODE_BOX = {
    "02": "서울특별시", "031": "경기도", "032": "인천광역시", "033": "강원도", "041": "충청남도", "042": "대전광역시",
    "043": "충청북도", "044": "세종특별자치시", "051": "부산광역시", "052": "울산광역시", "053": "대구광역시", "054": "경상북도",
    "055": "경상남도", "061": "전라남도", "062": "광주광역시", "063": "전라북도",
}
# 지역번호는 생활권을 따라 시도 경계를 넘는 경우가 있다. 좌표 자체가 틀린 행만 잡으려는 검사라 넉넉하게 본다
AREA_CODE_MARGIN_DEG = 0.35

KOREA_BOX = (33.0, 38.7, 124.5, 131.0)


class TableParser(html.parser.HTMLParser):
    """포털 페이지 안의 표를 읽는다. 스크립트를 실행하지 않고 텍스트만 꺼낸다."""

    def __init__(self):
        super().__init__()
        self.tables, self._table, self._row, self._cell = [], None, None, None

    def handle_starttag(self, tag, attrs):
        if tag == "table":
            self._table = []
        elif tag == "tr" and self._table is not None:
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            self._cell = []

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self._cell is not None:
            self._row.append(" ".join("".join(self._cell).split()))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            self._table.append(self._row)
            self._row = None
        elif tag == "table" and self._table is not None:
            self.tables.append(self._table)
            self._table = None

    def handle_data(self, data):
        if self._cell is not None:
            self._cell.append(data)


def read_rows(page, required):
    parser = TableParser()
    parser.feed(page)
    for table in parser.tables:
        if table and all(field in table[0] for field in required):
            header = table[0]
            return [dict(zip(header, row)) for row in table[1:] if len(row) == len(header)]
    raise SystemExit(f"표를 찾지 못했습니다. 필드가 바뀌었는지 확인하세요: {required}")


def coordinates(row):
    try:
        return float(row["위도"]), float(row["경도"])
    except ValueError:
        return None


def in_box(lat, lon, box, margin=0.0):
    return box[0] - margin <= lat <= box[1] + margin and box[2] - margin <= lon <= box[3] + margin


def stable_id(prefix, *parts):
    digest = hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()[:12]
    return f"{prefix}-{digest}"


def shelter_direction(raw):
    """'서울기점 + 부산종점'은 서울에서 부산으로 가는 쪽 시설로 읽는다."""
    match = re.search(r"([^\s+]+?)종점", raw)
    if not match:
        return None
    place = match.group(1)
    return f"{PLACE_TYPOS.get(place, place)}방향"


def build_shelters(rows, report):
    places = []
    for row in rows:
        name = row["졸음쉼터명"].strip()
        coord = coordinates(row)
        if not name or coord is None:
            report.append(f"제외(좌표 없음) 졸음쉼터 {name}")
            continue
        lat, lon = coord
        box = PROVINCE_BOX.get(row["시도명"])
        if box is None or not in_box(lat, lon, box):
            report.append(f"제외(시도와 좌표 불일치) 졸음쉼터 {name} {row['시도명']} {lat},{lon}")
            continue
        direction = shelter_direction(row["도로노선방향"])
        base = name if name.endswith("졸음쉼터") else f"{name}졸음쉼터"
        places.append({
            "id": stable_id("ds", row["도로노선명"], name, direction or "", f"{lat:.5f}", f"{lon:.5f}"),
            "name": f"{base}({direction})" if direction else base,
            "latitude": lat,
            "longitude": lon,
            "kind": "DROWSY_SHELTER",
            "direction": direction,
            "address": row["소재지도로명주소"] or row["소재지지번주소"],
        })
    return places


def rest_type_field(rows):
    """종류 열을 버리면 화물차휴게소가 일반 휴게소처럼 안내되므로, 열이 없으면 조용히 넘어가지 않고 멈춘다."""
    header = rows[0].keys() if rows else ()
    for field in REST_TYPE_FIELDS:
        if field in header:
            return field
    raise SystemExit(f"휴게소 종류 열을 찾지 못했습니다. 후보 {REST_TYPE_FIELDS}, 표의 열 {list(header)}")


def build_rest_areas(rows, report):
    type_field = rest_type_field(rows)
    places = []
    for row in rows:
        raw = row["휴게소명"].strip()
        coord = coordinates(row)
        if not raw or coord is None:
            report.append(f"제외(좌표 없음) 휴게소 {raw}")
            continue
        lat, lon = coord
        if not in_box(lat, lon, KOREA_BOX):
            report.append(f"제외(국내 범위 밖) 휴게소 {raw} {lat},{lon}")
            continue
        province = AREA_CODE_BOX.get(row["휴게소전화번호"].split("-")[0])
        if province and not in_box(lat, lon, PROVINCE_BOX[province], AREA_CODE_MARGIN_DEG):
            report.append(f"제외(지역번호와 좌표 불일치) 휴게소 {raw} {row['휴게소전화번호']} {lat},{lon}")
            continue
        match = re.fullmatch(r"(.+?)\((.+)\)", raw)
        base, side = (match.group(1), match.group(2)) if match else (raw, None)
        direction = f"{side}방향" if side else ("양방향" if row["도로노선방향"] == "양방향" else None)
        title = base if base.endswith("휴게소") else f"{base}휴게소"
        places.append({
            "id": stable_id("ra", row["도로노선명"], raw),
            "name": f"{title}({direction})" if direction and direction != "양방향" else title,
            "latitude": lat,
            "longitude": lon,
            "kind": "REST_AREA",
            "direction": direction,
            "address": "",
            # 원본 값을 그대로 둔다. 빈 칸은 종류 모름(null)이고 앱은 일반 휴게소로 본다
            "facility": row[type_field].strip() or None,
        })
    return places


def validate(places):
    ids = [p["id"] for p in places]
    if len(ids) != len(set(ids)):
        raise SystemExit("id가 겹칩니다")
    for place in places:
        if not in_box(place["latitude"], place["longitude"], KOREA_BOX):
            raise SystemExit(f"국내 범위 밖 좌표: {place}")
        if place["kind"] == "REST_AREA" and "facility" not in place:
            raise SystemExit(f"휴게소 종류 필드가 없습니다: {place['id']}")


def facility_counts(rest_areas):
    """종류별 개수. 화물차휴게소 수(조사 때 21행)를 사람이 원본과 대조하라고 출력한다."""
    counts = {}
    for place in rest_areas:
        key = place["facility"] or "(종류 없음)"
        counts[key] = counts.get(key, 0) + 1
    return counts


def is_truck_only(place):
    return TRUCK_MARK in (place.get("facility") or "")


def data_date(rows):
    return max(row["데이터기준일자"] for row in rows)


def load_page(url, cached, save_dir, filename):
    if cached is not None:
        return (pathlib.Path(cached) / filename).read_text(encoding="utf-8")
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            page = response.read().decode("utf-8")
    except urllib.error.URLError as error:
        # 인증서 검증을 끄지 않는다. macOS python.org 빌드는 인증서를 따로 설치해야 해 이 경로로 자주 온다
        raise SystemExit(f"{url} 를 읽지 못했습니다: {error.reason}\n브라우저나 curl로 받은 파일을 {filename}로 저장해 --html-dir로 넘기세요.")
    if save_dir is not None:
        directory = pathlib.Path(save_dir)
        directory.mkdir(parents=True, exist_ok=True)
        (directory / filename).write_text(page, encoding="utf-8")
    return page


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--html-dir", help="shelter.html, rest.html이 있는 폴더. 없으면 포털에서 읽는다")
    parser.add_argument("--save-html", help="포털에서 읽은 페이지를 보관할 폴더")
    parser.add_argument("--check", action="store_true", help="파일을 쓰지 않는다")
    parser.add_argument("--output", default=str(OUTPUT))
    args = parser.parse_args()

    shelter_rows = read_rows(load_page(SHELTER_URL, args.html_dir, args.save_html, "shelter.html"), SHELTER_FIELDS)
    rest_rows = read_rows(load_page(REST_URL, args.html_dir, args.save_html, "rest.html"), REST_FIELDS)

    report = []
    shelters = build_shelters(shelter_rows, report)
    rest_areas = build_rest_areas(rest_rows, report)
    places = sorted(shelters + rest_areas, key=lambda p: p["id"])
    validate(places)

    catalog = {
        "schemaVersion": 1,
        "notice": NOTICE,
        "sources": [
            {"name": SHELTER_DATASET, "url": SHELTER_URL, "dataDate": data_date(shelter_rows), "count": len(shelters)},
            {"name": REST_DATASET, "url": REST_URL, "dataDate": data_date(rest_rows), "count": len(rest_areas)},
        ],
        "places": places,
    }

    print(f"졸음쉼터 원본 {len(shelter_rows)}행 → {len(shelters)}개, 기준일 {data_date(shelter_rows)}")
    print(f"휴게소 원본 {len(rest_rows)}행 → {len(rest_areas)}개, 기준일 {data_date(rest_rows)}")
    for facility, count in sorted(facility_counts(rest_areas).items()):
        print(f"  종류 {facility}: {count}개")
    trucks = [p for p in rest_areas if is_truck_only(p)]
    print(f"  앱이 고르지 않는 화물차휴게소 {len(trucks)}개(P-15)")
    for place in trucks:
        print(f"    {place['id']} {place['name']}")
    print(f"제외 {len(report)}건")
    for line in report:
        print("  " + line)

    if args.check:
        return 0
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"저장 {output} ({output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
