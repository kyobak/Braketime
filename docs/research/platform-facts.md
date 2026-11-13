# 플랫폼·공모전 조사 결과 (2026-11-01 확인)

이 파일은 공식 문서와 실제 저장소에서 직접 확인한 사실만 담는다. 확인 안 된 것은 "미확인"으로 적었다.

## 공모전 심사 (1차, 배점 비공개, 네 항목 모두 필수)
- 차량특화성: 모바일 대비 차량 환경만의 가치, 주행·정차 상황과 차량 기능의 유기적 결합
- 창의성: 독창성, Pain Point가 명확하고 해결 방식이 분명한가
- 기술 활용성: Pleos Playground SDK·API를 핵심 기능과 사용 경험에 적절히 활용했는가
- 사업성: 타깃 고객, 사용자 확보, 지속 이용·확대 (타깃 분석, 예상 MAU, Retention, 확대 계획)
- 가산점: 시연 영상(화면 녹화 포함), GitHub 저장소(소스+APK)
- 2차 PT 대상은 PT 전까지 실제 동작하는 앱 필수. 최종 10팀은 현장 구동 전시용 APK 필수(구동 안정성 시험)

## GitHub 제출 조건
- APK는 저장소 `apk/` 폴더, 파일명 `팀명_앱이름_release.apk`, 실행 가능한 release 버전
- README.md 필수 내용: 앱 이름과 한 줄 소개, 실행 방법, 테스트 환경과 필요한 설정, APK 위치 또는 Release 링크

## 개발 환경
- Android API 28 이상, Gradle 8.0 이상, Java 또는 Kotlin
- 이 맥: Android SDK ~/Library/Android/sdk (platforms android-36, android-37.2, build-tools 36.0.0/36.1.0, sdkmanager 없음, android.car.jar는 platforms/android-36/optional/ 에 있음), JDK 21 (~/Library/Java/JavaVirtualMachines/temurin-21.0.12.1/Contents/Home, 기본 java는 27이므로 JAVA_HOME을 21로 지정해야 함), Gradle 9.8.2, Node 24, adb는 ~/Library/Android/sdk/platform-tools/adb (PATH에 없음). 잘 빌드되는 다른 로컬 Android 프로젝트를 읽기 전용으로만 참고했다

## Pleos SDK (라이선스 동의 완료, 공개 저장소에서 받을 수 있음을 확인)
- Maven: https://nexus-playground.pleos.ai/repository/maven-releases/
- 좌표(메타데이터로 확인): ai.pleos.playground:Vehicle:2.3.2, NaviHelper:3.0.5, TextToSpeech:3.3.1, SpeechToText:3.3.1, FusedLocation:1.1.3
- 라이선스: SDK 역분해·디컴파일 금지(3.4). API 형태는 공식 문서와 컴파일 결과로만 확인한다. 42dot 상표·로고 사용권 없음(3.6).
- 공식 문서 시작점: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/ 와 https://document.pleos.ai/en/api-reference/connect-sdk-pleos/
- 각 SDK 하위 페이지 패턴: /docs/pleos-only/connect-sdk-pleos/{Vehicle|NaviHelper|TextToSpeech|SpeechToText|FusedLocation}/{intro|project-setup|importing-sdk|declaring-permissions|initialization-and-release|event-listeners|entry|...}
- Vehicle: 진입점 `Vehicle`, `initialize()`/`release()`, 도메인 객체는 `getXxx()`, `checkXXXCapabilities`로 지원 확인, 결과는 onComplete/onFailed 람다. 도메인: Brake, CarInfo, Display, Door, DrivingMode, EvBattery, Fuel, HVAC, Light, Odometer, Safety, Seat, SideMirror, Steeringwheel, Tire, Toll, TurnSignal, Window, Wiper. 튜토리얼: /docs/pleos-only/tutorial/vehicle-sdk-sample-app
- 표준 데이터(속도·기어)는 AAOS Car API(CarPropertyManager) 사용 권장. SDK는 표준에 없는 영역만 제공
- NaviHelper: `ai.pleos.playground.navi.helper.NaviHelper(applicationContext)`, `initialize(onConnected=, onFailed=)`, `addListener`, `requestRoute(RouteInfo)`, `addWaypoint(RequestWaypointInfo(latitude, longitude, waypointIndex, poiId, poiName, poiSubId, address))` (경유지 최대 2개, WaypointIndex.FIRST/SECOND/ALL), `getWaypointInfo()`, `getDestinationInfo()`, `removeListener`, `release`. 경로 잔여 시간과 장소 검색 여부는 /NaviHelper/routes 와 /NaviHelper/entry 에서 확인할 것
- TTS: `ai.pleos.playground.tts.TextToSpeech(applicationContext, Mode.HYBRID)`, `initialize(OnInitializeListener)`, `addEventListener`, `setLocale`, `speak`, `stop`, `removeEventListener`, `release`. Mode: HYBRID/SERVER/ON_DEVICE(네트워크·앱 등록 불필요). 권한 pleos.car.permission.TTS_SERVICE
- STT: `ai.pleos.playground.stt.SpeechToText(applicationContext, Mode.HYBRID)`, `initialize(OnInitializeListener)`, `addListener(ResultListener)`, `setLocale`, `request`, `stop`, `removeListener`, `release`. 권한 pleos.car.permission.STT_SERVICE
- FusedLocation: `FusedLocation`, `isSupported`, `getLastKnownLocation`, `registerFusedLocationCallback`, `unregisterFusedLocationCallback`, `addProximityAlert`, `showLocationAccessBlockedPopup`, `initialize`/`release`. 권한 pleos.car.permission.FUSED_LOCATION. `LocationData`: 위도, 경도, 고도, 진행 방향, 신뢰도, 타임스탬프

## Pleos Connect 에뮬레이터
- 이미지: system-images;android-34;pleos_car_emulator;arm64-v8a, Pleos Connect v2.1.0, tag android-automotive, vendor pleos. 지금 내려받아 ~/Library/Android/sdk/system-images/android-34/pleos_car_emulator/ 아래에 설치 중
- AVD 권장값: Automotive, API 34, Cold boot, RAM 4GB. Virtual Console 제공(2.1.0 이상)
- 차종마다 지원 API가 다르다 (API Compatibility & Availability 문서)

## 앱 구조·UI 규칙 (Pleos 공식)
- 앱 영역은 창 1개 또는 2개. Driving view는 고정 영역이라 앱이 가릴 수 없음
- `screenOrientation` 미지정이면 Portrait(창 1개), `landscape`면 창 2개를 쓸 수 있음(실측: 미지정 빌드도 에뮬레이터 주 디스플레이에서 1682×1210px 2창, `docs/test-scenarios.md` 5장·U-17. 실차 창 정책은 미확인). 두 방향 모두 설계해야 하고, Portrait에 핵심 기능·주요 정보, Landscape에 추가 정보·확장 기능을 둔다. 창 크기가 바뀌어도 핵심 기능을 쓸 수 있어야 함
- 포그라운드 복귀 시 상태 복원, 백그라운드 진입 시 화면 갱신·애니메이션 중단, 불필요한 상주·폴링 최소화. 프로세스 재생성 후에도 복원되게 저장
- 주행 제한: `CarUxRestrictions`로 제한 상태를 확인해 UI·기능을 줄이거나 끈다. 주행 중 영상·애니메이션·자동 스크롤·움직이는 이미지 금지. 터치 최소화, 음성 우선. 상세 설정은 정차·주차 중에만
- 글랜서빌리티: 1~2초 안에 이해. 입력 응답 0.25초 이내(늦으면 처리 중 표시)
- 대비 최소 4.5:1. 글자 높이 핵심 정보(경고 등) 4mm(한국어 가이드 5mm) 이상, 일반 3mm 이상, 최소 12sp, 줄높이 1.5배 권장. 메시지는 짧게(한 줄 80자, 문단 3줄 이하). 아이콘에 텍스트 라벨 병기
- 터치 타깃은 버튼보다 약간 크게, 큰 타깃 사용. 터치에 햅틱/오디오 피드백
- 시스템 폰트: Asta Sans (Google Fonts, 한글·영문 지원, SIL OFL). 브랜드 폰트를 쓸 수 있으나 장식 글꼴 지양
- 주야간 모드 자동 대응. 다국어 지원, 차종별 해상도·비율 대응. 디스플레이: Connect-S(17/14.6/12.9인치), Connect-W/W-Extended, Connect-WF. 개발 장비 17인치 2560x1440 (Devbox)
- 중요 주행 음성은 Audio Focus 정책에 맞게(미디어 일시정지·덕킹)
- 색상 토큰 페이지(/en/docs/connect/guide/docs-design/foundations/colors)의 값은 이미지로만 제공됨. 토큰 분류: Background, Button, Controller, Driving, Icon, Informative, Regulation, Surface, Text 등
- 디자인 가이드 시작점: https://document.pleos.ai/en/docs/connect/guide/docs-design/Introduction/intro , 운전 안전: /en/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline , 리뷰: /en/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements

## 현대차 브랜드
- 공식 가이드는 공개 문서로 확인하지 못함. Hyundai Blue #002C5F는 복수 출처로 확인. Active Blue #00AAD2, Sand #E4DCD3, Light Sand #F6F3F2는 널리 쓰이나 공식 원문 미확인
- Hyundai Sans 글꼴은 라이선스 미확인이라 번들하지 않는다. 현대차·Pleos 로고와 상표도 앱에 넣지 않는다(사용권 없음, 공모전 앱이 공식 앱처럼 보이면 안 됨)

## 에뮬레이터 실측 (2026-11-01, 직접 실행해서 확인)
- AVD 이름 `pleos` 생성 완료. 실행: `~/Library/Android/sdk/emulator/emulator -avd pleos -no-snapshot -no-audio -no-boot-anim` (이미 실행 중일 수 있으니 `adb devices` 먼저 확인). 2560x1440, density 160, API 34, user 빌드(adb root 불가, `cmd car_service inject-vhal-event` 불가), 로케일 ko-KR.
- **차량 조작은 Virtual Console REST로 한다.** 게스트 8088 포트이고 `adb forward tcp:8088 tcp:8088` 후 사용. 브라우저로 http://127.0.0.1:8088/ 를 열어도 된다.
  - 입력이 먹으려면 하트비트가 3초 안에 계속 와야 한다: `POST /browser/session?client=cli` (1초 주기). 없으면 `{"error":"emulator_inactive"}`.
  - `PUT /vehicle/gear?gear=Drive|Park|Reverse|Neutral`, `PUT /vehicle/controls?speed=80&accelerator=30&brake=0&steering=0`, `GET /vehicle/state`, `GET /vehicle/catalog`, 그 밖에 `PUT /vehicle/hvac?...`, `PUT /vehicle/body?...`.
  - 80km/h + Drive 설정 시 `dumpsys car_service --services CarDrivingStateService`가 Current Driving State 2(Moving)로, `CarUxRestrictionsManagerService`가 `DO: true UxR: 80`으로 바뀌는 것을 확인했다. 즉 실제 CarUxRestrictions 경로를 그대로 탄다.
  - 하트비트가 끊겨도 마지막 속도·기어 값은 유지된다.
- 주행 제한 강제: 문서의 `cmd hmg_car_service --service drivingstate driving_regulation on|off`는 이 이미지(v2.1.0)에 서비스가 없어 동작하지 않는다(`docs/spike-results.md`). D 기어로 전환하면 DO=true가 된다.
- 설치된 서비스: ai.pleos.playground.service.{vehicle,navi,location.fused,sync,adas,cap}, ai.pleos.service.car, 지도 앱 ai.umos.maps.android.navigation.app
- 화면 녹화: `adb exec-out screenrecord --output-format=h264 - > screen.h264`

## 실제 Pleos Connect 화면에서 관찰한 시각 언어 (docs/research/pleos-home-day.png 를 직접 볼 것)
- 왼쪽 약 1/3이 Driving view(앱이 가릴 수 없음, 속도·기어 PRND·차량 그림), 오른쪽이 앱 영역, 맨 위 상태바(자물쇠·검색·프로필·시계), 맨 아래 검은 공조·GNB 바.
- 주간 모드 기준 희고 차분한 배경에 **검정 면과 얇은 회색 선**이 중심이다. **모서리가 전부 직각**이다(검색창, 지도 버튼, GNB 칩). 그림자·그라데이션 없음. 선택 표시는 주황 밑줄 하나뿐, 파랑은 현재 위치·방향에만 쓴다. 숫자는 가볍고 크며, 라벨은 작고 회색이다.
- 이 앱의 화면은 이 시스템 UI 옆에 나란히 놓인다. 둥근 카드나 화려한 색은 바로 이질적으로 보인다.
- `com.android` 기본 앱 영역 실제 크기는 앱을 띄워 확인할 것(대략 가로 1700px, 세로 1210px 안팎의 앱 영역).
