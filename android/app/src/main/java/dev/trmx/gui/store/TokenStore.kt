package dev.trmx.gui.store

/*
 * Device-side secret + connection store.
 *
 * Phase 10 (hardening): the pairing token is stored in EncryptedSharedPreferences
 * (AES256-GCM master key in the Android Keystore, AES256-GCM value encryption).
 * Non-secret connection settings (installBase) stay in plain MODE_PRIVATE
 * SharedPreferences as before.
 *
 * One-time migration: if a token exists only in the legacy "trmx_store"
 * prefs (Phase 4 layout), it is copied into the encrypted store and the
 * plaintext entry is deleted on first access.
 */

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

class TokenStore(context: Context) {

    private val legacyPrefs: SharedPreferences =
        context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences = openSecurePrefs(context)

    init {
        migrateLegacyToken()
    }

    /** Data-plane base URL — loopback only in V1 (Phase 0 decision). */
    val bridgeBaseUrl: String = "http://127.0.0.1:27342"

    /** Pairing token — always read from the encrypted store. A damaged
     *  stored value reads as "no token" (re-pairing heals it) instead of
     *  throwing into app startup. */
    var token: String
        get() = runCatching { securePrefs.getString(KEY_TOKEN, "") ?: "" }
            .getOrElse { "" }
        set(value) {
            // commit(), NOT apply() (2026-10-17): apply() flushes to disk
            // asynchronously, and Samsung kills this app's process on
            // app-switch — a token written moments before the kill could
            // roll back, the next Begin then generated a FRESH token, and
            // the bridge/app pair silently diverged (round-1 ingredient).
            // The token is the one value that must be durable the moment
            // it is set; it is written rarely, so the sync cost is right.
            runCatching { securePrefs.edit().putString(KEY_TOKEN, value).commit() }
        }

    var installBase: String
        get() = legacyPrefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE
        set(value) = legacyPrefs.edit().putString(KEY_BASE, value).apply()

    val isPaired: Boolean get() = token.length >= 20

    fun clear() {
        securePrefs.edit().clear().apply()
        legacyPrefs.edit().clear().apply()
    }

    /** App-generated pairing token: 32 random bytes, url-safe base64 (~43 chars). */
    fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Moves a plaintext token from the Phase 4 layout into the encrypted
     * store. Runs once per process start; idempotent.
     */
    private fun migrateLegacyToken() {
        runCatching {
            if (securePrefs.contains(KEY_TOKEN)) return
            val legacyToken = legacyPrefs.getString(KEY_TOKEN, "") ?: ""
            if (legacyToken.isNotEmpty()) {
                securePrefs.edit().putString(KEY_TOKEN, legacyToken).commit()
                legacyPrefs.edit().remove(KEY_TOKEN).apply()
            }
        }
    }

    companion object {
        private const val LEGACY_NAME = "trmx_store"
        private const val SECURE_NAME = "trmx_store_enc"
        private const val KEY_TOKEN = "pairing_token"
        private const val KEY_BASE = "install_base"
        private const val DEFAULT_BASE =
            "https://raw.githubusercontent.com/sq987in-wq/TRMX-GUI/main/termux"

        private fun masterKey(context: Context): MasterKey =
            MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

        /**
         * Opens the encrypted store — and heals it if damaged. A corrupt
         * keyset/prefs file (partial write during a killed setup run, a
         * keystore hiccup) makes EncryptedSharedPreferences.create()
         * throw; before this heal, that exception escaped the TokenStore
         * constructor and killed the app on every launch until the user
         * cleared app data by hand (seen on device, 2026-10-16). Now:
         * wipe the damaged store and recreate it once; if even that
         * fails, fall back to plain prefs so the app always opens — a
         * lost token is healed by re-pairing; a dead app is healed by
         * nothing.
         */
        private fun openSecurePrefs(context: Context): SharedPreferences {
            fun create(): SharedPreferences = EncryptedSharedPreferences.create(
                context,
                SECURE_NAME,
                masterKey(context),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return runCatching { create() }.getOrElse {
                runCatching {
                    context.deleteSharedPreferences(SECURE_NAME)
                    create()
                }.getOrElse {
                    context.getSharedPreferences(
                        SECURE_NAME + "_plain", Context.MODE_PRIVATE)
                }
            }
        }
    }
}
