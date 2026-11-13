# 스파이크 결과 (18.1, 2026-11-01 실측)

환경: Pleos Connect v2.1.0 에뮬레이터(AVD `pleos`, API 34, user 빌드, 2560x1440), Virtual Console REST, 맥에서 다른 빌드와 함께 실행.
검증 수단: 저장소 밖 임시 스파이크 앱, 실제 APK(debug, release), `PlatformDeviceTest`(계측 시험 11개, 저장소에 남김). 임시 진단 코드는 저장소에 남기지 않았다.

**판정**: 통과 = 통과 기준을 실제로 봄. 일부 = 일부만 봄. 미확인 = 이 환경에서 확인 못 함.

## 18.1 표

| # | 확인 | 판정 | 실측 결과 | 코드·설계 반영 |
| --- | --- | --- | --- | --- |
| 1 | DO Activity 유지, `SetupActivity` 차단 | 일부 | DO 선언 Activity는 D, 80km/h에서 유지. **DO 미선언 Activity도 플랫폼이 막지 않았다**(debug 스파이크 앱, D 0km/h·80km/h, `Current Driving State 2`, `UxR 80`, `Blocked activity log` 비어 있음). `cmd hmg_car_service`는 이 이미지에 없어 `driving_regulation`은 쓸 수 없다. 실제 앱은 자체 게이트로 D에서 `SetupActivity`가 닫히고 `MainActivity`로 돌아간다(debug, release) | 8.1 수정: 플랫폼 차단에 기대지 않는다 |
| 2 | 속도, 기어, 시동 수신 | 일부 | `PERF_VEHICLE_SPEED`(m/s), `GEAR_SELECTION` 수신. 콘솔 변경 후 1초 안(로그 시각 기준). 값: P=4, R=2, N=1, D=8. 1Hz로 요청해도 5~10Hz(minSampleRate 10). **`IGNITION_STATE`는 config null**(앱에 보이는 속성 목록 10개에 없음). 콘솔 `power-system/simulator`도 Car 전원 상태를 바꾸지 않아 시동 조작 수단이 없다 | 기준대로 서비스 시작을 ON으로 간주. `SpeedThrottle`로 1Hz. 재시동은 JVM 시험 |
| 3 | `CAR_SPEED` 동의, UX 제한 시점 | 통과 | 권한 없으면 `getCarPropertyConfig`가 null(미지원과 구분 안 됨). `pm grant`는 **`--user current` 필요**(운전자가 사용자 10). D 진입 즉시 `DO=true, UxR=0x50`, P 진입 즉시 `DO=false`. N도 DO=true 유지 | 8.1 게이트 그대로. `PlatformSupport.speedPermission`으로 구분 |
| 4 | 앱 영역, 주·야간 UI | 일부 | Landscape 창 1682x1210px, density 160, `screenWidthDp=1682`, `screenHeightDp=1120`, `smallestScreenWidthDp=1440`. 야간은 `cmd uimode night yes`로 전환되고 캡처를 `docs/research/pleos-home-night.png`에 두었다. Portrait는 보지 못했다(당시 매니페스트가 landscape 고정). **2026-11-05 Pleos 폼 팩터 테스트로 보충**: 콘솔 프리셋 6종의 세로·가로 창(예: 12.9인치 628×855dp, 1274×855dp)에 앱을 띄웠다. landscape 고정본은 세로 창 6개에서 모두 레터박스됐고 고정을 뺀 빌드는 창을 채웠다(`docs/test-scenarios.md` 5장, U-17) | 토큰 확정은 WP6 몫. 방향 고정 제거 |
| 5 | Pleos TTS·STT ON_DEVICE 한국어 | 일부 | TTS 연결·재생 정상(43자 약 5.5초, 네트워크·앱 등록 불필요). STT는 마이크가 없어 PCM `sendAudio`로 확인: "괜찮아", "피곤해", "응", "아니", "조금 졸려요" 모두 정확. "쉬어갈게"는 "출력할게"로 오인식(1회, 합성 음성). 첫 요청은 `ON_DEVICE_ENGINE_ERROR` 뒤 오답이 왔다(`sendAudio`를 준비 전에 보낸 경우). 실제 마이크 인식은 미확인 | `ListenSession` |
| 6 | 음성 출력·마이크 AVD, 음악 중 포커스, 첫 음절 | 일부 | **오디오를 켠 AVD는 이 환경(비대화형 하위 프로세스)에서 띄우지 못했다**(`-allow-host-audio` 유무와 무관, `QEMU2 main loop` 멈춤 뒤 종료. 창 있는 실행도 같은 증상이라 원인 단정 못 함. `-no-window -no-audio`만 부팅). 포커스: `USAGE_MEDIA GAIN` 보유 중 안내 요청 GRANTED, 음악 쪽 콜백 없음. `VOICE_COMMUNICATION GAIN_TRANSIENT`가 들어오면 안내가 `INTERRUPTED`. Speech Service는 포커스를 잡지 않고 `USAGE_MEDIA` 트랙으로 재생한다. 덕킹 음량과 첫 음절은 들어 보지 못했다 | 앱이 직접 포커스를 잡는다(이중 요청 아님). C-22 150ms는 미검증 그대로. 사람이 터미널에서 AVD를 띄워 들어 봐야 한다 |
| 7 | 잔여 시간, `requestTrip`, `addWaypoint` | 통과 | `onDrivingInfo.destination.duration/distance`, `onDestinationInfo.remainTime` 수신(경로 없으면 0). `requestTrip` → `onTripInfo`(거리 m, ETA 초). 외부 좌표 경유지 추가 성공. 상세는 아래 | `PleosNavi`, F3-9 시간 변경 |
| 8 | FusedLocation이 에뮬레이터 위치를 받나 | 통과 | `FUSED_LOCATION`은 dangerous, 없으면 호출 즉시 SecurityException. `adb emu geo fix 127.0276 37.4979` 반영 확인(accuracy 5). 조작 전 기본값은 37.42, -122.08(미국)이고 heading은 0.0. NaviHelper 현재 위치는 37.4127, 127.0947 | 국외 좌표는 쓰지 않고 다음 공급자로(`FixRules`), heading 0은 없음 처리 |
| 9 | 앱이 화면에 없을 때 FGS + 음성 + 위치 | 일부 | 지도를 앱 영역에 올린 채 SC2가 V02, V03, V04까지 진행하며 음성 재생(`USAGE_MEDIA` 트랙 확인). 프로세스는 FGS로 유지. 끝까지 돌려 보지는 않았다. 위치는 화면 밖에서 따로 보지 않았다(계측 시험은 앱 프로세스에서 수신 확인) | `specialUse` 유지 |
| 10 | 재부팅, suspend 후 서비스 시작 | 일부 | `adb reboot` 뒤 사용자 10에서 `BOOT_COMPLETED`로 **자동 시작 성공**, FGS 승격 성공. 단 `BOOT_COMPLETED` 게시 후 **83초** 뒤에 앱이 깨어났다(offload 큐). 사용자 0 프로세스는 뜨지 않았다. BOOT_COUNT 1→2 확인. **suspend는 이 에뮬레이터에서 일으킬 수단을 찾지 못했다** | 13장 표 유지. S4 "측정 안 된 구간"은 시작 실패 때만 |
| 11 | 헤드업 `CATEGORY_NAVIGATION` | 일부 | `NotificationCenterListener: headUp NotificationPosted` 시스템 로그와 알림 기록(importance 4), 상단 벨 표시 확인. 배너 화면 자체는 캡처 시점에 놓쳤다 | `DriveNotifier` |
| 12 | Odometer, HVAC 재순환, Seat | 통과 | Odometer READ, 콘솔 123.4km 반영. 재순환 READ_WRITE, **영역이 전 좌석 묶음 하나**(단일 좌석은 IllegalArgumentException), 콘솔·앱 양방향 반영. 콘솔 하트비트를 끈 뒤 실행 2회에서 `set`이 onComplete를 주고도 값이 바뀌지 않았다(하트비트가 원인인지는 이 상관만 봤다). 그래서 `set`은 다시 읽어 확인한다. 자동 재순환 꺼짐. Seat READ, 좌석 5개. **콘솔 값과 SDK 값이 반대**(콘솔 Occupied → SDK false, 콘솔 Empty → SDK true). `CarInfo`는 VIN, CRN만 제공하고 차종 이름이 없다 | F10·F11 진행. 동승 시연은 `ScriptedOccupancy`. CarInfo 미사용(10.2) |
| 13 | release 서명 APK, cleartext | 일부 | release에서 서비스, FGS, 게이트, Setup 닫힘 확인. cleartext는 debug 빌드(같은 `network_security_config`)에서 `10.0.2.2:8080` 페어링 성공, `example.com`은 `CLEARTEXT ... not permitted`. **release 음성은 아래 차단 버그로 동작하지 않는다** | |
| 14 | 공공데이터 POI | 통과 | WP2 완료(`docs/poi-sources.md`) | |

## 차단 버그 (통합 단계에서 수정, `IcuRegexTest`가 재발을 막는다)

**`core/.../voice/VoiceCatalog.kt:18`의 `Regex("""\{[^}]*}""")`가 Android에서 `PatternSyntaxException`을 던진다.** JVM 정규식은 짝 없는 `}`를 받지만 Android ICU는 거부한다. `VoiceCatalog` 초기화가 실패해(`ExceptionInInitializerError`) **실제 기기에서 모든 음성이 나가지 않는다**. JVM 시험은 통과하므로 CI로는 안 잡힌다. 고칠 내용은 `Regex("""\{[^}]*\}""")` 한 글자다. 이 표의 시연·음성 항목은 이 한 글자만 바꾼 **저장소 밖 임시 빌드**로 확인했고, 저장소의 APK에서는 `runtime: ExceptionInInitializerError`가 그대로 난다.

## NaviHelper 실측 (`PleosNavi`에 반영)

| 항목 | 결과 |
| --- | --- |
| 안내 중 판별 | `RouteStateInfo.isRouting`은 안내 중에도 false. `naviMode == ROUTE_GUIDING`을 쓴다 |
| `requestRoute` | `onRouteStarted`까지 6.0초(유휴), 15초(다른 빌드 동시 실행). 지도 앱이 앱 영역 전면으로 올라온다 |
| `addWaypoint` | `onRouteCancelled` → `SAFE_GUIDING` → `onWaypointChanged(ADD)` 순서. 완료까지 9.2~9.4초(유휴), 25초(동시 실행). 취소 이벤트는 재탐색의 일부다 |
| `WaypointIndex.FIRST` 재사용 | 기존 경유지를 지우지 않고 뒤에 붙는다 |
| 슬롯 참(2개), 같은 곳 | 내비가 **아무 이벤트도 주지 않는다**. 어댑터가 먼저 답한다(`SLOTS_FULL`, `ALREADY_WAYPOINT`, 약 1초) |
| `WaypointInfo` | 좌표가 0.0으로 온다. 같은 곳 판정은 `poiId`로 한다 |
| 경유지 도착(`onWayPointArrived`) | 차가 움직이지 않아 **미확인**. 콜백 배선만 되어 있다 |

## SDK 사용 시 주의

| SDK | 실측 |
| --- | --- |
| TextToSpeech | `initialize` 전 `addEventListener`는 lateinit 예외. 리스너는 `onSuccess`에서 건다. 재생 없이 `stop`해도 `onStop`이 온다. 콜백은 별도 스레드 |
| SpeechToText | `request` 후 `onReady`까지 1.2~1.8초. `onEndedRecognition`이 `completed=true`보다 먼저 온다. 마이크 경로의 `stop`은 콜백이 없다. 무음이면 스스로 끝나지 않는다. RECORD_AUDIO 없이 `request`가 받아들여진다 |
| Android 대체 | `TextToSpeech` 엔진 없음(init ERROR). `SpeechRecognizer`는 available이고 무음에서 NoSpeech로 끝난다. 실제 인식은 미확인 |
| Vehicle | `initialize`에 완료 콜백이 없다. 1.5초 뒤 호출은 성공. 어댑터는 재시도한다 |
| 권한 | dangerous: `CAR_SPEED`, `FUSED_LOCATION`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`. 그 외 Pleos 권한은 설치 시 부여. `adb install -g`도 동작 |
| Virtual Console | `/vehicle/seat?area=passenger&kind=occupied&value=true`, `/vehicle/odometer?traveled_distance_km=`, `/vehicle/hvac?recirc=true`, `/vehicle/power-system/simulator?area_id=0&state=` (시스템 영향 없음) |

## 실행 메모

- 이 세션에서 에뮬레이터를 다시 띄울 때 창 모드와 오디오 모드는 멈췄고 `emulator -avd pleos -no-snapshot -no-audio -no-boot-anim -no-window`만 부팅됐다. 사람이 터미널에서 띄우면 다를 수 있다.
- 시연 자동화의 `adb shell am start ... SetupActivity --es scenario sc1`은 시나리오 ID가 소문자이고 debug 빌드에서만 된다(release는 `exported=false`).
- 내비의 경로는 앱 프로세스와 무관하게 남는다. 시연 전 "안내 종료" 또는 에뮬레이터 재시작으로 지운다.
