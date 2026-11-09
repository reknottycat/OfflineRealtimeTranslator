package com.bohanli.ruzhtranslator.asr

/** Shared lifecycle for Vosk and third-party sherpa-onnx Russian ASR engines. */
interface AsrEngine {
    suspend fun initialize()
    fun startListening()
    fun stopListening()
    fun release()
}
