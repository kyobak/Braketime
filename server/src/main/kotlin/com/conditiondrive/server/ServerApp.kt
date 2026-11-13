package com.conditiondrive.server

import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.auth.Authenticator
import com.conditiondrive.server.auth.Secrets
import com.conditiondrive.server.db.Database
import com.conditiondrive.server.routes.apiRoutes
import com.conditiondrive.server.routes.installHttp
import com.conditiondrive.server.service.AccountService
import com.conditiondrive.server.service.ClaimGuard
import com.conditiondrive.server.service.ConsentService
import com.conditiondrive.server.service.GuardianService
import com.conditiondrive.server.service.RateLimiter
import com.conditiondrive.server.service.SummaryService
import com.conditiondrive.server.service.SweepService
import com.conditiondrive.server.service.TripService
import com.conditiondrive.server.sms.GuardianMessageSender
import com.conditiondrive.server.sms.LogOnlySender
import com.conditiondrive.server.sms.OutboxFileSender
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import java.io.Closeable

/** 시계와 발송기를 주입해 만료, 호출 제한, 발송을 테스트에서 실제 시간 없이 돌린다(12.15). */
class ServerApp(
    val config: Config,
    val clock: Clock,
    sender: GuardianMessageSender = config.smsOutboxPath?.let(::OutboxFileSender) ?: LogOnlySender(),
) : Closeable {
    val db: Database = Database.open(config.dbPath)
    private val secrets = Secrets(config.hmacKey)
    val limiter = RateLimiter(clock)
    val claimGuard = ClaimGuard(clock)
    val auth = Authenticator(db, clock)
    val accounts = AccountService(db, clock, secrets)
    val summaries = SummaryService(db, clock)
    val consents = ConsentService(db, clock)
    val trips = TripService(db, clock)
    val guardians = GuardianService(db, clock, secrets, sender, config.allowPartnerRealtime)
    val sweeper = SweepService(db, clock, limiter)

    override fun close() = db.close()
}

fun Application.conditionDriveModule(app: ServerApp) {
    installHttp(app.clock)
    routing { apiRoutes(app) }
}
