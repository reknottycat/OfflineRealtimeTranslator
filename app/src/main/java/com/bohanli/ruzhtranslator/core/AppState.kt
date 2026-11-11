package com.bohanli.ruzhtranslator.core

sealed class AppStatus {
    data class Loading(val message: String) : AppStatus()
    object Ready : AppStatus()
    object AsrOnly : AppStatus()
    object Listening : AppStatus()
    object Translating : AppStatus()
    object Paused : AppStatus()
    data class Error(val message: String) : AppStatus()

    fun toDisplayString(): String = when (this) {
        is Loading -> "[加载] $message"
        Ready -> "[就绪] 点击按钮开始监听"
        AsrOnly -> "[仅语音识别] 可开始转写，字幕自动保存"
        Listening -> "[监听] 正在识别语音..."
        Translating -> "[翻译] 正在翻译..."
        Paused -> "[暂停] 已暂停，点击继续"
        is Error -> "[错误] $message"
    }
}
