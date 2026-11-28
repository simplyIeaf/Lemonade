package com.leaf.lemonade.format

import android.os.Build

object RbxDomNative {
    const val LIBRARY = "lemonade_rbxdom"

    private val loadError: String? = try {
        System.loadLibrary(LIBRARY)
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message ?: "not found"
    }

    val available: Boolean get() = loadError == null

    val abis: List<String> get() = Build.SUPPORTED_ABIS.toList()

    val primaryAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    fun describe(): String =
        if (available) "rbx-dom native library loaded for $primaryAbi"
        else "rbx-dom native library unavailable for ${abis.joinToString()}: $loadError"

    @JvmStatic
    private external fun normalize(input: ByteArray): ByteArray

    @JvmStatic
    private external fun propertyType(className: String, propName: String): String?

    private val typeCache = HashMap<String, Int?>()

    fun serializedType(className: String, propName: String): Int? {
        if (!available) return null
        return typeCache.getOrPut("$className.$propName") {
            RbxBinaryWriter.typeByName(propertyType(className, propName) ?: return@getOrPut null)
        }
    }

    fun encode(bytes: ByteArray): ByteArray = if (available) normalize(bytes) else bytes
}
