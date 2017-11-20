package uy.kohesive.elasticsearch.kotlinscript.client

import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.script.Script
import org.elasticsearch.script.ScriptType
import uy.kohesive.elasticsearch.kotlinscript.common.EsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.KotlinScriptConfiguredChillambda

fun <T : Any?> SearchRequestBuilder.addScriptField(name: String, params: Map<String, Any> = emptyMap(), lambda: EsKotlinScriptTemplate.() -> T): SearchRequestBuilder {
    return this.addScriptField(name, lambdaToScript(params, lambda))
}

fun <T: Any?> lambdaToScript(params: Map<String, Any> = emptyMap(), lambda: EsKotlinScriptTemplate.() -> T): Script {
    return Script(ScriptType.INLINE, "kotlin",  KotlinScriptConfiguredChillambda.chillambda.serializeLambdaToBase64<EsKotlinScriptTemplate, Any>(lambda), params)
}

fun SearchRequestBuilder.addScriptField(name: String, params: Map<String, Any> = emptyMap(), scriptCode: String): SearchRequestBuilder {
    return this.addScriptField(name, Script(ScriptType.INLINE, "kotlin", scriptCode, params))
}