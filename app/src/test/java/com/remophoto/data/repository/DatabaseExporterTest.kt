package com.remophoto.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseExporterTest {
    @Test
    fun `vacuum into support follows sqlite introduction version`() {
        assertFalse(DatabaseExporter.supportsVacuumInto("3.22.0"))
        assertFalse(DatabaseExporter.supportsVacuumInto("3.26.9"))
        assertTrue(DatabaseExporter.supportsVacuumInto("3.27.0"))
        assertTrue(DatabaseExporter.supportsVacuumInto("3.45.1"))
        assertTrue(DatabaseExporter.supportsVacuumInto("4.0.0"))
    }
}
