package com.github.claudecommit

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.UUID

class TerminalIconException(val code: String, message: String) : RuntimeException(message)

internal data class TerminalIconRequest(
    val id: JsonElement,
    val clear: Boolean,
    val sessionId: String,
    val owner: String,
    val token: String?,
    val revision: Long?,
    val ttlSeconds: Int?,
    val asset: TerminalIconAsset?,
    val staticPlayback: Boolean,
)

/** Strict, bounded wire decoding. Never let Gson coerce strings into booleans/numbers. */
internal object TerminalIconProtocol {
    const val MAX_BODY_BYTES = 1024 * 1024

    fun parse(body: String): TerminalIconRequest {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) fail("Request is too large")
        try {
            // Preflight streaming validation rejects duplicate keys, deep nesting, and lenient JSON.
            JsonReader(StringReader(body)).use { reader ->
                reader.strictness = com.google.gson.Strictness.STRICT
                scan(reader, 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) fail("Trailing JSON data")
            }
            val root = obj(JsonParser.parseString(body), "request")
            keys(root, setOf("id", "method", "params"))
            val id = root.get("id") ?: fail("id is required")
            if (!id.isJsonPrimitive || !(id.asJsonPrimitive.isString || id.asJsonPrimitive.isNumber) || id.toString().length > 128) {
                fail("id must be a short string or number")
            }
            val method = string(root, "method", 64)
            if (method != "surface.icon.set" && method != "surface.icon.clear") fail("Unknown method")
            val p = obj(root.get("params"), "params")
            keys(p, setOf("terminal_session_id", "owner_id", "lease_token", "revision", "ttl_seconds", "asset", "playback"))
            val session = string(p, "terminal_session_id", 36)
            if (runCatching { UUID.fromString(session).toString() }.getOrNull() != session) fail("Expected a canonical terminal session UUID")
            val owner = string(p, "owner_id", 128)
            if (!owner.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) fail("Invalid owner_id")
            val token = if (p.has("lease_token")) string(p, "lease_token", 128) else null
            val revision = if (p.has("revision")) integer(p, "revision", 1, Long.MAX_VALUE) else null
            if ((token == null) != (revision == null)) fail("lease_token and revision must be supplied together")
            val ttl = if (p.has("ttl_seconds")) integer(p, "ttl_seconds", 1, 86400).toInt() else null
            val playback = if (p.has("playback")) string(p, "playback", 16) else "animated"
            if (playback !in setOf("animated", "static")) fail("Invalid playback")
            val asset = if (p.has("asset")) parseAsset(obj(p.get("asset"), "asset")) else null
            val clear = method == "surface.icon.clear"
            if (clear && (asset != null || ttl != null || p.has("playback") || token == null)) fail("clear requires a lease and accepts no asset/playback/TTL")
            if (!clear && asset == null && (token == null || p.has("playback"))) fail("Heartbeat requires a lease and accepts no playback")
            return TerminalIconRequest(id.deepCopy(), clear, session, owner, token, revision, ttl, asset, playback == "static")
        } catch (e: TerminalIconException) {
            throw e
        } catch (_: Exception) {
            fail("Malformed JSON request")
        }
    }

    private fun parseAsset(o: JsonObject): TerminalIconAsset {
        keys(o, setOf("frames", "loop", "static_frame"))
        val array = o.get("frames")?.takeIf { it.isJsonArray }?.asJsonArray ?: fail("frames must be an array")
        if (array.size() !in 1..64) fail("Expected 1 to 64 frames")
        val frames = array.map {
            val f = obj(it, "frame")
            keys(f, setOf("svg", "duration_ms"))
            TerminalIconFrame(string(f, "svg", 16384), integer(f, "duration_ms", 50, 5000).toInt())
        }
        val loop = o.get("loop")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: fail("loop must be boolean")
        val static = if (o.has("static_frame")) integer(o, "static_frame", 0, (frames.size - 1).toLong()).toInt() else 0
        return TerminalIconAsset(frames, loop, static)
    }

    private fun scan(r: JsonReader, depth: Int) {
        if (depth > 12) fail("JSON nesting is too deep")
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                val names = HashSet<String>()
                while (r.hasNext()) {
                    if (!names.add(r.nextName())) fail("Duplicate JSON key")
                    scan(r, depth + 1)
                }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                var count = 0
                while (r.hasNext()) {
                    if (++count > 64) fail("JSON array is too large")
                    scan(r, depth + 1)
                }
                r.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
            JsonToken.BOOLEAN -> r.nextBoolean()
            JsonToken.NULL -> r.nextNull()
            else -> fail("Invalid JSON")
        }
    }

    private fun obj(e: JsonElement?, name: String): JsonObject = e?.takeIf { it.isJsonObject }?.asJsonObject ?: fail("$name must be an object")
    private fun keys(o: JsonObject, allowed: Set<String>) {
        if (o.keySet().any { it !in allowed }) fail("Unknown field")
    }
    private fun string(o: JsonObject, key: String, max: Int): String {
        val p = o.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        if (p == null || !p.isString) fail("$key must be a string")
        return p.asString.also { if (it.isEmpty() || it.length > max) fail("Invalid $key length") }
    }
    private fun integer(o: JsonObject, key: String, min: Long, max: Long): Long {
        val p = o.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        if (p == null || !p.isNumber || !p.asString.matches(Regex("0|[1-9][0-9]*"))) fail("$key must be an integer")
        val n = p.asString.toLongOrNull() ?: fail("Invalid $key")
        if (n !in min..max) fail("$key is out of range")
        return n
    }
    private fun fail(message: String): Nothing = throw TerminalIconException("invalid_request", message)
}
