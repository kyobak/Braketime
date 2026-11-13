package com.conditiondrive.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.conditiondrive.companion.ui.CdTheme
import com.conditiondrive.companion.ui.CompanionRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val controller get() = (application as CompanionApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 화면 회전으로 다시 만들어질 때 같은 링크를 다시 채우지 않는다
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            val ui by controller.ui.collectAsStateWithLifecycle()
            CdTheme { CompanionRoot(controller, ui) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { controller.onForeground() }
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let(controller::onDeepLink)
    }
}
