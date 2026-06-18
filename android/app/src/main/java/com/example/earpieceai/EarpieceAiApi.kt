package com.example.earpieceai

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class UnauthorizedException(message: String) : Exception(message)

data class UsageTier(
    val tokens: Int,
    val duration: Double,
    val wordCount: Int,
    val transcriptions: Int
)

data class UsageBreakdown(
    val daily: UsageTier,
    val monthly: UsageTier,
    val lifetime: UsageTier
)

data class UserProfile(
    val id: Int,
    val email: String,
    val isSubscribed: Boolean,
    val createdAt: String,
    val lastTokenRequest: String,
    val usage: UsageBreakdown,
    val durationRemaining: Double?
)

data class UsageTotals(
    val totalDuration: Double,
    val totalTranscriptions: Int,
    val totalWordCount: Int,
    val durationRemaining: Double?
)

class EarpieceAiApi(private val context: Context) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "EarpieceAiApi"
        private const val BASE_URL = "https://earpieceai.ai/api"
        private const val PREF_FILE_NAME = "earpieceai_secure_prefs"
        private const val PREF_JWT_TOKEN = "jwt_token"
        private const val PREF_DEEPINFRA_TOKEN = "deepinfra_token"
        private const val PREF_TOKEN_EXPIRY = "token_expiry"
        private const val PREF_IS_SUBSCRIBED = "is_subscribed"
        private const val PREF_PENDING_DURATION = "pending_duration"
        private const val PREF_PENDING_WORDS = "pending_words"
        private const val PREF_PENDING_TRANSCRIPTIONS = "pending_transcriptions"
        private const val PREF_LOCAL_TRANSCRIPTION_TOTAL = "local_transcription_total"
        private const val PREF_DURATION_REMAINING = "duration_remaining"
    }

    private val pendingLock = Any()

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREF_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
            // Fallback to standard prefs if encryption fails (e.g. on some older devices or during dev)
            // Ideally should handle this more gracefully or warn user
            context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    suspend fun login(email: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            val fingerprint = getAndroidFingerprint()
            val json = JSONObject().apply {
                put("email", email)
                put("password", password)
                put("fingerprint_android", fingerprint)
            }

            val request = Request.Builder()
                .url("$BASE_URL/login")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val token = json.getString("token")
                    val isSubscribed = parseIsSubscribed(json.opt("is_subscribed"))
                    
                    prefs.edit().apply {
                        putString(PREF_JWT_TOKEN, token)
                        putBoolean(PREF_IS_SUBSCRIBED, isSubscribed)
                    }.apply()
                    Result.success(token)
                } else {
                    Result.failure(Exception("Login failed: ${response.code} ${response.message}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun signup(email: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            val fingerprint = getAndroidFingerprint()
            val json = JSONObject().apply {
                put("email", email)
                put("password", password)
                put("fingerprint_android", fingerprint)
            }

            val request = Request.Builder()
                .url("$BASE_URL/signup")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val token = json.getString("token")
                    val isSubscribed = parseIsSubscribed(json.opt("is_subscribed"))

                    prefs.edit().apply {
                        putString(PREF_JWT_TOKEN, token)
                        putBoolean(PREF_IS_SUBSCRIBED, isSubscribed)
                    }.apply()
                    Result.success(token)
                } else {
                    Result.failure(Exception("Signup failed: ${response.code} ${response.message}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getDeepInfraToken(forceRefresh: Boolean = false): Result<String> {
        val jwtToken = prefs.getString(PREF_JWT_TOKEN, null) ?: return Result.failure(Exception("Not logged in"))
        
        // Reuse local token if it's still valid for at least 15 minutes, unless forceRefresh is true
        val existingToken = prefs.getString(PREF_DEEPINFRA_TOKEN, null)
        val expiryStr = prefs.getString(PREF_TOKEN_EXPIRY, null)
        
        if (!forceRefresh && existingToken != null && expiryStr != null) {
            try {
                // Format example: 2026-01-30T12:00:00Z (ISO_OFFSET_DATE_TIME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val expiry = java.time.OffsetDateTime.parse(expiryStr)
                    val now = java.time.OffsetDateTime.now()
                    if (now.plusMinutes(15).isBefore(expiry)) {
                        Log.d(TAG, "Reusing cached DeepInfra token")
                        return Result.success(existingToken)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse expiry: $expiryStr")
            }
        }

        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Fetching new DeepInfra token from EarpieceAi API (forceRefresh=$forceRefresh)")
            val url = if (forceRefresh) "$BASE_URL/token?refresh=true" else "$BASE_URL/token"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $jwtToken")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val token = json.getString("token")
                    
                    Log.d(TAG, "Server returned token: '$token'")
                    
                    // expiry might be missing, use optString to avoid crash
                    val newExpiry = json.optString("expiry", "")
                    
                    prefs.edit().apply {
                        putString(PREF_DEEPINFRA_TOKEN, token)
                        if (newExpiry.isNotEmpty()) {
                            putString(PREF_TOKEN_EXPIRY, newExpiry)
                        } else {
                            // If server didn't send expiry, rely on server checks next time
                            remove(PREF_TOKEN_EXPIRY)
                        }
                        // If we successfully got a token, the user must be subscribed
                        putBoolean(PREF_IS_SUBSCRIBED, true)
                    }.apply()
                    Result.success(token)
                } else {
                    if (response.code == 403) {
                        prefs.edit().putBoolean(PREF_IS_SUBSCRIBED, false).apply()
                    }
                    Result.failure(Exception("Failed to get token: ${response.code} ${response.message}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun getStoredDeepInfraToken(): String? {
        return prefs.getString(PREF_DEEPINFRA_TOKEN, null)
    }

    fun isLoggedIn(): Boolean {
        return prefs.contains(PREF_JWT_TOKEN)
    }

    fun isSubscribed(): Boolean {
        return prefs.getBoolean(PREF_IS_SUBSCRIBED, false)
    }

    fun logout() {
        prefs.edit().apply {
            remove(PREF_JWT_TOKEN)
            remove(PREF_DEEPINFRA_TOKEN)
            remove(PREF_TOKEN_EXPIRY)
            remove(PREF_IS_SUBSCRIBED)
            remove(PREF_PENDING_DURATION)
            remove(PREF_PENDING_WORDS)
            remove(PREF_PENDING_TRANSCRIPTIONS)
            remove(PREF_LOCAL_TRANSCRIPTION_TOTAL)
        }.apply()
    }

    suspend fun trackUsage(durationSeconds: Double, wordCount: Int): Result<UsageTotals?> {
        val safeDuration = durationSeconds.coerceAtLeast(0.0)
        val safeWords = wordCount.coerceAtLeast(0)

        incrementLocalTranscriptionTotal()

        val immediateResult = reportUsage(
            durationSeconds = safeDuration,
            wordCount = safeWords,
            transcriptionCount = 1
        )

        if (immediateResult.isSuccess) {
            return immediateResult
        }

        appendPendingUsage(
            duration = safeDuration,
            words = safeWords,
            transcriptions = 1
        )

        return immediateResult
    }

    suspend fun flushPendingUsage(): Result<UsageTotals?> {
        val snapshot = synchronized(pendingLock) { readPendingUsage() }
        Log.d(
            TAG,
            "Pending usage snapshot: duration=${snapshot.duration}, words=${snapshot.words}, transcriptions=${snapshot.transcriptions}"
        )
        if (snapshot.duration <= 0.0 && snapshot.words <= 0 && snapshot.transcriptions <= 0) {
            Log.d(TAG, "No pending usage to flush")
            return Result.success(null)
        }

        val result = reportUsage(
            durationSeconds = snapshot.duration,
            wordCount = snapshot.words,
            transcriptionCount = snapshot.transcriptions
        )

        if (result.isSuccess) {
            clearPendingUsage()
        }

        return result
    }

    private suspend fun reportUsage(
        durationSeconds: Double,
        wordCount: Int,
        transcriptionCount: Int
    ): Result<UsageTotals> {
        val jwtToken = prefs.getString(PREF_JWT_TOKEN, null)
            ?: return Result.failure(UnauthorizedException("Not logged in"))

        return withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("duration", durationSeconds)
                put("word_count", wordCount)
                put("transcription_count", transcriptionCount)
            }

            val request = Request.Builder()
                .url("$BASE_URL/usage/report")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $jwtToken")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (response.code == 401 || response.code == 403) {
                    return@withContext Result.failure(UnauthorizedException("Session expired"))
                }

                if (!response.isSuccessful || body.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Usage report failed: ${response.code} ${response.message}"))
                }

                val json = JSONObject(body)
                val durationRemaining = json.optDouble("duration_remaining", Double.NaN).let { value ->
                    if (value.isNaN()) null else value
                }
                if (durationRemaining != null) {
                    setDurationRemainingSeconds(durationRemaining)
                }
                val totals = json.optJSONObject("totals")
                val parsed = UsageTotals(
                    totalDuration = totals?.optDouble("total_duration") ?: 0.0,
                    totalTranscriptions = totals?.optInt("total_transcriptions") ?: 0,
                    totalWordCount = totals?.optInt("total_word_count") ?: 0,
                    durationRemaining = durationRemaining
                )
                Result.success(parsed)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private data class PendingUsage(
        val duration: Double,
        val words: Int,
        val transcriptions: Int
    )

    private fun readPendingUsage(): PendingUsage {
        val durationBits = prefs.getLong(PREF_PENDING_DURATION, 0L)
        val duration = java.lang.Double.longBitsToDouble(durationBits)
        val words = prefs.getInt(PREF_PENDING_WORDS, 0)
        val transcriptions = prefs.getInt(PREF_PENDING_TRANSCRIPTIONS, 0)
        return PendingUsage(duration, words, transcriptions)
    }

    private fun writePendingUsage(pending: PendingUsage) {
        prefs.edit().apply {
            putLong(PREF_PENDING_DURATION, java.lang.Double.doubleToRawLongBits(pending.duration))
            putInt(PREF_PENDING_WORDS, pending.words)
            putInt(PREF_PENDING_TRANSCRIPTIONS, pending.transcriptions)
        }.apply()
    }

    private fun appendPendingUsage(duration: Double, words: Int, transcriptions: Int) {
        synchronized(pendingLock) {
            val current = readPendingUsage()
            writePendingUsage(
                PendingUsage(
                    duration = current.duration + duration,
                    words = current.words + words,
                    transcriptions = current.transcriptions + transcriptions
                )
            )
            val updated = readPendingUsage()
            Log.d(
                TAG,
                "Pending usage queued: duration=${updated.duration}, words=${updated.words}, transcriptions=${updated.transcriptions}"
            )
        }
    }

    private fun clearPendingUsage() {
        synchronized(pendingLock) {
            writePendingUsage(PendingUsage(duration = 0.0, words = 0, transcriptions = 0))
        }
    }

    private fun incrementLocalTranscriptionTotal() {
        val current = prefs.getLong(PREF_LOCAL_TRANSCRIPTION_TOTAL, 0L)
        prefs.edit().putLong(PREF_LOCAL_TRANSCRIPTION_TOTAL, current + 1L).apply()
    }

    fun getDurationRemainingSeconds(): Double? {
        if (!prefs.contains(PREF_DURATION_REMAINING)) {
            return null
        }
        val bits = prefs.getLong(PREF_DURATION_REMAINING, 0L)
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun setDurationRemainingSeconds(value: Double?) {
        if (value == null) {
            prefs.edit().remove(PREF_DURATION_REMAINING).apply()
            return
        }
        prefs.edit()
            .putLong(PREF_DURATION_REMAINING, java.lang.Double.doubleToRawLongBits(value))
            .apply()
    }

    private fun parseUsageBreakdown(usage: JSONObject?): UsageBreakdown {
        if (usage == null) {
            return UsageBreakdown(
                daily = UsageTier(0, 0.0, 0, 0),
                monthly = UsageTier(0, 0.0, 0, 0),
                lifetime = UsageTier(0, 0.0, 0, 0)
            )
        }

        val dailyObj = usage.optJSONObject("daily")
        val monthlyObj = usage.optJSONObject("monthly")
        val lifetimeObj = usage.optJSONObject("lifetime")

        if (dailyObj != null || monthlyObj != null || lifetimeObj != null) {
            return UsageBreakdown(
                daily = parseUsageTier(dailyObj),
                monthly = parseUsageTier(monthlyObj),
                lifetime = parseUsageTier(lifetimeObj)
            )
        }

        val legacyDaily = usage.optInt("daily")
        val legacyMonthly = usage.optInt("monthly")
        val legacyTotal = usage.optInt("total")

        return UsageBreakdown(
            daily = UsageTier(0, 0.0, 0, legacyDaily),
            monthly = UsageTier(0, 0.0, 0, legacyMonthly),
            lifetime = UsageTier(0, 0.0, 0, legacyTotal)
        )
    }

    private fun parseUsageTier(tier: JSONObject?): UsageTier {
        if (tier == null) {
            return UsageTier(0, 0.0, 0, 0)
        }
        return UsageTier(
            tokens = tier.optInt("tokens"),
            duration = tier.optDouble("duration"),
            wordCount = tier.optInt("word_count"),
            transcriptions = tier.optInt("transcriptions")
        )
    }

    suspend fun validate(): Result<UserProfile> {
        val jwtToken = prefs.getString(PREF_JWT_TOKEN, null)
            ?: return Result.failure(UnauthorizedException("Not logged in"))

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL/validate")
                .get()
                .addHeader("Authorization", "Bearer $jwtToken")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    val json = JSONObject(body)
                    val user = json.optJSONObject("user")
                        ?: return@withContext Result.failure(Exception("Validate failed: missing user object"))
                    val durationRemaining = user.optDouble("duration_remaining", Double.NaN).let { value ->
                        if (value.isNaN()) null else value
                    }
                    setDurationRemainingSeconds(durationRemaining)
                    val profile = UserProfile(
                        id = user.optInt("id"),
                        email = user.optString("email"),
                        isSubscribed = parseIsSubscribed(user.opt("is_subscribed")),
                        createdAt = user.optString("created_at"),
                        lastTokenRequest = user.optString("last_token_request"),
                        usage = parseUsageBreakdown(user.optJSONObject("usage")),
                        durationRemaining = durationRemaining
                    )

                    prefs.edit().putBoolean(PREF_IS_SUBSCRIBED, profile.isSubscribed).apply()
                    return@withContext Result.success(profile)
                }

                if (response.code == 401 || response.code == 403) {
                    return@withContext Result.failure(UnauthorizedException("Session expired"))
                }

                return@withContext Result.failure(Exception("Validate failed: ${response.code} ${response.message}"))
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    private fun getAndroidFingerprint(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
    }

    private fun parseIsSubscribed(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", true) || value == "1" || value.equals("yes", true)
            else -> false
        }
    }
}
