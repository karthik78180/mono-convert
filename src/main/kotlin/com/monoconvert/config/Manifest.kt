package com.monoconvert.config

import com.fasterxml.jackson.annotation.JsonProperty

enum class SourceMode {
    @JsonProperty("clone") CLONE,
    @JsonProperty("local") LOCAL,
}

data class MonorepoSpec(
    val name: String,
)

data class Manifest(
    val monorepo: MonorepoSpec,
    val source: SourceMode,
    val path: String? = null,
    val repos: List<String> = emptyList(),
)
