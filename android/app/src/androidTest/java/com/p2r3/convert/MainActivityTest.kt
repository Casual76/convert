package com.p2r3.convert

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesNativeShellAndShowsPrimaryNavigation() {
        composeRule.onAllNodesWithText("Home")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Converti").assertIsDisplayed()
        composeRule.onNodeWithText("Impostazioni").assertIsDisplayed()
    }

    @Test
    fun importingShareIntentNavigatesToConvertAndLoadsInput() {
        val uri = tempFileUri("shared-note.md", "# Shared note")

        importIncomingIntent(
            Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("shared-note.md").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("1. Scegli i file").assertIsDisplayed()
        composeRule.onNodeWithText("shared-note.md").assertIsDisplayed()
    }

    @Test
    fun importingOpenWithIntentOnWarmStartAsksForConfirmation() {
        val firstUri = tempFileUri("first-note.txt", "first")
        val secondUri = tempFileUri("second-note.txt", "second")

        importIncomingIntent(
            Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, firstUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("first-note.txt").fetchSemanticsNodes().isNotEmpty()
        }

        importIncomingIntent(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(secondUri, "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )

        composeRule.onNodeWithText("Sostituire la selezione corrente?").assertIsDisplayed()
        composeRule.onNodeWithText("first-note.txt").assertIsDisplayed()
        composeRule.onNodeWithText("Sostituisci").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("second-note.txt").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("second-note.txt").assertIsDisplayed()
    }

    private fun importIncomingIntent(intent: Intent) {
        composeRule.runOnIdle {
            composeRule.activity.importIncomingIntent(intent)
        }
    }

    private fun tempFileUri(name: String, contents: String): Uri {
        val file = File(composeRule.activity.cacheDir, name).apply {
            parentFile?.mkdirs()
            writeText(contents)
        }
        return Uri.fromFile(file)
    }
}
