package uy.kohesive.elasticsearch.kotlinscript.common

import uy.klutter.conversion.TypeConversionConfig.defaultConverter
import java.lang.reflect.Type


open class ConcreteEsKotlinScriptTemplate(param: Map<String, Any>,
                                          doc: MutableMap<String, MutableList<Any>>,
                                          ctx: MutableMap<String, Any>,
                                          _value: Any?,
                                          _score: Double) : EsKotlinScriptTemplate(param, doc, ctx, _value, _score)


/**
 * Where is the doc:
 *
 * Scripted updates, update by query:  https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-update.html
 *
 *     ctx._source.counter += number    // inc counter
 *     ctx._source.tags.add(params.tag) // add to list
 *     ctx._source.new_field = bla      // add new field to index
 *     ctx._source.remove(old_field)    // remove field from index
 *
 *     also in ctx is:  _index, _type, _id, _version, _routing, _parent, _now (current timestamp)
 *
 *     ctx.op = delete (delete doc instead of update), noop  (do nothing)
 *
 * Script fields in queries: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-script-fields.html
 *
 *     doc['myfield'].value               // access a current field in the doc, loading all values, caching, simple values, typically single value (but can be multi)
 *     params['_source'].['fieldName']    // access the original _source from the document, each doc parsed, slower, full JSON access
 *
 * Scripted aggregations: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-metrics-scripted-metric-aggregation.html
 *
 *     doc.type.value           // doc type
 *     doc.fieldName.value      // access field from doc
 *     params._agg.whatever     // can place anything here for map step, or retrieving in combine step
 *     params._agg              // contents for reduce script
 *
 *     scripts can only return or store into _agg object primitive, string, map (of same types listed here), array (of same types listed here)
 *
 * Script ingest processor: https://www.elastic.co/guide/en/elasticsearch/reference/current/script-processor.html
 *
 *    ctx.fieldName             // value of a field
 *
 * Function score query: https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-function-score-query.html#function-script-score
 *
 *    _score
 *    doc['myField'].value
 *    _index.*                  // doc stats https://www.elastic.co/guide/en/elasticsearch/reference/current/modules-advanced-scripting.html
 *
 *    return double
 *
 * All cases
 *
 *     params['paramName']
 *
 * Some cases
 *
 *     _fields['fieldName'].value    // stored: true fields
 *
 */
@Suppress("UNCHECKED_CAST")
abstract class EsKotlinScriptTemplate(val param: Map<String, Any>,
                                      val doc: MutableMap<String, MutableList<Any>>,
                                      val ctx: MutableMap<String, Any>,
                                      val _value: Any?,
                                      val _score: Double) {
    @Suppress("UNCHECKED_CAST")
    val _source: MutableMap<String, Any> by lazy(LazyThreadSafetyMode.NONE) {
        (ctx.get("_source") ?: param.get("_source") ?: throw IllegalStateException("_source is not availalbe in this context")) as MutableMap<String, Any>
    }

    val _agg: MutableMap<String, Any> by lazy(LazyThreadSafetyMode.NONE) {
        param["_agg"] as MutableMap<String, Any>
    }

    val _aggs: List<Any> by lazy(LazyThreadSafetyMode.NONE) {
        param["_aggs"] as List<Any>
    }

    fun <R : Any> convert(value: Any, toType: Type): R {
        return defaultConverter.convertValue<Any, R>(value::class.java, toType, value) as R
    }

    inline fun <reified T : Any> Any?.asList(): List<T> {
        return if (this == null) emptyList()
        else if (this is List<*>) (this@asList as List<Any>).map { convert<T>(it, T::class.java) }
        else listOf(convert<T>(this@asList, T::class.java))
    }

    inline fun <reified T : Any> Any?.asList(default: List<T>): List<T> {
        return if (this@asList == null) default else this@asList!!.asList<T>()
    }

    inline fun <reified T : Any> Any.asValue(): T {
        return if (this@asValue is List<*>) (this@asValue as List<Any>).single().let { convert<T>(it, T::class.java) }
        else convert<T>(this@asValue, T::class.java)
    }

    inline fun <reified T : Any> Any?.asValue(default: T): T {
        return if (this@asValue == null) default else this@asValue!!.asValue<T>()
    }

    inline fun <reified T : Any> Any?.cast(): T? {
        return this as? T
    }

    inline fun <reified T : Any> Any?.cast(default: T): T {
        return this as? T ?: default
    }

    fun <T : Any> Map<String, T>.intVal(field: String, default: Int): Int = get(field)?.asValue<Long>()?.toInt() ?: default
    fun <T : Any> Map<String, T>.longVal(field: String, default: Long): Long = get(field)?.asValue<Long>() ?: default
    fun <T : Any> Map<String, T>.doubleVal(field: String, default: Double): Double = get(field)?.asValue<Double>() ?: default
    fun <T : Any> Map<String, T>.stringVal(field: String, default: String): String = get(field)?.asValue<String>() ?: default

    fun <T : Any> Map<String, T>.intVal(field: String): Int? = get(field)?.asValue<Long>()?.toInt()
    fun <T : Any> Map<String, T>.longVal(field: String): Long? = get(field)?.asValue<Long>()
    fun <T : Any> Map<String, T>.doubleVal(field: String): Double? = get(field)?.asValue<Double>()
    fun <T : Any> Map<String, T>.stringVal(field: String): String? = get(field)?.asValue<String>()

    fun <T : Any> Map<String, T>.intVals(field: String): List<Int> = get(field)?.asList<Long>()?.map { it.toInt() } ?: emptyList()
    fun <T : Any> Map<String, T>.longVals(field: String): List<Long> = get(field)?.asList<Long>() ?: emptyList()
    fun <T : Any> Map<String, T>.doubleVals(field: String): List<Double> = get(field)?.asList<Double>() ?: emptyList()
    fun <T : Any> Map<String, T>.stringVals(field: String): List<String> = get(field)?.asList<String>() ?: emptyList()
}
