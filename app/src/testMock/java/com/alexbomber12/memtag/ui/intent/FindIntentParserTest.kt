package com.alexbomber12.memtag.ui.intent

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FindIntentParserTest {
    @Test
    fun extractsEpcFromExtras() {
        val intent = Intent().putExtra("expected_epc", "E2000017221101441890ABCE")

        val result = extractFindEpc(intent)

        assertEquals("E2000017221101441890ABCE", result)
    }

    @Test
    fun extractsEpcFromUri() {
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("memtag://find?EPC=E2000017221101441890ABCD"),
            )

        val result = extractFindEpc(intent)

        assertEquals("E2000017221101441890ABCD", result)
    }

    @Test
    fun missingEpcReturnsNull() {
        val result = extractFindEpc(Intent())

        assertNull(result)
    }
}
