package org.jarvis.mobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val speakButton = findViewById<Button>(R.id.speakButton)
        // Initialize audio streamer with BuildConfig URL
        val streamer = org.jarvis.mobile.audio.AudioStreamer(BuildConfig.API_URL)

        var isSpeaking = false
        speakButton.setOnClickListener {
            if (!isSpeaking) {
                statusText.text = "Listening..."
                speakButton.text = "Stop"
                streamer.startStreaming()
                isSpeaking = true
            } else {
                statusText.text = "Processing..."
                speakButton.text = "Speak"
                streamer.stopStreaming()
                isSpeaking = false
            }
        }
    }
}
