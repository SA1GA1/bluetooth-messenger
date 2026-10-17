package com.example.bluetooth_messenger.domain.model

import org.junit.Assert.assertThrows
import org.junit.Test

class MessageLimitsTest {
    @Test fun rejectsNonPrintingControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) { validateText("x\u0000\u0001") }
    }
    @Test fun preservesOrdinaryWhitespaceInText() { validateText("строка\nвторая\r\n\tотступ") }
    @Test fun rejectsBlankText() { assertThrows(IllegalArgumentException::class.java) { validateText(" \n") } }
    @Test fun acceptsMaximumUtf8Text() { validateText("я".repeat(8192)) }
    @Test fun rejectsOversizeUtf8Text() {
        assertThrows(IllegalArgumentException::class.java) { validateText("я".repeat(8193)) }
    }
    @Test fun publicTextHasSmallerLimit() {
        assertThrows(IllegalArgumentException::class.java) { validateText("a".repeat(2049), public = true) }
    }
}
