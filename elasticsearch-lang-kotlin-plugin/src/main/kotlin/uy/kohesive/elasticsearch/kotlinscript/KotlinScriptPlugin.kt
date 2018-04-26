package uy.kohesive.elasticsearch.kotlinscript

import org.elasticsearch.SpecialPermission
import org.elasticsearch.common.settings.Setting
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.plugins.ScriptPlugin
import org.elasticsearch.script.ScriptContext
import org.elasticsearch.script.ScriptEngine
import uy.kohesive.elasticsearch.kotlinscript.common.KotlinScriptConfiguredChillambda
import java.security.AccessController
import java.security.PrivilegedAction

class KotlinScriptPlugin : Plugin(), ScriptPlugin {
    companion object {
        val LANGUAGE_NAME = "kotlin"
    }

    init {
        val sm = System.getSecurityManager()
        sm.checkPermission(SpecialPermission())
        AccessController.doPrivileged(PrivilegedAction {
            try {
                KotlinScriptConfiguredChillambda.init()
            } catch (ex: Exception) {
                // TODO: logging
                ex.printStackTrace()
                throw ex
            }
        })
    }

    override fun getSettings(): List<Setting<*>> {
        return emptyList() // listOf(Setting.simpleString(KotlinPath, Setting.Property.NodeScope))
    }

    override fun getScriptEngine(settings: Settings, contexts: MutableCollection<ScriptContext<*>>): ScriptEngine {
        return KotlinScriptEngineService(settings, contexts)
    }

}
