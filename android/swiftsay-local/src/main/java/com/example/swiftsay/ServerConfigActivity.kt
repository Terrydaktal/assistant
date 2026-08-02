package com.example.swiftsay

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ServerConfigActivity : AppCompatActivity() {
    private lateinit var hostEditText: EditText
    private lateinit var portEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_config)

        hostEditText = findViewById(R.id.host_edittext)
        portEditText = findViewById(R.id.port_edittext)
        findViewById<Button>(R.id.save_button).setOnClickListener { saveSettings() }

        hostEditText.setText(LocalServerPreferences.getHost(this))
        portEditText.setText(LocalServerPreferences.getPort(this).toString())
    }

    private fun saveSettings() {
        val host = hostEditText.text.toString().trim()
        val port = portEditText.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            Toast.makeText(this, "Enter a valid computer host and port", Toast.LENGTH_LONG).show()
            return
        }

        LocalServerPreferences.save(this, host, port)
        Toast.makeText(this, "Saved Whisper server: ${LocalServerPreferences.getDisplayValue(this)}", Toast.LENGTH_LONG).show()
        finish()
    }
}
