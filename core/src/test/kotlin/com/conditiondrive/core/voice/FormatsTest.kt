package com.conditiondrive.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatsTest {
    @Test
    fun driveTimeRoundsMinutesDown() {
        assertEquals("1시간 45분", Formats.driveTime(6300))
        assertEquals("1시간 44분", Formats.driveTime(6299))
        assertEquals("2시간", Formats.driveTime(7200))
        assertEquals("2시간", Formats.driveTime(7259))
        assertEquals("2시간 1분", Formats.driveTime(7260))
        assertEquals("30분", Formats.driveTime(1800))
        assertEquals("0분", Formats.driveTime(59))
        assertEquals("0분", Formats.driveTime(-5))
    }

    @Test
    fun remainingRoundsMinutesUp() {
        assertEquals("12분", Formats.remaining(720))
        assertEquals("13분", Formats.remaining(721))
        assertEquals("1분", Formats.remaining(1))
        assertEquals("15분", Formats.remaining(900))
        assertEquals("40분", Formats.remaining(2400))
        assertEquals("1시간", Formats.remaining(3600))
        assertEquals("1시간 1분", Formats.remaining(3601))
        assertEquals("0분", Formats.remaining(0))
        assertEquals("0분", Formats.remaining(-30))
    }

    @Test
    fun distanceUsesThreeBands() {
        assertEquals("800미터", Formats.distance(800))
        assertEquals("999미터", Formats.distance(999))
        assertEquals("1킬로미터", Formats.distance(1000))
        assertEquals("3.2킬로미터", Formats.distance(3200))
        assertEquals("3.2킬로미터", Formats.distance(3249))
        assertEquals("3.3킬로미터", Formats.distance(3250))
        assertEquals("3킬로미터", Formats.distance(3000))
        assertEquals("9.9킬로미터", Formats.distance(9949))
        assertEquals("10킬로미터", Formats.distance(9950))
        assertEquals("10킬로미터", Formats.distance(10_000))
        assertEquals("12킬로미터", Formats.distance(12_000))
        assertEquals("12킬로미터", Formats.distance(12_499))
        assertEquals("13킬로미터", Formats.distance(12_500))
        assertEquals("0미터", Formats.distance(-1))
    }

    @Test
    fun placeNameDropsParentheses() {
        assertEquals("칠곡졸음쉼터", Formats.placeName("칠곡졸음쉼터(부산방향)"))
        assertEquals("안성휴게소", Formats.placeName("안성휴게소 (서울방향)"))
        assertEquals("안성휴게소", Formats.placeName("안성휴게소（서울방향）"))
        assertEquals("안성휴게소 주유소", Formats.placeName("안성휴게소(서울) 주유소"))
        assertEquals("칠곡졸음쉼터", Formats.placeName("칠곡졸음쉼터(부산"))
        assertEquals("", Formats.placeName("(부산방향)"))
        assertEquals("휴게소", Formats.placeName("  휴게소  "))
    }

    @Test
    fun s1TimeSaysSoonUnderOneMinute() {
        assertEquals("곧", Formats.s1Time(59))
        assertEquals("곧", Formats.s1Time(0))
        assertEquals("곧", Formats.s1Time(-10))
        assertEquals("1분", Formats.s1Time(60))
        assertEquals("1시간 12분", Formats.s1Time(4320))
        assertEquals("1시간 12분", Formats.s1Time(4379))
    }

    @Test
    fun mgdlUsesFloorSoValueNeverRoundsUp() {
        assertEquals(72, Formats.mgdl(4.0))
        assertEquals(90, Formats.mgdl(5.0))
        assertEquals(97, Formats.mgdl(5.4))
        assertEquals(115, Formats.mgdl(6.4))
        assertEquals(71, Formats.mgdl(3.99))
    }
}
