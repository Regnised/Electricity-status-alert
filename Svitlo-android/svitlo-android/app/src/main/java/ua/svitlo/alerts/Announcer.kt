package ua.svitlo.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Сирена + голос українською. Звук іде в потік будильника, тож лунає і в беззвучному режимі. */
class Announcer(private val ctx: Context) : TextToSpeech.OnInitListener {

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts = TextToSpeech(ctx, this)
    @Volatile private var ready = false
    @Volatile var ukrainian = false; private set
    @Volatile private var stopped = false
    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private val exec = Executors.newSingleThreadExecutor()
    private var track: AudioTrack? = null
    private val am = ctx.getSystemService(AudioManager::class.java)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale("uk", "UA"))
        ukrainian = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setAudioAttributes(attrs)
        tts.setSpeechRate(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { latches.remove(id)?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { latches.remove(id)?.countDown() }
        })
        ready = true
    }

    fun announce(kind: String, text: String, repeats: Int, withSiren: Boolean) {
        exec.execute {
            stopped = false
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).build()
            am.requestAudioFocus(focus)
            try {
                if (kind == "off" && withSiren) playPcm(siren(6.0)) else playPcm(chime())
                repeat(repeats) { if (!stopped) speak(text) }
            } finally {
                am.abandonAudioFocusRequest(focus)
            }
        }
    }

    fun stop() {
        stopped = true
        try { track?.stop() } catch (_: Exception) {}
        tts.stop()
        latches.values.forEach { it.countDown() }
        latches.clear()
    }

    fun shutdown() {
        stop(); exec.shutdownNow(); tts.shutdown()
    }

    private fun speak(text: String) {
        var waited = 0
        while (!ready && waited < 8000) { Thread.sleep(100); waited += 100 }
        if (!ready) return
        val id = "u" + System.nanoTime()
        val latch = CountDownLatch(1)
        latches[id] = latch
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        latch.await(30, TimeUnit.SECONDS)
        Thread.sleep(400)
    }

    // ---------- синтез звуку ----------
    private val rate = 22050

    /** Сирена: пила 420↔1050 Гц, цикл 3 с, з м'яким фільтром і огинаючою. */
    private fun siren(sec: Double): ShortArray {
        val n = (sec * rate).toInt()
        val out = ShortArray(n)
        var phase = 0.0; var lp = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val c = (t % 3.0)
            val f = if (c < 1.8) 420 + (1050 - 420) * (c / 1.8) else 1050 - (1050 - 420) * ((c - 1.8) / 1.2)
            phase += f / rate
            val saw = 2.0 * (phase - Math.floor(phase)) - 1.0
            lp += 0.35 * (saw - lp)
            val env = minOf(1.0, t / 0.3, (sec - t) / 0.4)
            out[i] = (lp * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Три висхідні ноти (до-мі-соль). */
    private fun chime(): ShortArray {
        val n = (1.4 * rate).toInt()
        val out = DoubleArray(n)
        doubleArrayOf(523.25, 659.25, 783.99).forEachIndexed { k, f ->
            val start = (k * 0.22 * rate).toInt()
            for (i in 0 until (0.9 * rate).toInt()) {
                val j = start + i; if (j >= n) break
                val t = i.toDouble() / rate
                out[j] += sin(2 * PI * f * t) * exp(-t * 5) * minOf(1.0, t / 0.02) * 0.35
            }
        }
        return ShortArray(n) { (out[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun playPcm(data: ShortArray) {
        if (stopped) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        track = t
        try {
            t.write(data, 0, data.size)
            t.play()
            val ms = data.size * 1000L / rate
            var slept = 0L
            while (slept < ms && !stopped) { Thread.sleep(50); slept += 50 }
            try { t.stop() } catch (_: Exception) {}
        } finally {
            t.release(); track = null
        }
    }
}
