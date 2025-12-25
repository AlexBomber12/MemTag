package com.alexbomber12.memtag.integrations.memento

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldIdMapTest {
    @Test
    fun normalizesPrefixedAndPunctuatedNames() {
        val schema =
            MementoLibrarySchema(
                fields =
                    listOf(
                        MementoField(id = "f_epc", name = "Tech.EPC"),
                        MementoField(id = "f_label_rev", name = "Main Label-Rev"),
                        MementoField(id = "f_to_print", name = "Main To_Print"),
                        MementoField(id = "f_content", name = "Description"),
                        MementoField(id = "f_status", name = "Status"),
                    ),
            )

        val fieldMap = FieldIdMap.fromSchema(schema)

        assertEquals("f_epc", fieldMap.epcId)
        assertEquals("f_label_rev", fieldMap.labelRevId())
        assertEquals("f_to_print", fieldMap.toPrintId())
        assertEquals("f_content", fieldMap.contentId())
        assertEquals("f_status", fieldMap.statusId())
    }

    @Test
    fun keepsSimpleNamesIntact() {
        val schema =
            MementoLibrarySchema(
                fields =
                    listOf(
                        MementoField(id = "f_epc", name = "EPC"),
                        MementoField(id = "f_label_rev", name = "LabelRev"),
                        MementoField(id = "f_to_print", name = "ToPrint"),
                    ),
            )

        val fieldMap = FieldIdMap.fromSchema(schema)

        assertEquals("f_epc", fieldMap.epcId)
        assertEquals("f_label_rev", fieldMap.labelRevId())
        assertEquals("f_to_print", fieldMap.toPrintId())
    }

    @Test
    fun mapsPrefixedDescriptionToContent() {
        val mainSchema =
            MementoLibrarySchema(
                fields =
                    listOf(
                        MementoField(id = "f_epc_main", name = "EPC"),
                        MementoField(id = "f_content_main", name = "Main.Description"),
                    ),
            )
        val techSchema =
            MementoLibrarySchema(
                fields =
                    listOf(
                        MementoField(id = "f_epc_tech", name = "EPC"),
                        MementoField(id = "f_content_tech", name = "Tech.Description"),
                    ),
            )

        val mainFieldMap = FieldIdMap.fromSchema(mainSchema)
        val techFieldMap = FieldIdMap.fromSchema(techSchema)

        assertEquals("f_content_main", mainFieldMap.contentId())
        assertEquals("f_content_tech", techFieldMap.contentId())
    }
}
