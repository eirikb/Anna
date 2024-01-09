package no.eirikb.anna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.util.Log
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.assistant.AssistantId
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.core.Status
import com.aallam.openai.api.message.MessageContent
import com.aallam.openai.api.message.MessageRequest
import com.aallam.openai.api.run.RunRequest
import com.aallam.openai.api.thread.ThreadId
import com.aallam.openai.client.OpenAI
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechRecognizer
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import kotlinx.coroutines.*
import java.util.*


@OptIn(BetaOpenAI::class)
class YourService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    val key = "<PUT_YOUR_SPEECH_KEY_HERE>"
    val openAIApiToken = "<YOUR_OPEN_AI_API_TOKEN_HERE>"
    val region = "norwayeast"
    var openAI: OpenAI? = null
    var threadId: ThreadId? = null
    var isDaddy = false
    var busy = false
    val kåre = "asst_<ASSIST_ID_HERE>"
    val anna = "asst_<ASSIST_ID_HERE>"
    val aria = "asst_<ASSIST_ID_HERE>"
    var norwegian = true
    var voiceName = ""
    var assistantId = ""
    var requireOk = false
    private var sensorManager: SensorManager? = null
    private var accelerometerSensor: Sensor? = null
    var canStartListenFromShake = true


    override fun onBind(intent: Intent): IBinder? {
        Log.d("a", "onBind")
        return null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("a", "onUnbind")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        Log.d("a", "onRebind")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.d("a", "onLowMemory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("a", "Trim Memory: Level=$level")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("a", "Service Created")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(sensorListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private var lastUpdate: Long = 0
    private var last_x: Float = 0.0f
    private var last_y: Float = 0.0f
    private var last_z: Float = 0.0f
    private val SHAKE_THRESHOLD = 100
    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent) {
            if (isShakeGesture(event)) {
                Log.d("a", "Shake detected")
                if (!canStartListenFromShake) {
                    Log.d("a", "Can't start listening from shake")
                    return
                }
                triggerVibration(500)
                startLocalSpeechRecognition()
            }
        }

        private fun isShakeGesture(event: SensorEvent): Boolean {
            val sensor = event.sensor

            if (sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val curTime = System.currentTimeMillis()

                if ((curTime - lastUpdate) > 100) {
                    val diffTime = curTime - lastUpdate
                    lastUpdate = curTime

                    val speed = Math.abs(x + y + z - last_x - last_y - last_z) / diffTime * 10000

                    if (speed > SHAKE_THRESHOLD) {
                        last_x = x
                        last_y = y
                        last_z = z
                        return true
                    }
                }

                last_x = x
                last_y = y
                last_z = z
            }
            return false
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startForeground(1, getNotification())
        openAI = OpenAI(
            token = openAIApiToken
        )
        return START_STICKY
    }

    @OptIn(BetaOpenAI::class)
    private suspend fun ask(text: String): String {
        try {
            openAI!!.message(
                threadId = threadId!!,
                request = MessageRequest(
                    role = Role.User,
                    content = text
                ),
            )

            val run = openAI!!.createRun(
                threadId!!,
                request = RunRequest(
                    assistantId = AssistantId(assistantId),
                    instructions = if (isDaddy) "Personen du prater er pappa. Han lagde deg. Du forguder han. Han er også pappa til de andre barna" else null,
                )
            )

            do {
                delay(200)
                val retrievedRun = openAI!!.getRun(threadId = run!!.threadId, runId = run.id)
            } while (retrievedRun.status != Status.Completed)

            val assistantMessages = openAI!!.messages(run.threadId)
            return assistantMessages.first().content.map { it as MessageContent.Text }
                .joinToString(",") { it.text.value }
        } catch (e: Exception) {
            Log.d("a", "ERROR: ${e.message}")
            return "Noe gikk galt på internettet, prøv igjen"
        }
    }

    private fun triggerVibration(milliseconds: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        milliseconds,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                vibrator.vibrate(milliseconds) // Deprecated in API 26
            }
        }
    }

    private suspend fun startSpeechRecognition() {
        try {
            val config = SpeechConfig.fromSubscription(key, region)
            config.speechRecognitionLanguage = if (norwegian) "nb-NO" else "en-US"
            val audioInput = AudioConfig.fromDefaultMicrophoneInput()
            val recognizer = SpeechRecognizer(config, audioInput)

            recognizer.recognizing.addEventListener { _, speechRecognitionEventArgs ->
                val result = speechRecognitionEventArgs.result
                Log.d("t", "RECOGNIZING: Text=${result.text}")
            }

            var count = 0

            recognizer.recognized.addEventListener { _, speechRecognitionEventArgs ->
                serviceScope.launch {
                    val result = speechRecognitionEventArgs.result
                    count++
                    Log.d("a", "RECOGNIZED: Count=${count}. Text=${result.text}")

                    if (result.text.trim().isEmpty()) {
                        if (count > 7) {
                            recognizer.stopContinuousRecognitionAsync()
                            threadId = null
                            canStartListenFromShake = true
                            textToSpeech("Snakkes")
                        }
                        return@launch
                    }

                    if (result.text.lowercase().startsWith("stop")) {
                        recognizer.stopContinuousRecognitionAsync()
                        threadId = null
                        canStartListenFromShake = true
                        textToSpeech("Snakkes")
                        return@launch
                    }

                    val t = result.text.lowercase()
                    if (requireOk && !(t.startsWith("ok") || t.startsWith("øk"))) {
                        Log.d("a", "Skip because not ok")
                        if (count > 7) {
                            recognizer.stopContinuousRecognitionAsync()
                            threadId = null
                            canStartListenFromShake = true
                            textToSpeech("Snakkes")
                        }
                        return@launch
                    }

                    if (threadId == null) {
                        threadId = openAI!!.thread().id
                    }
                    count = 0
                    triggerVibration(500)
                    Log.d("YES", "YES")
                    recognizer.stopContinuousRecognitionAsync()
                    val response = ask(result.text)
                    textToSpeech(response)
                    recognizer.startContinuousRecognitionAsync()
                }
            }

            recognizer.startContinuousRecognitionAsync()
        } catch (ex: Exception) {
            println("Error: ${ex.message}")
        }
    }

    private val intent = run {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag("nb-NO"))
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
    }

    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    private var count = 0
    private fun startLocalSpeechRecognition(countDown: Int = 10) {
        if (countDown == 0) {
            Log.d("a", "Countdown is now 0, stop listening")
            canStartListenFromShake = true
            return
        }
        Log.d("a", "start listening")
        canStartListenFromShake = false

        count++
        val runtime = Runtime.getRuntime()

        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMemory = (runtime.maxMemory()) / (1024L * 1024L)
        val availableMemory = maxMemory - usedMemory


        Log.d(
            "a",
            "START LISTENING ($countDown) $count. Used: $usedMemory, Max: $maxMemory, Available: $availableMemory"
        )
        speechRecognizer?.destroy()
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(bundle: Bundle) {}
                override fun onBeginningOfSpeech() {
                    Log.d("a", "onBeginningOfSpeech")
                }

                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(bytes: ByteArray) {}
                override fun onPartialResults(bundle: Bundle) {
                    Log.d("a", "onPartialResults")
                }

                override fun onEvent(i: Int, bundle: Bundle) {}
                override fun onEndOfSpeech() {
                    Log.d("a", "onEndOfSpeech")
                }

                override fun onError(i: Int) {
                    Log.d("a", "onError $i")
                    startLocalSpeechRecognition(countDown - 1)
                }

                override fun onResults(bundle: Bundle) {
                    val matches = bundle.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d("a", matches.toString())
                    Log.d("a", "onResult")
                    Log.d("a", matches.toString())
                    val isAnna = matches?.any { it.lowercase().contains("anna") } == true
                    val isKåre = matches?.any { it.lowercase().contains("kåre") } == true
                    val isAria = matches?.any { it.lowercase().contains("aria") } == true
                    val isWakeUp = matches?.any {
                        val l = it.lowercase()
                        (l.contains("våk") && l.contains("opp")) ||
                                (l.contains("kom") && l.contains("fram"))
                    } == true
                    requireOk = matches?.any {
                        val l = it.lowercase()
                        (l.contains("kom") && l.contains("fram"))
                    } == true

                    if (isWakeUp && (isAnna || isKåre || isAria)) {
                        norwegian = isAnna || isKåre
                        assistantId = if (isAria) {
                            aria
                        } else if (isKåre) {
                            kåre
                        } else {
                            anna
                        }
                        voiceName = if (isAria) {
                            "en-US-AnaNeural"
                        } else if (isKåre) {
                            "nb-NO-FinnNeural"
                        } else {
                            "nb-NO-IselinNeural"
                        }

                        Log.d("a", "start talking to ${if (isAnna) "Anna" else if (isKåre) "Kåre" else "Aria"}")
                        triggerVibration(500)
                        serviceScope.launch {
                            startSpeechRecognition()
                        }
                    } else {
                        startLocalSpeechRecognition(countDown - 1)
                    }
                }
            })
            startListening(intent)
        }
    }


    private suspend fun textToSpeech(text: String) = coroutineScope {
        val config = SpeechConfig.fromSubscription(key, region)
        config.speechSynthesisVoiceName = voiceName
        val synthesizer = SpeechSynthesizer(config)

        val deferred = async(Dispatchers.IO) {
            synthesizer.SpeakTextAsync(text).get()
        }
        deferred.await()
        synthesizer.close()
    }

    private fun getNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "Your_Channel_ID"
            val channel = NotificationChannel(
                channelId,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Notification.Builder(this, channelId)
                .build()
        } else {
            Notification.Builder(this)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(sensorListener)
        Log.d("a", "onDestroy")
        serviceScope.cancel()
        Handler(Looper.getMainLooper()).postDelayed({
            val serviceIntent = Intent(this, YourService::class.java)
            startService(serviceIntent)
        }, 10000)
    }
}

