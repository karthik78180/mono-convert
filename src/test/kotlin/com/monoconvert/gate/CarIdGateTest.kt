package com.monoconvert.gate

import com.monoconvert.MigrationException
import com.monoconvert.source.SourceRepo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CarIdGateTest {

    private val gate = CarIdGate()

    private fun repoWithCarId(tmp: Path, name: String, carId: String?): SourceRepo {
        val root = tmp.resolve(name)
        val meta = root.resolve("meta")
        meta.createDirectories()
        val body = if (carId == null) "otherKey: x\n" else "carId: $carId\notherKey: x\n"
        meta.resolve("source.yaml").writeText(body)
        return SourceRepo(name = name, target = name, root = root)
    }

    @Test
    fun `passes and returns the shared carId when all repos agree`(@TempDir tmp: Path) {
        val repos = listOf(
            repoWithCarId(tmp, "a", "200009890"),
            repoWithCarId(tmp, "b", "200009890"),
        )

        gate.verify(repos) shouldBe "200009890"
    }

    @Test
    fun `fails when carIds differ`(@TempDir tmp: Path) {
        val repos = listOf(
            repoWithCarId(tmp, "a", "200009890"),
            repoWithCarId(tmp, "b", "999999999"),
        )

        val ex = shouldThrow<MigrationException> { gate.verify(repos) }
        ex.message!!.contains("carId mismatch") shouldBe true
    }

    @Test
    fun `fails when carId is missing in a repo`(@TempDir tmp: Path) {
        val repos = listOf(repoWithCarId(tmp, "a", null))

        val ex = shouldThrow<MigrationException> { gate.verify(repos) }
        ex.message!!.contains("missing 'carId'") shouldBe true
    }

    @Test
    fun `fails when meta source-yaml file is missing entirely`(@TempDir tmp: Path) {
        val root = tmp.resolve("no-meta")
        root.createDirectories() // repo root exists, but no meta/source.yaml
        val repos = listOf(SourceRepo(name = "no-meta", target = "no-meta", root = root))

        val ex = shouldThrow<MigrationException> { gate.verify(repos) }
        ex.message!!.contains("missing meta/source.yaml") shouldBe true
    }

    @Test
    fun `fails when carId is blank`(@TempDir tmp: Path) {
        val repos = listOf(repoWithCarId(tmp, "a", "\"\""))

        val ex = shouldThrow<MigrationException> { gate.verify(repos) }
        ex.message!!.contains("blank 'carId'") shouldBe true
    }

    @Test
    fun `treats numeric and quoted carId as equal`(@TempDir tmp: Path) {
        val repos = listOf(
            repoWithCarId(tmp, "numeric", "200009890"),
            repoWithCarId(tmp, "quoted", "\"200009890\""),
        )

        gate.verify(repos) shouldBe "200009890"
    }
}
