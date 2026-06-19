package com.metaldetectoraudioapp.app.ui.model

enum class ClassLabel {
    TARGET,
    JUNK,
    AMBIENT,
    /** UI-only state meaning "no class selected yet". Never persisted to disk; serialized as TARGET. */
    NONE;

    companion object {
        fun fromWireValue(value: String): ClassLabel {
            return entries.firstOrNull { it != NONE && it.name.equals(value, ignoreCase = true) } ?: AMBIENT
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

enum class LabelConfidence {
    UNCONFIRMED,
    CONFIRMED,
    HIGH_QUALITY;

    companion object {
        fun fromWireValue(value: String): LabelConfidence {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNCONFIRMED
        }
    }
}

enum class LocationVisibility {
    PRIVATE,
    TEXT_LABEL,
    EXACT;

    companion object {
        fun fromWireValue(value: String): LocationVisibility {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PRIVATE
        }
    }
}

enum class SyncStatus {
    NOT_UPLOADED,
    PENDING,
    UPLOADED,
    ERROR;

    companion object {
        fun fromWireValue(value: String): SyncStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NOT_UPLOADED
        }
    }
}
