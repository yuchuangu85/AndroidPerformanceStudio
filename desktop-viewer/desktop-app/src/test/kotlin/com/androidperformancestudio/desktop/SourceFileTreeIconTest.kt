package com.androidperformancestudio.desktop

import com.androidperformancestudio.source.SourceLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceFileTreeIconTest {
    @Test
    fun `source languages map to their IDE-style file icon kinds`() {
        assertEquals(SourceFileTreeIconKind.KOTLIN, SourceLanguage.KOTLIN.sourceFileTreeIconKind())
        assertEquals(SourceFileTreeIconKind.JAVA, SourceLanguage.JAVA.sourceFileTreeIconKind())
        assertEquals(SourceFileTreeIconKind.XML, SourceLanguage.XML.sourceFileTreeIconKind())
        assertEquals(SourceFileTreeIconKind.NATIVE, SourceLanguage.C.sourceFileTreeIconKind())
        assertEquals(SourceFileTreeIconKind.NATIVE, SourceLanguage.CPP.sourceFileTreeIconKind())
        assertEquals(SourceFileTreeIconKind.GENERIC, SourceLanguage.OTHER.sourceFileTreeIconKind())
    }
}
