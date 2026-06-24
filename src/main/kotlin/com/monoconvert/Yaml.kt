package com.monoconvert

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * Single shared YAML mapper for all config/metadata readers. Lenient (ignores
 * unknown properties) so files can gain fields without breaking parsing.
 * Jackson's [ObjectMapper] is thread-safe once configured, so one instance is reused.
 */
object Yaml {
    val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}
