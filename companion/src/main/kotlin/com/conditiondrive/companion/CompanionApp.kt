package com.conditiondrive.companion

import android.app.Application
import android.os.Build
import com.conditiondrive.companion.pairing.PairingService
import com.conditiondrive.companion.simulator.GlucoseSimulator
import com.conditiondrive.companion.simulator.SimulatorService
import com.conditiondrive.companion.sleep.AndroidWorkScheduler
import com.conditiondrive.companion.sleep.HealthConnectGateway
import com.conditiondrive.companion.storage.AesGcmTokenCipher
import com.conditiondrive.companion.storage.DataStoreCompanionStore
import com.conditiondrive.companion.storage.KeystoreKey
import com.conditiondrive.companion.storage.TokenVault
import com.conditiondrive.companion.upload.OkHttpCompanionApi
import com.conditiondrive.companion.upload.ServerAddress
import com.conditiondrive.companion.upload.UploadCoordinator
import com.conditiondrive.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.ZoneId

/** 프로세스당 하나. 화면, 서비스, 워커가 같은 컨트롤러를 본다 */
class CompanionApp : Application() {
    lateinit var controller: CompanionController
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = Clock { System.currentTimeMillis() }
        val store = DataStoreCompanionStore(this)
        val tokens = TokenVault(store, AesGcmTokenCipher(KeystoreKey::getOrCreate))
        val fallbackServer = ServerAddress.default(isEmulator())
        val api = OkHttpCompanionApi(baseUrl = { store.read().serverUrl ?: fallbackServer })
        val pairing = PairingService(
            api = api,
            store = store,
            tokens = tokens,
            clock = clock,
            deviceName = { Build.MODEL.orEmpty() },
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
        )
        // 컨트롤러와 전송기가 서로를 부른다. 콜백은 런타임에만 불리므로 나중에 채워도 안전하다
        lateinit var created: CompanionController
        val uploads = UploadCoordinator(
            api = api,
            store = store,
            tokens = tokens,
            clock = clock,
            zone = { ZoneId.systemDefault() },
            onUnauthorized = { created.onUnauthorized() },
            onConsentRequired = { created.onConsentRequired() },
        )
        created = CompanionController(
            store = store,
            pairing = pairing,
            uploads = uploads,
            simulator = GlucoseSimulator(),
            health = HealthConnectGateway(this),
            work = AndroidWorkScheduler(this),
            services = { SimulatorService.start(this) },
            clock = clock,
            scope = scope,
            defaultServerUrl = fallbackServer,
        )
        controller = created
        controller.start()
    }

    private fun isEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.FINGERPRINT.startsWith("generic")
}
