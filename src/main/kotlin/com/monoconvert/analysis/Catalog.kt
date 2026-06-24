package com.monoconvert.analysis

/** A `[libraries]` catalog entry. */
data class CatalogLibrary(val alias: String, val module: String, val version: String)

/** A `[plugins]` catalog entry. */
data class CatalogPlugin(val alias: String, val id: String, val version: String)

/** The unified version catalog model (libraries + plugins). */
data class CatalogModel(val libraries: List<CatalogLibrary>, val plugins: List<CatalogPlugin>)

/**
 * Builds a [CatalogModel] from resolved items. Only items with a fixed winner are catalog-ized
 * (dynamic/unresolved items are reported elsewhere, never auto-pinned). Library aliases default to
 * the artifact id, de-collided by the last group segment when two artifacts share a name.
 * Plugin alias derivation (last id segment) is intentionally simple; formalizing naming is a Plan 3 item.
 */
object CatalogBuilder {

    fun build(items: List<ResolvedItem>): CatalogModel {
        val libItems = items.filter { it.kind == ItemKind.LIBRARY && it.winner != null }
        val libModules = libItems.map { it.key }
        val libraries = libItems
            .map { CatalogLibrary(libraryAlias(it.key, libModules), it.key, it.winner!!.toString()) }
            .sortedBy { it.alias }
        val plugins = items
            .filter { it.kind == ItemKind.PLUGIN && it.winner != null }
            .map { CatalogPlugin(pluginAlias(it.key), it.key, it.winner!!.toString()) }
            .sortedBy { it.alias }
        return CatalogModel(libraries, plugins)
    }

    private fun libraryAlias(module: String, allModules: List<String>): String {
        val parts = module.split(":")
        val group = parts[0]
        val artifact = parts.getOrElse(1) { module }
        val base = artifact.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val collides = allModules.any { it != module && it.split(":").getOrNull(1) == artifact }
        return if (collides) "${group.substringAfterLast('.')}-$base" else base
    }

    private fun pluginAlias(id: String): String = id.substringAfterLast('.')
}
