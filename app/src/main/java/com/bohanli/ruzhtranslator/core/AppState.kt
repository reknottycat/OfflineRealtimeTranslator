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
        AsrOnly -> "[仅俄语识别] 可开始转写、自动保存历史；如需中文翻译，请在设置中下载翻译模型"
        Listening -> "[监听] 正在识别俄语..."
        Translating -> "[翻译] 正在翻译..."
        Paused -> "[暂停] 已暂停，点击继续"
        is Error -> "[错误] $message"
    }
}
