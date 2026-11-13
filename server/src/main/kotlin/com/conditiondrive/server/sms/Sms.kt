package com.conditiondrive.server.sms

import com.conditiondrive.server.Config
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

enum class SmsTemplate { GUARDIAN_CONSENT_V1, GUARDIAN_ALERT_V1 }
data class SmsVars(val driverName: String, val code: String? = null, val optOutCode: String? = null)
data class OutboundSms(val toE164: String, val template: SmsTemplate, val vars: SmsVars, val idempotencyKey: String)
sealed interface SendResult {
    data class Accepted(val delivery: String, val providerRef: String?) : SendResult
    data class Failed(val retryable: Boolean, val code: String) : SendResult
}
interface GuardianMessageSender { suspend fun send(sms: OutboundSms): SendResult }

object Delivery {
    const val LOGGED = "logged"
    const val PROVIDER_ACCEPTED = "provider_accepted"
    const val SUPPRESSED_SIMULATED = "suppressed_simulated"
}

object PhoneMask {
    private const val VISIBLE_PREFIX = 3
    private const val VISIBLE_SUFFIX = 4

    fun mask(e164: String): String =
        e164.take(VISIBLE_PREFIX) + "*".repeat((e164.length - VISIBLE_PREFIX - VISIBLE_SUFFIX).coerceAtLeast(0)) + e164.takeLast(VISIBLE_SUFFIX)
}

/** 12.14 문구. 자유 문자열 입력이 없어 어떤 호출 경로로도 건강값이 문자에 들어갈 수 없다(S18). */
object SmsRenderer {
    fun render(sms: OutboundSms): String = when (sms.template) {
        SmsTemplate.GUARDIAN_CONSENT_V1 -> {
            val name = sms.vars.driverName
            "[Braketime] ${name}님이 운전 중 휴식 권고 알림 수신자로 등록하려 합니다. " +
                "동의하시면 확인 코드 ${sms.vars.code}를 ${name}님께 알려 주세요. 원치 않으면 무시하세요. " +
                "수신 거부 코드 ${sms.vars.optOutCode}"
        }
        SmsTemplate.GUARDIAN_ALERT_V1 ->
            "[Braketime] ${sms.vars.driverName}님의 차량 앱이 정차를 권고했지만 주행이 이어지고 있어요. " +
                "안전한 때에 연락해 확인해 주세요. 의료 판단이 아닙니다. 수신 거부 코드 ${sms.vars.optOutCode}"
    }
}

/** 기본 발송기. 실제 문자를 보내지 않는다. 코드와 전체 번호는 로그 금지 항목이라 찍지 않는다(12.17). */
class LogOnlySender : GuardianMessageSender {
    private val logger = LoggerFactory.getLogger("cd.sms")

    override suspend fun send(sms: OutboundSms): SendResult {
        logger.info("sms_logged to=${PhoneMask.mask(sms.toE164)} template=${sms.template.name}")
        return SendResult.Accepted(Delivery.LOGGED, null)
    }
}

/**
 * 로그에는 동의 코드를 남길 수 없어, 로그 전용만으로는 시연에서 동의 확인 단계를 지날 방법이 없다.
 * CD_SMS_OUTBOX를 명시한 때만 켜지고, 문자 전문을 소유자 전용 로컬 파일에 쌓는다. 외부로 나가지 않는다.
 */
class OutboxFileSender(private val file: Path) : GuardianMessageSender {
    private val logOnly = LogOnlySender()

    override suspend fun send(sms: OutboundSms): SendResult {
        file.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(
            file,
            "to=${PhoneMask.mask(sms.toE164)} template=${sms.template.name} text=${SmsRenderer.render(sms)}\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        Config.ownerOnly(file)
        return logOnly.send(sms)
    }
}
