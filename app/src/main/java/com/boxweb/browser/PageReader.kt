package com.boxweb.browser

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads page text aloud with the phone's text-to-speech engine. The engine starts on first use. */
class PageReader(context: Context, private val onError: () -> Unit) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun isSpeaking(): Boolean = pending != null || tts?.isSpeaking == true

    fun speak(text: String) {
        if (tts == null) {
            pending = text
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setLanguage(Locale.getDefault())
                    val waiting = pending
                    pending = null
                    if (waiting != null) speakNow(waiting)
                } else {
                    pending = null
                    tts?.shutdown()
                    tts = null
                    onError()
                }
            }
        } else if (ready) {
            speakNow(text)
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
    }

    fun shutdown() {
        pending = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        chunks(text).forEachIndexed { index, part ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(part, mode, null, "boxweb-$index")
        }
    }

    /** The engine refuses very long input, so the page is read in pieces that end at a line or a space. */
    private fun chunks(text: String): List<String> {
        val limit = minOf(TextToSpeech.getMaxSpeechInputLength(), CHUNK_CHARS)
        val out = ArrayList<String>()
        var rest = text.trim().take(MAX_CHARS)
        while (rest.isNotEmpty()) {
            if (rest.length <= limit) {
                out += rest
                break
            }
            var cut = rest.lastIndexOf('\n', limit)
            if (cut < limit / 2) cut = rest.lastIndexOf(' ', limit)
            if (cut <= 0) cut = limit
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        return out.filter { it.isNotBlank() }
    }

    private companion object {
        const val CHUNK_CHARS = 3500
        const val MAX_CHARS = 60000
    }
}
