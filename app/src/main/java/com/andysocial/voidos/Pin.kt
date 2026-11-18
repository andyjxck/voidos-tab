package com.andysocial.voidos

import androidx.compose.runtime.mutableLongStateOf
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 hashes for the VoidOS PIN. The PIN itself is never stored. */
object PinHash {
    private const val ROUNDS = 20_000

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun derive(pin: String, salt: ByteArray, rounds: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, rounds, 256)).encoded

    fun make(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "pbkdf2\$$ROUNDS\$${hex(salt)}\$${hex(derive(pin, salt, ROUNDS))}"
    }

    fun check(pin: String, stored: String): Boolean = runCatching {
        val (kind, rounds, salt, hash) = stored.split('$')
        kind == "pbkdf2" && MessageDigest.isEqual(derive(pin, unhex(salt), rounds.toInt()), unhex(hash))
    }.getOrDefault(false)
}

/**
 * Wrong-PIN lockouts, shared by every PIN pad (lock screen, way out, Settings)
 * so closing one pad and opening another doesn't reset the count. Five wrong
 * in a row waits 30 seconds; every five after that waits a minute more, up to
 * five minutes. A right PIN clears it.
 */
object PinGuard {
    private var misses = 0
    /** When the pad opens again, in System.currentTimeMillis(); 0 when it is open. */
    val waitUntil = mutableLongStateOf(0L)

    fun waiting(now: Long = System.currentTimeMillis()) = now < waitUntil.longValue

    /** Records a wrong PIN. Returns the wait it started, in ms, or 0. */
    fun miss(now: Long = System.currentTimeMillis()): Long {
        misses++
        if (misses % 5 != 0) return 0
        val wait = when (misses / 5) { 1 -> 30_000L; else -> minOf(300_000L, 60_000L * (misses / 5 - 1)) }
        waitUntil.longValue = now + wait
        return wait
    }

    fun ok() { misses = 0; waitUntil.longValue = 0 }
    fun reset() = ok()
}
