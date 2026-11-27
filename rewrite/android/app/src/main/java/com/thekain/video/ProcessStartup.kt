package com.thekain.video

import java.io.FileInputStream

/** Secondary/isolated Services must not initialize Room, recovery or WorkManager application work. */
object ProcessStartup {
    fun isOwner(packageName: String): Boolean = try {
        FileInputStream("/proc/self/cmdline").use { input ->
            val bytes = ByteArray(512)
            val count = input.read(bytes)
            count > 0 && String(bytes, 0, count, Charsets.US_ASCII).substringBefore('\u0000') == packageName
        }
    } catch (_: Exception) { false }
}
