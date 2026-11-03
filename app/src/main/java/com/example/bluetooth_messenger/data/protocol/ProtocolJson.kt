package com.example.bluetooth_messenger.data.protocol

import android.util.JsonReader
import android.util.JsonToken
import com.example.bluetooth_messenger.domain.model.Limits
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONObject

internal fun json(bytes: ByteArray): JSONObject {
    require(bytes.size <= Limits.PACKET_BYTES)
    val decoder =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
    JsonReader(StringReader(text)).use { reader ->
        reader.isLenient = false
        require(reader.peek() == JsonToken.BEGIN_OBJECT)
        var depth = 0
        while (true) {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    require(++depth <= 16)
                    reader.beginObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    require(++depth <= 16)
                    reader.beginArray()
                }
                JsonToken.END_OBJECT -> {
                    reader.endObject()
                    depth--
                }
                JsonToken.END_ARRAY -> {
                    reader.endArray()
                    depth--
                }
                JsonToken.NAME -> reader.nextName()
                JsonToken.STRING,
                JsonToken.NUMBER -> reader.nextString()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> reader.nextNull()
                JsonToken.END_DOCUMENT -> break
            }
        }
    }
    return JSONObject(text)
}

internal fun JSONObject.bytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)

internal fun encodeBytes(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

internal fun decodeBytes(value: String, size: Int): ByteArray {
    require(value.length <= size * 2 + 8)
    return Base64.getDecoder().decode(value).also { require(it.size == size) }
}
