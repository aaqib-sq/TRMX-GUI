package dev.trmx.gui.killer

data class AppInfo(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val isSystem: Boolean,
)
