package com.metaldetectoraudioapp.app.ui.model

// ─────────────────────────────────────────────────────────────────────────────
// Label catalog — single source of truth for all platforms (Android, Desktop, Web)
//
// Wire format for a recorded target:  object:name:material
//   e.g.  coin:nickel:cupronickel
//
// Multiple targets on one recording are pipe-separated:
//   e.g.  coin:quarter:cupronickel-clad-copper|trash:pull-tab:aluminum
//
// HOW TO ADD A NEW OBJECT / NAME / MATERIAL
// ──────────────────────────────────────────
// Edit LABEL_CATALOG_CSV below — add a row in "object,name,material" format.
// Order of first appearance determines dropdown order.
//
// Android and Desktop may additionally override these defaults at runtime by
// shipping a label_dropdown_options.csv file; use parseCsvCatalog() with that
// content and fall back to defaultLabelCatalog() on failure.
//
// The UI shows these as type-ahead dropdowns; users can still type any free-form
// value, so omitting a rarely-used option is fine — it won't block data entry.
// ─────────────────────────────────────────────────────────────────────────────

// Represents one object:name:material triple within a recording label.
data class LabelEntry(val obj: String = "", val name: String = "", val material: String = "")

// Holds the full set of suggested values shown in the label picker dropdowns.
data class LabelSuggestionCatalog(
    val objects: List<String>,
    val namesByObject: Map<String, List<String>>,
    val materialsByObject: Map<String, List<String>>,
)

private val LABEL_CATALOG_CSV = """
object,name,material
currency,quarter,cupronickel-clad-copper
currency,quarter,silver-900
currency,dime,cupronickel-clad-copper
currency,dime-mercury,silver-900
currency,nickel,cupronickel
currency,nickel-buffalo,cupronickel
currency,penny,copper-plated-zinc
currency,penny-indian-head,bronze
currency,penny-wheat,bronze
currency,penny-steel,zinc-plated-steel
currency,large-cent,copper
currency,roman-denarius,silver
currency,roman-sestertius,copper-alloy
currency,hammered-halfpenny,silver
trash,pull-tab,aluminum
trash,pull-tab-ring,aluminum
trash,beaver-tail,aluminum
trash,foil,aluminum
trash,bottle-cap,steel
trash,fragment,aluminum
trash,wire-fragment,copper
trash,wire-fragment,aluminum
hardware,nail,steel
hardware,nail-bent,steel
hardware,nail-straight,steel
hardware,screw,steel
hardware,horseshoe,iron
hardware,plow-point,steel
hardware,pipe-fragment,cast-iron
hardware,structural-bolt,steel
jewelry,ring,gold
jewelry,ring,silver
jewelry,ring,brass
jewelry,earring,gold
jewelry,earring,silver
jewelry,earring,brass
jewelry,necklace,gold
jewelry,necklace,silver
jewelry,necklace,brass
test_fixture,calibration-disc,steel
test_fixture,calibration-disc,copper
relic,button,pewter
relic,button,brass
relic,buckle,brass
relic,buckle,iron
relic,musket-ball,lead
relic,minie-ball,lead
relic,token,brass
relic,medallion,bronze
relic,key,copper-alloy
relic,key,iron
relic,toy,steel
relic,utensil,steel
relic,utensil,nickel-silver
mineral,ore,hematite
mineral,flake,gold
mineral,nugget,gold
mineral,nugget,copper
mineral,nugget,silver
ambient,background,unknown
""".trimIndent()

/** Canonical label options used by all platforms. */
fun defaultLabelCatalog(): LabelSuggestionCatalog =
    parseCsvCatalog(LABEL_CATALOG_CSV) ?: error("Built-in LABEL_CATALOG_CSV is malformed")

/**
 * Parses a CSV string (object,name,material header + data rows) into a
 * LabelSuggestionCatalog. Returns null if the CSV is missing required columns
 * or produces no objects, so callers can fall back to defaultLabelCatalog().
 */
fun parseCsvCatalog(csv: String): LabelSuggestionCatalog? {
    val rows = parseCsvRows(csv)
    if (rows.isEmpty()) return null

    val header = rows.first().map { it.trim().lowercase() }
    val objectIndex = header.indexOf("object")
    val nameIndex = header.indexOf("name")
    val materialIndex = header.indexOf("material")
    if (objectIndex < 0 || nameIndex < 0 || materialIndex < 0) return null

    val objects = linkedSetOf<String>()
    val namesByObject = linkedMapOf<String, LinkedHashSet<String>>()
    val materialsByObject = linkedMapOf<String, LinkedHashSet<String>>()

    rows.drop(1).forEach { row ->
        val obj = row.getOrElse(objectIndex) { "" }.trim()
        val name = row.getOrElse(nameIndex) { "" }.trim()
        val material = row.getOrElse(materialIndex) { "" }.trim()
        if (obj.isBlank()) return@forEach

        objects += obj
        if (name.isNotBlank()) namesByObject.getOrPut(obj) { linkedSetOf() } += name
        if (material.isNotBlank()) materialsByObject.getOrPut(obj) { linkedSetOf() } += material
    }

    if (objects.isEmpty()) return null

    return LabelSuggestionCatalog(
        objects = objects.toList(),
        namesByObject = namesByObject.mapValues { it.value.toList() },
        materialsByObject = materialsByObject.mapValues { it.value.toList() },
    )
}

private fun parseCsvRows(raw: String): List<List<String>> {
    if (raw.isBlank()) return emptyList()

    val rows = mutableListOf<List<String>>()
    val currentRow = mutableListOf<String>()
    val currentCell = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < raw.length) {
        val ch = raw[index]
        when {
            ch == '"' -> {
                val nextIsQuote = index + 1 < raw.length && raw[index + 1] == '"'
                if (inQuotes && nextIsQuote) {
                    currentCell.append('"')
                    index += 1
                } else {
                    inQuotes = !inQuotes
                }
            }
            ch == ',' && !inQuotes -> {
                currentRow += currentCell.toString()
                currentCell.clear()
            }
            (ch == '\n' || ch == '\r') && !inQuotes -> {
                if (ch == '\r' && index + 1 < raw.length && raw[index + 1] == '\n') index += 1
                currentRow += currentCell.toString()
                currentCell.clear()
                if (currentRow.any { it.isNotBlank() }) rows += currentRow.toList()
                currentRow.clear()
            }
            else -> currentCell.append(ch)
        }
        index += 1
    }

    currentRow += currentCell.toString()
    if (currentRow.any { it.isNotBlank() }) rows += currentRow

    return rows
}

// Parses a raw label string (wire format) into a list of LabelEntry objects.
// Accepts comma, semicolon, or pipe as multi-target delimiters.
fun parseLabelEntries(raw: String): List<LabelEntry> {
    if (raw.isBlank()) return listOf(LabelEntry())
    return raw.split(',', ';', '|')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { token ->
            val parts = token.split(":")
            LabelEntry(
                obj      = parts.getOrElse(0) { "" },
                name     = parts.getOrElse(1) { "" },
                material = parts.getOrElse(2) { "" },
            )
        }
        .ifEmpty { listOf(LabelEntry()) }
}

// Serializes a list of LabelEntry objects back to the pipe-separated wire format.
fun serializeLabelEntries(entries: List<LabelEntry>): String =
    entries
        .filter { it.obj.isNotBlank() || it.name.isNotBlank() || it.material.isNotBlank() }
        .joinToString("|") { "${it.obj}:${it.name}:${it.material}" }
