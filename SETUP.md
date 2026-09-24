# Configuration

## 1. AndroidManifest.xml (dans `<application>`)

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>

<!-- Android 11+ : nécessaire pour lister les applis installées -->
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent>
</queries>

<service
    android:name=".AssistantService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService"/>
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_config"/>
</service>
```

## 2. res/xml/accessibility_config.xml

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:accessibilityFlags="flagDefault"
    android:description="@string/accessibility_description"/>
```

Ajoute `accessibility_description` dans `strings.xml`.

## 3. Activation

Réglages → Accessibilité → Applications installées → ton assistant → Activer.
(Sur Android 13+, si c'est grisé : Infos de l'appli → ⋮ → « Autoriser les réglages restreints ».)

## 4. Boucle d'agent (côté serveur ou app)

1. Envoie à l'API la voix transcrite + `ToolExecutor.toolDefinitions`.
2. Si la réponse contient un bloc `tool_use` : exécute `ToolExecutor.run(...)`, puis renvoie un `tool_result`.
3. Répète jusqu'à ce que Claude réponde par du texte, puis lis-le avec `TextToSpeech`.
4. Mets une limite (ex. 15 étapes) et un bouton « Stop ».

Conseil : commence par `open_app` + `read_screen` + `tap`, ça suffit pour des tâches surprenantes.
