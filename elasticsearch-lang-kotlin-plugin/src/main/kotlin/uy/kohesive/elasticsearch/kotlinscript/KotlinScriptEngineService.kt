package uy.kohesive.elasticsearch.kotlinscript

import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.search.Scorer
import org.elasticsearch.SpecialPermission
import org.elasticsearch.common.SuppressForbidden
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.script.*
import org.elasticsearch.search.lookup.LeafSearchLookup
import org.elasticsearch.search.lookup.SearchLookup
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.repl.ReplCodeLine
import org.jetbrains.kotlin.cli.common.repl.ReplCompileResult
import org.jetbrains.kotlin.cli.common.repl.ScriptArgsWithTypes
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.repl.GenericReplCompiler
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.utils.PathUtil
import uy.kohesive.chillamda.Chillambda
import uy.kohesive.cuarentena.Cuarentena
import uy.kohesive.cuarentena.Cuarentena.Companion.painlessPlusKotlinPolicy
import uy.kohesive.cuarentena.NamedClassBytes
import uy.kohesive.cuarentena.policy.AccessTypes
import uy.kohesive.cuarentena.policy.PolicyAllowance
import uy.kohesive.cuarentena.policy.toPolicy
import uy.kohesive.elasticsearch.kotlinscript.common.ConcreteEsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.EsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.KotlinScriptConfiguredChillambda
import uy.kohesive.elasticsearch.kotlinscript.common.KotlinScriptConfiguredChillambda.scriptTemplateCuarentenaPolicies
import uy.kohesive.keplin.util.ClassPathUtils.findRequiredScriptingJarFiles
import uy.kohesive.keplin.util.KotlinScriptDefinitionEx
import java.io.File
import java.security.AccessController
import java.security.PrivilegedAction
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class KotlinScriptEngineService(val settings: Settings) : ScriptEngineService {
    companion object {
        val LANGUAGE_NAME = KotlinScriptPlugin.LANGUAGE_NAME
        val uniqueScriptId: AtomicInteger = AtomicInteger(0)

        val SCRIPT_RESULT_FIELD_NAME = "\$\$result"


    }

    val sm = System.getSecurityManager()
    val uniqueSessionId = UUID.randomUUID().toString()
    val compilerMessages: MessageCollector = CapturingMessageCollector()

    val disposable = run {
        sm.checkPermission(SpecialPermission())
        AccessController.doPrivileged(PrivilegedAction
        {
            Disposer.newDisposable()
        })
    }

    val chillambda by lazy {
        sm.checkPermission(SpecialPermission())
        AccessController.doPrivileged(PrivilegedAction
        {
            KotlinScriptConfiguredChillambda.chillambda
        })
    }

    val repl by lazy {
        sm.checkPermission(SpecialPermission())
        AccessController.doPrivileged(PrivilegedAction {
            val scriptDefinition = KotlinScriptDefinitionEx(EsKotlinScriptTemplate::class, makeArgs())
            val additionalClasspath = emptyList<File>()
            val moduleName = "kotlin-script-module-${uniqueSessionId}"
            val messageCollector = compilerMessages
            val compilerConfig = CompilerConfiguration().apply {
                addJvmClasspathRoots(PathUtil.getJdkClassesRootsFromCurrentJre())
                addJvmClasspathRoots(findRequiredScriptingJarFiles(scriptDefinition.template,
                        includeScriptEngine = false,
                        includeKotlinCompiler = false,
                        includeStdLib = true,
                        includeRuntime = true))
                addJvmClasspathRoots(additionalClasspath)
                put(CommonConfigurationKeys.MODULE_NAME, moduleName)
                put<MessageCollector>(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
            }

            GenericReplCompiler(disposable, scriptDefinition, compilerConfig, messageCollector)
        })
    }

    override fun getExtension(): String = LANGUAGE_NAME

    override fun getType(): String = LANGUAGE_NAME

    override fun executable(compiledScript: CompiledScript, vars: Map<String, Any>?): ExecutableScript {
        return ExecutableKotlin(compiledScript, vars)
    }

    class ExecutableKotlin(val compiledScript: CompiledScript, val params: Map<String, Any>?) : ExecutableScript {
        val _mutableVars: MutableMap<String, Any> = HashMap<String, Any>(params)

        override fun run(): Any? {
            @Suppress("UNCHECKED_CAST")
            val ctx = _mutableVars.get("ctx") as? MutableMap<String, Any> ?: hashMapOf()
            val args = makeArgs(variables = _mutableVars, ctx = ctx)
            val executable = compiledScript.compiled() as PreparedScript
            return executable.code.invoker(executable.code, args)
        }

        override fun setNextVar(name: String, value: Any) {
            _mutableVars.put(name, value)
        }

    }

    override fun search(compiledScript: CompiledScript, lookup: SearchLookup, vars: Map<String, Any>?): SearchScript {
        return object : SearchScript {
            override fun needsScores(): Boolean {
                return (compiledScript.compiled() as PreparedScript).scoreFieldAccessed
            }

            override fun getLeafSearchScript(context: LeafReaderContext?): LeafSearchScript {
                return LeafSearchScriptKotlin(compiledScript, vars, lookup.getLeafSearchLookup(context))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class LeafSearchScriptKotlin(val compiledScript: CompiledScript, val vars: Map<String, Any>?, val lookup: LeafSearchLookup) : LeafSearchScript {
        val _mutableVars: MutableMap<String, Any> = HashMap<String, Any>(vars).apply {
            putAll(lookup.asMap())
        }

        var _doc = lookup.doc() as MutableMap<String, MutableList<Any>>
        var _aggregationValue: Any? = null
        var _scorer: Scorer? = null

        // TODO:  implement something for _score and ctx standard var names

        override fun run(): Any? {
            val score = _scorer?.score()?.toDouble() ?: 0.0
            val ctx = _mutableVars.get("ctx") as? MutableMap<String, Any> ?: hashMapOf()
            val args = makeArgs(_mutableVars, score, _doc, ctx, _aggregationValue)
            val executable = compiledScript.compiled() as PreparedScript
            return executable.code.invoker(executable.code, args)
        }

        override fun setScorer(scorer: Scorer?) {
            _scorer = scorer
        }

        override fun setNextVar(name: String, value: Any) {
            _mutableVars.put(name, value)
        }

        override fun setNextAggregationValue(value: Any?) {
            _aggregationValue = value
        }

        override fun runAsDouble(): Double {
            return run() as Double
        }

        override fun runAsLong(): Long {
            return run() as Long
        }

        override fun setSource(source: MutableMap<String, Any>?) {
            lookup.source().setSource(source)
        }

        override fun setDocument(doc: Int) {
            lookup.setDocument(doc)
        }
    }

    override fun close() {
    }

    val scriptTemplateConstructor = ::ConcreteEsKotlinScriptTemplate

    override fun compile(scriptName: String?, scriptSource: String, params: Map<String, String>?): Any {
        sm.checkPermission(SpecialPermission())
        return AccessController.doPrivileged(PrivilegedAction
        {
            val executableCode = if (Chillambda.isPrefixedBase64(scriptSource)) {
                try {
                    val (className, classesAsBytes, serInstance, verification) = chillambda.deserFromPrefixedBase64<EsKotlinScriptTemplate, Any>(scriptSource)
                    val classLoader = ScriptClassLoader(Thread.currentThread().contextClassLoader).apply {
                        classesAsBytes.forEach {
                            addClass(it.className, it.bytes)
                        }
                    }
                    ExecutableCode(className, scriptSource, classesAsBytes, verification, serInstance) { scriptArgs ->
                        // this is ok to be in the privileged block since it is executed later in a
                        // non privileged scope.
                        val ocl = Thread.currentThread().contextClassLoader
                        try {
                            Thread.currentThread().contextClassLoader = classLoader
                            // deser every time in case it is mutable, we don't want a changing base (or is that really possible?)
                            try {
                                val lambda: EsKotlinScriptTemplate.() -> Any? = chillambda.instantiateSerializedLambdaSafely(className, serInstance, deserAdditionalPolicies)
                                val scriptTemplate = scriptTemplateConstructor.call(*scriptArgs.scriptArgs)
                                lambda.invoke(scriptTemplate)
                            } catch (ex: Exception) {
                                throw ScriptException(ex.message ?: "Error executing Lambda", ex, emptyList(), scriptSource, LANGUAGE_NAME)
                            }
                        } finally {
                            Thread.currentThread().contextClassLoader = ocl
                        }
                    }
                } catch (ex: Exception) {
                    if (ex is ScriptException) throw ex
                    else throw ScriptException(ex.message ?: "unknown error", ex, emptyList(), scriptSource, LANGUAGE_NAME)
                }
            } else {
                val scriptId = uniqueScriptId.incrementAndGet()
                val compilerOutCapture = CapturingMessageCollector()
                val compilerOutputs = arrayListOf<File>()
                try {
                   val codeLine = ReplCodeLine(scriptId, 0, scriptSource)
                    try {
                        val replState = repl.createState()
                        val replResult = repl.compile(replState, codeLine)
                        val compiledCode = when (replResult) {
                            is ReplCompileResult.Error -> throw toScriptException(replResult.message, scriptSource, replResult.location)
                            is ReplCompileResult.Incomplete -> throw toScriptException("Incomplete code", scriptSource, null)
                            is ReplCompileResult.CompiledClasses -> replResult
                        }

                        val classesAsBytes = compiledCode.classes.map {
                            NamedClassBytes(it.path.removeSuffix(".class").replace('/', '.'), it.bytes)
                        }

                        val classLoader = ScriptClassLoader(Thread.currentThread().contextClassLoader).apply {
                            classesAsBytes.forEach {
                                addClass(it.className, it.bytes)
                            }
                        }

                        val scriptClass = classLoader.loadClass(compiledCode.mainClassName)
                        val scriptConstructor = scriptClass.constructors.first()
                        val resultField = scriptClass.getDeclaredField(SCRIPT_RESULT_FIELD_NAME).apply { isAccessible = true }

                        val verification = chillambda.verifier.verifyClassAgainstPolicies(classesAsBytes, additionalPolicies = scriptTemplateCuarentenaPolicies)
                        if (verification.failed) {
                            val violations = verification.violations.sorted()
                            val exp = Exception("Illegal Access to unauthorized classes/methods: ${verification.violationsAsString()}")
                            throw  ScriptException(exp.message, exp, violations, scriptSource, LANGUAGE_NAME)
                        }

                        ExecutableCode(compiledCode.mainClassName, scriptSource, verification.filteredClasses, verification) { scriptArgs ->
                            // this is ok to be in the privileged block since it is executed later in a
                            // non privileged scope.
                            val completedScript = scriptConstructor.newInstance(*scriptArgs.scriptArgs)
                            resultField.get(completedScript)
                        }
                    } catch (ex: Exception) {
                        throw ScriptException(ex.message ?: "unknown error", ex, emptyList<String>(), scriptSource, LANGUAGE_NAME)
                    }
                } catch (ex: Exception) {
                    if (ex is ScriptException) throw ex
                    else throw ScriptException(ex.message ?: "unknown error", ex, emptyList(), scriptSource, LANGUAGE_NAME)
                }
            }

            val scoreAccessPossibilities = listOf(
                    PolicyAllowance.ClassLevel.ClassPropertyAccess(EsKotlinScriptTemplate::class.java.canonicalName, "_score", "D", setOf(AccessTypes.read_Class_Instance_Property))
            ).toPolicy()
            val isScoreAccessed = executableCode.verification.scanResults.allowances.any {
                it.asCheckStrings(true).any { it in scoreAccessPossibilities }
            }
            PreparedScript(executableCode, isScoreAccessed)
        })
    }

    data class ExecutableCode(val className: String, val code: String, val classes: List<NamedClassBytes>, val verification: Cuarentena.VerifyResults, val extraData: Any? = null, val invoker: ExecutableCode.(ScriptArgsWithTypes) -> Any?) {
        val deserAdditionalPolicies = classes.map { PolicyAllowance.ClassLevel.ClassAccess(it.className, setOf(AccessTypes.ref_Class_Instance)) }.toPolicy().toSet()
    }

    data class PreparedScript(val code: ExecutableCode, val scoreFieldAccessed: Boolean)
}

fun toScriptException(message: String, code: String, location: CompilerMessageLocation?): ScriptException {
    val msgs = message.split('\n')
    val text = msgs[0]
    val snippet = if (msgs.size > 1) msgs[1] else ""
    val errorMarker = if (msgs.size > 2) msgs[2] else ""
    val exp = Exception(text)
    return ScriptException(exp.message, exp, listOf(snippet, errorMarker), code, KotlinScriptEngineService.LANGUAGE_NAME)
}
