package com.monoconvert.config

data class GitConfig(
    val baseUrl: String,
    val defaultBranch: String = "main",
)

data class TemplateConfig(
    val repo: String,
)

data class ToolConfig(
    val git: GitConfig,
    val template: TemplateConfig,
)
