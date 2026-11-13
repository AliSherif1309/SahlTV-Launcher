package com.aly.sahltv

import android.content.Intent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aly.sahl.common.ConnectTls
import com.aly.sahl.connect.Protocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun exchange(code: Protocol.PairCode, request: JSONObject): JSONObject = ConnectTls.client(code.host, code.pin).use { s ->
        Protocol.write(s.getOutputStream(), request.put("v", 1).toString().toByteArray())
        JSONObject(Protocol.read(s.getInputStream()).toString(Charsets.UTF_8))
    }
    @Test fun pinnedTlsPairingOneUseRevocationAndDisabledEndpoint() {
        ConnectServer.start(context)
        try {
            val deadline = System.currentTimeMillis() + 15000
            while (!ConnectServer.running && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(ConnectServer.lastError, ConnectServer.running)
            val code = Protocol.PairCode(ConnectServer.pairCode())
            try { ConnectTls.client(code.host, Protocol.random()).use { fail("Wrong certificate accepted") } }
            catch (_: javax.net.ssl.SSLException) { /* expected */ }
            val q = JSONObject().put("op", "pair").put("secret", code.secret).put("name", "Emulator test")
            val pair = exchange(code, q)
            assertTrue(pair.toString(), pair.getBoolean("ok"))
            val token = pair.getString("token")
            assertFalse(exchange(code, q).getBoolean("ok"))
            assertTrue(exchange(code, JSONObject().put("op", "state").put("token", token)).getBoolean("ok"))
            assertTrue(exchange(code, JSONObject().put("op", "state").put("token", Protocol.random())).getBoolean("unauthorized"))
            ConnectServer.revoke(Protocol.hash(token.toByteArray()))
            assertTrue(exchange(code, JSONObject().put("op", "state").put("token", token)).getBoolean("unauthorized"))
            ConnectServer.stop()
            try { exchange(code, q); fail("Disabled endpoint accepted request") } catch (_: java.io.IOException) { /* expected */ }
        } finally { ConnectServer.stop() }
    }
    @Test fun unchangedFavouriteRetainsViewAndFocusAndRemovedFavouriteFallsBackToAll() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val deadline = System.currentTimeMillis() + 15000
            while (activity.loading && System.currentTimeMillis() < deadline) Thread.sleep(100)
            instrumentation.waitForIdleSync()
            lateinit var tile: View
            instrumentation.runOnMainSync {
                activity.apps = linkedMapOf(
                    "test.one" to MainActivity.App("test.one", "One", null, null, 0),
                    "test.two" to MainActivity.App("test.two", "Two", null, null, 0),
                    "test.three" to MainActivity.App("test.three", "Three", null, null, 0))
                activity.saveList("fav", listOf("test.one")); activity.saveList("recent", emptyList())
                activity.render(false)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                tile = activity.content.findViewWithTag("fav/test.one")
                assertTrue(tile.requestFocus())
                activity.saveList("recent", listOf("test.two", "test.three"))
                activity.render(true)
                assertSame(tile, activity.content.findViewWithTag<View>("fav/test.one"))
                assertTrue(tile.hasFocus())
                activity.saveList("fav", emptyList())
                activity.render(true)
                assertTrue(activity.content.findViewWithTag<View>("all/test.one").hasFocus())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
