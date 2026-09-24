package com.example.assistant

/*
 * Dépendances (build.gradle) :
 *   implementation("androidx.activity:activity-ktx:1.9.0")
 *   implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
 *   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
 *
 * Manifeste : déclare MainActivity (LAUNCHER) et ajoute
 *   android:usesCleartextTraffic="true" dans <application> si ton serveur est en http local.
 */

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

// ⚠️ À adapter
private const val SERVER_URL = "http://192.168.1.10:3000"   // ton serveur proxy
private const val APP_TOKEN = "change-moi"                    // même valeur que côté serveur
private const val MAX_STEPS = 15

class VoiceAssistant(
    private val context: Context,
    private val onState: (String) -> Unit
) {
    private var tts: TextToSpeech? = null
    @Volatile private var cancelled = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.FRENCH
        }
    }

    fun cancel() { cancelled = true; tts?.stop() }
    fun shutdown() { tts?.shutdown() }

    /** Un cycle complet : écoute → agent → réponse vocale. */
    suspend fun run() {
        cancelled = false
        onState("Je t'écoute…")
        val heard = listenOnce() ?: return onState("Je n'ai rien entendu")
        onState("« $heard »")
        val answer = try { ask(heard) } catch (e: Exception) { "Erreur : ${e.message}" }
        onState(answer)
        speak(answer)
    }

    // ---------- Reconnaissance vocale ----------

    suspend fun listenOnce(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String?> { cont ->
            val rec = SpeechRecognizer.createSpeechRecognizer(context)
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { rec.destroy() } }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    rec.destroy()
                    if (cont.isActive) cont.resume(text)
                }
                override fun onError(error: Int) {
                    rec.destroy()
                    if (cont.isActive) cont.resume(null)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { onState("… $it") }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            rec.startListening(intent)
        }
    }

    // ---------- Synthèse vocale ----------

    suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        val engine = tts
        if (engine == null) { cont.resume(Unit); return@suspendCancellableCoroutine }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        cont.invokeOnCancellation { engine.stop() }
    }

    // ---------- Boucle d'agent (tool use) ----------

    private suspend fun ask(userText: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", userText)
        )
        repeat(MAX_STEPS) {
            if (cancelled) return@withContext "Arrêté."
            val response = post(messages)
            val content = response.getJSONArray("content")
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val text = StringBuilder()
            val results = JSONArray()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.getString("type")) {
                    "text" -> text.append(block.getString("text"))
                    "tool_use" -> {
                        val name = block.getString("name")
                        val input = block.getJSONObject("input")
                        onState("⚙️ $name")
                        val output = runTool(name, input)
                        results.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", block.getString("id"))
                                .put("content", output)
                        )
                    }
                }
            }
            if (results.length() == 0) return@withContext text.toString().ifBlank { "Terminé." }
            messages.put(JSONObject().put("role", "user").put("content", results))
            delay(800) // laisse l'écran se stabiliser avant la prochaine lecture
        }
        "Trop d'étapes, j'arrête."
    }

    private suspend fun runTool(name: String, input: JSONObject): String {
        if (name in ToolExecutor.needsConfirmation) {
            speak("Je vais saisir : ${input.optString("text")}. Tu confirmes ?")
            val answer = listenOnce()
            if (answer?.contains("oui", ignoreCase = true) != true) return "Refusé par l'utilisateur"
        }
        return withContext(Dispatchers.Main) { ToolExecutor.run(context, name, input) }
    }

    private fun post(messages: JSONArray): JSONObject {
        val conn = (URL("$SERVER_URL/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $APP_TOKEN")
        }
        val body = JSONObject()
            .put("messages", messages)
            .put("tools", JSONArray(ToolExecutor.toolDefinitions))
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = conn.responseCode in 200..299
        val raw = (if (ok) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (!ok) error("Serveur ${conn.responseCode} : $raw")
        return JSONObject(raw)
    }
}

