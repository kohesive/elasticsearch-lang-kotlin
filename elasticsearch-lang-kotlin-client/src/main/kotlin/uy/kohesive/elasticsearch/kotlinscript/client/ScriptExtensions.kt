package uy.kohesive.elasticsearch.kotlinscript.client

import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.action.update.UpdateRequestBuilder
import org.elasticsearch.index.reindex.UpdateByQueryRequestBuilder
import org.elasticsearch.script.Script
import org.elasticsearch.script.ScriptType
import org.elasticsearch.search.aggregations.metrics.scripted.ScriptedMetricAggregationBuilder
import uy.kohesive.elasticsearch.kotlinscript.common.EsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.KotlinScriptConfiguredChillambda

fun <T: Any?> lambdaToScript(params: Map<String, Any> = emptyMap(), lambda: EsKotlinScriptTemplate.() -> T): Script {
    return Script(ScriptType.INLINE, "kotlin",  KotlinScriptConfiguredChillambda.chillambda.serializeLambdaToBase64<EsKotlinScriptTemplate, Any>(lambda), params)
}

// ===

fun <T : Any?> SearchRequestBuilder.addKotlinScriptField(name: String, params: Map<String, Any> = emptyMap(), lambda: EsKotlinScriptTemplate.() -> T): SearchRequestBuilder {
    return this.addScriptField(name, lambdaToScript(params, lambda))
}

fun SearchRequestBuilder.addKotlinScriptField(name: String, params: Map<String, Any> = emptyMap(), scriptCode: String): SearchRequestBuilder {
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

// ===

fun <T : Any?> ScriptedMetricAggregationBuilder.setKotlinInitScript(lambda: EsKotlinScriptTemplate.() -> T): ScriptedMetricAggregationBuilder {
    return this.initScript(lambdaToScript(emptyMap(), lambda))
}

fun ScriptedMetricAggregationBuilder.setKotlinInitScript(scriptCode: String): ScriptedMetricAggregationBuilder {
    return this.initScript(Script(ScriptType.INLINE, "kotlin", scriptCode, emptyMap()))
}
fun <T : Any?> ScriptedMetricAggregationBuilder.setKotlinMapScript(lambda: EsKotlinScriptTemplate.() -> T): ScriptedMetricAggregationBuilder {
    return this.mapScript(lambdaToScript(emptyMap(), lambda))
}

fun ScriptedMetricAggregationBuilder.setKotlinMapScript(scriptCode: String): ScriptedMetricAggregationBuilder {
    return this.mapScript(Script(ScriptType.INLINE, "kotlin", scriptCode, emptyMap()))
}

fun <T : Any?> ScriptedMetricAggregationBuilder.setKotlinCombineScript(lambda: EsKotlinScriptTemplate.() -> T): ScriptedMetricAggregationBuilder {
    return this.combineScript(lambdaToScript(emptyMap(), lambda))
}

fun ScriptedMetricAggregationBuilder.setKotlinCombineScript(scriptCode: String): ScriptedMetricAggregationBuilder {
    return this.combineScript(Script(ScriptType.INLINE, "kotlin", scriptCode, emptyMap()))
}

fun <T : Any?> ScriptedMetricAggregationBuilder.setKotlinReduceScript(lambda: EsKotlinScriptTemplate.() -> T): ScriptedMetricAggregationBuilder {
    return this.reduceScript(lambdaToScript(emptyMap(), lambda))
}

fun ScriptedMetricAggregationBuilder.setKotlinReduceScript(scriptCode: String): ScriptedMetricAggregationBuilder {
    return this.reduceScript(Script(ScriptType.INLINE, "kotlin", scriptCode, emptyMap()))
}