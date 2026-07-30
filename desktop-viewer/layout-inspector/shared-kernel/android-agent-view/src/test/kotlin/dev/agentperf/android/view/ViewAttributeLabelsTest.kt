package com.androidperformancestudio.android.view

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewAttributeLabelsTest {
    @Test
    fun `maps Android visibility and layer constants to inspector labels`() {
        assertEquals("VISIBLE", ViewAttributeLabels.visibility(0))
        assertEquals("INVISIBLE", ViewAttributeLabels.visibility(4))
        assertEquals("GONE", ViewAttributeLabels.visibility(8))
        assertEquals("UNKNOWN(9)", ViewAttributeLabels.visibility(9))

        assertEquals("NONE", ViewAttributeLabels.layerType(0))
        assertEquals("SOFTWARE", ViewAttributeLabels.layerType(1))
        assertEquals("HARDWARE", ViewAttributeLabels.layerType(2))
        assertEquals("UNKNOWN(3)", ViewAttributeLabels.layerType(3))
    }
}
