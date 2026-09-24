package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/** Service d'accessibilité : lit l'écran, clique, saisit du texte. */
class AssistantService : AccessibilityService() {

    companion object {
        @Volatile var instance: AssistantService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Renvoie une liste texte des éléments visibles avec leurs coordonnées. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Écran illisible (appli protégée ?)"
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val label = n.text ?: n.contentDescription
            if (label != null || n.isClickable) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                sb.append("[${r.centerX()},${r.centerY()}] ${label ?: ""}")
                if (n.isClickable) sb.append(" (cliquable)")
                if (n.isEditable) sb.append(" (champ texte)")
                sb.append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString().ifBlank { "Aucun élément détecté" }
    }

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
}

/** Exécute les outils demandés par Claude (tool use). */
object ToolExecutor {

    /** Outils sensibles : demander confirmation à l'utilisateur avant. */
    val needsConfirmation = setOf("type_text")

    fun run(context: Context, name: String, input: JSONObject): String {
        val svc = AssistantService.instance
        return when (name) {
            "open_app" -> openApp(context, input.getString("name"))
            "set_alarm" -> {
                val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, input.getInt("hour"))
                    putExtra(AlarmClock.EXTRA_MINUTES, input.getInt("minute"))
                    putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label", ""))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i); "Alarme créée"
            }
            "read_screen" -> svc?.readScreen() ?: "Service d'accessibilité non activé"
            "tap" -> svc?.let {
                it.tap(input.getDouble("x").toFloat(), input.getDouble("y").toFloat()); "OK"
            } ?: "Service non activé"
            "swipe" -> svc?.let {
                it.swipe(
                    input.getDouble("x1").toFloat(), input.getDouble("y1").toFloat(),
                    input.getDouble("x2").toFloat(), input.getDouble("y2").toFloat()
                ); "OK"
            } ?: "Service non activé"
            "type_text" -> svc?.let {
                if (it.typeText(input.getString("text"))) "OK" else "Aucun champ actif"
            } ?: "Service non activé"
            "back" -> { svc?.back(); "OK" }
            "home" -> { svc?.home(); "OK" }
            else -> "Outil inconnu : $name"
        }
    }

    private fun openApp(context: Context, query: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().contains(query, ignoreCase = true)
        } ?: return "Appli introuvable : $query"
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Impossible de lancer $query"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Ouverture de ${match.loadLabel(pm)}"
    }

    /** Définitions à envoyer à l'API Claude (champ "tools"). */
    val toolDefinitions = """
    [
      {"name":"open_app","description":"Ouvre une appli par son nom","input_schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}},
      {"name":"set_alarm","description":"Crée une alarme","input_schema":{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}},"required":["hour","minute"]}},
      {"name":"read_screen","description":"Liste les éléments visibles à l'écran avec leurs coordonnées [x,y]","input_schema":{"type":"object","properties":{}}},
      {"name":"tap","description":"Touche l'écran aux coordonnées données","input_schema":{"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"}},"required":["x","y"]}},
      {"name":"swipe","description":"Glisse d'un point à un autre (défilement)","input_schema":{"type":"object","properties":{"x1":{"type":"number"},"y1":{"type":"number"},"x2":{"type":"number"},"y2":{"type":"number"}},"required":["x1","y1","x2","y2"]}},
      {"name":"type_text","description":"Saisit du texte dans le champ actif","input_schema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}},
      {"name":"back","description":"Bouton retour","input_schema":{"type":"object","properties":{}}},
      {"name":"home","description":"Bouton accueil","input_schema":{"type":"object","properties":{}}}
    ]
    """.trimIndent()
}
