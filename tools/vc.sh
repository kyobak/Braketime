#!/usr/bin/env bash
# Pleos 에뮬레이터 Virtual Console REST 제어(설계서 15.4).
#
#   tools/vc.sh start                 포트 포워딩과 하트비트 시작
#   tools/vc.sh gear P|R|N|D          기어 변경
#   tools/vc.sh speed KMH [ACC BRAKE STEER]
#   tools/vc.sh state [gear|speed]    전체 JSON 또는 값 하나
#   tools/vc.sh drive [KMH]           D + 차속(기본 80)
#   tools/vc.sh park                  차속 0 + P
#   tools/vc.sh stop                  차속 0 + P 로 되돌리고 하트비트 종료
#   tools/vc.sh hb-stop               차량 값은 두고 하트비트만 종료
#   tools/vc.sh hvac 'k=v&k=v'        PUT /vehicle/hvac
#   tools/vc.sh body 'k=v&k=v'        PUT /vehicle/body
#   tools/vc.sh catalog               GET /vehicle/catalog
#   tools/vc.sh scenario sc1|sc2|sc3  차량 신호 시연이 기다리는 기어를 그때마다 넣음. 끝나면 대조 결과를 출력
#
# 주행 제한을 강제하는 명령은 이 이미지에 없다(cmd hmg_car_service 없음). D 기어로 바꾸면 DO=true가 된다.
#
# source 하면 vc_gear, vc_speed, vc_depart, vc_arrive, vc_scenario 등을 함수로 쓸 수 있다.
#
# 환경 변수: VC_SERIAL(기본 emulator-5554), VC_PORT(기본 8088), VC_HB_MAX_SEC(하트비트 수명, 기본 3600),
#            VC_POLL_SEC(시연 대기 확인 주기, 기본 0.5), VC_WAIT_SEC(scenario가 기다리는 최대 시간, 기본 900)

VC_SERIAL="${VC_SERIAL:-emulator-5554}"
VC_PORT="${VC_PORT:-8088}"
VC_HB_MAX_SEC="${VC_HB_MAX_SEC:-3600}"
VC_POLL_SEC="${VC_POLL_SEC:-0.5}"
VC_WAIT_SEC="${VC_WAIT_SEC:-900}"
VC_BASE="http://127.0.0.1:${VC_PORT}"
VC_ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
VC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VC_PID_FILE="${VC_ROOT}/.vc/heartbeat.${VC_SERIAL}.pid"

vc_forward() {
  "$VC_ADB" -s "$VC_SERIAL" forward "tcp:${VC_PORT}" "tcp:${VC_PORT}" >/dev/null
}

vc_beat() {
  curl -fsS -m 2 -X POST "${VC_BASE}/browser/session?client=cli" >/dev/null
}

vc_hb_running() {
  [[ -f "$VC_PID_FILE" ]] && kill -0 "$(cat "$VC_PID_FILE")" 2>/dev/null
}

# 3초 끊기면 콘솔이 입력을 거부하므로 1초 주기로 보낸다.
# 잊어도 영원히 돌지 않게 수명을 둔다. 끊겨도 차량 값은 유지된다(실측).
vc_hb_start() {
  vc_forward || return 1
  vc_beat || { echo "vc: 콘솔에 연결할 수 없음 (${VC_BASE})" >&2; return 1; }
  vc_hb_running && return 0
  mkdir -p "$(dirname "$VC_PID_FILE")"
  (
    end=$(($(date +%s) + VC_HB_MAX_SEC))
    while [[ $(date +%s) -lt $end ]]; do
      curl -fsS -m 2 -X POST "${VC_BASE}/browser/session?client=cli" >/dev/null 2>&1
      sleep 1
    done
  ) </dev/null >/dev/null 2>&1 &
  echo $! >"$VC_PID_FILE"
  disown 2>/dev/null || true
}

vc_hb_stop() {
  if vc_hb_running; then kill "$(cat "$VC_PID_FILE")" 2>/dev/null; fi
  rm -f "$VC_PID_FILE"
}

# 콘솔은 입력 거부도 JSON error로 돌려주므로 HTTP 상태만 믿지 않는다.
vc_put() {
  local out
  out="$(curl -sS -m 5 -X PUT "${VC_BASE}$1")" || { echo "vc: 요청 실패 $1" >&2; return 1; }
  if [[ "$out" == *'"error"'* ]]; then
    echo "vc: 거부됨 $1: $out" >&2
    return 1
  fi
}

vc_gear() {
  local gear
  case "$1" in
    P|p|Park) gear=Park ;;
    R|r|Reverse) gear=Reverse ;;
    N|n|Neutral) gear=Neutral ;;
    D|d|Drive) gear=Drive ;;
    *) echo "vc: 알 수 없는 기어 '$1' (P, R, N, D)" >&2; return 2 ;;
  esac
  vc_hb_start && vc_put "/vehicle/gear?gear=${gear}"
}

vc_speed() {
  local kmh="${1:?차속이 필요함}" acc="${2:-}" brake="${3:-0}" steer="${4:-0}"
  # 가속 페달을 안 주면 달리는 값과 멈춘 값이 페달과 어긋나 보이므로 차속에 맞춘다
  if [[ -z "$acc" ]]; then
    if [[ "$kmh" == "0" ]]; then acc=0; else acc=30; fi
  fi
  vc_hb_start && vc_put "/vehicle/controls?speed=${kmh}&accelerator=${acc}&brake=${brake}&steering=${steer}"
}

vc_state() {
  vc_forward || return 1
  curl -fsS -m 3 "${VC_BASE}/vehicle/state"
}

vc_get() {
  # gear 또는 speed 값 하나
  vc_state | python3 -I -c '
import json, sys
s = json.load(sys.stdin)
print({"gear": s["gear"]["actual"]["value"], "speed": s["controls"]["speed_kph"]}[sys.argv[1]])
' "$1"
}

vc_wait_gear() {
  # 넣은 값이 콘솔 상태에 반영될 때까지 기다린다. 최대 5초
  local want="$1" i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    [[ "$(vc_get gear)" == "$want" ]] && return 0
    sleep 0.5
  done
  echo "vc: 기어가 ${want}로 바뀌지 않음" >&2
  return 1
}

vc_depart() {
  local kmh="${1:-80}"
  vc_gear D && vc_wait_gear Drive && vc_speed "$kmh"
}

vc_arrive() {
  # 달리는 채로 P에 넣는 장면을 만들지 않으려고 차속을 먼저 내린다
  vc_speed 0 && vc_gear P && vc_wait_gear Park
}

vc_stop() {
  vc_arrive
  local rc=$?
  vc_hb_stop
  return $rc
}

# 앱이 CdDemoConsole 태그로 남긴 마지막 줄. "run=<실행> id=<시나리오> await=<기어|none|done|stopped>"
vc_cue() {
  "$VC_ADB" -s "$VC_SERIAL" logcat -d -s CdDemoConsole:I -v raw 2>/dev/null | tr -d '\r' | grep '^run=' | tail -n 1
}

vc_cue_field() {
  # $1=줄 $2=이름
  local field
  for field in $1; do
    if [[ "$field" == "$2="* ]]; then
      echo "${field#*=}"
      return 0
    fi
  done
}

vc_report_result() {
  "$VC_ADB" -s "$VC_SERIAL" logcat -d -s CdDemoReport:I -v raw 2>/dev/null | tr -d '\r' | grep "^$1|대조 결과" | tail -n 1 | cut -d'|' -f2-
}

# 앱이 기다리는 기어만 넣는다. 줄의 실제 시각은 음성과 인식 길이에 따라 달라서 고정 시간표로는 맞출 수 없다.
# 시나리오보다 먼저 P를 넣으면 응답을 기다리던 시연이 정차로 읽혀 대조가 어긋난다.
vc_scenario() {
  local id="${1:?시나리오가 필요함}"
  case "$id" in
    sc1|sc2|sc3) ;;
    *) echo "vc: 알 수 없는 시나리오 '$id'" >&2; return 2 ;;
  esac
  vc_arrive || return 1

  # 이미 끝난 실행의 마지막 줄을 이번 실행으로 읽지 않는다
  local line stale_run="" prev="" run await result announced="" end=$(($(date +%s) + VC_WAIT_SEC))
  line="$(vc_cue)"
  if [[ "$(vc_cue_field "$line" await)" == done || "$(vc_cue_field "$line" await)" == stopped ]]; then
    stale_run="$(vc_cue_field "$line" run)"
  fi

  while :; do
    line="$(vc_cue)"
    run="$(vc_cue_field "$line" run)"
    await="$(vc_cue_field "$line" await)"
    if [[ -n "$run" && "$run" != "$stale_run" && "$(vc_cue_field "$line" id)" == "$id" ]]; then
      announced=1
      if [[ "$line" != "$prev" ]]; then
        echo "vc: $line"
        case "$await" in
          DRIVE) vc_depart 80 || return 1 ;;
          PARK) vc_arrive || return 1 ;;
          REVERSE) vc_gear R || return 1 ;;
          NEUTRAL) vc_gear N || return 1 ;;
          done)
            vc_hb_stop
            result="$(vc_report_result "$id")"
            echo "vc: ${result:-대조 결과 없음}"
            [[ "$result" == *"대조 결과: 일치"* ]]
            return $?
            ;;
          stopped)
            vc_hb_stop
            echo "vc: 시연이 중간에 멈췄음" >&2
            return 1
            ;;
        esac
        prev="$line"
      fi
    elif [[ -z "$announced" ]]; then
      announced=0
      echo "vc: 앱에서 설정 > 시연 모드 > 실제 차량 신호 사용을 켜고 시나리오를 누르면 따라갑니다" >&2
    fi
    if [[ $(date +%s) -ge $end ]]; then
      echo "vc: ${VC_WAIT_SEC}초 안에 시연이 끝나지 않음" >&2
      return 1
    fi
    sleep "$VC_POLL_SEC"
  done
}

vc_main() {
  local cmd="${1:-}"
  [[ $# -gt 0 ]] && shift
  case "$cmd" in
    start) vc_hb_start ;;
    gear) vc_gear "$@" ;;
    speed) vc_speed "$@" ;;
    state)
      if [[ $# -gt 0 ]]; then vc_get "$1"; else vc_state; echo; fi
      ;;
    drive) vc_depart "$@" ;;
    park) vc_arrive ;;
    stop) vc_stop ;;
    hb-stop) vc_hb_stop ;;
    hvac) vc_hb_start && vc_put "/vehicle/hvac?${1:?쿼리가 필요함}" ;;
    body) vc_hb_start && vc_put "/vehicle/body?${1:?쿼리가 필요함}" ;;
    catalog) vc_forward && curl -fsS -m 3 "${VC_BASE}/vehicle/catalog"; echo ;;
    scenario) vc_scenario "$@" ;;
    *)
      sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      [[ -z "$cmd" ]] || return 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  vc_main "$@"
fi
