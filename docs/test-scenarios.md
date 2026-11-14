# Braketime 검증 시나리오

명세 13장 검증을 자동 테스트로 옮긴 표다. 판단 규칙의 원본은 `docs/DESIGN.md` 3~5장이다. 표와 설계서가 다르면 설계서를 먼저 확인해 둘 중 하나를 고치고, 이유를 커밋에 남긴다. 시뮬레이션으로 예측 성능을 주장하지 않는다. "규칙이 기준대로 동작한다"만 증명한다.

## 0. 13장 대응표와 실행

| 13장 항목 | 표 | 실행 위치 | 명령 |
| --- | --- | --- | --- |
| 판단 규칙 | A, B, C, G | `:core` JVM | `./gradlew :core:test` |
| 혈당 데모 | D | `:core` JVM, 시연 모드 대조 | `./gradlew :core:test`, `ScenarioCompare` 리포트 |
| 운전 방해 | H, V | Compose 테스트(Robolectric, 에뮬레이터 불필요), 수동 점검 | `./gradlew :vehicle:testDebugUnitTest`, H6 측정 |
| 오프라인 | E | JVM + 에뮬레이터 | 비행기 모드 수동 1회, 나머지 자동 |
| (추가) 장소 선택 | P | `:core` JVM | `./gradlew :core:test` |
| (추가) 서버·계약 | S | `:core`, `:server` JVM | `./gradlew :core:test :server:test` |
| (추가) 시연 정직성 | R | JVM, 에뮬레이터 | `./gradlew :core:test` |

**1차 통과 기준**: A, B, C, D, E, G, H1~H5, H7, P, R이 전부 통과. S는 서버 P0 범위. H6은 측정값을 기록한다.

## 1. 공통 규칙

- 시나리오는 `core/src/main/resources/scenarios/{ID}.json`이고 러너가 돌린다(설계서 9.4). 시각은 가상 시계다. 대기 없이 돈다.
- T는 첫 D 전환 기준 경과 시각이다. 음수는 전이다.
- **음성 칸은 엄격하다.** 직전 줄 이후 이 시각까지 나온 음성 전체와 정확히 같아야 한다. "없음"은 나오면 실패다.
- 괄호 안은 음성 변수다.
- 상태 칸의 괄호는 사유다. F=FATIGUE, C=CHECKIN, G=GLUCOSE, L=GLUCOSE_UNCONFIRMED.
- cds는 연속 운전 시간이다.
- 표에 없는 앞 구간은 P0와 A1(또는 해당 C·D 표)의 같은 시각 줄과 같다. 예컨대 A8의 1:45:00에는 V02, V03이 이미 나온 상태다. 시나리오 JSON에는 빠짐없이 적는다. 표에서는 차이가 나는 줄만 보인다.

- 표와 JSON의 대응: D4, D4 타이머 경계, G1~G4, G6, G8~G11은 시나리오가 아니라 `core/src/test`의 단위 테스트다. 5.4 칸 중 시나리오가 지나가지 않는 것은 `TransitionTableTest`다. E1은 A1에 요약 없음을 끼워 같은 로그인지 본다. E4는 엔진 몫(요약 없이 5초 뒤 V01)만, E3, E5도 엔진 몫만 JSON으로 돌린다. E6, E7은 런타임과 에뮬레이터 몫이고 R4~R9는 시연 런타임 몫이다.
- 표를 JSON으로 옮기며 정한 것: D21의 4.6은 0:40 P 줄에 넣는다(출발 전에 값이 있어야 V25다). D24는 0:30에 0:25 측정을 다시 받고 0:35의 새 판독에서 해제된다. D15, D16은 시동 줄의 상태와 5초 뒤 줄의 음성으로 나눈다(설계서 5.4 아래 규칙). A22b는 "그만" 뒤 15분 휴식으로 간격이 10분에 돌아오는지 본다. D13b는 유효했던 값이 불허 source로 바뀌면 V19가 나오는지 본다.

**사전 조건 P0**(별도 표시 없으면 적용): 2026-11-20 09:00 현지 기준. 그날 첫 시동.

| T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- |
| -0:01:00 | 시동 ON, P, 0 | NORMAL | 없음 |
| -0:00:55 | - | NORMAL | V01 |
| -0:00:50 | "괜찮아" | NORMAL | 없음 |
| 0:00:00 | D, 80 | NORMAL | 없음 |

## A. 피로 규칙

**A1 기본 흐름** (2시간 → 정차 권고, 15분 → 복귀)

| T | 입력 | 상태 | cds | 음성 |
| --- | --- | --- | --- | --- |
| 1:44:59 | - | NORMAL | 1:44:59 | 없음 |
| 1:45:00 | - | CAUTION(F) | 1:45:00 | V02(1시간 45분), V03 |
| 1:45:20 | "괜찮아" | CAUTION(F) | | 없음 |
| 1:59:59 | - | CAUTION(F) | | 없음 |
| 2:00:00 | - | STOP(F) | 2:00:00 | V04(2시간, 거리, 장소) |
| 2:00:10 | "응" | STOP(F) | | V29, 안내 시작 |
| 2:06:00 | P, 0 | RECOVER(F) | 2:06:00 | V11(15분) |
| 2:20:59 | - | RECOVER(F) | 2:06:00 | 없음 |
| 2:21:00 | - | NORMAL | 0 | V12 |

**A2 15분 경계**

| T | 입력 | 상태 | cds | 음성 |
| --- | --- | --- | --- | --- |
| 1:00:00 | P | NORMAL | 1:00:00 | 없음 |
| 1:14:59 | D, 30 | NORMAL | 1:00:00 | 없음 |
| 1:20:00 | P | NORMAL | 1:05:01 | 없음 |
| 1:35:00 | - | NORMAL | 0 | 없음 |

**A3 신호 대기는 운전** / **A4 정체는 휴식이 아님**

| ID | T | 입력 | 상태 | cds | 음성 |
| --- | --- | --- | --- | --- | --- |
| A3 | 1:44:00 | D, 0 | NORMAL | 1:44:00 | 없음 |
| A3 | 1:45:00 | - | CAUTION(F) | 1:45:00 | V02, V03 |
| A4 | 0:30:00 | D, 0 | NORMAL | 0:30:00 | 없음 |
| A4 | 0:50:00 | D, 80 | NORMAL | 0:50:00 | 없음 ("쉬었어요" 금지) |
| A4 | 1:00:00 | N, 0 | NORMAL | 1:00:00 | 없음 |
| A4 | 1:16:00 | D, 80 | NORMAL | 1:16:00 | 없음 |

**A5 거절 반복**(10장, 앞 구간 A1과 동일하게 2:00까지. 1:45의 V03에는 "괜찮아")

| T | 입력 | 상태 | ignored | 음성 |
| --- | --- | --- | --- | --- |
| 2:00:00 | - | STOP(F) | 0 | V04(2시간) |
| 2:00:10 | "아니" | STOP | 1 | 없음(효과 OfferVentilation) |
| 2:10:10 | - | STOP | 1 | V04(2시간 10분) |
| 2:10:20 | "괜찮아" | STOP | 2 | 없음 |
| 2:20:20 | - | STOP | 2 | V04(2시간 20분) |
| 2:20:30 | "아니" | STOP | 3 | 없음 |
| 2:30:30 | - | STOP | 3 | V04(2시간 30분) |
| 2:30:40 | "아니" | STOP | 4 | 없음 |
| 2:40:40 | - | STOP | 4 | V05(2시간 40분) |
| 2:45:00 | P | RECOVER | 4 | V11 |
| 3:00:00 | - | NORMAL | 0 | V12 |

**A6 회복 중 출발**

| T | 입력 | 상태 | cds | ignored | 음성 |
| --- | --- | --- | --- | --- | --- |
| 2:00:00 | - | STOP(F) | 2:00:00 | 0 | V04 |
| 2:05:00 | P | RECOVER | 2:05:00 | 0 | V11 |
| 2:10:00 | D, 40 | STOP | 2:05:00 | 0 | V18(10분) |
| 2:20:00 | - | STOP | 2:15:00 | 0 | V04(2시간 15분) |
| 2:22:00 | P | RECOVER, 휴식 0부터 | 2:17:00 | 0 | V11(15분) |
| 2:37:00 | - | NORMAL | 0 | 0 | V12 |

(A6의 2:00:00 앞은 1:45의 V03에 "괜찮아", 2:00:00의 V04는 무응답 처리. V04 재안내는 P로 취소된다.)

**A7 주차 이동 유예**

| T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- |
| 2:05:00 | P | RECOVER | V11 |
| 2:12:00 | D, 5 | RECOVER(유예) | 없음 |
| 2:12:20 | P | RECOVER, 휴식 유지 | 없음 |
| 2:20:00 | - | NORMAL | V12 |

A7b: 2:12:00 D, 5 유지 → 2:12:30 출발 확정, STOP, cds 30초 소급, V18(8분). A7c: 2:12:00 D, 15 → 즉시 확정.

**A8 재시동, 공백 15분 미만** / **A9 공백 15분 이상** / **A10 STOP에서 바로 끔** / **A11 프로세스 종료**

| ID | T | 입력 | 상태 | cds | 음성 |
| --- | --- | --- | --- | --- | --- |
| A8 | 1:50:00 | P | CAUTION(F) | 1:50:00 | 없음 |
| A8 | 1:51:00 | 시동 OFF | 저장 | | 없음 |
| A8 | 1:58:00 | 시동 ON | CAUTION(F), 휴식 8분 | 1:50:00 | 없음(체크인 없음) |
| A8 | 2:00:00 | D, 80 | CAUTION(F) | 1:50:00 | 없음 |
| A8 | 2:10:00 | - | STOP(F) | 2:00:00 | V04 |
| A9 | 2:00:00 | - | STOP(F) | 2:00:00 | V04 |
| A9 | 2:02:00 | P | RECOVER | 2:02:00 | V11 |
| A9 | 2:03:00 | 시동 OFF | 저장 | | 없음 |
| A9 | 2:20:00 | 시동 ON | NORMAL | 0 | V20 |
| A10 | 2:00:00 | - | STOP(F) | 2:00:00 | V04 |
| A10 | 2:01:00 | P | RECOVER | 2:01:00 | V11 |
| A10 | 2:01:05 | 시동 OFF | 저장 | | 없음 |
| A10 | 2:05:00 | 시동 ON | RECOVER, 휴식 4분 | | 없음 |
| A10 | 2:16:00 | - | NORMAL | 0 | V12 |
| A11 | 1:00:00 | 프로세스 종료 | - | | - |
| A11 | 1:05:00 | 재실행, 증거 UNKNOWN | NORMAL | 1:05:00 | 없음 |
| A12 | 1:00:00 | 프로세스 종료 | - | | - |
| A12 | 1:05:00 | 재실행, 증거 DROVE(거리 3km) | NORMAL | 1:05:00 | 없음 |
| A13 | 1:00:00 | 프로세스 종료 | - | | - |
| A13 | 1:05:00 | 재실행, 증거 PARKED(거리 0.1km), P | NORMAL, 휴식 5분 | 1:00:00 | 없음 |
| A14 | 1:40:00 | 프로세스 종료 | - | | - |
| A14 | 1:50:00 | 재실행, UNKNOWN | CAUTION(F) | 1:50:00 | V02(1시간 50분), V03 |

**A20~ 프롬프트 처리**(각 줄은 A1의 2:00:00에서 분기. 1:45의 V03에는 "괜찮아")

| ID | T | 입력 | 상태 | ignored | 음성 |
| --- | --- | --- | --- | --- | --- |
| A20 수락 후 미정차 | 2:00:10 | "응" | STOP | 0 | V29 |
| A20 | 2:20:09 | - | STOP | 0 | 없음 |
| A20 | 2:20:10 | - | STOP | 0 | V04(2시간 20분) |
| A21 경유지 도착 후 미정차 | 2:00:10 | "응" | STOP | 0 | V29 |
| A21 | 2:10:00 | 경유지 도착 | STOP | 0 | 없음 |
| A21 | 2:13:00 | - | STOP | 0 | V04(2시간 13분) |
| A22 "그만" | 2:00:10 | "그만" | STOP | 1 | V26 |
| A22 | 3:00:09 | - | STOP | 1 | 없음 |
| A22 | 3:00:10 | - | STOP | 1 | V04(3시간) |
| A22 | 3:05:00 | P | RECOVER | 1 | V11 |
| A22 | 3:20:00 | - | NORMAL | 0 | V12 (muted 해제) |
| A23 침묵 | 2:00:18 | 침묵 | STOP | 0 | 없음 |
| A23 | 2:10:18 | - | STOP | 0 | V04 |
| A24 분류 실패 | 2:00:10 | "음" | STOP | 0 | V21S |
| A24 | 2:00:20 | "글쎄" | STOP | 0 | 없음 |
| A24 | 2:10:20 | - | STOP | 0 | V04 |
| A25 뒤섞인 응답 | 2:00:10 | "아니 쉴게" | STOP | 0 | V21S |
| A25 | 2:00:20 | "응" | STOP | 0 | V29 |

**A30~ 곧 도착**(F2)

| ID | T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- | --- |
| A30 | 1:30:00 | 경로 잔여 1800초 | NORMAL | 없음 |
| A30 | 1:45:00 | (잔여 900초) | CAUTION(F) | V22(15분), V02·V03 없음 |
| A31 | 1:59:00 | 경로 잔여 780초 | CAUTION(F) | 없음 |
| A31 | 2:00:00 | (잔여 720초) | STOP(F) | V22(12분). 마이크 없음 |
| A31 | 2:10:00 | (잔여 120초) | STOP(F) | V22(2분) |
| A32 | 1:45:00 | 경로 잔여 901초 | CAUTION(F) | V02, V03 |
| A33 | 1:45:00 | 경로 없음 | CAUTION(F) | V02, V03 |

## B. 체크인

| ID | T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- | --- |
| B1 | -0:00:55 | - | NORMAL | V01 |
| B1 | -0:00:50 | "좀 피곤해" | CAUTION(C) | V31 |
| B1 | 0:00:00 | D, 80 | CAUTION(C) | 없음 |
| B1 | 0:30:00 | - | CAUTION(C) | V03 |
| B1 | 0:30:10 | "괜찮아" | NORMAL | 없음 |
| B2 | -0:00:50 | "피곤해" | CAUTION(C) | V31 |
| B2 | 0:30:00 | - | CAUTION(C) | V03 |
| B2 | 0:30:10 | "졸려" | STOP(C) | V06 |
| B2 | 0:30:20 | "응" | STOP(C) | V29 |
| B2 | 0:38:00 | P | RECOVER(C) | V11 |
| B2 | 0:53:00 | - | RECOVER(C), cds=0 | V03 |
| B2 | 0:53:10 | "좋아" | NORMAL | V12 |
| B3 | -0:00:55 | - | NORMAL | V01 |
| B3 | -0:00:50 | "음" | NORMAL | V21 |
| B3 | -0:00:45 | "글쎄" | NORMAL(보통) | 없음 |
| B4 | -0:00:55 | - | NORMAL | V01 |
| B4 | -0:00:47 | 침묵 | NORMAL(미응답) | 없음(V21 없음) |
| B5 | (B2의 0:53:00에 이어) 0:53:10 | "피곤해" | RECOVER(C), 휴식 재시작 | V23 |
| B5 | 0:58:00 | D, 40 | CAUTION(C), streak=1 | 없음 |
| B5 | 1:28:00 | - | CAUTION(C) | V03 |
| B6 | (B2의 0:53:00에 이어) 0:53:08 | 침묵 | CAUTION(C), streak=1 | 없음 |
| B7 | -0:00:55 | - | NORMAL | V01 |
| B7 | -0:00:50 | "괜찮아" | NORMAL | 없음 |
| B7 | 0:10:00 | 시동 OFF | 저장 | 없음 |
| B7 | 0:40:00 | 시동 ON(같은 날) | NORMAL | 없음 (0:40:05에도 없음) |
| B8 | -0:00:58 | D, 80 (시동 2초 뒤) | NORMAL | 없음. 이번 시동에는 V01 없음 |
| B8 | 다음 시동, P | - | NORMAL | 5초 뒤 V01 |
| B9 | 03:59:59 시동(전날 답함) | - | NORMAL | 없음 |
| B9 | 04:00:00 이후 시동 | - | NORMAL | 5초 뒤 V01 |
| B10 V01 듣는 중 출발 | -0:00:10 시동 ON, P | - | NORMAL | 없음 |
| B10 | -0:00:05 | - | NORMAL | V01 |
| B10 | 0:00:00 | D, 80 | NORMAL | 없음 |
| B10 | 0:00:02 | "좀 피곤해" | CAUTION(C) | V31 |

B3b(단위 테스트): C=CAUTION에서 V03에 "음", V21에 "글쎄"면 C=CAUTION 유지. B11(단위 테스트): 온보딩 중 시동 5초가 지나도 V01 없음, `checkinAskedDay` 기록 없음. 온보딩이 끝나면 P일 때 그 자리에서 V01.

**분류 사전**(`VoiceClassifier`, 파라미터화 테스트)

| 입력 | checkin | stopPrompt |
| --- | --- | --- |
| 괜찮아 | GOOD | DECLINE |
| 좋아 | GOOD | ACCEPT |
| 그냥 그래 | NEUTRAL | UNCLASSIFIED |
| 보통이야 | NEUTRAL | UNCLASSIFIED |
| 좀 피곤해 | TIRED | UNCLASSIFIED |
| 졸려 | TIRED | UNCLASSIFIED |
| 안 피곤해 | GOOD | UNCLASSIFIED |
| 피곤하지 않아 | GOOD | UNCLASSIFIED |
| 안 졸려 근데 힘들어 | TIRED | UNCLASSIFIED |
| 안 좋아 | TIRED | UNCLASSIFIED |
| 안 괜찮아 | TIRED | UNCLASSIFIED |
| 괜찮은데 좀 졸려 | TIRED | UNCLASSIFIED |
| 응 | NEUTRAL | ACCEPT |
| 네 | NEUTRAL | ACCEPT |
| 아니 | UNCLASSIFIED | DECLINE |
| 아니 괜찮아 | GOOD | DECLINE |
| 아니 피곤해 | TIRED | DECLINE |
| 응 쉴게 | NEUTRAL | ACCEPT |
| 가자 | UNCLASSIFIED | UNCLASSIFIED |
| 됐어 | NO_SPEECH | DECLINE |
| 나중에 | NO_SPEECH | DECLINE |
| 아니 쉴게 | UNCLASSIFIED | UNCLASSIFIED |
| 안 쉴게 | UNCLASSIFIED | DECLINE |
| 안 갈게 | UNCLASSIFIED | DECLINE |
| 그만 | NO_SPEECH | MUTE |
| 오늘은 됐어 | NO_SPEECH | MUTE |
| 응 그만 | NO_SPEECH | MUTE |
| 그만 쉴게 | NO_SPEECH | UNCLASSIFIED |
| 그냥 갈게 | NEUTRAL | DECLINE |
| 계속 가자 | UNCLASSIFIED | DECLINE |
| 더 갈게요 | UNCLASSIFIED | DECLINE |
| 이대로 갈게 | UNCLASSIFIED | DECLINE |
| 쉬어 갈게 | UNCLASSIFIED | ACCEPT |
| 그냥 쉬어 갈게 | NEUTRAL | ACCEPT |
| 더 쉬어 갈게 | UNCLASSIFIED | ACCEPT |
| 거기 들러 가자 | UNCLASSIFIED | ACCEPT |
| 어 | NEUTRAL | ACCEPT |
| 어 잠깐만 | NEUTRAL | UNCLASSIFIED |
| 쉬자 | UNCLASSIFIED | ACCEPT |
| 그냥 쉬자 | NEUTRAL | ACCEPT |
| 쉴래 | UNCLASSIFIED | ACCEPT |
| 쉬고 싶어 | UNCLASSIFIED | ACCEPT |
| 그러자 | UNCLASSIFIED | ACCEPT |
| 그럴게 | UNCLASSIFIED | ACCEPT |
| 알겠어요 | UNCLASSIFIED | ACCEPT |
| 오케이 | UNCLASSIFIED | ACCEPT |
| 넵 | NEUTRAL | ACCEPT |
| 네네 | NEUTRAL | ACCEPT |
| 안 쉬어 | UNCLASSIFIED | DECLINE |
| 안 쉬어도 돼 | UNCLASSIFIED | DECLINE |
| 안 쉬자 | UNCLASSIFIED | DECLINE |
| 쉬고 싶지 않아 | UNCLASSIFIED | DECLINE |
| 별로 안 피곤해 | GOOD | UNCLASSIFIED |
| 피곤하지는 않아 | GOOD | UNCLASSIFIED |
| 졸리지는 않아 | GOOD | UNCLASSIFIED |
| 피곤 안 해 | GOOD | UNCLASSIFIED |
| 안 힘들어 | GOOD | UNCLASSIFIED |
| 괜찮지 않아요 | TIRED | UNCLASSIFIED |
| 좋지 않아 | TIRED | UNCLASSIFIED |
| 졸립니다 | TIRED | UNCLASSIFIED |
| 졸립네요 | TIRED | UNCLASSIFIED |
| 힘드네요 | TIRED | UNCLASSIFIED |
| 힘듭니다 | TIRED | UNCLASSIFIED |
| 지치네 | TIRED | UNCLASSIFIED |
| 잠이 와요 | TIRED | UNCLASSIFIED |
| 어지러워요 | TIRED | UNCLASSIFIED |
| 나빠요 | TIRED | UNCLASSIFIED |
| 최악이에요 | TIRED | UNCLASSIFIED |
| (null, 공백) | NO_SPEECH | NO_SPEECH |
| 응답이야 ("응"이 토큰이 아님) | UNCLASSIFIED | UNCLASSIFIED |
| 어제 ("어"가 토큰이 아님) | UNCLASSIFIED | UNCLASSIFIED |

`stopPrompt`는 "안 좋", "안 괜찮"을 먼저 지운다. "안 좋아"가 ACCEPT로 잡히면 실패다. `그래`는 첫 토큰일 때만 ACCEPT다("그래 가자"는 ACCEPT, "그냥 그래"는 UNCLASSIFIED). `갈게`, `가자`, `갈래`는 쉬러 간다는 말(`쉬어`, `들러`, `거기`)이 함께 있을 때만 ACCEPT이고 `그냥`, `계속`, `더`, `그대로`, `이대로`와 함께면 DECLINE이다. 혼자면 UNCLASSIFIED라 V21S로 다시 묻는다. `어`는 문장 전체일 때만 ACCEPT다.

체크인에서 묻지 말라는 말(`그만`, `조용히`, `됐어`, `싫어`, `나중에`, `다음에`, `이따`)은 NO_SPEECH다. 재질문 없이 끝나고 S2에 "응답 없음"으로 남는다. 재질문 뒤에도 UNCLASSIFIED면 C가 있을 때 내리지 않는다(B3b).

## C. 수면과 시간대

| ID | T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- | --- |
| C1 | -0:01:00 | P0 시동과 함께 수면 290, 유효 | NORMAL | 없음 |
| C1 | 1:14:59 | - | NORMAL | 없음 |
| C1 | 1:15:00 | - | CAUTION(F) | V02(1시간 15분), V03 |
| C1 | 1:30:00 | - | STOP(F) | V04(앞당김 문장, 1시간 30분) |
| C2 | 1:40:00 | 수면 290 수신 | NORMAL→STOP(F) | V04(앞당김 문장, 1시간 40분) |
| C3 | | 수면 360 | 임계 1:45, 2:00 | - |
| C4 | | 수면 359 | 임계 1:15, 1:30 | - |
| C5 | | 요약 없음 | 임계 1:45, 2:00 | - |
| C6 | 1:20:00 | 수면 290 유효가 이 시각 만료 | 앞당김 유지, CAUTION(F) | 없음 |
| C6 | 1:30:00 | - | STOP(F) | V04(앞당김 문장, 1시간 30분) |
| C7 | (C6에 이어) 1:35:00~1:50:00 | P 15분, 수면 없음 | NORMAL, cds=0 | V11, V12 |
| C7 | 다시 운전 1:45:00 | - | CAUTION(F) | V02, V03 (기본 임계로 복귀) |
| C8 | 00:30 출발, cds 1:00:00 | - | NORMAL | V03 |
| C8 | cds 1:45:00 | - | CAUTION(F) | V02, V03 (임계 불변) |
| C8 | cds 2:00:00 | - | STOP(F) | V04 |
| C9 | 23:30 출발, cds 1:00:00 (00:30) | - | NORMAL | V03 |
| C10 | 07:00 출발, cds 1:00:00 | - | NORMAL | 없음 |
| C11 | 05:30 출발, cds 1:00:00 (06:30) | - | NORMAL | 없음 |
| C12 | 01:00 출발, cds 1:00:00, 상태 STOP(C) | - | STOP(C) | V03 없음 |
| C13 | 01:00 출발, 수면 290, cds 1:00:00 | - | NORMAL | V03 |
| C13 | cds 1:15:00 | - | CAUTION(F) | V02, V03 |
| C13 | cds 1:30:00 | - | STOP(F) | V04(앞당김 문장) |

## D. 혈당 (13장)

`keepGlucoseAlive=true`: 명시한 줄 사이는 같은 값과 추세가 5분마다 새 측정 시각으로 들어온다. 값은 mmol/L, 전부 `simulated`다. D3의 간격은 시뮬레이터 고정 값이고 예측 성능과 무관하다.

**D1 출발 보류**(T는 시동 기준)

| T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- |
| 0:00:00 | 시동 ON, P, 3.8 flat | RECOVER(G), hold | 없음 |
| 0:00:05 | - | RECOVER, hold | V17 |
| 0:20:00 | 5.0 rising | RECOVER, 타이머 시작, hold | V15 |
| 0:59:59 | - | RECOVER | 없음 |
| 1:00:00 | - | NORMAL | V16, V01 |

**D2 보류 중 출발**

| T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- |
| 0:00:05 | (D1과 같음) | RECOVER, hold | V17 |
| 0:05:00 | D, 40 | STOP(G), hold 해제 | V09, 자동 안내 |
| 0:07:00 | - | STOP(G) | V10 |

(경유지를 넣지 못했으면 런타임이 V10 대신 V10U를 말한다. 장소가 없으면 V09N, V10N이고 시속 70km 이상이면 V09H, V10H다(P22). 표의 음성 칸은 엔진 효과라 V09, V10 그대로다.)

**D3 주행 중**(P0 전체 후. 시동 때 6.4 flat)

| T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- |
| 0:20:00 | 5.6 falling | CAUTION(G) | V08 |
| 0:20:10 | "아니" | CAUTION(G) | 없음 |
| 0:25:00 | 5.0 falling | CAUTION(G) | 없음 |
| 0:30:00 | 4.4 falling_fast | CAUTION(G) | V08 (악화 1회) |
| 0:30:10 | "아니" | CAUTION(G) | 없음 |
| 0:35:00 | 3.9 falling | STOP(G) | V09, 자동 안내 |
| 0:37:00 | 3.8 | STOP(G) | V10 |
| 0:39:00 | 3.8 | STOP(G) | V10 |
| 0:40:00 | P | RECOVER(G) | V14 |
| 0:50:00 | 4.6 rising | RECOVER(G) | 없음 |
| 0:55:00 | - | RECOVER(G), cds=0 | 없음 (F 사유 없어 V13 없음) |
| 1:00:00 | 5.1 rising | RECOVER, 타이머 시작 | V15 |
| 1:20:00 | 4.9 flat | RECOVER, 타이머 삭제 | 없음 |
| 1:25:00 | 5.2 rising | RECOVER, 타이머 재시작 | V15 |
| 2:04:59 | - | RECOVER(G) | 없음 |
| 2:05:00 | - | NORMAL | V16 |

**D4 경계값**(각 값 단독 평가, 주행 중, G 사유 없음에서 시작)

| 값 | 추세 | 기대 G |
| --- | --- | --- |
| 4.0 | rising | NONE |
| 4.0 | flat | CAUTION |
| 3.99 | rising | STOP |
| 5.0 | flat | NONE |
| 4.99 | flat | CAUTION |
| 5.7 | falling | NONE |
| 5.69 | falling | CAUTION |
| 7.3 | falling_fast | NONE |
| 7.29 | falling_fast | CAUTION |
| 4.5 | rising | NONE |
| 4.5 | null(추세 없음) | CAUTION(flat 취급) |

타이머 경계: G=STOP에서 4.99는 시작 안 함, 5.0은 시작. 39:59 유지, 40:00 해제.

**D5 복합**(1:45에 CAUTION(F), V03은 "괜찮아")

| T | 입력 | 상태 | cds | 음성 |
| --- | --- | --- | --- | --- |
| 1:59:00 | 3.9 falling | STOP(F,G) | 1:59:00 | V09, 자동 안내 |
| 2:00:00 | - | STOP(F,G), F=STOP | 2:00:00 | 없음 |
| 2:01:00 | - | STOP | | V10 |
| 2:03:00 | P | RECOVER(F,G) | 2:03:00 | V14 |
| 2:10:00 | 5.0 rising | RECOVER, 타이머 시작 | | V15 |
| 2:18:00 | - | RECOVER(G) | 0 | V13 (V12 금지) |
| 2:50:00 | - | NORMAL | 0 | V16 |

**D6~D10 끊김**(`keepGlucoseAlive`는 "끊김" 표시 줄 이후 멈춘다)

| ID | T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- | --- |
| D6 주의 중 | 0:10:00 | 5.4 falling, ttl 900 | CAUTION(G) | V08 |
| D6 | 0:10:10 | "아니" | CAUTION(G) | 없음 |
| D6 | 0:12:00 | 끊김 | CAUTION(G) | 없음 |
| D6 | 0:25:00 | 유효 만료 | CAUTION(L) | V19 (D-31: 주의 중 끊김도 래치) |
| D6 | 0:30:00 | 6.4 flat 복귀 | CAUTION(G) | 없음 (끊긴 뒤 첫 값 하나로는 풀지 않음, C-29) |
| D6 | 0:35:00 | 6.4 flat (새 판독) | NORMAL | 없음 |
| D7 회복 중 | 0:39:00 | 3.7 | STOP(G) | V09 |
| D7 | 0:39:30 | 끊김 | STOP(G) | 없음 |
| D7 | 0:40:00 | P | RECOVER(G) | V14 |
| D7 | 0:54:00 | 유효 만료 | CAUTION(L) | V19R (V16, V12 금지) |
| D7 | 0:55:00 | 휴식 15분 | CAUTION(L), cds=0 | 없음 |
| D7 | 1:05:00 | 3.9 복귀 | RECOVER(G) | V14 |
| D8 타이머 이어짐 | 0:40:00 | P, G=STOP, 4.6 | RECOVER(G) | V14 |
| D8 | 0:50:00 | 5.2 | RECOVER, 타이머 시작 | V15 |
| D8 | 0:50:30 | 끊김 | RECOVER | 없음 |
| D8 | 1:05:00 | 유효 만료 | CAUTION(L) | V19R |
| D8 | 1:12:00 | 5.3 복귀(공백 7분) | RECOVER, 타이머 유지 | 없음 |
| D8 | 1:30:00 | 타이머 만료 | NORMAL | V16 |
| D9 공백 초과 | 1:05:00 | (D8과 같음) | CAUTION(L) | V19R |
| D9 | 1:25:00 | 5.3 복귀(공백 20분) | RECOVER, 타이머 재시작 | V15 |
| D10 복귀 값 부족 | 1:05:00 | (D8과 같음) | CAUTION(L) | V19R |
| D10 | 1:12:00 | 4.8 복귀 | RECOVER(G), 타이머 없음 | V14 |
| D11 피로 충족 | (D5 2:10까지 후 끊김) 2:18:00 | 휴식 15분, 유효 만료 전 | RECOVER(G) | V13 |
| D12 래치 + 피로 | 2:03:00 | P, F=STOP, G=STOP 끊김, 2:05 유효 만료 | CAUTION(L)이 아닌 RECOVER(F,L) | V19R |
| D12 | 2:18:00 | 휴식 15분 | CAUTION(L) | V19R (V12 금지) |
| D13 소스 불허 | 임의 | 3.5, source=health_connect | 혈당 없음 처리. G=NONE | 없음. STOP 안 됨 |
| D14 재시동, 값 낮음 | RECOVER(G) 시동 OFF, 10분 후 ON, 3.9 | - | RECOVER, hold | 5초 뒤 V17 |
| D15 재시동, 값 없음 | 같은 조건, 값 없음 | - | CAUTION(L) | V19R |
| D16 재시동, 긴 공백 | 공백 50분, 값 없음 | - | CAUTION(L) | V19R (D-32: 공백 길이와 무관하게 래치, V19 아님) |
| D16 | 1:32:00 | S5 보류 풀기(E34). 마지막 판독(0:35) 뒤 57분 | NORMAL, 기록 `GLUCOSE_HOLD_RELEASED` | 없음 |
| D17 연결 해제 | CAUTION(L) 중 | S5 연결 해제 | NORMAL | 없음 |
| D18 값 받은 적 없음 | 혈당 null만 수신 | - | NORMAL | V19 없음 |

**D27~D30 끊김 뒤 회복 음성과 보류 풀기**(D-31, D-33)

| ID | T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- | --- |
| D27 주의 중 끊김 후 휴식 | 1:20:00 | 5.4 falling, ttl 900 | CAUTION(G) | V08 |
| D27 | 1:20:10 | "아니" | CAUTION(G) | 없음 |
| D27 | 1:20:30 | 끊김 | CAUTION(G) | 없음 |
| D27 | 1:35:00 | 유효 만료 | CAUTION(L) | V19 |
| D27 | 1:45:00 | - | CAUTION(F,L) | V02, V03 |
| D27 | 1:45:10 | "괜찮아" | CAUTION(F,L) | 없음 |
| D27 | 1:46:00 | P | CAUTION(F,L) | 없음 |
| D27 | 2:01:00 | 휴식 15분 | CAUTION(L), cds=0 | V19R (V12 금지) |
| D27 | 2:05:00 | 4.6 flat 복귀 | CAUTION(G) | 없음 (STOP 래치였다면 RECOVER와 V14) |
| D27 | 2:06:00 | D, 80 | CAUTION(G) | V08F (끊긴 사이 5.0 아래로 내려와 들은 V08은 지난 값이다. 출발할 때 다시 알림) |
| D28 값 받은 뒤 끊김(G=NONE) | 1:00:00 | 끊김 | NORMAL | 없음 |
| D28 | 1:14:00 | 유효 만료 | NORMAL | V19 |
| D28 | 1:45:00 | - | CAUTION(F) | V02, V03 |
| D28 | 1:45:10 | "괜찮아" | CAUTION(F) | 없음 |
| D28 | 1:46:00 | P | CAUTION(F) | 없음 |
| D28 | 2:01:00 | 휴식 15분 | NORMAL, cds=0 | V19R (V12 금지, 래치는 없음) |
| D28 | 2:02:00 | S5 보류 풀기(E34) | NORMAL, 기록 `GLUCOSE_HOLD_RELEASED` | 없음. 값을 받은 적 없는 상태로 돌아가 다음 휴식은 V12 |
| D29 보류 풀기 거부 | 0:30:00 | 끊김(6.4 뒤) | NORMAL | 없음 |
| D29 | 0:44:00 | 유효 만료 | NORMAL | V19 |
| D29 | 0:45:00 | S5 보류 풀기, D 80 | NORMAL, 기록 없음 | 없음 (주행 중 거부) |
| D29 | 0:46:00 | P | NORMAL, 풀 수 있음 | 없음 |
| D29 | 0:47:00 | 3.6 falling | RECOVER(G), 풀 수 없음 | V14 |
| D29 | 0:48:00 | S5 보류 풀기 | RECOVER(G), 기록 없음 | 없음 (유효한 낮은 값이 판단) |
| D30 STOP 보류 40분 하한 | 0:35:00 | 3.9 falling, D 80 | STOP(G) | V09 |
| D30 | 0:40:00 | P | RECOVER(G) | V14 |
| D30 | 0:40:30 | 끊김 | RECOVER(G) | 없음 |
| D30 | 0:55:00 | 유효 만료 | CAUTION(L), 풀 수 없음 | V19R |
| D30 | 0:56:00 | S5 보류 풀기 | CAUTION(L), 기록 없음 | 없음 (마지막 판독에서 21분) |
| D30 | 1:19:59 | S5 보류 풀기 | CAUTION(L), 기록 없음 | 없음 (39분 59초) |
| D30 | 1:20:00 | S5 보류 풀기 | NORMAL, 기록 `GLUCOSE_HOLD_RELEASED` | 없음 (40분) |
| D30 | 1:25:00 | 4.6 flat | CAUTION(G) | 없음 (값을 받은 적 없는 상태라 첫 판독) |

시나리오 기대의 `events`는 음성처럼 생략하면 "없음"이다. 엔진이 거른 E34는 기록 줄이 없어야 한다. `holdReleasable`은 엔진 뷰 `glucoseHoldReleasableAtMs`가 지금 이전인지 본다.

**D20~ 그 밖의 규칙**

| ID | T | 입력 | 상태 | 음성 |
| --- | --- | --- | --- | --- |
| D20 타이머 중 출발 | 0:40:00 | P, 5.2 타이머 시작(V14 뒤 V15) | RECOVER | V14, V15 |
| D20 | 0:50:00 | D, 40 | STOP(G), 타이머 유지 | V24(30분) |
| D20 | 1:00:00 | - | STOP(G) | V24(20분) |
| D20 | 1:10:00 | - | STOP(G) | V24(10분) |
| D20 | 1:20:00 | 타이머 40:00 | NORMAL | V16D |
| D21 미회복 출발 | 0:50:00 | D, 40, 값 4.6 | STOP(G) | V25 |
| D21 | 1:00:00 | - | STOP(G) | V25 |
| D22 정차 중 주의 | 시동 P, 5.6 falling | - | CAUTION(G) | 없음 (V01만) |
| D22 | 출발 확정 | D, 80 | CAUTION(G) | V08 |
| D23 주의 해제 | 0:20:00 | 5.6 falling | CAUTION(G) | V08 |
| D23 | 0:25:00 | 6.5 flat | CAUTION(G) | 없음 |
| D23 | 0:30:00 | 6.5 flat (새 판독) | NORMAL | 없음 |
| D24 같은 측정 재수신 | 0:30:00 | measured_at 동일 재수신 | 판독 수 불변 | 없음 |
| D25 V19 재무장(`keepGlucoseAlive`=false, 유지 구간은 5분마다 같은 값) | 0:00:00 | 6.0 flat | NORMAL | 없음 |
| D25 | 0:05:00 | 요약 null | NORMAL | V19 |
| D25 | 0:10:00 | 6.0 복귀 | NORMAL | 없음 |
| D25 | 0:15:00 | 요약 null (복귀 후 5분) | NORMAL | 없음 |
| D25 | 0:20:00 | 6.0 복귀 | NORMAL | 없음 |
| D25 | 0:31:00 | 요약 null (복귀 후 11분) | NORMAL | V19 |
| D26 첫 요약 지연 | 시동 P, 요약 없음 → 시동 8초 뒤 3.8 수신 | - | RECOVER, hold | V01 후 V17(선점) |

D26에서 V01이 재생 중이면 V17이 선점한다(설계서 6.3). 러너에서는 효과 순서만 본다.

단위 테스트로 고정한 것(`EngineRulesTest`): 타이머 40:00에 값이 주의 범위면 V16C, 해제 판독을 기다리는 동안 값이 오르면 V08 없음, E33은 앞당김을 풀고 기본 임계로 F를 다시 잼, 수면 만료 뒤 쌓인 운전 없이 출발하면 앞당김 해제, 재부팅 공백에 걸린 40분은 회복이 아니라 래치.

단위 테스트로 고정한 것(`GlucoseHoldTest`, `BoundaryTest.latchSurvivesAnyGap`): 주의 래치의 복귀 값은 4.0 미만이면 E17, 그 밖에는 주의를 이어 판독 2회로 해제, 주의 래치 저장본 왕복과 출처 필드 없는 옛 저장본 디코드, 값을 받은 뒤 끊긴 재시동은 V20 대신 V19R, 값을 받은 적 없으면 V12 그대로, STOP 래치는 공백 2394초·2395초·하루·사흘 뒤에도 유지, 시동을 켠 채 끊긴 뒤 41분·3시간이 지나도 정차와 주행 모두 STOP·주의 래치 유지(`stopLatchHoldsWhileParkedWithIgnitionOn`, `stopLatchHoldsWhileDrivingSameIgnition`, `cautionLatchHoldsAfterFortyMinutesAndThreeHours`), E34는 주행 중·유효 값(실제 G=STOP)·값 받은 적 없음에서 무시되고 받으면 음성 없이 기록 줄(`event=GLUCOSE_HOLD_RELEASED`)을 남기며 수면과 앞당김은 그대로, STOP 래치는 3.9 직후 거부·39분 59초 거부·40분 허용, 40분은 측정이 아니라 받은 시각부터이고 끊긴 뒤 같은 판독을 다시 받으면 뒤로 밀림(`holdFloorCountsFromReceiveTime`), 혈당 없는 요약 직후와 시동이 꺼진 동안의 오래된 G=STOP도 40분 전에는 거부, 주의 래치는 바로 허용, 주의 래치 복귀 값이 5.0 아래로 내려가거나 추세가 나빠지면 출발 때 V08·V08F를 다시 말함, 시동이 꺼진 동안 G=NONE 값이 만료된 뒤 재시동하면 V19R을 한 번. 런타임(`EngineRuntimeTest`)은 폰 쪽 해제 뒤 40분 전 거부(false)와 40분 뒤 풀림(true), 주행 중 거부(false), 받은 조작은 상태가 그대로여도 바로 저장되는지를 본다. 연결 해제(E33)는 보류 풀기 줄도 지운다(`TransitionLogFileTest`).

**문구 검증**: `VoiceCatalog.allStrings()`와 S1~S4 문자열에 설계서 6.4의 금칙어가 없다. `simulated`일 때 `EngineView.glucoseSimulated=true`.

## E. 오프라인

| ID | 조건 | 기대 |
| --- | --- | --- |
| E1 | `SummarySource`, Kakao, 네트워크 Fake 전부 실패 + A1 | A1과 같은 상태와 음성. 장소는 내장 POI |
| E2 | 3단계 + 수면 290 + 통신 끊김, 혈당 유효 15분 | 15분 뒤 혈당 없음, V19 1회. 임계 1:15, 1:30 유지 |
| E3 | TTS ON_DEVICE 실패, STT 실패 | 체크인 응답 없음, 프롬프트 카운트 없이 재안내. 상태 정상 진행. 알림 표시 |
| E4 | 서버 다운 상태에서 시동 | 3초 뒤 진행, 1단계 동작 |
| E5 | 주행 중 서버 프로세스 종료 | 2시간 정차 권고 도달, 혈당 끊김 1회, 서버 복구 후 자동 재개 |
| E6 | 비행기 모드, 시연 모드 SC2 전체(에뮬레이터 수동) | 끝까지 동작, 녹화 증거 |
| E7 | 1단계 사용자, 앱 전체 실행 | 네트워크 호출 0회(Fake로 단언) |

## G. 파생 공식과 불변 조건

| ID | 내용 |
| --- | --- |
| G1 | F, C, G 각 3값 × 래치 2값 × 정차·주행 2값 전수: 설계서 3.1과 일치(108칸, 래치 true는 G=NONE만이라 실효 칸은 이보다 적다) |
| G2 | "정차 권고 > 주의": 조합에서 STOP이 있으면 CAUTION 아님 |
| G3 | "회복은 모두": 사유 중 하나라도 남으면 NORMAL 아님 |
| G4 | 임계 경계: 설계서 3.3과 5.2 표의 값 각각 -1초, 정확히, +1초 |
| G5 | 배속 불변: A1, A5, A6, B2, C1, D1, D3, D5, D7, SC1~SC3를 실시간, ×60, ×240, 무작위 시드 5개로 돌려 전이 로그 동일 |
| G6 | 시각 역행: 어떤 이벤트든 `atMs`가 과거여도 cds, 휴식 증가 없음, 예외 없음 |
| G7 | 상태 코덱 왕복: 모든 시나리오 각 줄에서 encode/decode 후 진행 결과 동일(프로세스 재시작 동등성) |
| G8 | 무작위 이벤트 속성 테스트: G=STOP 또는 래치 동안 V12, V16, V20 없음. 값을 받은 뒤 끊긴 동안 V12, V20 없음(D-31). 무작위 이벤트에 E34를 넣고 주행 중에는 풀리지 않음을 함께 본다 |
| G9 | 무작위 이벤트 속성 테스트: 값 < 4.0 주행 중 V09, V10 외 프롬프트로 마이크가 열리지 않음 |
| G10 | 프롬프트 ID: 오래된 `promptId`의 응답 무시 |
| G11 | `Engine`, `Policy`, `EngineView`의 어떤 경로에도 안드로이드, 시스템 시계, `demo` 참조 없음(빌드 검사) |

## P. 장소 선택 (`StopPicker`, 런타임 Fake)

기준 좌표는 경부고속도로 임의 지점, 진행 방향 180°(남), 80km/h.

| ID | 입력 | 기대 |
| --- | --- | --- |
| P1 | 전방 3.2km 쉼터 | 선택 |
| P2 | 후방 1km, 전방 3.2km | 전방 |
| P3 | 전방이지만 방위 차 70° | 제외 |
| P4 | 방위 차 60° | 포함 |
| P5 | 80km/h, 전방 1km | 제외(최소 1.33km) |
| P6 | 차속 null, 전방 400m | 제외(하한 500m) |
| P7 | 5km 없음, 8km 있음 | 반경 10에서 선택 |
| P8 | 진행 방향 null | 방향 필터 생략. 혈당 자동 안내면 N 변형으로 말하고 경로 측정이 확인해 줄 때만 경유지를 넣는다 |
| P8b | 같은 이름의 서울방향·부산방향 쌍, 남행 | 부산방향 |
| P8c | 표기 도시가 진행 방향의 반대(135° 이상)이고 쌍이 없음 | 제외 |
| P9 | 측정 경로 거리 직선의 2.1배 | 제외, 다음 후보 |
| P10 | 측정 실패 | 직선 거리로 진행 |
| P11 | 전부 없음 | N 변형, 30초 뒤 재검색 |
| P12 | 위치 null | N 변형, 위치 복귀 즉시 재시도 |
| P13 | 경유지 2개 참 | V28, 기존 경유지 유지 |
| P14 | 활성 경로 없음 | requestRoute |
| P15 | 같은 장소 경유지 | ALREADY_WAYPOINT, 재추가 없음 |
| P16 | 30초 무응답(실측 9~25초라 F3-9 변경) | FAILED, V28 |
| P17 | 장소명 "칠곡졸음쉼터(부산방향)" | 음성은 "칠곡졸음쉼터" |
| P18 | 혈당 자동 안내 + 경유지 2개 참 | 기존 경유지 유지, V09는 그대로. 다음 반복은 V10U |
| P19 | 혈당 자동 안내 + 경로 측정 직선의 2배 초과 | 경유지 안 넣음, 다음 후보 |
| P20 | 혈당 자동 안내 + 측정 실패, 방향 표기로 판별 불가 | 경유지 안 넣음, V10U로 말하고 다음 반복에 재시도 |
| P21 | V10 시각에 장소를 지났거나 멀어짐 | 장소를 지우고 다시 찾는다 |
| P22 | 혈당 자동 안내 + 장소 없음, 80km/h(V-07) | V09N 대신 V09H "…다음 쉼터에 들르거나 나들목으로 나간 뒤 안전한 곳에 차를 세우세요.", 다음 반복은 V10H. 80에서 60으로 정체돼도 V10H 유지. 처음부터 70.0km/h면 H, 69.9km/h와 차속 null이면 V09N, V10N. 어떤 혈당 정차 음성에도 "갓길", "차로", "정차하세요"가 없다 |
| P23 | 화물차휴게소(원본 종류에 "화물") 2km, 일반 휴게소 3km, 종류 모름 4km(P-15) | 일반, 모름 순. 화물차휴게소뿐이면 장소 없음(N·H 변형). 진행 방향을 몰라도 뺀다. 같은 이름 짝의 방향 판별에는 쓴다: 진행 쪽이 화물차휴게소면 맞은편 일반 휴게소도 고르지 않는다. 혈당 자동 경유지와 피로 제안 둘 다 같다. `tools/poi_build.py`는 종류 열을 그대로 옮기고 열이 없으면 멈춘다(`tools/poi_build_test.py`). 내장 `poi.json`은 2026-11-07부터 원본 종류를 담아 화물차휴게소 21곳이 표시돼 있고, 앱은 21곳 어디도 고르지 않는다(`PlaceCatalogTest`). 지금 `apk/`의 차량 APK(855d)에 이 값이 들어 있고, 에뮬레이터에서 매송 화물차휴게소 두 곳 근처의 혈당 정차 권고 때 매송 대신 일반휴게소인 화성휴게소를 먼저 골랐다(2026-11-07). 시연 고정 장소 칠곡휴게소(부산방향)도 21곳 중 하나다 |

## H. 운전 방해 (8장, 13장)

| ID | 점검 | 합격 | 방법 |
| --- | --- | --- | --- |
| H1 | S1 시맨틱 트리, 폭 628, 840, 1274, 1700dp, 모든 상태 | `hasClickAction` 0, `isFocusable` 입력 0, 스크롤 0 | Compose 자동 |
| H1c | S1 전환 | 첫 프레임과 5초 뒤 프레임이 픽셀까지 같다. 같은 검사가 일부러 넣은 애니메이션을 잡는지도 확인한다 | Compose 자동 |
| H1b | S1 소스 | `clickable`, `pointerInput`, `Animated*`, `Crossfade`, `updateTransition`, `infiniteTransition`, ripple 없음 | 정적 검사 |
| H2 | D, 0km/h (신호) | S2~S5 진입 불가, `DrivingGatePort=DRIVING` | 자동, 에뮬레이터 |
| H3 | 정차 후 출발 | P 이탈 즉시 S1, S2·S3·S4 닫힘, `SetupActivity` finish | 자동 |
| H4 | 모든 음성 | 응답 없이도 A1, D3 진행 | 자동(A23) |
| H5 | 8장 표 전 화면 | 주행 중 터치 요구 0 | 표 대조, 체크리스트 |
| H6 | 고정 문장 50개 × 3회, 조용한 조건 | 사전 분류 100%, STT 포함 90% 이상 목표. 결과를 그대로 기록 | 수동. 2차에 음악, 소음, 창문, 동승자 대화 추가 |
| H7 | 토큰 대비 | `design-tokens.md` 표의 조합이 4.5 이상(hairline, select 제외) | JVM 단위 테스트 |
| H8 | 미지정 상태값 | 서비스 미연결이면 DRIVING | 자동 |
| H9 | Parked 조건 | 2.9초 P 유지는 Driving, 3초 후 Parked. 출발 즉시 Driving | 자동 |
| H10 | 혈당 단어 | S1에 "혈당", 수치 없음. 동승자가 있거나 모르면 S2, S3에도 "혈당" 없음 | 자동 |
| H11 | 시뮬레이션 표시 | `glucose_source=simulated`이면 S2, S3에 "시뮬레이션 혈당"(동승 시 "시뮬레이션 데이터"), 시연이면 표지 | 자동 |
| H12 | D 기어(DO=true)에서 앱 동작. `driving_regulation`은 이 이미지에 없다 | S1 유지, `SetupActivity` 닫힘 | 에뮬레이터 수동 |
| H13 | 문자열 | 원시 enum, null, `10.0.2.2`, 상표 없음 | 자동 |
| H14 | S4 최근 7일 (폭 840, 1700) | 맨 위 "최근 7일" 아래 "운행 4회, 운전 8시간 37분"(라벨 "운행", 값 "4회, 운전 8시간 37분", U-24), "휴식 안내 4회", "15분 이상 휴식 3회"가 오늘 묶음보다 먼저 나온다. 오늘 합계는 "15분 이상 휴식 N회"이고 목록의 "휴식 N분" 행 수와 같다(U-23). "혈당", "거절", "무시", 점수·위반 표현, 금칙어 없음. 기록이 없으면 "최근 7일 동안 운행 기록이 없어요". 25-s4-week-before-u24.png 모양(오늘 운행 1개에 운전 행 2개 "운전 4분", "운전 2분", 어제 운행 1개)은 7일 "운행 2회, 운전 8분"이고 화면 어디에도 "운전 N회"나 "운전"만 쓴 라벨이 없다(U-24) | Compose 자동(`ParkedScreensTest`의 `s4RecentWeekOnTopSinglePane`, `s4RecentWeekOnTopTwoPanes`, `s4EmptyAndOdometerHidden`, `s4WeekCountSaysTripsSoDriveRowsDoNotContradict`) |
| H15 | S4 7일 계산 | 창은 오늘 포함 지역 날짜 7일(6일 전 0시 정각 포함, 1ms 전 제외). 운행은 시작한 날로 센다(창 전날 밤 시작해 자정을 넘긴 운행은 통째로 뺀다). 진행 중 구간은 지금까지, 미래 운행은 뺀다. 빈 기록이면 0과 빈 상태. 창 7일 ≤ 보존 30일(C-54), 8일 전 사건은 DB에 남아도 합계에서 빠진다. 휴식 수는 15분을 채운 정차 구간 수다(엔진 사건이 한 정차에 2번 나와도 1회). 운전 1분 미만 운행은 운행 수에 들지 않고, 15분 이상 정차도 안내도 없으면 7일과 오늘 쪽 모두 빈 상태다. 운전 30초 뒤 20분 정차면 운행 0이어도 오늘 목록에 "휴식 20분" 행이 서므로 7일도 빈 상태가 아니다(`subMinuteDriveWithFullRestIsNotEmpty`). 운행 수는 목록 행 수가 아니다: 휴식으로 갈린 운전 행 2개도 한 운행이다(U-24, `tripCountIsTripsNotDriveRows`) | JVM 자동(`RecentSummaryTest`, `TripLogDbTest`) |
| H16 | S4 목록 행(U-23) | 15분 이상 정차는 "휴식 N분", 15분 미만은 "멈춤 N분"(닫힌 정차 899초 멈춤, 900·901초 휴식). 1분 미만 구간은 행이 없다(59초 없음, 60초 있음, 합계 시간에는 든다). 중간 멈춤 59초는 횟수에 들지 않고 60초는 든다. 합친 운전이 1분 미만이면 안의 멈춤은 "멈춤 N분" 행으로 남는다. 1분 이상 운전한 운행은 늘 행이 있다. 같은 운행 안에서 15분 미만 멈춤으로 끊긴 운전은 한 행으로 합치고 길이는 운전 시간의 합(엔진 연속 운전의 근사값, 출발 유예만큼 짧을 수 있다), 멈춤은 보조 글 "중간 멈춤 N분"(여럿이면 "중간 멈춤 K회, N분"). 진행 중 멈춤은 15분이 되는 순간 "휴식"이 되고 합계가 1 오른다. 25-s4-week-before-fix.png(a7f1의 두 판 전 차량 APK sha256 앞자리 dc82635fdfe0e030의 실기 캡처) 기록(19:05 운전 1분, 19:06 12분, 19:18 30초, 19:19 진행 중 40초)은 "19:05 운전 1분 · 중간 멈춤 12분" 한 행, 합계 "15분 이상 휴식 0회", 7일 "운행 1회, 운전 1분" | JVM 자동(`TripTimelineTest`), Compose 자동(`ParkedScreensTest`의 `s4ScreenshotDataReadsWithoutContradiction`, `s4ShortStopRowSaysPauseAndFullRestSaysRest`, `s4RestWordMeansTheSameInAllThreePlaces`) |
| H17 | S4 운행 경계: 무기록 4시간(U-25) | 전날 13:07 출발 뒤 기록이 없고 다음 날 16:32 정차(재부팅 복원) → 전날 13:07 출발 구간은 마지막 기록 시각(13:07)에 닫혀 길이 0이 되고(행 없음, 운행 수 0), 오늘 목록·7일 합계에 못 본 시간이 운전으로 들어가지 않는다(관측한 3분 + 7분만). 무기록 4시간 이상 뒤 첫 기록이 정차 권고면(컨디션 정차 권고는 제외) 그때부터 운전으로 센다(복원 뒤 60분). 시동을 끈 5시간 주차는 P에 시동을 켠 주차와 같이 "휴식 15분" 1회로 남는다. 구간 없이 닫힌 운행은 다음 날 오늘에 들지 않는다. 기록이 4시간 미만 간격으로 이어지면 자정을 넘긴 운전도 한 운행이다 | `TripSilenceTest` |
| H18 | Pleos CCP Back 숨김(U-27, DESIGN 8.1) | 첫 화면(오늘 탭, 주행 S1, 대기 문장, 온보딩 1단계)의 Back은 `moveTaskToBack(true)`이고 Activity는 끝나지 않는다. 기록 탭 → 오늘 탭, 설정 → 오늘 탭(`MainActivity` 시작 후 `SetupActivity` finish), 창 하나의 S5 페이지 → 목록, 온보딩 3 → 2 → 1단계(3단계 말해 보기 중이면 말하기·마이크를 멈춘다). 스스로 닫히는 중(FINISH)의 Back은 버린다. 백그라운드에 다녀와도 열린 페이지가 숨김보다 먼저 닫힌다. 실제 두 Activity에 `KEYCODE_BACK`을 넣어도 같다 | Compose·Robolectric 자동(`BackNavigationTest`, `BackKeyActivityTest`). 취소가 엔진 정지로 이어지는 것은 `ListenerTest.cancellingListenStopsEngineMic`, `ChainSpeakerTest.cancellingSpeakStopsEngine`·`cancellingDuringLeadSilenceSendsNoStop`. `stopNow` 뒤 취소해도 stop이 한 번만 가는 것은 `ListenerTest.stopNowThenCancelSendsOneStop`·`ChainSpeakerTest.stopNowThenCancelSendsOneStop`, 재시도 묵음 대기 중 `stopNow`가 stop을 보내지 않는 것은 `ChainSpeakerTest.stopDuringRetryLeadSilenceSendsNoStop`. Pleos 에뮬레이터 수동(2026-11-06, 차량 APK 앞자리 `1c5130da91724dcc`, `adb shell input keyevent KEYCODE_BACK`): 오늘 탭과 주행 S1에서 `moveTaskToBackInner`로 태스크가 맨 아래로 가고 포커스가 Driving view로 넘어감, 같은 `ActivityRecord`가 `finishing=false`로 남고 `DriveMonitorService`는 포그라운드 그대로, 앱 목록 실행으로 같은 Activity가 오늘 탭으로 돌아옴. 기록 탭 → 오늘, 설정 → 오늘(`SetupActivity`만 끝남, `MainActivity` 같은 기록), 모두 숨기지 않음. 다만 이 에뮬레이터는 숨긴 뒤 같은 영역의 내비를 다시 시작하지 않아 앱 화면이 그대로 보였다. 앱을 지우고 새로 설치한 같은 APK에서 온보딩 1단계 Back은 숨김(같은 기록 `finishing=false`, 앱 목록 실행으로 같은 1단계 복귀), 2단계 → 1단계, 3단계 → 2단계, 시연 진행 중 Back은 숨김이고 숨긴 동안 시연이 이어졌다. 숨긴 채 주행·정차를 넣어도 앱이 앞으로 오지 않았고, `am force-stop` 뒤 앱 목록으로 열면 온보딩 없이 S1 상태가 이어졌다. 1c51에서는 3단계 말해 보기에서 듣는 중 Back을 누르면 2단계로 가고 오디오 포커스는 돌려주지만 Pleos STT 녹음이 다음 인식 요청까지 97초 이어졌다(듣기 코루틴만 취소되고 엔진 `stop()`이 불리지 않았다). **고친 뒤(2026-11-06, 차량 APK 앞자리 `88800b7057269735`)**: 듣는 중 Back에 07:31:35.517 녹음 시작, 07:31:36.497 `SpeechToText stop`, 07:31:36.515 녹음 종료가 찍혔다. Back 시각은 로그에 남지 않았다(스크립트는 녹음 시작을 본 뒤 약 1초 뒤에 Back을 보냈다). 확인한 것은 녹음 시작 0.98초 뒤 stop이 불려 녹음이 끝났다는 것이다. 화면은 2단계로 갔다(`moveTaskToBack` 0회). 상태 표시줄 마이크 표시는 시스템 표시기대로 약 4초 뒤 꺼졌다(Back 6초·20초 뒤 장에 없음). 말하는 중 Back은 TTS `stop`과 `onStop`이 오고 `onDone`, 이어지는 인식 요청, 마이크가 0이었다. 온보딩 1단계·오늘 탭 숨김과 2 → 1, 3단계(대기) → 2, 기록 → 오늘도 1c51과 같았다. **stop 한 번(2026-11-06, 차량 APK 앞자리 `8eb5e48fd4e64335`)**: Back 직전에 로그 표시를 남겼다. 듣는 중 Back은 표시 08:35:13.573 뒤 13.633에 `SpeechToText stop`이 한 번 불렸고(0.06초) 13.651에 녹음이 끝났다. 1초 뒤(08:35:14) 녹음 목록에서 Pleos 음성 서비스(`ai.pleos.playground.caas`)의 VOICE_RECOGNITION 세션이 사라졌다(Back 직전 목록에는 있었다). 마이크 표시는 stop 3.85초 뒤 꺼졌다. 말하는 중 Back은 표시 0.07초 뒤 TTS `stop`이 한 번 가고 `onStop`이 왔으며 `onDone`, 인식 요청, 녹음이 0이었다. 체크인 음성 중 기어 R로 큐가 끊을 때(`stopNow` 뒤 취소) SDK stop은 말하는 중 TTS 한 번, 듣는 중 STT 한 번이었고, P로 돌아오면 다시 말했다. 8880으로 같은 끊기를 재지는 않았다. 온보딩 1단계·오늘 탭 숨김과 2 → 1, 3단계(대기) → 2, 기록 → 오늘도 같았다. 창 하나의 S5 페이지는 주 디스플레이가 창 2개라 보지 않았다. 실차 CCP 하드키는 미확인 |
| H19 | CCP 미지원 선언(U-27) | 차량 매니페스트 `<application>` 바로 아래에 `com.hmg.rotary.ROTARY_SUPPORTED=false` 하나. 필수 조작은 터치(한 번 누르기)만으로 끝난다(주행 화면 조작 0, H1) | 정적 검사(`UiRulesTest.declaresRotaryUnsupportedInApplication`) |
| H20 | TTS 선행 묵음(U-27, DESIGN 10.2) | 발화마다 150ms(150~200 밖 값은 생성 거부). Pleos 엔진은 ③ 포커스 뒤 150ms 기다렸다 `speak`(엔진에 묵음 0), Android TTS는 ②에 가까운 엔진 재생 큐 안 기다림 `playSilentUtterance(150, FLUSH)` → `speak(ADD)`이고 묵음 발화의 `onDone`은 완료가 아니다. 묵음을 못 넣으면 150ms 기다린 뒤 `speak(FLUSH)`, 묵음 0이면 바로 FLUSH. 한 발화는 나누지 않아 묵음 하나, 재시도·대체 엔진은 새 재생이라 다시 넣는다(② 엔진의 재시도도). 묵음 중 멈추면 말하지 않고 아직 말하지 않은 엔진에 stop도 보내지 않는다 | JVM 자동(`ChainSpeakerTest`, `LeadSilenceUtteranceTest`). 실제 클리핑 완화는 들어 보지 않았다(H6과 같은 오디오 AVD 수동) |
| H21 | S2 앞당김 유지 사유 줄(U-29) | 앞당김이 남았고 수면 값이 없으면 왼쪽에 사유 줄 하나. 쌓인 운전이 있으면 "수면 값은 없지만 15분 쉴 때까지 안내 시점을 30분 앞당겨 둬요", 0이면 "수면 값은 없지만 앞서 받은 값으로 안내 시점을 30분 앞당겨 뒀어요"("쉴 때까지" 없음). 창 1개(840)와 창 2개(1700, 오른쪽 창 왼쪽 경계 안) 모두. 수면 행이 있거나 앞당김이 없으면 없다. 분은 Policy 값. S1은 바뀌지 않는다 | JVM 자동(`ParkedScreensTest.s2AdvanceKept*`). 실기 화면(차량 07dd): 운전이 쌓인 문장 `docs/screenshots/27-s2-advance-kept.png`, 운전 0 문장 `companion-33-vehicle-after-summary-delete.png` |
| H22 | S5 보류 풀기(U-30, E34) | 엔진 뷰 `glucoseUnavailable`일 때만 연결된 기기 페이지 맨 위에 "컨디션 값이 끊겨 있어요. 풀면 새 값이 올 때까지 시간 기준으로만 안내해요. 직접 확인했을 때만 푸세요"와 "끊김 보류 풀기"(보조 버튼 80dp). "멈췄어요" 같은 상태 말 없음. 연결이 있어도 없어도 보인다. 첫 누름은 "한 번 더 누르면 실행돼요", 두 번째에 조작 한 번. 문구에 "혈당" 없음. 엔진이 받았을 때만 "끊김 보류를 풀었어요…", 거절이나 런타임 없음은 "풀지 못했어요. 잠시 뒤 다시 해 주세요"와 버튼이 남는다. 결과는 이 페이지에만 있고 데이터 페이지에 없으며 새 유효 값이 오면 사라진다. 창 하나 목록의 "연결된 기기" 값은 "연결 끊김". 런타임은 화면이 보는 쪽(실사용 또는 시연)에 E34를 넣고 전이 로그에 `event=GLUCOSE_HOLD_RELEASED`가 남는다. 정차 전용은 S5 게이트와 엔진 양쪽 | JVM 자동(`ParkedScreensTest.s5Hold*`·`s5ListShowsHoldOnDevicesRow`, `GateHostTest.setupHoldReleaseFollowsEngineView`, 엔진 몫은 `GlucoseHoldTest`·`EngineRuntimeTest`). 실기 화면(차량 07dd): 버튼 `docs/screenshots/28-s5-hold-release.png`, 낮은 값 대기 줄 `29-s5-hold-wait.png` |
| H23 | S5 동의 철회 안내(U-31) | 데이터와 동의에 "동의 철회는 폰 동반 앱의 데이터 탭에서 해요" 한 줄. 차량에 "철회" 버튼 없음. 서버 `DELETE /v1/consents/*`의 차량 토큰 권한은 API로만 남는다(`ConsentApiTest.vehicleCanWithdrawHealthConsent`) | JVM 자동(`ParkedScreensTest.s5DataSaysWithdrawOnPhoneOnly`) |
| H24 | 안내 시점 라벨과 설계값(U-28) | S2·S3 규칙 창 제목 "안내 시점"("오늘 기준" 없음). S2 출처 줄 "2시간·15분 참고: 화물자동차 운수사업법 시행규칙"과 "기본 주의 시점 1시간 45분: 설계값"(창 1개는 왼쪽 아래, 창 2개는 오른쪽 아래). 앞당겨 규칙 행이 1시간 15분이어도 설계값 줄은 기본값. S5 기준과 출처에 시행규칙 제21조·제22조 참고와 기본 주의 시점 설계값 줄. S1 글은 그대로 | JVM 자동(`ParkedScreensTest.s2GuidancePointLabel*`, `cautionSourceStaysDefaultWhenAdvanced`, `s3RulesPaneUsesGuidancePointTitle`, `UiRulesTest.sourcesCiteGuideline`) |
| H25 | S5 보류 40분 하한과 진행 표시(D-33, U-30) | 낮은 값 뒤 40분 전(`glucoseHoldReleasableAtMs`가 지금 뒤)이면 버튼 대신 "마지막 값이 낮아 25분 뒤에 풀 수 있어요"처럼 남은 시간 한 줄. 시각이 지나면 버튼. 두 번째 누름 뒤 결과가 올 때까지 버튼 자리에 "푸는 중이에요"(다시 누를 수 없음) | JVM 자동(`ParkedScreensTest.s5HoldWaitsAfterLowValue`, `s5HoldButtonOnceWaitIsOver`, `s5HoldRefusedSaysFailed`, `s5HoldWithoutRuntimeSaysFailed`). 대기 줄은 실기 화면 `docs/screenshots/29-s5-hold-wait.png`(차량 07dd, 2026-11-07, "38분 뒤"). 시각이 지난 뒤 버튼으로 바뀌는 것과 "푸는 중이에요"는 실기 미확인 |

## V. 어댑터·런타임 (Fake)

| ID | 내용 |
| --- | --- |
| V1 | 음성 큐: 같은 시각 V02, V03 순서 |
| V2 | 선점: V04 재생 중 V09 → V04 중단, V09 |
| V3 | 합침: F 정차 + G 값 < 4.0 → V09만 |
| V4 | 폐기: V02가 2분 넘게 밀리면 폐기 |
| V5 | R 기어: 음성 대기, 벗어나면 재개 |
| V6 | 혈당 STOP: 마이크 열림 0회. 대기 중인 V03, V04, V27이 V09 뒤에 재생되지 않음 |
| V7 | TTS 완료 + 300ms 전에 STT 시작 안 함. "듣고 있어요"는 마이크 준비 뒤에 켜짐 |
| V8 | STT `completed=false`는 사용 안 함 |
| V9 | 포커스 `LOSS_TRANSIENT`와 거부: 정지, 재큐잉. ①은 첫 실패에 헤드업 1회. V17은 전용 헤드업 |
| V10 | 시동 OFF: 정지, 포기, 저장. 재개 시 자동 재생 없음 |
| V11 | 장소 없을 때 N 변형은 운행당 처음 1회, 찾으면 원문 ask |
| V12 | F10 표(설계서 17장), F11 표 |
| V13 | 재시작 시 BOOT_COUNT 다르면 혈당 무효 |
| V14 | `valid_until - server_time`으로 만료 계산, 기기 시계 무관 |
| V15 | 401이면 토큰·요약 삭제, 1단계 |
| V16 | 폴링: 시동 OFF 0회, 미연결 0회, 실패 15, 30, 60, 60 |
| V17 | 듣지 못하면 질문을 평서문으로 바꾸고(V04O, V06O, V08O, V08FO), 체크인과 V27은 말하지 않는다. 체크인은 "보통"이 아니라 응답 없음으로 남는다. O3 "말해 보기"도 묻지 않는다 |
| V18 | V19, V19R은 폐기되지 않고 같은 시각의 뒤 음성에 지워지지 않는다 |
| V19 | 폰 쪽 해제와 401은 값 없음 경로로 처리한다(래치, V19). 폰 쪽 해제로는 보류가 풀리지 않는다(D-32). 차에서 S5 연결 해제(E33)와 S5 보류 풀기(E34)로만 푼다 |
| V20 | 음성 엔진: 실패한 엔진은 60초 뒤, 시동 ON, 권한 변경 때 다시 시도한다. RECORD_AUDIO 없으면 차량 STT도 듣지 않는다 |
| V21 | 장소 없는 혈당 정차 음성의 고속 변형은 큐에 넣을 때가 아니라 말하는 순간의 고속 판정(`HighwayGuess`)으로 고른다. 통화로 밀린 V09N이 80km/h에서 나오면 V09H다. 판정은 히스테리시스가 있다: 80에서 60·69.9·35로 떨어져도 V10H, 30km/h 미만 4분 55초는 H이고 5분이 지나면 N, P에 들어가면 풀리고, 차속 신호가 끊기면 마지막 판단, 처음부터 모르면 N. 말하지 못한 V09H, V10H는 고른 변형 그대로 헤드업으로 넘기고 헤드업 본문은 "쉼터나 나들목으로 나간 뒤 안전한 곳에 세워 주세요" |

V1~V7, V9~V16, V21과 F10, F11 표, P11~P16, P18, P22, P23(런타임 쪽), R1, R2(JVM 대조), R4~R7, R8(포트 없는 JVM 완주), E7(호출 0회)은 `./gradlew :vehicle:testDebugUnitTest`의 `runtime`, `summary`, `poi`, `demo` 패키지에 있다. V8은 `ListenResult`에 최종 결과만 담는 포트 계약이라 `vehicle.platform` 테스트 몫이다. R2 리포트 파일, R8 실제 APK, R9는 에뮬레이터 몫이다.

## S. 계약과 서버

| ID | 내용 |
| --- | --- |
| S1 | 골든 픽스처 왕복. Strict와 Lenient 동작 |
| S2 | POST /v1/summary 12.7 표 14행 각각 정상과 실패 |
| S3 | 거부 키, 배열 값 → `raw_health_data_rejected`, 응답에 값 없음 |
| S4 | `partner_realtime` 기본 거절, `health_connect` 항상 거절 |
| S5 | `sleep_short`와 분 불일치 → `combination` |
| S6 | `generated_at` ±300초 경계, `valid_until` 경계 ±1초 |
| S7 | 오래된 `generated_at` → `applied:false`. null 덮어쓰기 동작 |
| S8 | GET latest 마스킹 4경우, 항상 200 |
| S9 | 페어링: 코드 수명, 1회용, 재발급 시 이전 무효, 전역 실패 제한 |
| S10 | 재페어링: 이전 폰 토큰 401, 이전 요약 삭제 |
| S11 | 역할 분리: 폰이 GET latest 403, 차량이 POST summary 403 |
| S12 | 동의 없음: claim, POST summary 거절. 철회 시 요약 삭제 |
| S13 | 만료 스윕: `Clock` 주입으로 삭제 확인 |
| S14 | 로그 카나리: `glucose_mmol: 3.1415`, `sleep_minutes_last_night: 271`, `+821099990000`, 토큰, 코드를 정상·실패·예외 경로로 넣고 로그 Appender에 해당 문자열 없음 |
| S15 | trips: rev 재전송, `reason`, `glucose` 필드 거절, 삭제 후 재업로드 무효 |
| S16 | 계정 삭제: 모든 행 삭제 |
| S17 | 보호자: 양쪽 동의 없으면 발송 0회. 호출 제한 경계, 재시작 후 유지. simulated면 발송기 호출 0회(Fake) |
| S18 | 문자 내용: 템플릿 외 자유 문자열 경로 없음. 금칙어 없음 |
| S19 | 시연용 APK: 서버 프로세스 없이 전체 시나리오 |
| S20 | Lenient: 응답에 새 필드가 있어도 파싱. 알 수 없는 enum이면 혈당 전체 무효 |
| S21 | 철회·해제가 진행 중인 업로드와 겹침: 저장 트랜잭션에서 다시 확인해 `applied:false`, 요약 0행. 폰은 진행 중인 전송이 끝난 뒤 삭제를 호출 |

## R. 시연 정직성

| ID | 내용 |
| --- | --- |
| R1 | SC1, SC2, SC3 시나리오가 JVM에서 통과 |
| R2 | 시연 후 전이 로그와 기대값이 시뮬레이션 시각 ±1초 안에서 일치. 리포트 저장(`docs/reports/`) |
| R3 | `:core`에 `demo` 패키지·심볼 없음 |
| R4 | 시연 표지가 모든 화면에 표시, 터치 없음. 배속 변경 시 갱신. 내장 시연이면 "차량 신호는 시나리오 입력", 수면 값이 있으면 "수면 시연 데이터", 콘솔 대기면 "콘솔에서 D로 바꿔 주세요" |
| R5 | 시연 모드는 기어 P에서만 시작 |
| R6 | 음성 중 시계 정지 |
| R7 | 시연 상태 저장 파일과 기록 DB를 실사용과 분리. 시작할 때마다 비움. 기록 탭은 시뮬레이션 시각으로 읽음 |
| R8 | 서버, 폰, 키, 네트워크 없이 release APK로 3개 시나리오 완주 |
| R9 | 다른 PC에서 README만 보고 재현하는 리허설 1회 기록 |
| R10 | 시연 자막: 앱 음성 문장, 시나리오 응답, 시동 끔/켬, 실제 SDK 호출 사실을 표지 아래 한 줄로 보인다. 실사용 S1에는 없다 |
| R11 | 시연이 끝나도 실제 내비로 자동 전송하지 않는다. 설정 > 시연 모드의 버튼을 눌러야 보낸다 |
| R12 | 시연 중 S5 시연 페이지에 "시연 끝내기", "처음부터 다시"가 있다 |
| R13 | 제출 문서 정합(S-11, S-12, `SubmissionDocsTest`): 결정 기록의 사람 몫 표는 12, 14~17행만 있고(1행 서명은 2026-11-13에 끝나, 19행 문의는 2026-11-14 사용자 결정으로 지웠다), 지운 1행 기록과 README APK 표가 같은 해시를 가리키며, 2026-11-06에 옮긴 항목마다 위 절에 [뒤집기]가 있는 결정 행이 있다. 기획서(`spec.md`)는 V09·V09H·V10H·V09N·V12를 엔진 문구 그대로 인용하고, 1장 표 19건의 옛 문구와 갓길 지시가 없으며, 금칙어 "출발해도 좋아요"는 금지 규칙에만 나온다. 11장에 규제 상담 항목(식약처 "혈당이 기준보다 낮아요")이 있다. `business.md`는 2천만 대를 "2030년 목표이자 전 세계 누적"으로 쓰고, 기획서와 같은 탑재·MAU 수치를 말한다 |

## W. 폰 동반 앱 (`:companion:testDebugUnitTest`, JVM)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| W1 | 입력이 같으면 `summary_full`, `summary_glucose_only`, `summary_all_null` 픽스처와 같은 값이 나온다 | `SummaryBuilderTest` |
| W2 | 무작위 입력 5000건이 시간대 5개에서 모두 `SummaryValidator.validate`를 통과한다. 키 9개, 2KB 이하 | `SummaryBuilderTest` |
| W3 | 측정 후 899초 전송, 900초 null. 수면은 계산 시각 기준 C-36으로 만료. 359/360 경계. 내림 | `SummaryBuilderTest` |
| W4 | 수면: 세션 없음 null, 겹침 합집합, 24시간 창으로 자름 | `SleepCalculatorTest` |
| W5 | 슬롯 최신 1건, 재시도 때 재생성, 실패 대기 15·30·60·60, 429 상한 5분, `clock_skew` 보정 1회, 재시도 금지 응답, 401, `consent_required`, 최소 간격 3초 | `UploadCoordinatorTest` |
| W6 | 미설치 상태에서 앱을 열어도 호출 0회. 딥링크는 코드만 채우고 호출 0회. 외부 QR 파라미터 무시 | `CompanionControllerTest`, `PairingCodeTest` |
| W7 | 해제·철회: 폰을 먼저 비움, 오프라인이면 대기 후 재시도, 404·401 처리, 재연결 실패 시 기존 연결 유지 | `PairingServiceTest` |
| W8 | 시뮬레이터: 범위, mg/dL 내림, source 항상 simulated, `sc1`·`sc3` 공유 파일 재생, 끊김, 건너뛰기, 수면 경로가 혈당을 null로 덮지 않음 | `GlucoseSimulatorTest`, `CompanionControllerTest` |
| W9 | 원본 미전송: 세션과 심박이 요약과 저장 상태에 없음. Health Connect 권한 3개뿐, 혈당 권한·타입 없음. 로깅 경로 없음, 백업 꺼짐 | `CompanionControllerTest`, `CopyAndPolicyTest` |
| W10 | 혈당 문장은 어떤 상태든 "시뮬레이션" 포함(C3 혈당 설명 `glucoseNote`, C4 수치 옆 표시). 맨 위 표지는 전송 중 문구에 "시뮬레이션", 보내지 않음 문구에 "시뮬레이터"를 담는다. 알림 제목·채널·작업 버튼("시뮬레이터 끄기")에 수치와 "혈당" 없음. C4 상태 줄(`simStatus`)을 포함한 UI 문자열 금칙어 없음 | `CopyAndPolicyTest` |
| W11 | 실제 ZXing QR을 Y 평면으로 만들어 읽음. 행 패딩 포함 | `PairingCodeTest` |
| W12 | 실제 `:server`를 띄우고 claim, 전송, null 덮어쓰기, 요약 삭제, 해제, `clock_skew`, 철회·재동의. `CD_COMPANION_SMOKE_URL`을 줄 때만 돈다 | `LiveServerSmokeTest` |
| W13 | 실기에서 찾은 표시 회귀: mg/dL 입력값과 C3 설명·C4 아래 줄의 수가 같음, 주소 저장 응답, 숨김 설정에 쓰는 주소 표시, 알림 아이콘이 면을 채우지 않음, QR 미리보기가 칸 안에서 잘림 | `CopyAndPolicyTest`, `CompanionControllerTest` |
| W14 | 디자인 수정(2026-11-03): C4 상태 줄 4가지와 보내지 않는 상태의 C3 값 문장, 큰 숫자 아래 줄이 같은 값을 되풀이하지 않음, 빼기 U+2212, 안내 막대 첫 문장 분리, 동의 "보관"의 굵게 보일 낱말이 문장에 있음, 글꼴은 `pretendard.ttf` 하나와 OFL 원문, 라이선스 화면 줄 합치기가 낱말을 바꾸지 않음 | `CopyAndPolicyTest` |
| W15 | 실기에서 찾은 표지·뒤로 가기 회귀(2026-11-03): 맨 위 표지, C4 상태 줄, mg/dL 아래 줄의 "보내는 값"이 `sendsGlucose`·실제 전송(`readingAt`)과 일치(꺼짐, 보내는 중, 센서 끊김, SC3 혈당 없는 재생, 끊김과 혈당 없는 재생이 겹친 경우 C3·C4 이유 같음), SC3 진행 줄 "수면 값만 계속 보내요". 하위 화면 뒤로 가기: 라이선스 → 데이터 탭(대조로 탭 화면은 앱 종료), 거부 → C1, 연결 바꾸기 → 지금 연결 유지, QR 스캔 → 스캔만 닫힘, 연결 바꾸기 중 스캔 → 스캔 먼저. 뒤 카메라 없음 → 앱이 닫히지 않고 코드 입력 안내 | `BackNavigationTest`(Robolectric Compose), `CopyAndPolicyTest` |
| W16 | 보관 고지(B-11): 앱 문자열 어디에도 "이 폰에만", "측정 원본", "원본은", "폰 밖으로"가 없다. C1 "보관"과 C3 아래 줄(`SENT_SCOPE`)이 둘 다 "최신 값 하나만"과 "이력은 남기지 않아요"를 말한다. "보관"의 굵게 보일 낱말은 최신 값 하나만·이력은 남기지 않아요·15분·다음 새벽 4시까지. "그날까지"가 없고 "보관"과 전송 중단 안내가 "다음 새벽 4시까지", "밤 10시 넘어", "하루 더"를 말하며 `SleepPolicy.validUntil`(08:00과 22:00은 다음 04:00, 22:00:01은 하루 더)과 맞는다. 폰의 해제·철회 완료 문장 4개는 "차 설정의 연결된 기기에서 풀 수 있어요"로 끝난다(D-32, `unpairAndWithdrawTellWhereToReleaseCarHold`). 화면의 15분은 `SummaryLimits.GLUCOSE_TTL_SEC`(900초)와 같고, "거치는 곳"의 "서버를 거쳐"는 그대로 있다 | `CopyAndPolicyTest.retentionCopyMatchesWhatServerKeeps`, `consentCoversRequiredItems`, `consentEmphasisWordsExist` |

## 2. 수동 점검표 (11/12 전)

- [x] 폰 앱 실기(에뮬레이터): 화면 배치(C1~C4, 주·야간, 글자 150%, 가로), QR 화면과 카메라 권한, 딥링크(adb), Health Connect 권한 화면, 시뮬레이터 알림, 앱을 내린 뒤 30초 주기 유지, 차량·서버 연동. 결과는 아래 "폰 앱 실기 결과"
- [ ] 폰 앱 실기 남은 항목: 실제 폰, 카메라로 QR 실제 판독, 시스템 카메라에서 딥링크, 수면·심박 실제 값 읽기, 잠금 화면의 알림, 6시간 동기화. JVM 테스트와 이번 실행으로 확인하지 못한 항목이다
- [ ] 에뮬레이터 cold boot부터 시연까지 README 절차대로 재현
- [ ] D 기어(DO=true)에서 S1 유지(H12)
- [x] 차량 에뮬레이터에서 `adb shell input keyevent KEYCODE_BACK`으로 오늘 탭·S1이 숨고(앱 목록에서 같은 Activity로 돌아옴) 기록 탭·설정은 한 단계만 돌아가는지(H18, 2026-11-06). 온보딩 1단계도 앱 데이터를 지운 실행에서 숨고 앱 목록으로 다시 열면 같은 1단계로 돌아왔다(2026-11-06). 3단계 듣는 중·말하는 중 Back은 녹음과 음성을 멈췄다(차량 APK 8880, 8eb5. 8eb5에서 stop은 한 번). 창 하나의 S5 페이지만 남았다(주 디스플레이가 창 2개). 실차 CCP 하드키는 실차에서만
- [ ] 녹화 파일에 소리 합침 확인
- [ ] 영어 권한 팝업, 디버그 배너, 터미널 정보 노출 없음
- [ ] 시연 전 CGM 사용자 2~3명, 일반 운전자에게 V09, V14~V17, V19R과 수치 숨김 15분 청취 확인. 이 설계의 사용자 시나리오는 인터뷰가 아니라 문헌과 명세로 만든 가설이다

## 3. 폰 앱 실기 결과 (2026-11-03, 09·20은 2026-11-06, 결정 반영 장은 2026-11-07 재촬영)

환경: 폰 AVD `daylist`(Android 16, API 36, 1080x2400, density 420, 로케일 en-US, `-read-only -no-snapshot-save -no-window`, 끝난 뒤 종료), 차량 `emulator-5554`(Pleos Connect v2.1.0), 서버 주소 `http://10.0.2.2:8080`. 실제 폰이 아니다. 폰 APK `apk/TEAM_ConditionDriveCompanion_release.apk`(그때 앱 이름은 ConditionDrive, 파일명도 그때 것, sha256 `b9044c749c1ee463…`), 당시 차량 APK `apk/TEAM_ConditionDrive_release.apk`(당시 파일명, sha256 `dc82635fdfe0e030…`)를 설치한 뒤 기기의 base.apk 해시가 파일과 같은지 확인했다. 지금 `apk/`의 차량 APK는 그 뒤 열 번 다시 빌드한 `apk/hyunkyo_Braketime_release.apk`, sha256 `78704db3c81b05ba84934fd982ad7fde1182cb90d4fcfa8e768a86cfd7f424f7`이다(배포 키 CN=hyunkyo 서명, 2026-11-13. 사이 빌드는 앞자리 `e653dccf828163a0`, `a7f1ce423e3482bb`, `3f7d1271c7fc3efb`, `914acea4cbfa5047`, `1c5130da91724dcc`, `88800b7057269735`, `8eb5e48fd4e64335`, `07dd72349a1d2157`, `855da4a356f94a12`). 지금 폰 APK는 `apk/hyunkyo_BraketimeCompanion_release.apk`, sha256 `b0764595a7dca79f489cb2f39a1ceb83e825490670673ad5b17df18b2c36de93`이다(배포 키 서명, 2026-11-13. 직전 debug 서명 19ca `19ca4c764139a87e`와 META-INF 밖 항목이 모두 같다). 914a → 1c51은 앱 라벨, 매니페스트의 `ROTARY_SUPPORTED`, Back 숨김, TTS 선행 묵음(U-26, U-27)이고 S4와 판단은 바뀌지 않았다. 1c51 → 8880은 듣기·말하기 취소 때 엔진 정지(`classes2.dex`, `baseline.prof`)뿐이다. 8880 → 8eb5도 같은 두 항목이고 취소 때 stop을 한 번만 보내고, 재시도 때 앞 시도의 엔진 기록을 지우는 것뿐이다. 8eb5 → 07dd는 2026-11-06 결정 반영이다(dex, `resources.arsc`, `poi.json`의 `facility` 칸, 시나리오 D6·D16 수정과 D27~D30 추가. README "결과를 만든 APK"). 07dd → 855d는 `assets/poi.json`의 휴게소 종류 값뿐이다(P-15). 이 절의 차량 장에는 장소 이름이 보이지 않아 855d에서도 같다. 855d → 7870은 서명(META-INF)뿐이다. 서버는 `tools/run-server.sh`와 같은 설정(`CD_DB_PATH`를 임시 파일로)으로 이미 빌드된 `server/build/install/server/bin/server`를 띄웠다. 스크립트의 `run`은 `:server:installDist`를 다시 돌려서다. 끝난 뒤 서버를 끄고 DB를 지웠다. 화면은 `docs/screenshots/companion-*.png`다. 이름을 바꾼 뒤 51장을 한 장씩 열어 보니 앱 이름이 보이는 장은 시스템이 앱 라벨을 그리는 두 장(09 Health Connect 권한, 20 알림)뿐이다. 앱 화면 안 문구에는 앱 이름이 없다(폰 앱 문자열에서 이름은 매니페스트 라벨에 쓰는 `app_name` 하나다). 두 장은 새 폰 APK `apk/hyunkyo_BraketimeCompanion_release.apk`(sha256 `83e987a3e22a39f1…`)로 같은 장면을 다시 찍었다(2026-11-06 07:07·07:09. 설치 뒤 base.apk 해시가 파일과 같고 라벨이 "Braketime 동반 앱"인 것을 확인했다. `daylist`를 `-read-only -no-snapshot-save -no-window`로 띄우고 서버는 포트 8091·임시 DB, curl로 코드 발급. 09는 C1 동의 → C2 연결 → C3 "수면 정보 허용" → HC 온보딩 → 권한 화면, 20은 C4 "시뮬레이터 켜기" → 알림 권한 허용 → 알림창. 끝난 뒤 계정 삭제·서버 종료·DB 삭제·AVD 종료). 옛 장과는 앱 이름만 다르고, 20에는 옛 장에 없던 시스템 알림 "qwerty2 configured"가 Android System 묶음에 함께 보인다. 직전 폰 APK(`b9044c749c1ee463`)와는 라벨, 모듈 이름만 바뀐 `classes3.dex`, `baseline.prof`만 다르다. C4·라이선스 장(12, 13, 15, 17, 21, 22, 26, 27, 28, 40, 41, 42, 44)은 직전 폰 APK(앞자리 `b9044c749c1ee463`)로, 09·20을 뺀 나머지는 그 앞 폰 APK(앞자리 `1cd4ce2759a771bb`)로 찍었다(2026-11-06 당시. 그 뒤 폰 19ca로 다시 찍은 13장은 이 문단 끝에 있다). 그 사이 바뀐 것은 아래 "찾아 고친 폰 앱 문제"의 문구(맨 위 표지, mg/dL 아래 줄의 "보내는 값", SC3 진행 줄)와 뒤로 가기, 카메라 없음 처리뿐이다. 다시 찍지 않은 장 중 맨 위 표지가 보이는 C3·데이터 장(24, 30, 31, 43, 45. 이 가운데 24와 45는 2026-11-07에 19ca로 다시 찍었다)은 모두 보내는 중 상태라 표지 문구("시뮬레이션 혈당 전송 중")가 새 APK와 같다. 재촬영은 폰 AVD만 `-read-only -no-snapshot-save -no-window`로 띄우고 서버를 포트 8091·임시 DB로 따로 띄워(끝난 뒤 종료, DB 삭제) 페어링 코드는 curl로 차량 등록·코드 발급을 직접 해 받았다. 차량 에뮬레이터는 쓰지 않았고 새 base.apk 해시가 파일과 같은지 확인했다. 새 APK 실기에서 라이선스 화면의 시스템 뒤로 가기가 앱을 닫지 않고 데이터 탭으로 돌아가는 것도 확인했다. 차량 화면이 들어간 장(`companion-*-vehicle-*` 10장: 08, 14, 16, 18, 19, 25, 33, 35, 50, 51)은 처음에 차량 APK sha256 앞자리 `dc82635fdfe0e030`으로 찍었다. 그 가운데 14, 18, 25, 33, 35, 50 여섯 장은 2026-11-07에 차량 07dd로 다시 찍었고(아래), 08, 16, 19, 51 네 장은 dc82 그대로다. 열어 보니 이 10장에는 차량 앱 이름이 보이지 않는다(S5에는 "동반 앱"이라는 일반 명사만 있다). 그 APK와 914a(`914acea4cbfa5047…`) 사이 바뀐 것은 S4 기록 탭(표기와 운행 경계)과 화면 방향 고정 제거뿐이고, 914a → 1c51은 위의 앱 라벨, `ROTARY_SUPPORTED`, Back 숨김, TTS 선행 묵음이고 1c51 → 8880 → 8eb5는 취소 때 엔진 정지라 이 장들에 드러나지 않는다. 8eb5 → 07dd에서 글이 바뀐 여섯 장(14, 18, 25, 33, 35, 50)은 아래처럼 2026-11-07에 다시 찍었다. dc82 그대로인 네 장(08, 16, 19, 51)에는 07dd에서 바뀐 글이 보이지 않는다. dc82 → e653: 목록에 "멈춤", "중간 멈춤" 행과 보조 글이 생기고 휴식 횟수가 "15분 이상 휴식"으로 바뀌었으며, 1분 미만 운행을 7일 운행 수에서 뺀다(두 APK의 문자열 자원을 비교했고, 그 사이 바뀐 앱 소스는 S4 계산과 화면 파일뿐이다). e653 → a7f1: 7일 첫 줄이 "운전 | N회, M분"에서 "운행 | N회, 운전 M분"으로, 빈 상태 문구가 "최근 7일 동안 운행 기록이 없어요"로 바뀌고(U-24), 운행이 0이어도 15분 이상 휴식이나 휴식 안내가 있으면 빈 상태 대신 세 줄을 보인다(리소스 덤프에서 바뀐 값은 그 세 문자열뿐). a7f1 → 3f7d: 매니페스트에서 `MainActivity`의 `screenOrientation="landscape"`만 빠졌다(U-17. APK 항목 해시를 비교하면 `AndroidManifest.xml`만 다르다). 3f7d → 914a: S4 운행 경계(`TripFold`)만 바뀌었다(U-25. APK 항목 해시를 비교하면 dex 세 개와 `assets/dexopt/baseline.prof`·`baseline.profm`만 다르다). 주 디스플레이의 가로 창에서는 앱 창이 1682×1210px로 같아 화면이 달라지지 않는다. 이 장들에는 S4 화면이 없다. 37은 카메라 미리보기 한 장으로 합쳤고(전에 있던 겹침 증거와 `-fixed`는 지움), 23번은 처음부터 없다. 2026-11-07에는 2026-11-06 결정으로 보이는 글이 바뀐 장을 다시 찍었다. 폰 장 13장(01, 01b, 02, 07, 10, 11, 24, 29, 34, 39, 45, 47, 48)은 폰 19ca(`19ca4c764139a87e`)이고, 차량이 든 장 6장(14, 18, 25, 33, 35, 50)은 차량 07dd(`07dd72349a1d2157`)다. 바뀐 글은 C1 "보관", C3 아래 줄, 전송 중단 안내, 해제·철회 완료 문장 끝의 "차에 남은 끊김 보류는 차 설정의 연결된 기기에서 풀 수 있어요.", 차량 S2 제목 "안내 시점"과 출처 두 줄, 33의 사유 줄, 35의 보류 문단과 "끊김 보류 풀기"다. 폰 AVD `daylist`를 `-read-only -no-snapshot-save -no-window`로 띄워 새 base.apk 해시가 파일과 같은 것을 확인했고, 설치 때 받은 건강·카메라·알림 권한을 회수해 처음 상태로 돌렸다. 서버는 포트 8080·임시 DB로 띄워 차량과 실제로 연결했다(코드는 차량 S5에서 받음, 끝난 뒤 서버 종료·DB 삭제, 차량에서 연결 해제, 폰 AVD 종료). 순서와 장면은 2026-11-03과 같다. 다른 점은 셋이다. 18은 3.8을 거치지 않고 5.4에서 바로 센서 끊김을 재현했고, 24의 혈당은 5.43이 아니라 5.4이며, 47은 안내 막대가 세 줄이 되어 "동의하지 않기" 버튼 아래가 화면 끝에 걸린다(C1은 스크롤 화면). 폰 crash 버퍼는 0줄이었다. 나머지 장은 바뀐 글이 보이지 않아 그대로 두었다(43은 아래 줄이 화면 밖).

| 항목 | 결과 | 확인한 것 |
| --- | --- | --- |
| C1 동의 | 통과 | 체크 비어 있음, 5개 항목, "보관"의 15분·그날까지·이 폰에만 굵게, 체크 없이 누르면 안내 문장, 거부 화면과 "동의 내용 다시 보기", 동의 전까지 폰의 서버 호출 0회 |
| C2 연결 | 통과 | 제목 5회 탭으로 숨김 설정, `http://evil.example:8080` "이 주소는 쓸 수 없어요.", `10.0.2.2` 저장 문장, 11자 코드는 서버 호출 없이 "코드는 8자예요", 없는 코드 `abcdefgh`는 대문자로 바뀌어 claim 404와 "코드가 맞지 않아요", 소문자·하이픈 `tbdd-9dd2` 연결, 해제 뒤 재연결, 철회 뒤 재동의·재연결 |
| C2 카메라 | 일부 | 첫 요청 거부 → "카메라를 쓸 수 없어요" 문장, 다시 눌러 허용 → 미리보기가 칸 안에 있고 안내 문장을 덮지 않음, QR 닫기. 에뮬레이터 카메라가 시험 무늬만 보여 QR 판독은 못 함 |
| 딥링크 | 통과(adb) | `conditiondrive://pair?v=1&code=…&server=http://evil.example:8080`으로 코드만 채우고 서버 호출 0회, 숨김 설정 주소는 `10.0.2.2` 그대로. 시스템 카메라에서 여는 경로는 못 함 |
| C3 상태 | 통과 | 수면 안내 3가지("허용하지 않았어요", "기록이 없어요", 4시간 50분과 "기준 6시간보다 짧아요 (설계값) · 수면 시뮬레이션 값이에요"), 혈당 행 "시뮬레이션", 고지 면, 마지막 전송 HH:mm:ss, 전송 중단 문장과 다시 시작, 서버를 끄면 "연결 안 됨. 최신 값만 다시 보내 볼게요." 뒤 서버를 다시 켜면 전송 재개 |
| Health Connect | 일부 | 새 이미지라 HC 온보딩이 먼저 뜸, 권한 화면(심박·수면), 개인정보 링크가 동의 내용(`ViewPermissionUsageActivity`), 백그라운드 권한 화면. 기록 앱이 없어 값 읽기는 못 함("지난 24시간 수면 기록이 없어요"까지) |
| C4 시뮬레이터 | 통과 | 첫 켜기에서 알림 권한 요청, 상태 줄 4가지(꺼짐, 보내는 중, 센서 끊김 재현, 혈당 없이 재생), 끊김·SC3 재생 중 맨 위 "시뮬레이터 실행 중 · 혈당 보내지 않음"(17, 27, 28), SC3 mg/dL 아래 줄 "6.4 mmol/L · 변화 적음"과 진행 줄 "0:00:00 경과. 수면 값만 계속 보내요", 값 칩 `− 0.1`·`+ 0.1`, mg/dL에서 98과 "보내는 값 5.43 mmol/L"·C3 "98 mg/dL" 일치, SC1 재생과 건너뛰기(0:21:00 경과), SC3 재생, 수면 시뮬레이션 |
| 데이터 탭 | 통과 | 한 번 누르면 테두리와 "한 번 더 누르면 실행해요", 5초 뒤 풀림, 다른 행을 누르면 그 행으로 옮김, 요약 지우기(온라인 "지웠어요", 서버를 끈 상태 "연결 안 됨…"), 요약 지우기가 시뮬레이터와 알림도 멈춤, 연결 해제, 동의 철회 뒤 C1 체크 비움, 글꼴 라이선스 "Pretendard · SIL OFL 1.1"과 시스템 뒤로 가기로 데이터 탭 복귀(새 폰 APK) |
| 차량 반응 | 통과 | 5.4 → S2 "기준 위, 시뮬레이션 데이터". 3.8 빠르게 내려감 → S3 "쉬는 중", "회복 기준 위로 오르면 40분 뒤 알려 드릴게요", 오른쪽 "기준 아래", 음성 "조금 쉬어 가요. 때가 되면 다시 알려 드릴게요." 끊김 → S2 "연결 끊김", 음성 "연결이 끊겼어요. 직접 확인하고 출발하세요."(V19R). 3.8 복귀 → S3. 5.4 → "회복 후 40분 중 남은 시간 39분", 음성 "40분 뒤에 다시 알려 드릴게요." SC3(수면 290, 혈당 없음) → S2 "수면 기준보다 짧음, 기준을 30분 앞당겼어요", "연결 끊김", 임계 1:15, 1:30. 차량은 60초마다 요약을 읽어 반응까지 최대 1분 걸린다 |
| 해제 반응 | 통과 | 폰에서 해제 → 폰 "연결을 해제했어요. 서버의 요약도 지웠어요.", 차량 S5 "없음", 차량 S2 "연결 끊김" 유지(D-14). 이 칸은 당시 차량 dc82다. 07dd에서는 값을 받은 뒤 폰에서 해제하면 차량 S5 연결된 기기가 "연결 끊김"이고 보류 문단과 "끊김 보류 풀기"가 보인다(2026-11-07 `companion-35` 재촬영, D-32). 폰 해제로 보류가 풀리지 않는 것은 같다. 차량에서 해제(두 번 눌러 실행) → 폰 다음 전송 401, C2와 "차와의 연결이 끊겼어요", 알림과 서비스 종료, 차량 S2 초기화(1:45, 2:00) |
| 이전 DB 토큰 | 통과 | 지운 DB의 토큰을 가진 차량이 "연결 코드 받기"에서 401을 받고 다시 등록해 새 코드를 보여 줌(51) |
| 표시 | 통과 | 다크·라이트, 글자 150%, 가로(본문 약 600dp 가운데), 키보드(숫자 줄 있는 비밀번호형), 긴 안내 막대에서 잘림·겹침 없음, Pretendard. 폰 앱 FATAL·`AndroidRuntime` 0건, 차량 crash 버퍼에 이 앱 0건 |
| 백그라운드 | 통과 | 홈으로 내린 뒤 65초 동안 30초 주기 2건 전송, FGS 유지 |
| 시뮬레이터 알림 | 일부 | 제목 "시뮬레이션 전송 중이에요", 수치·"혈당" 없음, 수평선 아이콘, `vis=SECRET`, "시뮬레이터 끄기" 작업 버튼으로 서비스와 표지 종료. 이 AVD에는 화면 잠금이 없어 잠금 화면 표시는 못 봄 |
| 6시간 동기화 | 못 함 | 주기 실행은 기다리지 않았다 |
| 실제 폰 | 못 함 | `adb reverse` 경로 포함 |

2026-11-01 첫 실기에서 고친 폰 앱 버그(W13): 알림 아이콘이 흰 사각형, QR 미리보기가 안내 문장을 덮음, mg/dL 입력 뒤 한 화면에 98과 97, 주소 저장에 응답 없음, 숨김 설정의 주소 칸이 비어 있음. 이번 실행에서 다섯 가지 모두 다시 나오지 않았다.

이번 실행에서 찾아 고친 폰 앱 문제(W15, 새 폰 APK로 다시 찍음):

- 글꼴 라이선스 화면에서 시스템 뒤로 가기를 누르면 데이터 탭으로 돌아가지 않고 앱이 닫혔다(`LicenseScreen`에 뒤로 가기 처리가 없었음). `BackHandler`를 넣어 데이터 탭으로 돌아간다. 시험 `BackNavigationTest.backFromLicenseReturnsToDataTab`.
- C4를 mg/dL로 두면 큰 숫자 아래 줄이 상태와 상관없이 "보내는 값 5.6 mmol/L"로 시작했다. SC3 재생 중에는 바로 위 상태 줄 "혈당 없이 재생 · 보내지 않아요"와 어긋났다. "보내는 값"은 보내는 중(`sendsGlucose`)일 때만 붙는다. 시험 `CopyAndPolicyTest.bigValueLineSaysSendingOnlyWhileSending`.
- 맨 위 표지 "시뮬레이션 혈당 전송 중"이 SC3 재생(혈당 없이 재생)과 센서 끊김 재현 중에도 그대로 보였다. 이때는 "시뮬레이터 실행 중 · 혈당 보내지 않음"이다. 시험 `CopyAndPolicyTest.runningMarkerFollowsWhatIsActuallySent`, `BackNavigationTest.markerSaysNotSendingWhileSensorCut`.
- 같은 종류의 문구 충돌로 SC3 재생 중 진행 줄 "마지막 값을 계속 보내요"를 "수면 값만 계속 보내요"로 바꿨다. 실기가 아니라 시험을 쓰다가 찾은 것으로, 권한은 있는데 뒤 카메라가 없으면 QR 찍기에서 앱이 닫히던 것도 코드 입력 안내로 돌아가게 고쳤다(에뮬레이터·일반 폰은 뒤 카메라가 있어 실기로는 재현하지 않았다).

차량 쪽에서 본 것(고치지 않음):

- S2 수치 대신 범위 문구가 나오는 것은 설계다. 좌석 값 방향을 확인하기 전이라 실사용에서 항상 수치를 숨긴다(F11). 그래서 서버 경로로는 차량에서 혈당 숫자를 볼 수 없다.
- (dc82로 찍은 옛 33) 수면 부족을 받은 뒤 요약을 지우면 S2의 수면 행은 사라지고 그때 제목 "오늘 기준"의 임계는 1:15, 1:30으로 남았다. `advanceLatched` 규칙대로이고 다음 운행에서 풀리지만, 화면만 보면 왜 짧은지 알 수 없다. 2026-11-06에 고쳤다. 라벨은 "안내 시점"이고 왼쪽에 사유 줄이 선다(U-28, U-29, H21). 33은 2026-11-07에 차량 07dd로 다시 찍어 지금은 사유 줄 "수면 값은 없지만 앞서 받은 값으로 안내 시점을 30분 앞당겨 뒀어요"가 보인다.
- 폰에서 동의를 철회한 직후 차량 S5는 다음 요약 읽기(최대 60초)까지 "연결됨"으로 남는다.
- 이 절과 4절의 C1 "보관"(15분·그날까지·이 폰에만 굵게, "측정 / 원본은" 줄바꿈)은 2026-11-06에 고친 문구 전의 기록이다. 지금 "보관"은 "서버에는 최신 값 하나만 남고 이력은 남기지 않아요. 혈당은 15분, 수면은 다음 새벽 4시까지 남아요(밤 10시 넘어 보내면 하루 더)"이고 C3 아래 줄도 "최신 값 하나만"과 "이력은 남기지 않아요"를 말한다(B-11, W16). C1·C3 장은 2026-11-07에 폰 19ca로 다시 찍어 지금 문구다.

## 4. 폰 앱 디자인 수정 실기 결과 (2026-11-03)

환경: 폰 AVD `daylist`(API 36, 1080x2400, density 420, 로케일 en-US, `-read-only -no-snapshot-save`, 끝난 뒤 종료), `tools/run-server.sh`를 포트 8091·임시 DB로 띄움(끝난 뒤 종료, DB 삭제). 차량 에뮬레이터는 쓰지 않았다. 페어링 코드는 서버에 차량 등록과 코드 발급을 curl로 직접 요청해 받았다. 화면은 작업 폴더 밖 `design/after/companion/`에 남겼고 `docs/screenshots`는 바꾸지 않았다.

| 항목 | 결과 | 확인한 것 |
| --- | --- | --- |
| 글꼴 | 통과 | Pretendard W400/W500/W600, 라이선스 화면 제목 아래 "Pretendard · SIL OFL 1.1"과 원문(RFN 줄 포함) |
| 한글 줄바꿈 | 통과 | `LineBreak.Heading`·`Paragraph+Phrase`와 `LocaleList("ko-KR")`만으로 C1 "측정 / 원본은", "그대로 / 작동해요", "(민감정보 별도 동의)", 150% "앞당기지 / 않아요" 등 모든 화면에서 어절 중간 끊김 0건. U+2060은 넣지 않았다 |
| C1 | 통과 | "보관" 값의 15분·그날까지·이 폰에만 W600, 고지 사이 간격과 선, 체크박스 라벨 bodyL W500, 거부 버튼 56dp titleM |
| C2 | 통과 | 빈 칸 "8자 코드", 밑줄 평소 text3 1dp·입력 중 text1 2dp, 소문자 입력이 대문자로 저장, 오류 때 확인 안내 숨김. `KeyboardType.Password`로 Gboard 추천 줄이 사라지고 숫자 줄이 생김. 빠르게 친 8자가 모두 들어감(Ascii일 때는 1자 빠짐). 자동 채우기 저장 창 없음(`dumpsys autofill` showsSaveUi false), 접근성 노드 `password=false` |
| C3 | 통과 | 값 없는 행 bodyL text2, 혈당 "5.43 mmol/L" + "98 mg/dL · 변화 적음 · 시뮬레이션", 고지 면, 보조 버튼이 구분선에서 떨어짐, 마지막 전송 HH:mm:ss |
| C4 | 통과 | 상태 줄 4가지(꺼짐, 보내는 중, 센서 끊김, 혈당 없이 재생), 숫자·단위·시뮬레이션 한 기준선, 아래 한 줄, 선택 칩 높이 같음, 값 칩 두 줄, 시나리오 진행 줄이 버튼 위 |
| 데이터 탭 | 통과 | 실행 행 text1·`>` 없음, 한 번 누르면 테두리와 굵은 확인 문장, 5초 뒤 풀림, 다른 행을 누르면 그 행으로 옮겨감, 철회 행 맨 아래 |
| 안내 막대 | 통과 | bodyM, 첫 문장만 W500 |
| 알림 | 통과 | 수평선 아이콘, "시뮬레이터 끄기" 작업 버튼으로 서비스 종료와 표지 사라짐 |
| 가로·다크·150% | 통과 | 본문 600dp 가운데, 낮은 화면 여백 축소, 다크 토큰, 150%에서 잘림·겹침 없음 |
| C2 TalkBack 키 낭독 | 못 함 | `KeyboardType.Password`는 IME에만 비밀번호 칸으로 알린다(접근성 노드 `password=false`). TalkBack을 켠 상태의 키 낭독과 Gboard 클립보드 붙여넣기는 확인하지 않았다. 문제가 있으면 `KeyboardType.Ascii`+`autoCorrectEnabled=false`로 바꾸고 `TextFieldValue`로 선택·조합 상태를 보존해 빠른 입력 누락을 막는다 |
| minSdk 28~32 줄바꿈 | 못 함 | 해당 API AVD가 없다. Phrase가 없는 버전이라 글자 단위로 끊길 수 있다(design-tokens 7.3) |


## 5. 차량 앱 폼 팩터 실기 결과 (2026-11-05)

환경: Pleos 에뮬레이터 emulator-5554(AVD `pleos`, Pleos Connect v2.1.0, 창 있음, `-grpc 8554 -grpc-use-token`), Host Display Bridge 1.0.0(Go, 127.0.0.1:8096), 기기 안 FormFactorConsole(`ai.pleos.connectwebconsole`, 8095 포워딩). 프리셋 크기는 콘솔 `window-policy.js` 값 그대로이고 px = dp × DPI / 160이다. 조합마다 가상 디스플레이를 만들고 콘솔의 앱 목록을 띄운 뒤 앱을 그 디스플레이에서 새로 실행해(`am force-stop` 후 `am start --display <ID>`) 오늘·기록·설정(탭을 눌러 엶)·주행 S1(`tools/vc.sh drive 60`)·정차 복귀(`tools/vc.sh park`)를 찍었다. 앱 창 크기는 `dumpsys window`의 frame이다.

| 프리셋 | 세로 창 | 고치기 전(a7f1, landscape 고정) 세로 앱 창 | 고친 뒤(3f7d) 세로 앱 창 | 가로 창(두 APK 같음) |
| --- | --- | --- | --- | --- |
| Connect-S 17″, 160 DPI | 840×1184dp | 840×832px, 아래 352px 검정 | 840×1184px | 1700×1184dp |
| Connect-S 14.6″, 200 DPI | 672×948dp(840×1185px) | 840×832px, 아래 353px 검정 | 840×1185px | 1360×948dp(1700×1185px) |
| Connect-S 12.9″, 160 DPI | 628×855dp | 628×622px, 아래 233px 검정 | 628×855px | 1274×855dp |
| Connect-WS 24.6″, 160 DPI | 800×810dp | 800×792px, 아래 18px 검정 | 800×810px | 1640×810dp |
| Connect-WE 24.6″, 160 DPI | 800×1400dp | 800×792px, 아래 608px 검정 | 800×1400px | 1640×1400dp |
| Connect-WF 18.7″, 200 DPI | 648×800dp(810×1000px) | 810×802px, 아래 198px 검정 | 810×1000px | 1256×800dp(1570×1000px) |

- 고치기 전 세로: 시스템이 가로 고정 앱을 거의 정사각형으로 레터박스했다. 기록 탭 끝줄이 탭 바에 잘렸고(12.9인치는 "운전 27시간 25분,"에서 끊김(진행 중 운전이라 찍은 시각마다 값이 늘어 아래 "27시간 19분"과 다르다), 18.7인치는 "휴식 안내 0회"가 반쯤 가림), 방향 고정이 없는 `SetupActivity`(설정)만 창을 채워 탭 바가 탭마다 위아래로 옮겨졌다(최대 608px). 고친 뒤 세로 30장, 가로 30장은 모두 창을 채우고 잘림·겹침이 없었다(장마다 이전 빌드 화면과 대조). 전후 비교: `docs/screenshots/26-formfactor-portrait.png`.
- 가로: 두 APK 모두 창을 채우고 2창으로 나뉘었다. 주 디스플레이(2560×1440)의 앱 창도 두 APK 모두 1682×1210px으로 같다. 두 APK는 `AndroidManifest.xml`의 `screenOrientation` 속성 하나만 다르고 dex·리소스 등 나머지 항목은 해시가 같다.
- 주행 S1 글자(창 px ÷ DPI로 잰 추정): 남은 시간 숫자 약 18~19mm, 시간·분 단위 약 7.6mm, 상태어 약 6.5~7mm, "다음 휴식까지" 약 4.2~4.6mm, 가장 작은 막대 눈금 숫자 약 3.2mm. 중요 5mm·보조 3mm 기준을 넘는다. 모든 항목이 한 줄이고 대비는 4.5:1 이상이다.
- 설계대로인 것: 폭 1100dp 미만 한 창에서는 S1의 연속 운전 막대와 S2 규칙 창의 세 줄이 빠진다(8.1). 그래서 세로 S1은 아래 절반 이상이 비고, 촬영한 a7f1·3f7d에서는 S2 맨 아래 옛 출처 줄 "기준: 화물차 휴게 기준 참고"가 화면에 없는 기준을 가리켰다(그때 규칙 창 제목은 "오늘 기준"). 2026-11-06에 제목은 "안내 시점", 출처 줄은 "2시간·15분 참고: 화물자동차 운수사업법 시행규칙"과 "기본 주의 시점 1시간 45분: 설계값"으로 바꿨다(U-28). 한 창에서 규칙 세 줄이 빠지는 것은 그대로다. 가로 S1의 눈금 "1:45"와 "2:00"은 간격이 11~12px라 한 덩어리로 읽힌다. 고치지 않았다.
- 화면의 기록 값(오늘 "13:07 운전 27시간 19분")은 이 에뮬레이터에 남아 있던 실사용 기록이다. 레이아웃과 무관하다. 원인은 재부팅 복원 뒤 운행 경계(`TripFold`)였고 U-25로 고쳤다(H17). 이 촬영은 고치기 전 APK(a7f1, 3f7d)로 했다. U-25가 든 차량 APK(앞자리 914acea4cbfa5047)는 3f7d와 dex(그리고 거기서 나오는 baseline 프로필)만 다르고 매니페스트·리소스가 같아 레이아웃 결과는 그대로다(바뀐 것은 S4 운행 접기). 그다음 차량 APK(앞자리 1c5130da91724dcc, U-26·U-27)는 914a와 매니페스트의 `ROTARY_SUPPORTED` 메타데이터 한 줄, 앱 라벨, dex(Back·묵음 코드와 Kotlin 모듈 이름)만 달라 레이아웃 결과는 그대로이고, 폼 팩터는 다시 돌리지 않았다(주 디스플레이 가로 앱 창은 1c51에서도 1682×1210px, `dumpsys window`). 그 뒤 차량 APK 8880(앞자리 88800b7057269735)과 8eb5(앞자리 8eb5e48fd4e64335)는 1c51과 `classes2.dex`와 baseline 프로필만 달라(취소 때 엔진 정지) 레이아웃 결과가 같다. 차량 APK 07dd(앞자리 07dd72349a1d2157)는 2026-11-06 결정으로 S2 출처 줄이 두 줄이 되고 S5에 보류·철회 안내 줄이 늘었다. 지금 차량 APK 855d(앞자리 855da4a356f94a12)는 07dd와 `assets/poi.json`만 달라 화면 배치가 같다. 주 디스플레이 가로 앱 창은 두 APK 모두 1682×1210px로 같지만(2026-11-07 `dumpsys window`) 폼 팩터 프리셋은 다시 돌리지 않았다. 세로 한 창에서 두 줄 출처가 어떻게 보이는지는 확인하지 않았다.
- 못 본 것: 실제 세로 화면 차량, 앱이 떠 있는 채 방향이 바뀌는 경우(가상 디스플레이는 만들 때 방향이 정해진다), 폼 팩터 창에서의 터치 반응 속도.
