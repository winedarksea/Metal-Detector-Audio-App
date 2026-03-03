package com.metaldetectoraudioapp.app.ui.model

enum class ClassLabel {
    TARGET,
    JUNK,
    AMBIENT;

    companion object {
        fun fromWireValue(value: String): ClassLabel {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AMBIENT
        }
    }
}

enum class SweepPattern {
    SWING,
    WIGGLE;

    companion object {
        fun fromWireValue(value: String): SweepPattern {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SWING
        }
    }
}
