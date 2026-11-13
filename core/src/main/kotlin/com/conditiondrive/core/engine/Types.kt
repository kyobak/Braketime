package com.conditiondrive.core.engine

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }
enum class Ignition { OFF, ON, UNKNOWN }
enum class Level { NONE, CAUTION, STOP }            // ordinal이 위험도
enum class DriverState { NORMAL, CAUTION, STOP, RECOVER }
enum class Reason { FATIGUE, CHECKIN, GLUCOSE, GLUCOSE_UNCONFIRMED }
enum class Trend { FALLING_FAST, FALLING, FLAT, RISING }
enum class CheckinAnswer { GOOD, NEUTRAL, TIRED, NO_SPEECH, UNCLASSIFIED }
enum class PromptAnswer { ACCEPT, DECLINE, MUTE, NO_SPEECH, UNCLASSIFIED }
enum class PromptKind { FATIGUE, CHECKIN, GLUCOSE_CAUTION }
enum class AdvanceReason { SLEEP_SHORT }
enum class GapEvidence { NONE, DROVE, PARKED, UNKNOWN }  // UNKNOWN은 DROVE로 처리(보수)
enum class TripEvent { DRIVE_STARTED, REST_STARTED, REST_COMPLETED, STOP_ADVISED, DECLINED, CONDITION_STOP_ADVISED }

/** 경계 변환이 끝난 요약. 시각은 모두 엔진 Clock 기준. */
data class ConditionInput(
    val sleepMinutes: Int?,
    val sleepExpiresAtMs: Long?,
    val glucoseMmol: Double?,
    val glucoseTrend: Trend?,
    val glucoseMeasuredAtMs: Long?,
    val glucoseExpiresAtMs: Long?,
    val glucoseSimulated: Boolean,
)
