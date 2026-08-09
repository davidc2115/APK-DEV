package com.jarvis.assistant

data class Message(
    val text: String,
    val isUser: Boolean,
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)
