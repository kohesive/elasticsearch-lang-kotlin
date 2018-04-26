package uy.kohesive.elasticsearch.kotlinscript

import org.apache.lucene.index.LeafReaderContext
import org.elasticsearch.SpecialPermission
import org.elasticsearch.script.ExecutableScript
import org.elasticsearch.script.SearchScript
import org.elasticsearch.search.lookup.SearchLookup
import org.jetbrains.kotlin.cli.common.repl.ScriptArgsWithTypes
import uy.kohesive.cuarentena.Cuarentena
import uy.kohesive.cuarentena.NamedClassBytes
import uy.kohesive.cuarentena.policy.AccessTypes
import uy.kohesive.cuarentena.policy.PolicyAllowance
import uy.kohesive.cuarentena.policy.toPolicy
import java.security.AccessController
import java.security.PrivilegedAction
import java.util.function.DoubleSupplier
import java.util.function.Function


/**
 * KotlinScriptImpl can be used as either an [ExecutableScript] or a [SearchScript]
 * to run a previously compiled Kotlin script.
 */
class KotlinScriptImpl(
    private val script: PreparedScript,
    vars: Map<String, Any>?,
    lookup: SearchLookup?,
    leafContext: LeafReaderContext?
) : SearchScript(null, lookup, leafContext) {

    /**
     * A map that can be used to access input parameters at run-time.
     */
    private val variables: MutableMap<String, Any> = ((vars?.toMap() ?: emptyMap()) +
            (leafLookup?.asMap() ?: emptyMap())).toMutableMap()

    /**
     * Looks up the `_score` from [.scorer] if `_score` is used, otherwise returns `0.0`.
     */
    private val scoreLookup: SearchScript.() -> Double =
        if (script.scoreFieldAccessed) {
            { this.score }
        } else {
            { 0.0 }
        }

    // like we have scoreFieldAccessed, should this be done for ctx?  is it really an optimization?

    /**
     * Current _value for aggregation
     * @see .setNextAggregationValue
     */
    private var aggregationValue: Any? = null

    override fun getParams(): Map<String, Any> {
        return variables
    }

    override fun setNextVar(name: String, value: Any) {
        variables[name] = value
    }

    override fun setNextAggregationValue(value: Any) {
        this.aggregationValue = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun run(): Any? {
        val variables = variables
        val score = scoreLookup()
        val doc = (doc as? Map<String, List<Any?>>) ?: emptyMap()
        val aggregationValue = aggregationValue
        val ctx = variables["ctx"] as? MutableMap<String, Any> ?: hashMapOf()

        val scriptArgs = makeArgs(variables, score, doc, ctx, aggregationValue)
        return script.code.invoker(script.code, scriptArgs)
    }

    override fun runAsDouble(): Double {
        return (run() as Number).toDouble()
    }

    override fun runAsLong(): Long {
        return (run() as Number).toLong()
    }
}


data class ExecutableCode(
    val className: String, val code: String,
    val classes: List<NamedClassBytes>,
    val verification: Cuarentena.VerifyResults,
    val extraData: Any? = null,
    val scriptClassLoader: ClassLoader,
    val invoker: ExecutableCode.(ScriptArgsWithTypes) -> Any?
) {
    val deserAdditionalPolicies = classes.map {
        PolicyAllowance.ClassLevel.ClassAccess(
            it.className, setOf(
                AccessTypes.ref_Class_Instance
            )
        )
    }.toPolicy().toSet()

    fun runWithArgs(args: ScriptArgsWithTypes): Any? {
        val sm = System.getSecurityManager()
        sm.checkPermission(SpecialPermission())
        val ocl = AccessController.doPrivileged(PrivilegedAction { Thread.currentThread().contextClassLoader })
        return try {
            AccessController.doPrivileged(PrivilegedAction {
                Thread.currentThread().contextClassLoader = scriptClassLoader
            })
            with(code) { invoker(args) }
        } finally {
            AccessController.doPrivileged(PrivilegedAction { Thread.currentThread().contextClassLoader = ocl })
        }
    }
}

data class PreparedScript(val code: ExecutableCode, val scoreFieldAccessed: Boolean)