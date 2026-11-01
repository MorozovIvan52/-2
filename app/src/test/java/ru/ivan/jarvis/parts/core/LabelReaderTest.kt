package ru.ivan.jarvis.parts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelReaderTest {
    @Test fun vin() {
        val r = LabelReader.read("СВИДЕТЕЛЬСТВО\nVIN Z94CT41DBHR123456\nМарка HYUNDAI SOLARIS") as LabelReader.Result.Vin
        assertEquals("Z94CT41DBHR123456", r.vin)
        assertEquals("Hyundai/Kia (Россия)", r.maker)
        assertEquals(2017, r.year)
        // O вместо 0 после распознавания
        assertEquals("XTA210740D1234567", (LabelReader.read("XTA21074OD1234567") as LabelReader.Result.Vin).vin)
    }

    @Test fun article() {
        val r = LabelReader.read("MANN\nFILTER\nW 75/3\nMade in Germany\n4011558738100") as LabelReader.Result.Part
        assertEquals("W753", r.q.article)
        assertEquals("MANN-FILTER", r.q.brand)
        val k = LabelReader.read("KNECHT\nOC 90\nOil filter") as LabelReader.Result.Part
        assertEquals("OC90", k.q.article)
        assertTrue(LabelReader.read("Hello world") is LabelReader.Result.Nothing)
    }
}
