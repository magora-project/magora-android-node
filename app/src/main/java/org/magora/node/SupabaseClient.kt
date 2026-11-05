package org.magora.node

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Supabase access as the node's own per-node user, per docs/node-contract.md:
 * password grant at /auth/v1/token, the user access token for REST writes, the anon key
 * for the whitelist read. Tokens are held in memory only and are never logged.
 */
class SupabaseClient(
    private val config: NodeConfig,
    private val credentials: () -> NodeConfig.Credentials?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class AuthException(val status: Int) : IOException("auth failed: HTTP $status")

    private var accessToken: String? = null
    private var expiresAtMillis = 0L

    @Synchronized
    fun hasFreshToken(): Boolean = accessToken != null && clock() < expiresAtMillis - REFRESH_MARGIN_MILLIS

    /** Signs in when there is no token or it is within [REFRESH_MARGIN_MILLIS] of expiry. */
    @Synchronized
    fun ensureToken() {
        if (!hasFreshToken()) signIn()
    }

    /** POST {url}/auth/v1/token?grant_type=password. Throws [AuthException] on a non-2xx reply. */
    @Synchronized
    fun signIn() {
        accessToken = null
        val creds = credentials() ?: throw AuthException(0)
        val body = JSONObject().put("email", creds.email).put("password", creds.password).toString()
        val conn = open("${config.supabaseUrl}/auth/v1/token?grant_type=password", "POST")
        conn.setRequestProperty("apikey", config.anonKey)
        conn.setRequestProperty("Content-Type", "application/json")
        val status = send(conn, body)
        try {
            if (status !in 200..299) throw AuthException(status)
            val reply = JSONObject(readBody(conn))
            val token = reply.optString("access_token", "")
            if (token.isEmpty()) throw AuthException(status)
            val expiresIn = reply.optLong("expires_in", 3600L)
            accessToken = token
            expiresAtMillis = clock() + expiresIn * 1000L
        } finally {
            conn.disconnect()
        }
    }

    @Synchronized
    fun invalidateToken() {
        accessToken = null
    }

    /** POST /rest/v1/<table> with the node JWT; returns the HTTP status. Transport errors throw. */
    fun postRow(table: String, json: String): Int {
        val token = synchronized(this) { accessToken } ?: throw AuthException(401)
        val conn = open("${config.supabaseUrl}/rest/v1/$table", "POST")
        conn.setRequestProperty("apikey", config.anonKey)
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Prefer", "return=minimal")
        return try {
            val status = send(conn, json)
            drain(conn)
            status
        } finally {
            conn.disconnect()
        }
    }

    /**
     * GET /rest/v1/nodes?id=eq.<node_id>&select=species_whitelist with the anon key.
     * Returns the array (possibly empty), or null when the row or column is absent/null.
     * Throws on transport errors and non-JSON replies, like detect.py's r.json().
     */
    fun fetchWhitelist(): List<String>? {
        val id = URLEncoder.encode("eq.${config.nodeId}", "UTF-8")
        val conn = open("${config.supabaseUrl}/rest/v1/nodes?id=$id&select=species_whitelist", "GET")
        conn.setRequestProperty("apikey", config.anonKey)
        conn.setRequestProperty("Authorization", "Bearer ${config.anonKey}")
        try {
            conn.connect()
            conn.responseCode
            val rows = JSONArray(readBody(conn))
            if (rows.length() == 0) return null
            val row = rows.getJSONObject(0)
            if (!row.has("species_whitelist") || row.isNull("species_whitelist")) return null
            val array = row.getJSONArray("species_whitelist")
            return List(array.length()) { array.getString(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            useCaches = false
            instanceFollowRedirects = false
        }

    private fun send(conn: HttpURLConnection, body: String): Int {
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return conn.responseCode
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun drain(conn: HttpURLConnection) {
        try {
            readBody(conn)
        } catch (_: IOException) {
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val REFRESH_MARGIN_MILLIS = 5L * 60 * 1000
    }
}
