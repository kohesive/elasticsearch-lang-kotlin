package uy.kohesive.elasticsearch.kotlinscript

import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.search.Scorer
import org.elasticsearch.SpecialPermission
import org.elasticsearch.common.component.AbstractComponent
import org.elasticsearch.common.io.FastStringReader
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
import kotlin.reflect.KClass

class KotlinScriptEngineService(val settings: Settings,
                                val contexts: MutableCollection<ScriptContext<*>>)
    : AbstractComponent(settings), ScriptEngine {

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
        AccessController.doPrivileged(PrivilegedAction { Disposer.newDisposable() })
    }

    val chillambda = run {
        sm.checkPermission(SpecialPermission())
        AccessController.doPrivileged(PrivilegedAction { KotlinScriptConfiguredChillambda.chillambda })
    }


    val replCompilers = mutableMapOf<KClass<out Any>, GenericReplCompiler>().apply {
        for (context in contexts) {
            val templateClass = when (context.instanceClazz) {
                FilterScript::class.java -> EsKotlinScriptTemplate::class
                SearchScript::class.java -> EsKotlinScriptTemplate::class
                ExecutableScript::class.java -> EsKotlinScriptTemplate::class
                else -> EsKotlinScriptTemplate::class
            }
            put(context.instanceClazz.kotlin, makeReplCompiler(templateClass))
        }
    }

    fun <T: Any> makeReplCompiler(template: KClass<T>): GenericReplCompiler {
        sm.checkPermission(SpecialPermission())
        return AccessController.doPrivileged(PrivilegedAction {
            val scriptDefinition = KotlinScriptDefinitionEx(template, makeArgs())
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

    override fun getType(): String = LANGUAGE_NAME



    override fun <FactoryType> compile(
        scriptName: String,
        scriptSource: String,
        context: ScriptContext<FactoryType>,
        params: Map<String, String>
    ): FactoryType {
        sm.checkPermission(SpecialPermission())
        return AccessController.doPrivileged(PrivilegedAction
        {
            val executableCode = if (Chillambda.isPrefixedBase64(scriptSource)) {
                try {
                    val (className, classesAsBytes, serInstance, verification) = chillambda.deserFromPrefixedBase64<EsKotlinScriptTemplate, Any>(scriptSource)
                    val classLoader = ScriptClassLoader(this.javaClass.classLoader).apply {
                        classesAsBytes.forEach {
                            addClass(it.className, it.bytes)
                        }
                    }
                    ExecutableCode(className, scriptSource, classesAsBytes, verification, serInstance, classLoader) { scriptArgs ->
                        // this is deferred to be executed later, so is not really in priviledged block
                        // deser every time in case it is mutable, we don't want a changing base (or is that really possible?)
                        try {
                            val lambda: EsKotlinScriptTemplate.() -> Any? = chillambda.instantiateSerializedLambdaSafely(className, serInstance, scriptClassLoader, deserAdditionalPolicies)
                            val scriptTemplate = scriptTemplateConstructor.call(*scriptArgs.scriptArgs)
                            lambda.invoke(scriptTemplate)
                        } catch (ex: Exception) {
                            throw ScriptException(ex.message ?: "Error executing Lambda", ex, emptyList(), scriptSource, LANGUAGE_NAME)
                        }
                    }
                } catch (ex: Exception) {
                    if (ex is ScriptException) throw ex
                    else throw ScriptException(ex.message ?: "unknown error", ex, emptyList(), scriptSource, LANGUAGE_NAME)
                }
            } else {
                val compiler = replCompilers.get(context.instanceClazz.kotlin)
                        ?:  throw IllegalArgumentException("Kotlin engine does not know how to handle context [" + context.name + "]")

                val scriptId = uniqueScriptId.incrementAndGet()
                val compilerOutCapture = CapturingMessageCollector()
                val compilerOutputs = arrayListOf<File>()
                try {
                    val codeLine = ReplCodeLine(scriptId, 0, scriptSource)
                    try {
                        val replState = compiler.createState()
                        val replResult = compiler.compile(replState, codeLine)
                        val compiledCode = when (replResult) {
                            is ReplCompileResult.Error -> throw toScriptException(replResult.message, scriptSource, replResult.location)
                            is ReplCompileResult.Incomplete -> throw toScriptException("Incomplete code", scriptSource, null)
                            is ReplCompileResult.CompiledClasses -> replResult
                        }

                        val classesAsBytes = compiledCode.classes.map {
                            NamedClassBytes(it.path.removeSuffix(".class").replace('/', '.'), it.bytes)
                        }

                        val classLoader = ScriptClassLoader(this.javaClass.classLoader).apply {
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

                        ExecutableCode(compiledCode.mainClassName, scriptSource, verification.filteredClasses, verification, null, classLoader) { scriptArgs ->
                            // this is deferred to be executed later, so is not really in priviledged block
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

            val preparedScript = PreparedScript(executableCode, isScoreAccessed)

            if (context.instanceClazz == SearchScript::class.java) {
                val factory = SearchScript.Factory { p, lookup ->
                    object : SearchScript.LeafFactory {
                        override fun newInstance(context: LeafReaderContext): SearchScript {
                            return KotlinScriptImpl(preparedScript, p, lookup, context)
                        }

                        override fun needs_score(): Boolean {
                            return preparedScript.scoreFieldAccessed
                        }
                    }
                }
                context.factoryClazz.cast(factory)
            } else if (context.instanceClazz == ExecutableScript::class.java) {
                val factory = ExecutableScript.Factory { p ->
                    KotlinScriptImpl(preparedScript, p, null, null)
                }
                context.factoryClazz.cast(factory)
            } else {
                throw IllegalStateException("Unsure how to handle this search instance type")
            }
        })
    }

    override fun close() {
    }

    val scriptTemplateConstructor = ::ConcreteEsKotlinScriptTemplate


}

fun toScriptException(message: String, code: String, location: CompilerMessageLocation?): ScriptException {
    val msgs = message.split('\n')
    val text = msgs[0]
    val snippet = if (msgs.size > 1) msgs[1] else ""
    val errorMarker = if (msgs.size > 2) msgs[2] else ""
    val exp = Exception(text)
    return ScriptException(exp.message, exp, listOf(snippet, errorMarker), code, KotlinScriptEngineService.LANGUAGE_NAME)
}
