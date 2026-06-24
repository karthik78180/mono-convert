package com.monoconvert.config

data class GitConfig(
    val baseUrl: String,
    val defaultBranch: String = "main",
)

data class TemplateConfig(
    val repo: String? = null,   // clone mode (template repo name)
    val path: String? = null,   // local mode (template directory, copied as-is)
)

data class ToolConfig(
    val git: GitConfig,
    val template: TemplateConfig,
)
