package com.jarvis.app

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private val client = OkHttpClient()

    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0)
            if (spokenText != null) {
                statusText.text = "Toi: $spokenText"
                askGemini(spokenText)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER
        layout.setPadding(40, 40, 40, 40)

        statusText = TextView(this)
        statusText.text = "Appuie pour parler"
        statusText.textSize = 20f
        statusText.gravity = Gravity.CENTER

        val micButton = Button(this)
        micButton.text = "🎤 Parler"
        micButton.setOnClickListener { startListening() }

        layout.addView(statusText)
        layout.addView(micButton)
        setContentView(layout)
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Parle à Jarvis...")
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Reconnaissance vocale indisponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askGemini(userText: String) {
        statusText.text = "Jarvis réfléchit..."

        val json = JSONObject()
        val contents = JSONArray()
        val content = JSONObject()
        val parts = JSONArray()
        val part = JSONObject()
        part.put("text", userText)
        parts.put(part)
        content.put("parts", parts)
        contents.put(content)
        json.put("contents", contents)

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { statusText.text = "Erreur Gemini: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string()
                if (!response.isSuccessful || respBody == null) {
                    runOnUiThread { statusText.text = "Erreur Gemini (${response.code}): $respBody" }
                    return
                }
                try {
                    val resultJson = JSONObject(respBody)
                    val answer = resultJson
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    runOnUiThread { statusText.text = "Jarvis: $answer" }
                    speakWithFishAudio(answer)
                } catch (e: Exception) {
                    runOnUiThread { statusText.text = "Erreur de lecture réponse: ${e.message}" }
                }
            }
        })
    }

    private fun speakWithFishAudio(text: String) {
        val json = JSONObject()
        json.put("text", text)
        json.put("reference_id", BuildConfig.FISH_VOICE_ID)
        json.put("format", "mp3")

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.fish.audio/v1/tts")
            .addHeader("Authorization", "Bearer ${BuildConfig.FISH_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Erreur voix: ${e.message}", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val err = response.body?.string()
                    runOnUiThread { Toast.makeText(this@MainActivity, "Erreur Fish Audio (${response.code}): $err", Toast.LENGTH_LONG).show() }
                    return
                }
                val bytes = response.body?.bytes() ?: return
                val file = File(cacheDir, "jarvis_reply.mp3")
                file.writeBytes(bytes)

                runOnUiThread {
                    val player = MediaPlayer()
                    player.setDataSource(file.absolutePath)
                    player.prepare()
                    player.start()
                }
            }
        })
    }
}
