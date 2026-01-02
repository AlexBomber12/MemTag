package com.alexbomber12.memtag.integrations.memento

class FieldIdMap private constructor(
    private val ids: Map<String, String>,
) {
    val epcId: String =
        ids[FIELD_EPC] ?: throw MementoSchemaException("Library schema is missing the EPC field.")

    fun idFor(fieldName: String): String? {
        return ids[normalizeFieldName(fieldName)]
    }

    fun idForCanonical(canonicalName: String): String? {
        return ids[canonicalName]
    }

    companion object {
        private const val FIELD_EPC = "epc"
        private const val FIELD_NAME = "name"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_LOCATION = "location"
        private const val FIELD_STATUS = "status"
        private const val FIELD_CATEGORY = "category"
        private const val FIELD_COMMENT = "comment"
        private const val FIELD_LABEL_REV = "labelrev"
        private const val FIELD_TO_PRINT = "toprint"
        private const val FIELD_PHOTO = "photo"
        private const val FIELD_UM = "um"
        private const val FIELD_QR = "qr"

        private val supported =
            setOf(
                FIELD_EPC,
                FIELD_NAME,
                FIELD_CONTENT,
                FIELD_LOCATION,
                FIELD_STATUS,
                FIELD_CATEGORY,
                FIELD_COMMENT,
                FIELD_LABEL_REV,
                FIELD_TO_PRINT,
                FIELD_PHOTO,
                FIELD_UM,
                FIELD_QR,
            )

        private val locationAliases =
            setOf(
                "locationpath",
                "locationtext",
                "location",
                "locpath",
            )

        fun fromSchema(schema: MementoLibrarySchema): FieldIdMap {
            val ids = mutableMapOf<String, String>()
            var locationId: String? = null
            var locationPriority = Int.MAX_VALUE
            schema.fields.forEach { field ->
                val normalized = normalizeFieldName(field.name)
                val canonical =
                    when {
                        normalized == "description" -> FIELD_CONTENT
                        normalized in supported && normalized != FIELD_LOCATION -> normalized
                        else -> null
                    }
                if (canonical != null && canonical in supported) {
                    if (!ids.containsKey(canonical)) {
                        ids[canonical] = field.id
                    }
                }
                val candidatePriority = locationPriority(normalized)
                if (candidatePriority != null) {
                    if (locationId == null || candidatePriority < locationPriority) {
                        locationId = field.id
                        locationPriority = candidatePriority
                    }
                }
            }
            if (locationId != null) {
                ids[FIELD_LOCATION] = locationId
            }
            return FieldIdMap(ids)
        }

        fun fieldNameFor(canonicalName: String): String {
            return canonicalName
        }

        fun canonicalNames(): Set<String> = supported

        private fun normalizeFieldName(value: String): String {
            val cleaned =
                value
                    .trim()
                    .lowercase()
                    .filter { it.isLetterOrDigit() }
            val remainder =
                when {
                    cleaned.startsWith("tech") -> cleaned.removePrefix("tech")
                    cleaned.startsWith("main") -> cleaned.removePrefix("main")
                    else -> null
                }
            return if (
                remainder != null &&
                (remainder in supported || remainder == "description" || remainder in locationAliases)
            ) {
                remainder
            } else {
                cleaned
            }
        }

        private fun locationPriority(normalized: String): Int? {
            return when {
                normalized == "locationpath" -> 0
                normalized in locationAliases -> 1
                normalized.contains(FIELD_LOCATION) -> 2
                else -> null
            }
        }
    }

    fun nameId(): String? = ids[FIELD_NAME]

    fun contentId(): String? = ids[FIELD_CONTENT]

    fun locationId(): String? = ids[FIELD_LOCATION]

    fun statusId(): String? = ids[FIELD_STATUS]

    fun categoryId(): String? = ids[FIELD_CATEGORY]

    fun commentId(): String? = ids[FIELD_COMMENT]

    fun labelRevId(): String? = ids[FIELD_LABEL_REV]

    fun toPrintId(): String? = ids[FIELD_TO_PRINT]

    fun photoId(): String? = ids[FIELD_PHOTO]

    fun umId(): String? = ids[FIELD_UM]

    fun qrId(): String? = ids[FIELD_QR]
}
