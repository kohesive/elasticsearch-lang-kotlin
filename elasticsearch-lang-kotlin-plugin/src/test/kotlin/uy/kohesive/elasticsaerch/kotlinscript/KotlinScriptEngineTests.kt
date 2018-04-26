@file:Suppress("NOTHING_TO_INLINE")

package uy.kohesive.elasticsaerch.kotlinscript

import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.Client
import org.elasticsearch.common.SuppressForbidden
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.xcontent.XContentFactory
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.fielddata.ScriptDocValues
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.ingest.common.IngestCommonPlugin
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.script.Script
import org.elasticsearch.script.ScriptType
import org.elasticsearch.search.SearchHit
import org.elasticsearch.test.ESIntegTestCase
import org.junit.Before
import uy.klutter.core.common.toIsoString
import uy.kohesive.elasticsearch.kotlinscript.KotlinScriptPlugin
import uy.kohesive.elasticsearch.kotlinscript.common.ConcreteEsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.EsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.KotlinScriptConfiguredChillambda
import java.time.Instant
import java.util.regex.Pattern
import kotlin.system.measureTimeMillis

@ESIntegTestCase.ClusterScope(transportClientRatio = 1.0, numDataNodes = 1)
@SuppressForbidden(reason = "test class with intentional forbidden things (print, lowerCase)")
class KotlinScriptEngineTests : ESIntegTestCase() {
    override fun nodeSettings(nodeOrdinal: Int): Settings {
        val temp = super.nodeSettings(nodeOrdinal)
        val builder = Settings.builder().apply {
            temp.keySet().forEach { key ->
                put(key, temp[key])
            }
            //  put("script.painless.regex.enabled", true)
        }
        return builder.build()
    }

    companion object {
        val INDEX_NAME = "test"
    }

    private lateinit var client: Client

    override fun nodePlugins(): Collection<Class<out Plugin>> =
            listOf(KotlinScriptPlugin::class.java, IngestCommonPlugin::class.java)

    fun testNormalQuery() {

        val prep = client.prepareSearch(INDEX_NAME)
                .setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)
        val results = prep.execute().actionGet()

        results.assertHasResults()
        results.printHitsSourceField("title")

    }

    fun testMoreComplexKotlinAsScript() {
        val prep = client.prepareSearch(INDEX_NAME)
                .addScriptField("scriptField1", Script(ScriptType.INLINE, "kotlin", """
                    val currentValue = doc.stringVal("badContent") ?: ""
                    "^(\\w+)\\s*\\:\\s*(.+)$".toRegex().matchEntire(currentValue)
                            ?.takeIf { it.groups.size > 2 }
                            ?.let {
                                val typeName = it.groups[1]!!.value.toLowerCase()
                                it.groups[2]!!.value.split(',')
                                        .map { it.trim().toLowerCase() }
                                        .filterNot { it.isBlank() }
                                        .map { typeName + ": " + it }
                            } ?: listOf(currentValue)
                  """, emptyMap()))
                .setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)

        prep.runManyTimes {
            printHitsField("scriptField1")
        }
    }

    fun testFuncRefWithMockContext() {
        val badCategoryPattern = """^(\w+)\s*\:\s*(.+)$""".toPattern() // Pattern is serializable, Regex is not
        val scriptFunc = fun EsKotlinScriptTemplate.(): Any? {
            val currentValue = doc["badContent"].asList<String>()
            return currentValue.map { value -> badCategoryPattern.toRegex().matchEntire(value)?.takeIf { it.groups.size > 2 } }
                    .filterNotNull()
                    .map {
                        val typeName = it.groups[1]!!.value.toLowerCase()
                        it.groups[2]!!.value.split(',')
                                .map { it.trim().toLowerCase() }
                                .filterNot { it.isBlank() }
                                .map { "$typeName: $it" }
                    }.flatten()
        }

        val mockContext = ConcreteEsKotlinScriptTemplate(param = emptyMap(),
                doc = mapOf("badContent" to MockStringDocValues(listOf("category:  History, Science, Fish")) as ScriptDocValues<*>),
                ctx = mutableMapOf(),
                _value = 0,
                _score = 0.0)

        val expectedResults = listOf("category: history", "category: science", "category: fish")

        assertEquals(expectedResults, mockContext.scriptFunc())
    }

    fun testLambdaAsIngestPipelineStep() {
        val badCategoryPattern = """^(\w+)\s*\:\s*(.+)$""".toPattern() // Pattern is serializable, Regex is not
        val scriptFunc = fun EsKotlinScriptTemplate.(): Any? {
            val newValue = ctx["badContent"]?.asValue<String>()
                    ?.let { currentValue ->
                        badCategoryPattern.takeIfMatching(currentValue, 3)
                                ?.let {
                                    val typeName = it.groups[1]!!.value.toLowerCase()
                                    it.groups[2]!!.value.split(',')
                                            .map { it.trim().toLowerCase() }
                                            .filterNot { it.isBlank() }
                                            .map { "$typeName: $it" }
                                } ?: listOf(currentValue)
                    } ?: emptyList()
            ctx["badContent"] = newValue
            return true
        }

        val simulateSource = makeSimulatePipelineJsonForLambda(scriptFunc)

        val simulateResults = client.admin().cluster().prepareSimulatePipeline(simulateSource).execute().actionGet()
        simulateResults.results[0]!!.let {
            val expectedResults = listOf("category: history", "category: science", "category: fish")
            // TODO: no response parsing is in the client, need to handle this specially
        }

    }

    @Before
    fun createTestIndex() {
        // Delete any previously indexed content.
        client = ESIntegTestCase.client()

        if (client.admin().indices().prepareExists(INDEX_NAME).get().isExists) {
            client.admin().indices().prepareDelete(INDEX_NAME).get()
        }

        val bulk = client.prepareBulk()

        client.admin().indices().prepareCreate(INDEX_NAME).setSource("""
               {
                  "settings": {
                    "index": {
                      "number_of_shards": "2",
                      "number_of_replicas": "0"
                    }
                  },
                  "mappings": {
                    "test": {
                      "_all": {
                        "enabled": false
                      },
                      "properties": {
                        "url": { "type": "keyword" },
                        "title": { "type": "text" },
                        "content": { "type": "text" },
                        "number": { "type": "integer" },
                        "badContent": { "type": "keyword" },
                        "multiValue": { "type": "keyword" },
                        "dblValue": { "type": "double" },
                        "dtValue": { "type": "date" }
                      }
                    }
                  }
               }
        """, XContentType.JSON).execute().actionGet()

        (1..5).forEach { i ->
            bulk.add(client.prepareIndex()
                    .setIndex(INDEX_NAME)
                    .setType("test")
                    .setId(i.toString())
                    .setSource(XContentFactory.jsonBuilder()
                            .startObject()
                            .field("url", "http://google.com/$i")
                            .field("title", "Title #$i")
                            .field("content", "Hello World $i!")
                            .field("number", i)
                            .field("badContent", "category:  History, Science, Fish")
                            .field("multiValue", listOf("one", "two", "three"))
                            .field("dblValue", i * 1.33)
                            .field("dtValue", if (i % 2 == 1) Instant.now().toIsoString() else Instant.now().toEpochMilli())
                            // badContent is incorrect, should be multi-value
                            // ["category: history", "category: science", "category: fish"]
                            .endObject()
                    )
            )
        }

        bulk.execute().actionGet()

        flushAndRefresh(INDEX_NAME)
        ensureGreen(INDEX_NAME)
    }


    fun <T : Any?> makeSimulatePipelineJsonForLambda(lambda: EsKotlinScriptTemplate.() -> T): BytesReference {
        val pipelineDef = XContentFactory.jsonBuilder()
                .startObject()
                .field("description", "Kotlin lambda test pipeline")
                .startArray("processors")
                .startObject()
                .startObject("script")
                .field("lang", "kotlin")
                .field("inline", KotlinScriptConfiguredChillambda.chillambda.serializeLambdaToBase64<EsKotlinScriptTemplate, Any>(lambda))
                .startObject("params").endObject()
                .endObject()
                .endObject()
                .endArray()
                .endObject()

        val simulationJson = XContentFactory.jsonBuilder()
                .startObject()
                .rawField("pipeline", pipelineDef.bytes())
                .startArray("docs")
                .startObject().startObject("_source").field("id", 1).field("badContent", "category:  History, Science, Fish").endObject().endObject()
                .startObject().startObject("_source").field("id", 2).field("badContent", "category:  monkeys, mountains").endObject().endObject()
                .endArray()
                .endObject()

        return simulationJson.bytes()
    }


    fun SearchHit.printHitSourceField(fieldName: String) {
        println("${id} => ${sourceAsMap["title"].toString()}")
    }

    fun SearchHit.printHitField(fieldName: String) {
        println("${id} => ${fields[fieldName]?.values.toString()}")
    }

    fun SearchResponse.printHitsSourceField(fieldName: String) {
        hits.hits.forEach { it.printHitSourceField(fieldName) }
    }

    fun SearchResponse.printHitsField(fieldName: String) {
        hits.hits.forEach { it.printHitField(fieldName) }
    }

    fun SearchResponse.assertHasResults() {
        if (hits == null || hits.hits == null || hits.hits.isEmpty()) {
            fail("no data returned in query:\n${this.toString()}")
        }
    }

    fun SearchRequestBuilder.runManyTimes(func: SearchResponse.() -> Unit) {
        (1..25).forEach { idx ->
            println("...RUN $idx :>>>>")
            val time = measureTimeMillis {
                val results = execute().actionGet()
                results.assertHasResults()
                results.func()
            }
            println("  ${time}ms")
        }
    }

}

inline fun Pattern.takeIfMatching(text: String, minGroups: Int) =
        toRegex().matchEntire(text)?.takeIf { it.groups.size >= minGroups }