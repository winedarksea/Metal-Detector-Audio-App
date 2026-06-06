package com.metaldetectoraudioapp.app.ui.model

data class LabelEntry(val obj: String = "", val name: String = "", val material: String = "")

data class LabelSuggestionCatalog(
    val objects: List<String>,
    val namesByObject: Map<String, List<String>>,
    val materialsByObject: Map<String, List<String>>,
)

fun defaultLabelCatalog(): LabelSuggestionCatalog = LabelSuggestionCatalog(
    objects = listOf("coin", "trash", "hardware", "jewelry", "test_fixture", "ambient"),
    namesByObject = mapOf(
        "coin" to listOf("dime", "nickel", "quarter", "penny"),
        "trash" to listOf("pull-tab", "pull-tab-ring", "beaver-tail", "foil", "bottle-cap", "fragment"),
        "hardware" to listOf("nail", "nail-bent", "nail-straight"),
        "jewelry" to listOf("ring"),
        "ambient" to listOf("background"),
    ),
    materialsByObject = mapOf(
        "coin" to listOf(
            "cupronickel-clad-copper",
            "cupronickel",
            "bronze-copper",
            "bronze-indian-head",
            "copper-plated-zinc",
            "silver-900",
        ),
        "trash" to listOf("aluminum", "steel"),
        "hardware" to listOf("steel"),
        "jewelry" to listOf("silver", "gold", "brass"),
        "ambient" to listOf("unknown"),
    ),
)

fun parseLabelEntries(raw: String): List<LabelEntry> {
    if (raw.isBlank()) return listOf(LabelEntry())
    return raw.split(',', ';', '|')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { token ->
            val parts = token.split(":")
            LabelEntry(
                obj = parts.getOrElse(0) { "" },
                name = parts.getOrElse(1) { "" },
                material = parts.getOrElse(2) { "" },
            )
        }
        .ifEmpty { listOf(LabelEntry()) }
}

fun serializeLabelEntries(entries: List<LabelEntry>): String =
    entries
        .filter { it.obj.isNotBlank() || it.name.isNotBlank() || it.material.isNotBlank() }
        .joinToString("|") { "${it.obj}:${it.name}:${it.material}" }
