package uy.kohesive.elasticsearch.kotlinscript

import org.jetbrains.kotlin.cli.common.repl.ScriptArgsWithTypes
import kotlin.reflect.KClass


val SCRIPT_ARGS_TYPES: Array<out KClass<out Any>> = arrayOf(Map::class, Map::class, Map::class, Any::class, Double::class)
val EMPTY_SCRIPT_ARGS: Array<out Any?> = makeArgs().scriptArgs  // must be after types

fun makeArgs(variables: Map<String, Any> = emptyMap(),
             score: Double = 0.0,
             doc: MutableMap<String, MutableList<Any>> = hashMapOf(),
             ctx: MutableMap<String, Any> = hashMapOf(),
             value: Any? = null): ScriptArgsWithTypes {
    return ScriptArgsWithTypes(arrayOf(variables, doc, ctx, value, score), SCRIPT_ARGS_TYPES)
}
