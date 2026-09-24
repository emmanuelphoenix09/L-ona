package com.example.assistant

/*
 * Manifeste : ajoute ces permissions (hors <application>) et ces composants.
 *
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
 * <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
 *
 * <service
 *     android:name=".ListeningService"
 *     android:exported="false"
 *     android:foregroundServiceType="microphone"/>
 *
 * <activity android:name=".MainActivity" android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.MAIN"/>
 *         <category android:name="android.intent.category.LAUNCHER"/>
 *     </intent-filter>
 * </activity>
 *
 * minSdk 26 minimum (bulle flottante). Dépendances : core-ktx, activity-ktx,
 * kotlinx-coroutines-android, porcupine-android.
 */

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Léona : service au premier plan qui garde le micro, écoute « Hey Léona »
 * et affiche la bulle violette par-dessus les autres applis.
 */
class ListeningService : Service() {

    companion object {
        const val ACTION_START = "com.example.assistant.START"
        const val ACTION_LISTEN = "com.example.assistant.LISTEN"
        const val ACTION_STOP = "com.example.assistant.STOP"
        private const val CHANNEL_ID = "leona"
        private const val NOTIF_ID = 1
        private const val READY = "Dis « Hey Léona »"
    }

    private lateinit var assistant: VoiceAssistant
    private lateinit var bubble: FloatingBubble
    private lateinit var wakeWord: WakeWordDetector

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Léona", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        assistant = VoiceAssistant(this) { state -> onAssistantState(state) }
        bubble = FloatingBubble(this) { converse(greeting = null) }
        // Le callback de Porcupine arrive sur un thread audio : on repasse sur le thread principal.
        wakeWord = WakeWordDetector(this) { main.post { converse(greeting = "Oui ?") } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Doit être appelé rapidement après startForegroundService()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(READY),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_LISTEN -> { ensureStarted(); converse(greeting = null) }
            else -> ensureStarted()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeWord.release()
        bubble.hide()
        assistant.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Logique ----------

    private fun ensureStarted() {
        if (running) return
        running = true
        bubble.show()
        if (wakeWord.start()) notify(READY)
        else notify("Mot d'activation indisponible : ${wakeWord.lastError}")
    }

    private fun converse(greeting: String?) {
        assistant.cancel()
        job?.cancel()
        wakeWord.stop() // libère le micro pour SpeechRecognizer
        job = scope.launch {
            try {
                assistant.run(greeting)
            } finally {
                // Ne relance le mot d'activation que si aucune conversation plus récente n'a pris le relais
                if (coroutineContext[Job] === job) {
                    bubble.setPhase(FloatingBubble.Phase.IDLE)
                    notify(READY)
                    wakeWord.start()
                }
            }
        }
    }

    private fun shutdown() {
        assistant.cancel()
        job?.cancel()
        job = null
        wakeWord.release()
        bubble.hide()
        running = false
        stopSelf()
    }

    /** Appelé depuis n'importe quel thread : met à jour la bulle et la notification. */
    private fun onAssistantState(state: String) {
        main.post {
            bubble.setPhase(
                when {
                    state.startsWith("Je t'écoute") || state.startsWith("…") -> FloatingBubble.Phase.LISTENING
                    state.startsWith("⚙️") || state.startsWith("«") -> FloatingBubble.Phase.WORKING
                    else -> FloatingBubble.Phase.IDLE
                }
            )
            notify(state)
        }
    }

    // ---------- Notification ----------

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun action(label: String, action: String, requestCode: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, requestCode,
            Intent(this, ListeningService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Léona")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action("🎤 Parler", ACTION_LISTEN, 1))
            .addAction(action("Arrêter", ACTION_STOP, 2))
            .build()
}

/** Écran de démarrage : permissions, puis activation de Léona. */
class MainActivity : ComponentActivity() {

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                send(ListeningService.ACTION_START)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = TextView(this).apply {
            text = "🟣 Léona\n\n1. Active le service d'accessibilité.\n" +
                "2. Appuie sur « Activer Léona » et autorise l'affichage par-dessus les autres applis.\n" +
                "3. Dis « Hey Léona » ou touche la bulle."
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        val accessibility = Button(this).apply {
            text = "Réglages d'accessibilité"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val activate = Button(this).apply {
            text = "🟣 Activer Léona"
            setOnClickListener { activate() }
        }
        val stop = Button(this).apply {
            text = "⏹ Arrêter Léona"
            setOnClickListener { send(ListeningService.ACTION_STOP) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info); addView(accessibility); addView(activate); addView(stop)
        })
    }

    private fun activate() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise Léona à s'afficher par-dessus les autres applis", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isEmpty()) send(ListeningService.ACTION_START)
        else permissions.launch(missing.toTypedArray())
    }

    private fun send(action: String) {
        ContextCompat.startForegroundService(
            this, Intent(this, ListeningService::class.java).setAction(action)
        )
    }
}
