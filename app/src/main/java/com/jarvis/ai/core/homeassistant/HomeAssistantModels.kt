package com.jarvis.ai.core.homeassistant

data class HAEntityState(
    val entityId: String,
    val state: String,
    val attributes: Map<String, Any?> = emptyMap()
)

data class HAServiceCall(
    val domain: String,   // ex: "light", "switch", "scene"
    val service: String,  // ex: "turn_on", "turn_off", "toggle"
    val entityId: String
)
