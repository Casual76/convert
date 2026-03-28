package com.p2r3.convert

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2r3.convert.ui.IncomingShareIntentParser
import com.p2r3.convert.ui.IncomingShareSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingShareIntentParserTest {

    @Test
    fun parsesActionSendStreamAsShareSheetPayload() {
        val uri = Uri.parse("file:///tmp/source.md")
        val payload = IncomingShareIntentParser.parse(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        )

        assertNotNull(payload)
        assertEquals(IncomingShareSource.SHARE_SHEET, payload?.source)
        assertEquals(listOf(uri), payload?.uris)
    }

    @Test
    fun parsesActionSendMultipleAndDeduplicatesClipData() {
        val first = Uri.parse("file:///tmp/first.txt")
        val second = Uri.parse("content://example/second.txt")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            clipData = ClipData.newRawUri("shared", first).apply {
                addItem(ClipData.Item(second))
            }
        }

        val payload = IncomingShareIntentParser.parse(intent)

        assertNotNull(payload)
        assertEquals(listOf(first, second), payload?.uris)
    }

    @Test
    fun parsesActionViewAsOpenWithPayload() {
        val uri = Uri.parse("content://example/document.pdf")
        val payload = IncomingShareIntentParser.parse(
            Intent(Intent.ACTION_VIEW).setData(uri)
        )

        assertNotNull(payload)
        assertEquals(IncomingShareSource.OPEN_WITH, payload?.source)
        assertEquals(listOf(uri), payload?.uris)
    }

    @Test
    fun ignoresUnsupportedUriSchemes() {
        val payload = IncomingShareIntentParser.parse(
            Intent(Intent.ACTION_VIEW).setData(Uri.parse("https://example.com/file.pdf"))
        )

        assertNull(payload)
    }
}
