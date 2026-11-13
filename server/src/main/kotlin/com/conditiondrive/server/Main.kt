package com.conditiondrive.server

import com.conditiondrive.core.time.Clock
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val config = Config.fromEnv()
    val app = ServerApp(config, Clock { System.currentTimeMillis() })
    // 12.1: 에뮬레이터의 10.0.2.2가 호스트 루프백으로 오므로 외부 인터페이스에 열지 않는다
    embeddedServer(Netty, host = Config.HOST, port = config.port) {
        conditionDriveModule(app)
        app.sweeper.start(this)
        monitor.subscribe(ApplicationStopped) { app.close() }
        LoggerFactory.getLogger("cd.server").info("listening port=${config.port} partner_realtime=${config.allowPartnerRealtime}")
    }.start(wait = true)
}
