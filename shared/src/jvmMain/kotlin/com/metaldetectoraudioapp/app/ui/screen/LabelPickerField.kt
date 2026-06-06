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
import com.metaldetectoraudioapp.app.ui.model.LabelEntry
import com.metaldetectoraudioapp.app.ui.model.LabelSuggestionCatalog
import com.metaldetectoraudioapp.app.ui.model.defaultLabelCatalog
import com.metaldetectoraudioapp.app.ui.model.parseCsvCatalog
import com.metaldetectoraudioapp.app.ui.model.parseLabelEntries
import com.metaldetectoraudioapp.app.ui.model.serializeLabelEntries
import java.io.File

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
        return rawCsv?.let { parseCsvCatalog(it) } ?: defaultLabelCatalog()
    }
}

@Composable
fun LabelPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = remember { LabelSuggestionConfigLoader.load() }
    var entries by remember { mutableStateOf(parseLabelEntries(value)) }

    LaunchedEffect(value) {
        val serialized = serializeLabelEntries(entries)
        if (serialized != value) {
            entries = parseLabelEntries(value)
        }
    }

    fun updateEntry(index: Int, updated: LabelEntry) {
        entries = entries.toMutableList().also { it[index] = updated }
        onValueChange(serializeLabelEntries(entries))
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
                        onValueChange(serializeLabelEntries(entries))
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
