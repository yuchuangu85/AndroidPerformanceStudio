package com.androidperformancestudio.memory.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verified against clang++ -stdlib=libc++ mangled symbols demangled with llvm-cxxfilt (the real
 * Android libc++/ART/framework symbol corpus).
 */
class CppSymbolDemanglerTest {
    @Test
    fun `plain C functions pass through unchanged`() {
        assertEquals("malloc", CppSymbolDemangler.demangle("malloc"))
        assertEquals("calloc", CppSymbolDemangler.demangle("calloc"))
    }

    @Test
    fun `demangles new and delete operators`() {
        assertEquals("operator new(unsigned long)", CppSymbolDemangler.demangle("_Znwm"))
        assertEquals("operator delete(void*)", CppSymbolDemangler.demangle("_ZdlPv"))
    }

    @Test
    fun `demangles a plain function`() {
        assertEquals("makeString(char const*)", CppSymbolDemangler.demangle("_Z10makeStringPKc"))
    }

    @Test
    fun `demangles a nested name`() {
        assertEquals(
            "android::BitmapUtils::decode(android::String16 const*)",
            CppSymbolDemangler.demangle("_ZN7android11BitmapUtils6decodeEPKNS_8String16E"),
        )
        assertEquals(
            "android::BitmapUtils::decodeByValue(android::String16)",
            CppSymbolDemangler.demangle("_ZN7android11BitmapUtils13decodeByValueENS_8String16E"),
        )
    }

    @Test
    fun `demangles libcxx basic_string with template substitutions`() {
        assertEquals(
            "std::__1::basic_string<char, std::__1::char_traits<char>, std::__1::allocator<char>>::basic_string(char const*)",
            CppSymbolDemangler.demangle("_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEC2EPKc"),
        )
        assertEquals(
            "takeString(std::__1::basic_string<char, std::__1::char_traits<char>, std::__1::allocator<char>> const&)",
            CppSymbolDemangler.demangle("_Z10takeStringRKNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEE"),
        )
    }

    @Test
    fun `demangles nested template arguments referencing the enclosing scope`() {
        assertEquals(
            "useList(foo::List<foo::Type>&)",
            CppSymbolDemangler.demangle("_Z7useListRN3foo4ListINS_4TypeEEE"),
        )
        assertEquals(
            "takeStringPtr(foo::List<foo::Type> const*)",
            CppSymbolDemangler.demangle("_Z13takeStringPtrPKN3foo4ListINS_4TypeEEE"),
        )
    }

    @Test
    fun `demangles template function with template param return and substitutions`() {
        assertEquals(
            "int foo::method<int>(int, int)",
            CppSymbolDemangler.demangle("_ZN3foo6methodIiEET_S1_S1_"),
        )
    }
}
