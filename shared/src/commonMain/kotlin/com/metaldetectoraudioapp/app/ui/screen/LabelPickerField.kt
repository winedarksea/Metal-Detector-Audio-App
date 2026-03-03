package com.metaldetectoraudioapp.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
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

private data class LabelEntry(val obj: String = "", val name: String = "", val material: String = "")

private object LabelSuggestions {
    val objects = listOf("coin", "trash", "hardware", "jewelry", "test_fixture", "ambient")

    val namesByObject = mapOf(
        "coin" to listOf("dime", "nickel", "quarter", "penny"),
        "trash" to listOf("pull-tab", "pull-tab-ring", "beaver-tail", "foil", "bottle-cap", "fragment"),
        "hardware" to listOf("nail", "nail-bent", "nail-straight"),
        "jewelry" to listOf("ring"),
        "ambient" to listOf("background")
    )

    val materialsByObject = mapOf(
        "coin" to listOf(
            "cupronickel-clad-copper", "cupronickel", "bronze-copper",
            "bronze-indian-head", "copper-plated-zinc", "silver-900"
        ),
        "trash" to listOf("aluminum", "steel"),
        "hardware" to listOf("steel"),
        "jewelry" to listOf("silver", "gold", "brass"),
        "ambient" to listOf("unknown")
    )
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
                material = parts.getOrElse(2) { "" }
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
    modifier: Modifier = Modifier
) {
    var entries by remember { mutableStateOf(parseEntries(value)) }

    // Sync inbound value changes (e.g. when the card is re-keyed for a new recording)
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
                    val nameSuggestions = LabelSuggestions.namesByObject[entry.obj] ?: emptyList()
                    val materialSuggestions = LabelSuggestions.materialsByObject[entry.obj] ?: emptyList()

                    DropdownField(
                        label = "Object",
                        value = entry.obj,
                        suggestions = LabelSuggestions.objects,
                        onValueChange = { newObj ->
                            // Reset name/material if object changes to a known category
                            val resetName = if (LabelSuggestions.namesByObject.containsKey(newObj) &&
                                !LabelSuggestions.namesByObject[newObj].orEmpty().contains(entry.name)
                            ) "" else entry.name
                            val resetMaterial = if (LabelSuggestions.materialsByObject.containsKey(newObj) &&
                                !LabelSuggestions.materialsByObject[newObj].orEmpty().contains(entry.material)
                            ) "" else entry.material
                            updateEntry(index, entry.copy(obj = newObj, name = resetName, material = resetMaterial))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownField(
                        label = "Name",
                        value = entry.name,
                        suggestions = nameSuggestions,
                        onValueChange = { updateEntry(index, entry.copy(name = it)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownField(
                        label = "Material",
                        value = entry.material,
                        suggestions = materialSuggestions,
                        onValueChange = { updateEntry(index, entry.copy(material = it)) },
                        modifier = Modifier.fillMaxWidth()
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
    modifier: Modifier = Modifier
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
