package com.conditiondrive.core.contract

import kotlinx.serialization.json.Json

object ContractJson {
    /** 서버 입력용. 알 수 없는 키를 원본 건강 데이터 유입으로 보고 거절한다 */
    val Strict: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }

    /** 클라이언트 입력용. 응답 필드 추가로 설치된 APK가 깨지지 않게 한다 */
    val Lenient: Json = Json {
        ignoreUnknownKeys = true
    }
}
