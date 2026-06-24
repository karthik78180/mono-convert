package com.monoconvert.source

import java.nio.file.Path

/** A source repo resolved onto the local filesystem, ready to inventory. */
data class SourceRepo(
    val name: String,
    val target: String,
    val root: Path,
)
