package com.saeed.ikev2vpn.data

enum class AppTheme(val id: String, val title: String, val description: String) {
    BLUE("blue", "Classic blue", "Original colors · Sans serif"),
    TERMINAL("terminal", "Terminal green", "Phosphor green · Monospace"),
    AMBER("amber", "Amber console", "Warm amber · Monospace"),
    ROSE("rose", "Rosewood", "Muted rose · Serif headings"),
    ;

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: BLUE
    }
}
