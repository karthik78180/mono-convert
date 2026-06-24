package com.monoconvert.assembly

import com.monoconvert.source.SourceRepo
import java.nio.file.Path

/** Phase 3: copies a source repo's tree into `<monorepoDir>/<target>/` verbatim (idempotent). */
object RepoCopier {

    fun copy(repo: SourceRepo, monorepoDir: Path): Path {
        val dest = monorepoDir.resolve(repo.target)
        repo.root.toFile().copyRecursively(dest.toFile(), overwrite = true)
        return dest
    }
}
