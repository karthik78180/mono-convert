package com.monoconvert

/** Thrown for any expected, user-facing migration failure (config, gate, validation). */
class MigrationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
