package com.monoconvert.assembly

import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MetaConsolidatorTest {

    @Test
    fun `writes root carId and removes per-module meta`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("vehicle-platform").also { it.createDirectories() }
        val paymentsMeta = monorepo.resolve("payments-service/meta").also { it.createDirectories() }
        paymentsMeta.resolve("source.yaml").writeText("carId: 200009890\notherKey: payments\n")
        val billingMeta = monorepo.resolve("billing-service/meta").also { it.createDirectories() }
        billingMeta.resolve("source.yaml").writeText("carId: 200009890\n")

        val dest = MetaConsolidator.consolidate(
            monorepo, "200009890", listOf("payments-service", "billing-service"),
        )

        dest shouldBe monorepo.resolve("meta/source.yaml")
        dest.readText() shouldBe "carId: 200009890\n"
        monorepo.resolve("payments-service/meta").shouldNotExist()
        monorepo.resolve("billing-service/meta").shouldNotExist()
        monorepo.resolve("payments-service").shouldExist() // module dir itself stays
    }

    @Test
    fun `tolerates a module without a meta directory`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.resolve("svc").createDirectories() }

        val dest = MetaConsolidator.consolidate(monorepo, "42", listOf("svc"))

        dest.readText() shouldBe "carId: 42\n"
    }
}
