package com.example.mcc_phase3.execution

import java.io.File

/**
 * Central resolver for Crowdio runtime path aliases injected into Python builtins.
 */
object CrowdioPathAliasResolver {
    const val FILE_DIR = "@CROWDIO:FILE_DIR"
    const val CACHE_DIR = "@CROWDIO:CACHE_DIR"
    const val OUTPUT_DIR = "@CROWDIO:OUTPUT_DIR"

    fun buildAliasMap(fileDir: String, cacheDir: String, outputDir: String): Map<String, String> {
        return mapOf(
            FILE_DIR to fileDir,
            CACHE_DIR to cacheDir,
            OUTPUT_DIR to outputDir
        )
    }

    fun allAliases(): Set<String> = setOf(FILE_DIR, CACHE_DIR, OUTPUT_DIR)

    fun isAliasToken(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return allAliases().contains(value.trim())
    }

    fun validate(fileDir: String, outputDir: String): String? {
        val inputDir = File(fileDir)
        if (!inputDir.exists() || !inputDir.isDirectory) {
            return "FILE_DIR is invalid or missing: $fileDir"
        }

        val output = File(outputDir)
        if (!output.exists() && !output.mkdirs()) {
            return "OUTPUT_DIR could not be created: $outputDir"
        }
        if (!output.isDirectory) {
            return "OUTPUT_DIR is not a directory: $outputDir"
        }
        if (!output.canWrite()) {
            return "OUTPUT_DIR is not writable: $outputDir"
        }

        return null
    }
}
