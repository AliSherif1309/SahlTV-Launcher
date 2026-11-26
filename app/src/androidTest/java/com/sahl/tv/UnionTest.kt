package com.sahl.tv

import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sahl.tv.common.SahlUnion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnionTest {
    @Test
    fun unionPermissionIsGrantedAndProvidersRespond() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(SahlUnion.PERMISSION)
        )

        val prayers = ctx.contentResolver.query(Uri.parse("${SahlUnion.TV_UNION}/prayers"), null, null, null, null)
        assertNotNull(prayers)
        prayers!!.use { c ->
            assertEquals(10, c.count) // 5 prayers * 2 days
            assertTrue(c.moveToFirst())
            assertTrue(c.getLong(c.getColumnIndexOrThrow("time")) > 0L)
        }

        val prefs = ctx.contentResolver.query(Uri.parse("${SahlUnion.TV_UNION}/prefs"), null, null, null, null)
        assertNotNull(prefs)
        prefs!!.use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertTrue(c.getFloat(c.getColumnIndexOrThrow("scale")) >= 1f)
        }
    }
}
