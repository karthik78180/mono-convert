package com.monoconvert.analysis

/**
 * Renders a [CatalogModel] as a `gradle/libs.versions.toml` preview string. Versions are inlined
 * per entry; sharing `[versions]` `version.ref`s across entries is a later optimization (spec §6.3).
 */
object CatalogRenderer {

    fun render(model: CatalogModel): String {
        val sb = StringBuilder()
        sb.appendLine("[libraries]")
        for (lib in model.libraries) {
            sb.appendLine("""${lib.alias} = { module = "${lib.module}", version = "${lib.version}" }""")
        }
        if (model.plugins.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[plugins]")
            for (p in model.plugins) {
                sb.appendLine("""${p.alias} = { id = "${p.id}", version = "${p.version}" }""")
            }
        }
        return sb.toString().trimEnd() + "\n"
    }
}
