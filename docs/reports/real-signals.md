> 실행: release APK(`apk/hyunkyo_Braketime_release.apk`, 앱 이름 Braketime, 서명은 배포 키(CN=hyunkyo), sha256 앞자리 78704db3c81b05ba), Pleos 에뮬레이터 emulator-5554(창과 소리를 켠 상태. 첫 실행 전에 하트비트를 켜고 `tools/vc.sh body 'doors_locked=true'`와 `PUT /vehicle/odometer?remaining_range_m=500000`으로 Driving view를 주행 가능 거리 500km·문 잠김으로 맞춘 뒤 `tools/vc.sh hb-stop`), 설치와 온보딩은 `sc1.md` 머리말과 같다(13:21 `adb uninstall` 뒤 `adb install -g`, base.apk 해시 확인, 온보딩 다시 함, 연결된 기기 없음). 차량 신호 시연(README대로 설정 제목 5번 > 시연 모드 > 실제 차량 신호 사용을 시나리오마다 켬), 기어는 README대로 `tools/vc.sh scenario <id>`가 넣었다(앱이 기다릴 때만). 서버·폰 없음, 2026-11-13. 실행 중 맥은 잠들지 않았다(`pmset -g log`, 마지막 잠자기는 실행 전인 2026-11-13 12:57이었고 실행 동안 `caffeinate -dimsu`를 켰다). 이 APK는 855d와 서명(META-INF)만 다르다(META-INF를 뺀 항목 이름과 CRC가 모두 같다). 시연 장소 칠곡휴게소(부산방향)는 앱이 고르지 않는 화물차휴게소 21곳 중 하나지만, 시연은 종류 없는 고정 장소를 그대로 안내해 표와 경유 장소가 855d 리포트와 같다(P-15). 표는 앱이 만든 `ScenarioCompare.report`이고 logcat 태그 `CdDemoReport`에서 옮겼다. 응답은 시나리오 입력이고 QA는 실제 소리를 듣지 못했다.
>
> 시나리오마다 내비 안내를 종료하고 돌렸다. 첫 시나리오 전에 README대로 `tools/vc.sh start`를 실행했다. sc1 다음 sc2를 누른 직후(13:48) 에뮬레이터 프로세스가 스스로 끝났다(macOS 크래시 리포트 `qemu-system-aarch64`, 에뮬레이터 멈춤 감지기가 낸 SIGSEGV. QA가 끈 것이 아니다). 같은 인자로 다시 띄워 콜드 부팅하고, base.apk 해시와 온보딩·연결된 기기 없음이 남은 것을 확인한 뒤 Driving view 값(문 잠김, 500km)을 다시 넣고 sc2를 처음부터 다시 돌렸다. 아래 sc2, sc3 표는 다시 켠 뒤의 실행이다. 세 시나리오 모두 경유지가 내비로 가며 지도 앱이 앱 위로 올라오는 것을 1초 표본(`topResumedActivity`)으로 확인했고(sc1 85.4초, sc2 114.4초, sc3 111.9초), 앱 슬롯을 눌러 돌아왔다. sc2는 시작 전 `tools/vc.sh hvac 'recirc=true'`, 하트비트를 켠 채로 돌렸고 콘솔 `recirc_on`이 true → false → 약 2초 뒤 true로 바뀌었다(96.0초 false, 98.0초 true). sc2 준비 때 `tools/vc.sh hvac 'recirc=true'` 뒤 2초 간격으로 다섯 번 읽은 콘솔 값과 설정을 열기 직전, 시나리오를 누르기 직전에 읽은 값이 모두 true였다. sc3는 시작 전 `tools/vc.sh hvac 'recirc=false'`를 넣었다. sc1은 따로 넣지 않았다. 직전 내장 시연 셋째 행 동안 콘솔 `recirc_on`은 true였지만, sc1 직전 `tools/vc.sh start` 뒤 읽은 값은 false였고 실행 내내 false였다. 855d 때와 같고 까닭은 확인하지 못했다. SC1은 공기 모드를 묻지 않아 결과에 영향이 없다. sc1, sc3는 시나리오를 누른 뒤에, sc2는 누르기 약 3초 전에 `tools/vc.sh scenario`를 실행했다. 세 실행의 S1 표본 119개(sc1 34, sc2 43, sc3 42) 중 터치 요소가 있던 표본은 0개다. sc1의 1초 화면 캡처에서 시스템 Driving view가 D, 80km/h를 보이는 것을 확인했다(sc2, sc3는 화면 캡처를 남기지 않았다). 세 실행 모두 표지에 "차량 신호는 시나리오 입력"이 없었다. 기어 대기는 `tools/vc.sh`가 0.5초 안에 넣어 표지의 "콘솔에서 D로 바꿔 주세요"는 약 2초 간격 덤프에 잡히지 않았다. 영상은 찍지 않았다. 사람이 콘솔을 직접 누른 실행은 없다. 세 실행 뒤 내비 안내를 종료했다.

# sc1 SC1 저혈당 대비

대조 결과: 일치 (시뮬레이션 시각 ±1초)

| 시각 | 전 | 후 | 사유 | 음성 |
| --- | --- | --- | --- | --- |
| -00:00:54 | NORMAL | NORMAL |  | V01 |
| 00:20:00 | NORMAL | CAUTION | GLUCOSE | V08 |
| 00:30:00 | CAUTION | CAUTION | GLUCOSE | V08 |
| 00:35:00 | CAUTION | STOP | GLUCOSE | V09 |
| 00:37:00 | STOP | STOP | GLUCOSE | V10 |
| 00:39:00 | STOP | STOP | GLUCOSE | V10 |
| 00:40:00 | STOP | RECOVER | GLUCOSE | V14 |
| 01:00:00 | RECOVER | RECOVER | GLUCOSE | V15 |
| 01:40:00 | RECOVER | NORMAL |  | V16 |
| 01:42:00 | NORMAL | RECOVER | GLUCOSE |  |
| 01:42:05 | RECOVER | RECOVER | GLUCOSE | V17 |

# sc2 SC2 기기 없는 피로 관리

대조 결과: 일치 (시뮬레이션 시각 ±1초)

| 시각 | 전 | 후 | 사유 | 음성 |
| --- | --- | --- | --- | --- |
| -00:00:54 | NORMAL | NORMAL |  | V01 |
| 01:45:00 | NORMAL | CAUTION | FATIGUE | V02, V03 |
| 02:00:00 | CAUTION | STOP | FATIGUE | V04 |
| 02:10:10 | STOP | STOP | FATIGUE | V04 |
| 02:10:20 | STOP | STOP | FATIGUE | V29 |
| 02:16:00 | STOP | RECOVER | FATIGUE | V11 |
| 02:31:00 | RECOVER | NORMAL |  | V12 |

# sc3 SC3 수면 부족

대조 결과: 일치 (시뮬레이션 시각 ±1초)

| 시각 | 전 | 후 | 사유 | 음성 |
| --- | --- | --- | --- | --- |
| -00:00:54 | NORMAL | NORMAL |  | V01 |
| -00:00:50 | NORMAL | CAUTION | CHECKIN | V31 |
| 00:30:00 | CAUTION | CAUTION | CHECKIN | V03 |
| 00:30:10 | CAUTION | NORMAL |  |  |
| 01:15:00 | NORMAL | CAUTION | FATIGUE | V02, V03 |
| 01:30:00 | CAUTION | STOP | FATIGUE | V04 |
| 01:40:10 | STOP | STOP | FATIGUE | V04 |
| 01:40:20 | STOP | STOP | FATIGUE | V29 |
| 01:45:00 | STOP | RECOVER | FATIGUE | V11 |
