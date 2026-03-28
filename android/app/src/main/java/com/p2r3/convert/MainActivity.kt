package com.p2r3.convert

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.p2r3.convert.ui.ConvertRoot
import com.p2r3.convert.ui.IncomingShareIntentParser
import com.p2r3.convert.ui.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        importIncomingIntent(intent)

        setContent {
            ConvertRoot(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importIncomingIntent(intent)
    }

    fun importIncomingIntent(intent: Intent?) {
        IncomingShareIntentParser.parse(intent)?.let(viewModel::handleIncomingPayload)
    }
}
