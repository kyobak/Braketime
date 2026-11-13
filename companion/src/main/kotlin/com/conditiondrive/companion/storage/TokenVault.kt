package com.conditiondrive.companion.storage

/** 평문 토큰은 호출하는 동안만 메모리에 있다 */
class TokenVault(private val store: CompanionStore, private val cipher: TokenCipher) {
    fun seal(token: String): String = cipher.seal(token)
    fun open(blob: String): String? = cipher.open(blob)
    suspend fun current(): String? = store.read().tokenBlob?.let(cipher::open)
}
