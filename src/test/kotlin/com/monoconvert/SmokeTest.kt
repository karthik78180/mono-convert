package com.monoconvert

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun `project compiles and a trivial assertion holds`() {
        (1 + 1) shouldBe 2
    }
}
