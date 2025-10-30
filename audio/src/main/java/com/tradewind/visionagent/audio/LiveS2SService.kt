@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.tradewind.visionagent.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.*
import com.tradewind.visionagent.perception_exec.PerceptionStore
import com.tradewind.visionagent.perception_exec.UiBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LiveS2SService : Service() {

    companion object {
        private const val CH = "gemini_live_voice"
        private const val NOTIF_ID = 42
        private const val TAG = "LiveS2SService"

        // App → Service
        const val ACTION_START        = "tg.live.START"

        // Voice / narration controls
        const val ACTION_NARRATE_ONCE = "com.tradewind.visionagent.NARRATE_ONCE"
        const val ACTION_TOGGLE_AUTO  = "com.tradewind.visionagent.TOGGLE_AUTO"
        const val ACTION_SHUSH        = "com.tradewind.visionagent.SHUSH"
        const val ACTION_END          = "com.tradewind.visionagent.END"

        // Optional UI intents
        const val ACTION_PRESS_LABEL  = "com.tradewind.visionagent.PRESS_LABEL"
        const val ACTION_PRESS_DESC   = "com.tradewind.visionagent.PRESS_DESC"
        const val ACTION_INPUT_TEXT   = "com.tradewind.visionagent.INPUT_TEXT"
        const val ACTION_GO_HOME      = "com.tradewind.visionagent.GO_HOME"

        // extras
        const val EXTRA_LABEL = "label"
        const val EXTRA_DESC  = "contentDesc"
        const val EXTRA_EXACT = "exact"
        const val EXTRA_TEXT  = "text"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: LiveSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    @Volatile private var lostFocusAtLeastOnce = false

    // Narration state
    @Volatile private var describeMode = true
    @Volatile private var isNarrating = false
    private val stabilizeMs = 300L
    private val cooldownMs = 2000L
    private var lastSpokenAt = 0L
    private var perceptionJob: Job? = null

    // Preemption epoch
    @Volatile private var dumpEpoch: Int = 0

    // ==== Function declarations (TOOLS) ====
    private val pressLabelDecl = FunctionDeclaration(
        name = "press_label",
        description = "Tap a UI element whose visible text matches a label.",
        parameters = mapOf(
            "label" to Schema.string("Visible text to match"),
            "exact" to Schema.boolean("If true, exact match; else substring")
        )
    )
    private val pressDescDecl = FunctionDeclaration(
        name = "press_desc",
        description = "Tap a UI element by its contentDescription.",
        parameters = mapOf(
            "contentDesc" to Schema.string("Content description to match"),
            "exact" to Schema.boolean("If true, exact match; else substring")
        )
    )
    private val inputTextDecl = FunctionDeclaration(
        name = "input_text",
        description = "Type into the currently focused editable field.",
        parameters = mapOf("text" to Schema.string("Text to type"))
    )
    private val goHomeDecl = FunctionDeclaration(
        name = "go_home",
        description = "Navigate to Android home screen.",
        parameters = emptyMap()
    )
    private val narrateDecl = FunctionDeclaration(
        name = "narrate_screen",
        description = "Speak one short description of the current screen.",
        parameters = emptyMap()
    )
    private val setAutoDecl = FunctionDeclaration(
        name = "set_auto_description",
        description = "Enable/disable automatic narration when screen changes.",
        parameters = mapOf("on" to Schema.boolean("true to enable, false to disable"))
    )

    // ─────────────────────────── Lifecycle ───────────────────────────

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notif = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // use built-in icon to avoid module R issues
            .setContentTitle("Vision Agent")
            .setContentText("Speech ↔ Speech running")
            .setOngoing(true)
            .build()
        // Android 14+: must include FGS type (microphone)
        startAsFgs(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        // Self-bootstrap if service was started without ACTION_START
        scope.launch { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startLive()
        }
        }
    }

    // Helper: startForeground with type on API 34+
    private fun startAsFgs(id: Int, notif: Notification, typeMask: Int) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(id, notif, typeMask)
        else startForeground(id, notif)
    }

    // ─────────────────────────── Live bootstrap ───────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun startLive() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted — skipping Live connect.")
            stopSelf(); return
        }

        val systemInstruction = content {
            text(
                """
                You are a voice assistant for VisionAgent.

                TURN-TAKING:
                - If a function can achieve the user's intent, CALL THE FUNCTION FIRST.
                - Do not speak before calling a function.
                - After the tool result returns, reply with ONE short confirmation sentence.

                SCREEN DESCRIPTION:
                - ~2 sentences: app name, page title, focused element (if any), and 3–6 actionable items (paraphrased).
                - Do not invent content.
                """.trimIndent()
            )
        }

        try {
            val model = Firebase
                .ai(backend = GenerativeBackend.googleAI())
                .liveModel(
                    modelName = "gemini-live-2.5-flash-preview",
                    generationConfig = liveGenerationConfig {
                        responseModality = ResponseModality.AUDIO
                        speechConfig = SpeechConfig(voice = Voice("FENRIR"))
                    },
                    systemInstruction = systemInstruction,
                    tools = listOf(
                        Tool.functionDeclarations(
                            listOf(
                                pressLabelDecl, pressDescDecl, inputTextDecl,
                                goHomeDecl, narrateDecl, setAutoDecl
                            )
                        )
                    )
                )

            val s = model.connect()
            session = s

            // Audio routing + focus
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager = am
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) am.setCommunicationDevice(speaker)
            }

            focusListener = AudioManager.OnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        lostFocusAtLeastOnce = true
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (lostFocusAtLeastOnce) {
                            lostFocusAtLeastOnce = false
                            scope.launch { delay(180); narrateScreenOnce() }
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        audioFocusRequest?.let { req ->
                            am.abandonAudioFocusRequest(req)
                            am.requestAudioFocus(req)
                        }
                    }
                }
            }

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(focusListener!!)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).build()
            am.requestAudioFocus(audioFocusRequest!!)

            // Start live audio w/ tool handler
            s.startAudioConversation(::functionCallHandler)

            // Optional initial narration
            scope.launch {
                delay(600)
                val e = dumpEpoch
                narrateScreenOnce(e)
            }

            startPerceptionCollector()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Live session", t)
            stopSelf()
        }
    }

    // ─────────────────────────── Perception → narration ───────────────────────────

    private fun startPerceptionCollector() {
        perceptionJob?.cancel()
        perceptionJob = scope.launch {
            var lastHash = 0
            PerceptionStore.dump.collectLatest { dump: String ->
                if (!describeMode || dump.isBlank()) return@collectLatest
                delay(stabilizeMs) // let UI settle

                val h = dump.hashCode()
                if (h == lastHash) return@collectLatest
                lastHash = h

                val thisEpoch = bumpEpoch()

                val now = System.currentTimeMillis()
                if (now - lastSpokenAt < cooldownMs) return@collectLatest
                lastSpokenAt = now

                narrateScreenOnce(thisEpoch)
            }
        }
    }

    private fun bumpEpoch(): Int { dumpEpoch += 1; return dumpEpoch }

    private suspend fun narrateScreenOnce(expectedEpoch: Int = dumpEpoch) {
        val s = session ?: return
        if (isNarrating) return
        isNarrating = true
        try {
            if (expectedEpoch != dumpEpoch) return

            val dump = PerceptionStore.latestSummary()
            if (dump.isBlank()) {
                if (expectedEpoch == dumpEpoch) s.send(content { text("I don’t have the screen yet.") })
                return
            }

            if (expectedEpoch != dumpEpoch) return

            s.send(
                content = content {
                    text(
                        """
                        Read ONLY the SCREEN_DUMP below.
                        Output one brief paragraph (~2 sentences) that includes:
                        - App name, page title, focused element (if any).
                        - 3–6 actionable items, paraphrased.
                        Do NOT give instructions or suggestions. Do NOT invent content.
                        """.trimIndent()
                    )
                    text("SCREEN_DUMP:\n$dump")
                }
            )
        } finally {
            delay(80)
            isNarrating = false
        }
    }

    private suspend fun microPause(ms: Long = 220L) { delay(ms) }
    private suspend fun say(line: String) { session?.send(content { text(line) }) }

    // ─────────────────────────── Tool calls ───────────────────────────

    private fun functionCallHandler(call: FunctionCallPart): FunctionResponsePart {
        fun ok(name: String, block: (MutableMap<String, Any?>) -> Unit = { }): FunctionResponsePart {
            val map = mutableMapOf<String, Any?>("success" to true)
            block(map)
            return FunctionResponsePart(name, buildJsonObject {
                map.forEach { (k, v) ->
                    when (v) {
                        is String -> put(k, v)
                        is Boolean -> put(k, v)
                        is Int -> put(k, v)
                        is Long -> put(k, v)
                        null -> {}
                    }
                }
            })
        }
        fun err(name: String, msg: String) =
            FunctionResponsePart(name, buildJsonObject { put("success", false); put("error", msg) })

        return try {
            when (call.name) {
                "press_label" -> {
                    val label = call.args["label"]?.jsonPrimitive?.content
                        ?: return err(call.name, "Missing label")
                    val exact = call.args["exact"]?.jsonPrimitive?.booleanOrNull ?: true
                    val hit = UiBridge.press(label = label, contentDesc = null, exact = exact)

                    val newEpoch = bumpEpoch()
                    scope.launch { microPause(220) }
                    scope.launch { delay(180); narrateScreenOnce(newEpoch) }

                    ok(call.name) { it["epoch"] = newEpoch; it["hit"] = hit; it["message"] =
                        if (hit) "Tapped \"$label\"" else "Couldn’t tap \"$label\"" }
                }
                "press_desc" -> {
                    val desc = call.args["contentDesc"]?.jsonPrimitive?.content
                        ?: return err(call.name, "Missing contentDesc")
                    val exact = call.args["exact"]?.jsonPrimitive?.booleanOrNull ?: true
                    val hit = UiBridge.press(label = null, contentDesc = desc, exact = exact)

                    val newEpoch = bumpEpoch()
                    scope.launch { microPause(220) }
                    scope.launch { delay(180); narrateScreenOnce(newEpoch) }

                    ok(call.name) { it["epoch"] = newEpoch; it["hit"] = hit; it["message"] =
                        if (hit) "Tapped described item" else "Couldn’t find described item" }
                }
                "input_text" -> {
                    val text = call.args["text"]?.jsonPrimitive?.content
                        ?: return err(call.name, "Missing text")
                    val hit = UiBridge.inputText(text)

                    val newEpoch = bumpEpoch()
                    scope.launch { microPause(220) }
                    scope.launch { delay(180); narrateScreenOnce(newEpoch) }

                    ok(call.name) { it["epoch"] = newEpoch; it["hit"] = hit; it["message"] =
                        if (hit) "Typed" else "No focused field" }
                }
                "go_home" -> {
                    val hit = UiBridge.goHome()

                    val newEpoch = bumpEpoch()
                    scope.launch { microPause(220) }
                    scope.launch { delay(200); narrateScreenOnce(newEpoch) }

                    ok(call.name) { it["epoch"] = newEpoch; it["hit"] = hit; it["message"] =
                        if (hit) "Home" else "Couldn’t go home" }
                }
                "narrate_screen" -> {
                    scope.launch { narrateScreenOnce() }
                    ok(call.name) { it["message"] = "Narrating" }
                }
                "set_auto_description" -> {
                    val on = call.args["on"]?.jsonPrimitive?.booleanOrNull ?: true
                    describeMode = on
                    if (on) startPerceptionCollector() else perceptionJob?.cancel()
                    ok(call.name) { it["message"] = "Auto description ${if (on) "on" else "off"}" }
                }
                else -> err(call.name, "Unknown function: ${call.name}")
            }
        } catch (t: Throwable) {
            FunctionResponsePart(call.name, buildJsonObject {
                put("success", false); put("error", t.message ?: t::class.java.simpleName)
            })
        }
    }

    // ─────────────────────────── Intents from UI ───────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { if (session == null) startLive() }
            ACTION_NARRATE_ONCE -> scope.launch { narrateScreenOnce() }
            ACTION_TOGGLE_AUTO -> {
                describeMode = !describeMode
                if (describeMode) startPerceptionCollector() else perceptionJob?.cancel()
                scope.launch { say(if (describeMode) "Auto description on." else "Auto description off.") }
            }
            ACTION_SHUSH -> {
                describeMode = false
                perceptionJob?.cancel()
                scope.launch { say("Okay, I’ll pause.") }
            }
            ACTION_END -> {
                scope.launch {
                    say("Okay, ending now. Goodbye!")
                    delay(500)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_PRESS_LABEL -> {
                val label = intent.getStringExtra(EXTRA_LABEL)
                val exact = intent.getBooleanExtra(EXTRA_EXACT, true)
                scope.launch {
                    val hit = UiBridge.press(label = label, contentDesc = null, exact = exact)
                    microPause(220)
                    val e = bumpEpoch()
                    say(if (hit) "Tapped $label." else "Couldn’t tap $label.")
                    delay(180)
                    narrateScreenOnce(e)
                }
            }
            ACTION_PRESS_DESC -> {
                val desc = intent.getStringExtra(EXTRA_DESC)
                val exact = intent.getBooleanExtra(EXTRA_EXACT, true)
                scope.launch {
                    val hit = UiBridge.press(label = null, contentDesc = desc, exact = exact)
                    microPause(220)
                    val e = bumpEpoch()
                    say(if (hit) "Tapped item." else "Couldn’t find that item.")
                    delay(180)
                    narrateScreenOnce(e)
                }
            }
            ACTION_INPUT_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                scope.launch {
                    val hit = UiBridge.inputText(text)
                    microPause(220)
                    val e = bumpEpoch()
                    say(if (hit) "Typed it." else "No focused field.")
                    delay(180)
                    narrateScreenOnce(e)
                }
            }
            ACTION_GO_HOME -> {
                scope.launch {
                    val hit = UiBridge.goHome()
                    microPause(220)
                    val e = bumpEpoch()
                    say(if (hit) "Going home." else "Couldn’t go home.")
                    delay(200)
                    narrateScreenOnce(e)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy()")
        perceptionJob?.cancel()
        scope.cancel()
        session = null
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        audioFocusRequest?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            am.abandonAudioFocusRequest(it)
        } else {
            TODO("VERSION.SDK_INT < O")
        }
        }
        audioFocusRequest = null
        focusListener = null
        audioManager = null
        super.onDestroy()
    }

    // ─────────────────────────── Utilities ───────────────────────────

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "Gemini Live Voice", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
