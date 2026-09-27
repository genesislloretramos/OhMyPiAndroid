package com.omp.terminal.web

import java.io.File
import java.security.SecureRandom

/**
 * The per-install token, and the one file it lives in.
 *
 * **Generated once, at the first start of the service, and never again.** [load] reads it if it is
 * there and mints one if it is not, so a user who restarts the app, whose phone reboots, or whose
 * service is killed and started again by the system all keep the same token — and therefore keep
 * the same bookmark, the same notification text, and the same URL in `web` output. A token that
 * changed on every start would be a token nobody could write down.
 *
 * **It is in the app's private storage**, which on Android is `filesDir` and is readable by this
 * app's uid and nothing else. It is deliberately *not* in the conversation container: that folder
 * is under `Documents/omp`, which any app holding the all-files grant can read, and a credential
 * that a third party can read is not a credential.
 *
 * **Forty-three characters of base32 from [SecureRandom].** Twenty-six bytes of entropy is a
 * number no one guesses and no one brute-forces over a loopback socket; the alphabet is Crockford
 * base32, which is case-insensitive on the way in, so a user typing it out of a notification does
 * not get it wrong over `l` versus `I` or `0` versus `O`, and has no `-` to mistype either. The
 * token is written with the file's own permissions, in a directory only this app can enter.
 *
 * The KDoc on [TokenGate] is where this token's threat model is written down, because that is the
 * class whose behaviour depends on it; this one only decides where it comes from.
 */
class AccessToken(
    /** The file the token is kept in. Its directory must already exist. */
    private val file: File,
) {

    /**
     * The token for this install, creating it if this is the first time.
     *
     * @throws java.io.IOException when the file cannot be read or written. That is deliberately
     *   not swallowed: a server that started with a token it could not persist would hand out a
     *   different one on every restart, and a user who could not be told why would have no way to
     *   tell which.
     */
    fun load(): String {
        if (file.isFile) {
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isNotEmpty()) return text
        }
        val minted = mint()
        // Through a scratch file and a rename, for the reason every other file this app writes
        // atomically is: a token half-written is a token nobody has, and the service would then be
        // running with a credential that is not on disk.
        val scratch = File(file.parentFile, file.name + SCRATCH)
        scratch.writeText(minted, Charsets.UTF_8)
        if (!scratch.renameTo(file)) {
            // A rename that will not happen — a file system the phone's storage does not do it on.
            // The direct write is the same bytes in the same place; it is only not atomic.
            file.writeText(minted, Charsets.UTF_8)
            scratch.delete()
        }
        return minted
    }

    /** Twenty-six bytes of [SecureRandom], as 43 case-insensitive base32 characters. */
    private fun mint(): String {
        val bytes = ByteArray(ENTROPY_BYTES)
        SecureRandom().nextBytes(bytes)
        val out = StringBuilder(ALPHABET.length * 2)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    private companion object {
        /**
         * Crockford base32: no `I`, `L`, `O` or `U`, so a token read off a notification cannot be
         * mistyped into a different token. Digits come first, which puts the digits this alphabet
         * *does* have at the front of a keyboard row a person is looking at.
         */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** 26 bytes is 208 bits, and 43 characters of base32 carry 215. */
        const val ENTROPY_BYTES = 26

        const val SCRATCH = ".tmp"
    }
}
