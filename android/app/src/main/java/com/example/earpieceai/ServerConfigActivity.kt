package com.example.earpieceai

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class ServerConfigActivity : AppCompatActivity() {

    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var earpieceaiApi: EarpieceAiApi

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        earpieceaiApi = EarpieceAiApi(this)
        if (earpieceaiApi.isLoggedIn()) {
            launchMainAndFinish()
            return
        }

        setContentView(R.layout.activity_server_config)

        emailEditText = findViewById(R.id.email_edittext)
        passwordEditText = findViewById(R.id.password_edittext)
        loginButton = findViewById(R.id.login_button)

        loginButton.setOnClickListener {
            performLogin()
        }
    }

    private fun performLogin() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }

        loginButton.isEnabled = false
        loginButton.text = "Logging in..."

        coroutineScope.launch {
            val result = earpieceaiApi.login(email, password)

            if (result.isSuccess) {
                Toast.makeText(this@ServerConfigActivity, "✅ Login successful!", Toast.LENGTH_LONG).show()
                // Fetch deepinfra token immediately after login
                val tokenResult = earpieceaiApi.getDeepInfraToken()
                if (tokenResult.isSuccess) {
                    launchMainAndFinish()
                } else {
                    val errorMsg = tokenResult.exceptionOrNull()?.message ?: "Unknown error"
                    Toast.makeText(this@ServerConfigActivity, "⚠️ Token Fetch Failed: $errorMsg", Toast.LENGTH_LONG).show()
                    launchMainAndFinish()
                }
            } else {
                Toast.makeText(this@ServerConfigActivity, "❌ Login failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                loginButton.isEnabled = true
                loginButton.text = "Login"
            }
        }
    }

    private fun launchMainAndFinish() {
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}
