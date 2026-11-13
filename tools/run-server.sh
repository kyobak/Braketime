#!/usr/bin/env bash
# Braketime 서버 실행(설계서 12장). 127.0.0.1:8080에만 열린다.
#
#   tools/run-server.sh           빌드 후 포그라운드로 실행 (Ctrl+C로 종료)
#   tools/run-server.sh smoke     떠 있는 서버에 페어링 → 요약 업로드 → 최신 조회를 curl로 돌린다
#
# 기기 연결: 에뮬레이터는 http://10.0.2.2:8080, 실폰은 adb reverse tcp:8080 tcp:8080 뒤 http://127.0.0.1:8080
#
# 환경 변수
#   CD_PORT                     포트(기본 8080)
#   CD_DB_PATH                  SQLite 파일(기본 저장소의 .server-data/conditiondrive.db)
#   CD_HMAC_KEY                 번호·코드 HMAC 키, base64url 32바이트 이상. 없으면 DB 옆 .hmac.key 파일을 만들어 쓴다
#   CD_ALLOW_PARTNER_REALTIME   true일 때만 partner_realtime 허용(기본 false)
#   CD_SMS_OUTBOX               지정하면 모의 문자 전문을 이 파일에 쌓는다. 코드가 들어 있으니 시연 때만 쓴다
#   JAVA_HOME                   JDK 21(기본 ~/Library/Java/JavaVirtualMachines/temurin-21.0.12.1/Contents/Home)
#   ANDROID_HOME                Gradle 설정 단계에 필요(기본 ~/Library/Android/sdk)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${CD_PORT:-8080}"
BASE="http://127.0.0.1:${PORT}"

# date -d와 -r이 macOS와 리눅스에서 달라 초 단위 시각 계산만 분기한다
utc_at() {
  local epoch="$1"
  if date -u -r 0 >/dev/null 2>&1; then
    date -u -r "$epoch" +%Y-%m-%dT%H:%M:%SZ
  else
    date -u -d "@$epoch" +%Y-%m-%dT%H:%M:%SZ
  fi
}

field() { # JSON 문자열 값 하나만 꺼낸다. 이 스크립트가 만든 요청의 응답만 읽으므로 jq 의존을 두지 않는다
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"
}

smoke() {
  local vehicle code phone now generated until_ sleep_until measured
  echo "== healthz"
  curl -fsS "$BASE/healthz"; echo

  echo "== 차량 등록"
  vehicle="$(curl -fsS -X POST "$BASE/v1/devices/vehicle" -H 'Content-Type: application/json' -d '{"app_version":"0.1.0"}' | field vehicle_token)"
  echo "vehicle_token 발급됨"

  echo "== 페어링 코드"
  code="$(curl -fsS -X POST "$BASE/v1/pairing/codes" -H "Authorization: Bearer $vehicle" | field code)"
  echo "code 발급됨"

  echo "== 폰 claim"
  phone="$(curl -fsS -X POST "$BASE/v1/pairing/claim" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$code\",\"device_name\":\"Smoke Phone\",\"app_version\":\"0.1.0\",\"consents\":[{\"type\":\"health_summary\",\"version\":\"2026-11-v1\"}]}" \
    | field phone_token)"
  echo "phone_token 발급됨"

  now="$(date +%s)"
  generated="$(utc_at "$now")"
  until_="$(utc_at $((now + 870)))"
  sleep_until="$(utc_at $((now + 72000)))"
  measured="$(utc_at $((now - 30)))"

  echo "== 요약 업로드"
  curl -fsS -X POST "$BASE/v1/summary" -H "Authorization: Bearer $phone" -H 'Content-Type: application/json' \
    -d "{\"generated_at\":\"$generated\",\"valid_until\":\"$until_\",\"sleep_minutes_last_night\":290,\"sleep_short\":true,\"sleep_valid_until\":\"$sleep_until\",\"glucose_mmol\":5.4,\"glucose_trend\":\"falling\",\"glucose_source\":\"simulated\",\"glucose_measured_at\":\"$measured\"}"
  echo

  echo "== 최신 조회(차량)"
  curl -fsS "$BASE/v1/summary/latest" -H "Authorization: Bearer $vehicle"; echo

  echo "== 정리: 계정 삭제"
  curl -fsS -X DELETE "$BASE/v1/account" -H "Authorization: Bearer $vehicle"; echo
}

case "${1:-run}" in
  smoke)
    smoke
    ;;
  run)
    default_jdk="$HOME/Library/Java/JavaVirtualMachines/temurin-21.0.12.1/Contents/Home"
    if [[ -z "${JAVA_HOME:-}" && -d "$default_jdk" ]]; then export JAVA_HOME="$default_jdk"; fi
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    export CD_DB_PATH="${CD_DB_PATH:-$ROOT/.server-data/conditiondrive.db}"
    mkdir -p "$(dirname "$CD_DB_PATH")"
    chmod 700 "$(dirname "$CD_DB_PATH")"
    (cd "$ROOT" && ./gradlew :server:installDist -q)
    exec "$ROOT/server/build/install/server/bin/server"
    ;;
  *)
    echo "사용법: tools/run-server.sh [run|smoke]" >&2
    exit 2
    ;;
esac
