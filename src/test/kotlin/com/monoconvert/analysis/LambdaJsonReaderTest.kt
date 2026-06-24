package com.monoconvert.analysis

import com.monoconvert.TestFixtures
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test

class LambdaJsonReaderTest {

    @Test
    fun `reads top-level version and depAddress functionVersion`() {
        val file = TestFixtures.path("source-repos/payments-service/config/charge/lambda.json")

        LambdaJsonReader.versionCandidates(file)
            .shouldContainExactlyInAnyOrder(listOf("1.0.9", "1.0.9"))
    }
}
