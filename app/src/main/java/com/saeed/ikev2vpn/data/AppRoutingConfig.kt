package com.saeed.ikev2vpn.data

import org.json.JSONArray
import org.json.JSONObject

/** App selection is local to each profile and is independent of server proxy metadata. */
enum class AppRoutingMode { ALL, ONLY_SELECTED, EXCLUDE_SELECTED }

data class AppRoutingConfig(
    val mode: AppRoutingMode = AppRoutingMode.ALL,
    val packages: Set<String> = emptySet(),
) {
    fun encode(): String = JSONObject().apply {
        put("mode", mode.name)
        put("packages", JSONArray(packages.sorted()))
    }.toString()

    companion object {
        fun decode(value: String?): AppRoutingConfig {
            if (value == null) return AppRoutingConfig()
            val json = JSONObject(value)
            val mode = AppRoutingMode.valueOf(json.getString("mode"))
            val entries = json.getJSONArray("packages")
            val packages = (0 until entries.length()).map { entries.getString(it) }.toSet()
            require(packages.all { it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) }) {
                "Invalid application package in stored VPN routing."
            }
            require(mode != AppRoutingMode.ONLY_SELECTED || packages.isNotEmpty()) {
                "Select at least one application for this VPN profile."
            }
            return AppRoutingConfig(mode, packages)
        }
    }
}
