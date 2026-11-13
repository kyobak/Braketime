package com.conditiondrive.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import com.conditiondrive.companion.ui.CdTheme
import com.conditiondrive.companion.ui.ConsentContent

/** Health Connect 권한 화면이 "왜 필요한지"로 여는 화면. 동의 화면과 같은 내용을 읽기 전용으로 보여 준다 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CdTheme {
                Box(Modifier.fillMaxSize().background(CdTheme.colors.bg).systemBarsPadding()) { ConsentContent() }
            }
        }
    }
}
