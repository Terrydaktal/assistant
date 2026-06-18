package com.example.earpieceai

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var backButton: MaterialButton
    private lateinit var emailText: TextView
    private lateinit var userIdText: TextView
    private lateinit var subscriptionText: TextView
    private lateinit var createdAtText: TextView
    private lateinit var lastTokenText: TextView
    private lateinit var durationRemainingText: TextView
    private lateinit var usageDailyTokensText: TextView
    private lateinit var usageDailyDurationText: TextView
    private lateinit var usageDailyWordsText: TextView
    private lateinit var usageDailyTranscriptionsText: TextView
    private lateinit var usageMonthlyTokensText: TextView
    private lateinit var usageMonthlyDurationText: TextView
    private lateinit var usageMonthlyWordsText: TextView
    private lateinit var usageMonthlyTranscriptionsText: TextView
    private lateinit var usageLifetimeTokensText: TextView
    private lateinit var usageLifetimeDurationText: TextView
    private lateinit var usageLifetimeWordsText: TextView
    private lateinit var usageLifetimeTranscriptionsText: TextView
    private lateinit var earpieceaiApi: EarpieceAiApi

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        earpieceaiApi = EarpieceAiApi(this)

        if (!earpieceaiApi.isLoggedIn()) {
            launchLoginAndFinish()
            return
        }

        setContentView(R.layout.activity_profile)

        swipeRefresh = findViewById(R.id.profile_swipe_refresh)
        backButton = findViewById(R.id.profile_back_button)
        emailText = findViewById(R.id.profile_email)
        userIdText = findViewById(R.id.profile_user_id)
        subscriptionText = findViewById(R.id.profile_subscription)
        createdAtText = findViewById(R.id.profile_created_at)
        lastTokenText = findViewById(R.id.profile_last_token)
        durationRemainingText = findViewById(R.id.profile_duration_remaining)
        usageDailyTokensText = findViewById(R.id.profile_usage_daily_tokens)
        usageDailyDurationText = findViewById(R.id.profile_usage_daily_duration)
        usageDailyWordsText = findViewById(R.id.profile_usage_daily_words)
        usageDailyTranscriptionsText = findViewById(R.id.profile_usage_daily_transcriptions)
        usageMonthlyTokensText = findViewById(R.id.profile_usage_monthly_tokens)
        usageMonthlyDurationText = findViewById(R.id.profile_usage_monthly_duration)
        usageMonthlyWordsText = findViewById(R.id.profile_usage_monthly_words)
        usageMonthlyTranscriptionsText = findViewById(R.id.profile_usage_monthly_transcriptions)
        usageLifetimeTokensText = findViewById(R.id.profile_usage_lifetime_tokens)
        usageLifetimeDurationText = findViewById(R.id.profile_usage_lifetime_duration)
        usageLifetimeWordsText = findViewById(R.id.profile_usage_lifetime_words)
        usageLifetimeTranscriptionsText = findViewById(R.id.profile_usage_lifetime_transcriptions)

        backButton.setOnClickListener { finish() }
        swipeRefresh.setOnRefreshListener { loadProfile() }

        swipeRefresh.isRefreshing = true
        loadProfile()
    }

    private fun loadProfile() {
        coroutineScope.launch {
            val result = earpieceaiApi.validate()
            swipeRefresh.isRefreshing = false

            if (result.isSuccess) {
                val profile = result.getOrNull() ?: return@launch
                emailText.text = "Email: ${profile.email}"
                userIdText.text = "User ID: ${profile.id}"
                subscriptionText.text = "Subscription: ${if (profile.isSubscribed) "Active" else "Inactive"}"
                createdAtText.text = "Created: ${profile.createdAt}"
                lastTokenText.text = "Last token request: ${profile.lastTokenRequest}"
                durationRemainingText.text = "Duration remaining (sec): ${formatSeconds(profile.durationRemaining)}"
                val daily = profile.usage.daily
                usageDailyTokensText.text = "Tokens: ${daily.tokens}"
                usageDailyDurationText.text = "Duration (sec): ${formatSeconds(daily.duration)}"
                usageDailyWordsText.text = "Words: ${daily.wordCount}"
                usageDailyTranscriptionsText.text = "Sessions: ${daily.transcriptions}"

                val monthly = profile.usage.monthly
                usageMonthlyTokensText.text = "Tokens: ${monthly.tokens}"
                usageMonthlyDurationText.text = "Duration (sec): ${formatSeconds(monthly.duration)}"
                usageMonthlyWordsText.text = "Words: ${monthly.wordCount}"
                usageMonthlyTranscriptionsText.text = "Sessions: ${monthly.transcriptions}"

                val lifetime = profile.usage.lifetime
                usageLifetimeTokensText.text = "Tokens: ${lifetime.tokens}"
                usageLifetimeDurationText.text = "Duration (sec): ${formatSeconds(lifetime.duration)}"
                usageLifetimeWordsText.text = "Words: ${lifetime.wordCount}"
                usageLifetimeTranscriptionsText.text = "Sessions: ${lifetime.transcriptions}"
            } else {
                val error = result.exceptionOrNull()
                if (error is UnauthorizedException) {
                    earpieceaiApi.logout()
                    launchLoginAndFinish()
                } else {
                    Toast.makeText(this@ProfileActivity, "Failed to load profile", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun launchLoginAndFinish() {
        val intent = Intent(this, ServerConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun formatSeconds(value: Double?): String {
        if (value == null) {
            return "—"
        }
        return String.format("%.1f", value)
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}
