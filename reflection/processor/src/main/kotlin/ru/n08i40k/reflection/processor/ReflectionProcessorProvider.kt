package ru.n08i40k.reflection.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP options:
 * - `reflection.classpath` — client jars to check the mirrors against, separated by the path separator;
 * - `reflection.clientVersion` — client version of these jars, by default read from BuildVars;
 * - `reflection.optIn` — "true" marks versioned accessors with @RequiresClientSince/Before/Between.
 */
public class ReflectionProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ReflectionProcessor(environment.codeGenerator, environment.logger, environment.options)
}
