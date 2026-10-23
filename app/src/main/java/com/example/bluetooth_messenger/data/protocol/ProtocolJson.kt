package com.example.bluetooth_messenger.data.protocol

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal fun json(bytes: ByteArray): JSONObject {
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    return JSONObject(decoder.decode(ByteBuffer.wrap(bytes)).toString())
}
internal fun JSONObject.bytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)
internal fun encodeBytes(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
internal fun decodeBytes(value: String, size: Int): ByteArray {
    require(value.length <= size * 2 + 8)
    return Base64.getDecoder().decode(value).also { require(it.size == size) }
}
