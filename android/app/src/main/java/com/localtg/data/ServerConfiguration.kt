package com.localtg.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class ServerConfigField(
    val key: String, val group: String, val title: String, val kind: String,
    val min: Double = 0.0, val max: Double = 0.0, val options: List<String> = emptyList(), val value: JsonElement = JsonNull,
)
@Serializable
data class ServerConfiguration(
    val revision: String, val fields: List<ServerConfigField>, val configPath: String = "", val dataDir: String = "", val restartRequired: Boolean = true,
)
