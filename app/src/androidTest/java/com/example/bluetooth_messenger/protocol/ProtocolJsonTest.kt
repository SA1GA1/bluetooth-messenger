package com.example.bluetooth_messenger.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bluetooth_messenger.data.protocol.bytes
import com.example.bluetooth_messenger.data.protocol.json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolJsonTest {
    @Test fun deeplyNestedInputIsRejectedAndFollowingValidInputStillParses() {
        val nested = "{\"x\":" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "}"
        val failure = runCatching { json(nested.toByteArray()) }.exceptionOrNull()
        assertTrue("Expected bounded rejection, got ${failure?.javaClass?.simpleName}", failure is IllegalArgumentException)
        assertEquals("ok", json("{\"text\":\"ok\"}".toByteArray()).getString("text"))
    }

    @Test fun strictSyntaxCannotHideNestingAndQuotedBracketsRemainValidText() {
        val lenient = listOf("{'x':1}", "{x:1}", "{\"x\":/*comment*/1}", "{\"x\"=1}", "{\"x\":1} trailing")
        for (value in lenient) {
            assertNotNull("Lenient input must be rejected: $value", runCatching { json(value.toByteArray()) }.exceptionOrNull())
        }
        val text = "[".repeat(1000) + "\\\"'" + "]".repeat(1000)
        assertEquals(text, json(JSONObject().put("text", text).bytes()).getString("text"))
    }
}
