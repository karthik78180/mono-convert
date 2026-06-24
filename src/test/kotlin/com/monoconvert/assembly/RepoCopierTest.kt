package com.monoconvert.assembly

import com.monoconvert.source.SourceRepo
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RepoCopierTest {

    @Test
    fun `copies repo root into monorepo under target name`(@TempDir tmp: Path) {
        val repoRoot = tmp.resolve("src-payments").also { it.resolve("config/charge").createDirectories() }
        repoRoot.resolve("build.gradle").writeText("plugins { id 'java' }\n")
        repoRoot.resolve("config/charge/lambda.json").writeText("{}\n")
        val repo = SourceRepo(name = "payments-service", target = "payments-service", root = repoRoot)
        val monorepo = tmp.resolve("out/vehicle-platform").also { it.createDirectories() }

        val dest = RepoCopier.copy(repo, monorepo)

        dest shouldBe monorepo.resolve("payments-service")
        dest.resolve("build.gradle").readText() shouldBe "plugins { id 'java' }\n"
        dest.resolve("config/charge/lambda.json").shouldExist()
    }

    @Test
    fun `does not mutate the source repo`(@TempDir tmp: Path) {
        val repoRoot = tmp.resolve("src").also { it.createDirectories() }
        repoRoot.resolve("build.gradle").writeText("// original\n")
        val repo = SourceRepo(name = "r", target = "r", root = repoRoot)
        val monorepo = tmp.resolve("out").also { it.createDirectories() }

        RepoCopier.copy(repo, monorepo)

        repoRoot.resolve("build.gradle").readText() shouldBe "// original\n"
    }
}
