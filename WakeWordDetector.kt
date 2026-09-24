package com.example.assistant

/*
 * Mot d'activation « Hey Léona » (hors ligne, via Picovoice Porcupine).
 *
 * MISE EN PLACE (une seule fois, gratuit pour un usage perso) :
 * 1. Crée un compte sur https://console.picovoice.ai et copie ton AccessKey
 *    dans PICOVOICE_ACCESS_KEY ci-dessous.
 * 2. Console → Porcupine → langue « French » → phrase « Hey Léona » →
 *    plateforme Android → Train → télécharge le .ppn.
 *    Renomme-le en hey_leona_fr_android.ppn.
 * 3. Télécharge porcupine_params_fr.pv depuis le dépôt GitHub picovoice/porcupine
 *    (dossier lib/common/).
 * 4. Place les deux fichiers dans app/src/main/assets/.
 * 5. Gradle : implementation("ai.picovoice:porcupine-android:3.0.2")
 *    → utilise la dernière version 3.x et garde-la alignée avec le .pv / .ppn.
 */

import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context

private const val PICOVOICE_ACCESS_KEY = "TA_CLE_PICOVOICE"
private const val KEYWORD_FILE = "hey_leona_fr_android.ppn"
private const val MODEL_FILE = "porcupine_params_fr.pv"
private const val SENSITIVITY = 0.7f // 0 à 1 : plus haut = moins de ratés, plus de faux positifs

class WakeWordDetector(
    private val context: Context,
    private val onWake: () -> Unit
) {
    private var manager: PorcupineManager? = null
    var lastError: String? = null
        private set

    /** Démarre l'écoute du mot d'activation. Renvoie false en cas d'erreur (clé, fichiers…). */
    fun start(): Boolean = try {
        if (manager == null) {
            manager = PorcupineManager.Builder()
                .setAccessKey(PICOVOICE_ACCESS_KEY)
                .setKeywordPaths(arrayOf(KEYWORD_FILE))
                .setModelPath(MODEL_FILE)
                .setSensitivities(floatArrayOf(SENSITIVITY))
                .build(context, PorcupineManagerCallback { onWake() })
        }
        manager?.start()
        true
    } catch (e: Exception) {
        lastError = e.message
        false
    }

    /** Pause : libère le micro (nécessaire avant SpeechRecognizer). */
    fun stop() {
        try { manager?.stop() } catch (_: Exception) { }
    }

    fun release() {
        try { manager?.delete() } catch (_: Exception) { }
        manager = null
    }
}
