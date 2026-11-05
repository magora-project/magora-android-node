package org.magora.node

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The node's identity, from a portal-generated magora-config.json.
 *
 * Non-secret fields live in app-private SharedPreferences. node_email and node_password are
 * encrypted with an AES-256-GCM key held in the Android Keystore (the key never leaves it).
 * Wi-Fi and SSH fields are Pi provisioning fields and are never stored.
 */
data class NodeConfig(
    val nodeId: String,
    val nodeName: String,
    val lat: Double,
    val lon: Double,
    val supabaseUrl: String,
    val anonKey: String,
) {
    val site: Rows.Site get() = Rows.Site(nodeId, lat, lon)

    data class Credentials(val email: String, val password: String) {
        override fun toString(): String = "Credentials(redacted)"
    }

    override fun toString(): String = "NodeConfig(nodeId=$nodeId, nodeName=$nodeName)"

    companion object {
        private const val PREFS = "magora_node_config"
        const val IMPORT_FILE = "magora-config.json"

        fun load(context: Context): NodeConfig? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = p.getString("node_id", null) ?: return null
            return NodeConfig(
                nodeId = id,
                nodeName = p.getString("node_name", "") ?: "",
                lat = java.lang.Double.longBitsToDouble(p.getLong("lat_bits", 0L)),
                lon = java.lang.Double.longBitsToDouble(p.getLong("lon_bits", 0L)),
                supabaseUrl = p.getString("supabase_url", null) ?: return null,
                anonKey = p.getString("supabase_anon_key", null) ?: return null,
            )
        }

        private fun save(context: Context, config: NodeConfig) {
            val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("node_id", config.nodeId)
                .putString("node_name", config.nodeName)
                .putLong("lat_bits", java.lang.Double.doubleToRawLongBits(config.lat))
                .putLong("lon_bits", java.lang.Double.doubleToRawLongBits(config.lon))
                .putString("supabase_url", config.supabaseUrl)
                .putString("supabase_anon_key", config.anonKey)
                .putLong("imported_at", System.currentTimeMillis())
                .commit()
            check(ok) { "config prefs write failed" }
        }

        /**
         * Refuses anything but the public key: a legacy JWT whose role is "anon", or a
         * publishable key. A service_role JWT or an sb_secret_ key is rejected.
         */
        internal fun isPublicKey(key: String): Boolean {
            if (key.startsWith("sb_publishable_")) return true
            val parts = key.split('.')
            if (parts.size != 3) return false
            return try {
                val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
                JSONObject(payload).optString("role") == "anon"
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Imports files/magora-config.json once, then deletes it whether or not the import
         * succeeded, so no plaintext credential file is left behind. Logs only node_id and
         * field names, never values of credentials or keys.
         */
        @Synchronized
        fun importIfPresent(context: Context) {
            val file = File(context.filesDir, IMPORT_FILE)
            if (!file.exists()) return
            try {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val missing = listOf("node_id", "node_email", "node_password", "supabase_url", "supabase_anon_key", "lat", "lon")
                    .filter { !json.has(it) || json.isNull(it) }
                require(missing.isEmpty()) { "missing_fields=${missing.joinToString(",")}" }
                val nodeId = UUID.fromString(json.getString("node_id")).toString()
                // Node identity is fixed once imported. Switching this phone to another node
                // means clearing app data (`pm clear org.magora.node`), which also discards the
                // old node's queue and credentials; nothing is re-pointed under a running service.
                val existing = load(context)?.nodeId
                require(existing == null || existing == nodeId) { "different_node_requires_app_data_reset" }
                val lat = json.getDouble("lat")
                val lon = json.getDouble("lon")
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "coordinates_out_of_range" }
                val url = json.getString("supabase_url").trimEnd('/')
                require(url.startsWith("https://")) { "supabase_url_not_https" }
                val email = json.getString("node_email")
                val password = json.getString("node_password")
                val anonKey = json.getString("supabase_anon_key")
                require(email.isNotBlank() && password.isNotEmpty() && anonKey.isNotBlank()) { "empty_credential_field" }
                require(isPublicKey(anonKey)) { "anon_key_rejected" }

                // Credentials are bound to nodeId: if the config write below fails, the old
                // config can no longer load them, so nothing signs in with a mismatched pair.
                CredentialStore.save(context, nodeId, Credentials(email, password))
                check(CredentialStore.load(context, nodeId) == Credentials(email, password)) { "credential_readback_failed" }
                save(context, NodeConfig(nodeId, json.optString("node_name", ""), lat, lon, url, anonKey))
                NodeState.resetIngestStatus(context)
                DailyLog.append(context, "event=config_imported node_id=$nodeId")
            } catch (error: Exception) {
                val detail = if (error is IllegalArgumentException || error is IllegalStateException) {
                    error.message ?: "invalid"
                } else {
                    error.javaClass.simpleName
                }
                DailyLog.append(context, "event=config_import_error error=$detail")
            } finally {
                val deleted = file.delete() || !file.exists()
                DailyLog.append(context, "event=config_plaintext_deleted ok=$deleted")
            }
        }
    }
}

/** Keystore-backed storage for the per-node account. */
object CredentialStore {
    private const val PREFS = "magora_node_secure"
    private const val KEY_ALIAS = "magora_node_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Sealed together with [nodeId], so they can only ever be loaded for that node. */
    fun save(context: Context, nodeId: String, credentials: NodeConfig.Credentials) {
        val plain = JSONObject().put("n", nodeId).put("e", credentials.email).put("p", credentials.password).toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .commit()
        check(ok) { "credential prefs write failed" }
    }

    /**
     * The credentials sealed for [nodeId], or null when the stored account belongs to another
     * node (e.g. mid re-provisioning) so a client can never sign in with a different node's
     * account.
     */
    fun load(context: Context, nodeId: String): NodeConfig.Credentials? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = p.getString("iv", null) ?: return null
        val ct = p.getString("ct", null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val json = JSONObject(String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8))
        if (json.optString("n", "") != nodeId) return null
        return NodeConfig.Credentials(json.getString("e"), json.getString("p"))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
