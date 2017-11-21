package uy.kohesive.elasticsearch.kotlinscript.client

import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.action.update.UpdateRequestBuilder
import org.elasticsearch.index.reindex.UpdateByQueryRequestBuilder
import org.elasticsearch.script.Script
import org.elasticsearch.script.ScriptType
import uy.kohesive.elasticsearch.kotlinscript.common.EsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.KotlinScriptConfiguredChillambda

fun <T: Any?> lambdaToScript(params: Map<String, Any> = emptyMap(), lambda: EsKotlinScriptTemplate.() -> T): Script {
    return Script(ScriptType.INLINE, "kotlin",  KotlinScriptConfiguredChillambda.chillambda.serializeLambdaToBase64<EsKotlinScriptTemplate, Any>(lambda), params)
}

// ===

fun <T : Any?> SearchRequestBuilder.setKotlinScript(name: String, params: Map<String, Any> = emptyMap(), lambda: EsKotlinScriptTemplate.() -> T): SearchRequestBuilder {
    return this.addScriptField(name, lambdaToScript(params, lambda))
}

fun SearchRequestBuilder.setKotlinScript(name: String, params: Map<String, Any> = emptyMap(), scriptCode: String): SearchRequestBuilder {
    return this.addScriptField(name, Script(ScriptType.INLINE, "kotlin", scriptCode, params))
}

// ===

fun <T : Any?> UpdateRequestBuilder.setKotlinScript(params: Map<String, Any> = emptyMap(), lambda: EsKotlinScriptTemplate.() -> T): UpdateRequestBuilder {
    return this.setScript(lambdaToScript(params, lambda))
}

fun UpdateRequestBuilder.setKotlinScript(params: Map<String, Any> = emptyMap(), scriptCode: String): UpdateRequestBuilder {
    return this.setScript(Script(ScriptType.INLINE, "kotlin", scriptCode, params))
}

// ===

fun <T : Any?> UpdateByQueryRequestBuilder.setKotlinScript(params: Map<String, Any> = emptyMap(), lambda: EsKotlinScriptTemplate.() -> T): UpdateByQueryRequestBuilder {
    return this.script(lambdaToScript(params, lambda))
}

fun UpdateByQueryRequestBuilder.setKotlinScript(params: Map<String, Any> = emptyMap(), scriptCode: String): UpdateByQueryRequestBuilder {
    return this.script(Script(ScriptType.INLINE, "kotlin", scriptCode, params))
}


