package com.bod3.app.recipe

/**
 * Typed access to a parsed JSON object (from [com.bod3.app.profile.MiniJson]) that collects every
 * problem with its path (`recipes[2].start.request: missing`) instead of stopping at the first one.
 * Shared by the actuator and coding recipe codecs.
 */
class JsonFields(val errors: MutableList<String>) {

    fun path(base: String, key: String) = if (base.isEmpty()) key else "$base.$key"

    fun string(map: Map<*, *>, key: String, base: String, required: Boolean = true): String? = when (val v = map[key]) {
        null -> { if (required) errors += "${path(base, key)}: missing"; null }
        is String -> v.trim().also { if (required && it.isEmpty()) errors += "${path(base, key)}: must not be empty" }
        else -> { errors += "${path(base, key)}: must be text"; null }
    }

    fun number(map: Map<*, *>, key: String, base: String, required: Boolean = true): Double? = when (val v = map[key]) {
        null -> { if (required) errors += "${path(base, key)}: missing"; null }
        is Number -> v.toDouble()
        else -> { errors += "${path(base, key)}: must be a number"; null }
    }

    fun int(map: Map<*, *>, key: String, base: String, required: Boolean = true): Int? =
        number(map, key, base, required)?.let { d ->
            if (d % 1.0 != 0.0) { errors += "${path(base, key)}: must be a whole number"; null } else d.toInt()
        }

    fun boolean(map: Map<*, *>, key: String, base: String): Boolean? = when (val v = map[key]) {
        null -> null
        is Boolean -> v
        else -> { errors += "${path(base, key)}: must be true or false"; null }
    }

    fun list(map: Map<*, *>, key: String, base: String): List<Any?> = when (val v = map[key]) {
        null -> emptyList()
        is List<*> -> v
        else -> { errors += "${path(base, key)}: must be a list"; emptyList() }
    }

    fun strings(map: Map<*, *>, key: String, base: String): List<String> =
        list(map, key, base).mapIndexedNotNull { i, v ->
            (v as? String)?.trim() ?: run { errors += "${path(base, key)}[$i]: must be text"; null }
        }

    fun obj(map: Map<*, *>, key: String, base: String, required: Boolean = true): Map<*, *>? = when (val v = map[key]) {
        null -> { if (required) errors += "${path(base, key)}: missing"; null }
        is Map<*, *> -> v
        else -> { errors += "${path(base, key)}: must be an object"; null }
    }

    fun asObject(item: Any?, path: String): Map<*, *>? = item as? Map<*, *> ?: run { errors += "$path: must be an object"; null }

    /** A hex byte string such as `2F 0A 01 03 01` or `2F0A010301`. */
    fun hex(map: Map<*, *>, key: String, base: String, required: Boolean = true): List<Int>? {
        val text = string(map, key, base, required) ?: return null
        val bytes = Hex.parse(text)
        if (bytes == null) errors += "${path(base, key)}: \"$text\" is not a hex byte string like \"2F 0A 01 03\""
        return bytes
    }

    /** A hex number such as `03`, `0x03` or `F187` (also accepts a JSON number). */
    fun hexInt(map: Map<*, *>, key: String, base: String, required: Boolean = true, max: Int = 0xFFFF): Int? {
        val value = when (val v = map[key]) {
            null -> { if (required) errors += "${path(base, key)}: missing"; return null }
            is Number -> v.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
            is String -> v.trim().removePrefix("0x").removePrefix("0X").takeIf { it.isNotEmpty() && it.length <= 8 }?.toIntOrNull(16)
            else -> null
        }
        if (value == null || value !in 0..max) {
            errors += "${path(base, key)}: must be a hex number up to %X".format(max)
            return null
        }
        return value
    }
}

object Hex {
    /** Parses `2F 0A 01`, `2F0A01`, `2f:0a:01`; null when not an even number of hex digits. */
    fun parse(text: String): List<Int>? {
        val clean = text.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (clean.isEmpty() || clean.length % 2 != 0 || !clean.all { it.isHexDigit() }) return null
        return clean.chunked(2).map { it.toInt(16) }
    }

    fun format(bytes: List<Int>, separator: String = " "): String = bytes.joinToString(separator) { "%02X".format(it) }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
