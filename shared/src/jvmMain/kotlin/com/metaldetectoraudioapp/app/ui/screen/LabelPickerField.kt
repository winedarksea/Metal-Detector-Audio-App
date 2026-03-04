package com.metaldetectoraudioapp.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

private data class LabelEntry(val obj: String = "", val name: String = "", val material: String = "")

private data class LabelSuggestionCatalog(
    val objects: List<String>,
    val namesByObject: Map<String, List<String>>,
    val materialsByObject: Map<String, List<String>>,
)

private object LabelSuggestionConfigLoader {
    private const val CONFIG_RESOURCE = "label_dropdown_options.csv"

    fun load(): LabelSuggestionCatalog {
        val classpathCsv = javaClass.classLoader
            ?.getResourceAsStream(CONFIG_RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }

        val fileCsv = runCatching {
            val file = File(System.getProperty("user.dir"), "assets/$CONFIG_RESOURCE")
            if (file.exists()) file.readText() else null
        }.getOrNull()

        val rawCsv = classpathCsv ?: fileCsv
        return rawCsv?.let { parseCatalog(it) } ?: defaultCatalog()
    }

    private fun parseCatalog(csv: String): LabelSuggestionCatalog {
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) {
            return defaultCatalog()
        }

        val header = rows.first().map { it.trim().lowercase() }
        val objectIndex = header.indexOf("object")
        val nameIndex = header.indexOf("name")
        val materialIndex = header.indexOf("material")

        if (objectIndex < 0 || nameIndex < 0 || materialIndex < 0) {
            return defaultCatalog()
        }

        val objects = linkedSetOf<String>()
        val namesByObject = linkedMapOf<String, LinkedHashSet<String>>()
        val materialsByObject = linkedMapOf<String, LinkedHashSet<String>>()

        rows.drop(1).forEach { row ->
            val obj = row.getOrElse(objectIndex) { "" }.trim()
            val name = row.getOrElse(nameIndex) { "" }.trim()
            val material = row.getOrElse(materialIndex) { "" }.trim()
            if (obj.isBlank()) {
                return@forEach
            }

            objects += obj
            if (name.isNotBlank()) {
                namesByObject.getOrPut(obj) { linkedSetOf() }.add(name)
            }
            if (material.isNotBlank()) {
                materialsByObject.getOrPut(obj) { linkedSetOf() }.add(material)
            }
        }

        if (objects.isEmpty()) {
            return defaultCatalog()
        }

        return LabelSuggestionCatalog(
            objects = objects.toList(),
            namesByObject = namesByObject.mapValues { it.value.toList() },
            materialsByObject = materialsByObject.mapValues { it.value.toList() },
        )
    }

    private fun parseCsvRows(raw: String): List<List<String>> {
        if (raw.isBlank()) {
            return emptyList()
        }

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
                    if (ch == '\r' && index + 1 < raw.length && raw[index + 1] == '\n') {
                        index += 1
                    }
                    currentRow += currentCell.toString()
                    currentCell.clear()
                    if (currentRow.any { it.isNotBlank() }) {
                        rows += currentRow.toList()
                    }
                    currentRow.clear()
                }

                else -> currentCell.append(ch)
            }
            index += 1
        }

        currentRow += currentCell.toString()
        if (currentRow.any { it.isNotBlank() }) {
            rows += currentRow
        }

        return rows
    }

    private fun defaultCatalog(): LabelSuggestionCatalog {
        return LabelSuggestionCatalog(
            objects = listOf("coin", "trash", "hardware", "jewelry", "test_fixture", "ambient"),
            namesByObject = mapOf(
                "coin" to listOf("dime", "nickel", "quarter", "penny"),
                "trash" to listOf("pull-tab", "pull-tab-ring", "beaver-tail", "foil", "bottle-cap", "fragment"),
                "hardware" to listOf("nail", "nail-bent", "nail-straight"),
                "jewelry" to listOf("ring"),
                "ambient" to listOf("background")
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
                "ambient" to listOf("unknown")
            )
        )
    }
}

private fun parseEntries(raw: String): List<LabelEntry> {
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

private fun serializeEntries(entries: List<LabelEntry>): String =
    entries
        .filter { it.obj.isNotBlank() || it.name.isNotBlank() || it.material.isNotBlank() }
        .joinToString("|") { "${it.obj}:${it.name}:${it.material}" }

@Composable
fun LabelPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = remember { LabelSuggestionConfigLoader.load() }
    var entries by remember { mutableStateOf(parseEntries(value)) }

    LaunchedEffect(value) {
        val serialized = serializeEntries(entries)
        if (serialized != value) {
            entries = parseEntries(value)
        }
    }

    fun updateEntry(index: Int, updated: LabelEntry) {
        entries = entries.toMutableList().also { it[index] = updated }
        onValueChange(serializeEntries(entries))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEachIndexed { index, entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val nameSuggestions = suggestions.namesByObject[entry.obj] ?: emptyList()
                    val materialSuggestions = suggestions.materialsByObject[entry.obj] ?: emptyList()

                    DropdownField(
                        label = "Object",
                        value = entry.obj,
                        suggestions = suggestions.objects,
                        onValueChange = { newObj ->
                            val resetName = if (suggestions.namesByObject.containsKey(newObj) &&
                                !suggestions.namesByObject[newObj].orEmpty().contains(entry.name)
                            ) "" else entry.name
                            val resetMaterial = if (suggestions.materialsByObject.containsKey(newObj) &&
                                !suggestions.materialsByObject[newObj].orEmpty().contains(entry.material)
                            ) "" else entry.material
                            updateEntry(index, entry.copy(obj = newObj, name = resetName, material = resetMaterial))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownField(
                        label = "Name",
                        value = entry.name,
                        suggestions = nameSuggestions,
                        onValueChange = { updateEntry(index, entry.copy(name = it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownField(
                        label = "Material",
                        value = entry.material,
                        suggestions = materialSuggestions,
                        onValueChange = { updateEntry(index, entry.copy(material = it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (entries.size > 1) {
                    IconButton(onClick = {
                        entries = entries.toMutableList().also { it.removeAt(index) }
                        onValueChange(serializeEntries(entries))
                    }) {
                        Text("\u00D7")
                    }
                }
            }
        }
        TextButton(onClick = {
            entries = entries + LabelEntry()
        }) {
            Text("+ Add Label")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = suggestions.filter { it.startsWith(value, ignoreCase = true) }

    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && filtered.isNotEmpty())
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        if (filtered.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                filtered.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
