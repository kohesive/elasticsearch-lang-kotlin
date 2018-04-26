package uy.kohesive.elasticsearch.kotlinscript.client

import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.Client
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.TransportAddress
import org.elasticsearch.common.xcontent.XContentFactory
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.fielddata.ScriptDocValues
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.index.reindex.ReindexPlugin
import org.elasticsearch.index.reindex.UpdateByQueryAction
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.search.SearchHit
import org.elasticsearch.search.aggregations.metrics.scripted.ScriptedMetric
import org.elasticsearch.search.aggregations.metrics.scripted.ScriptedMetricAggregationBuilder
import org.elasticsearch.test.ESIntegTestCase
import org.elasticsearch.transport.client.PreBuiltTransportClient
import org.junit.Before
import uy.klutter.core.common.toIsoString
import uy.kohesive.chillamda.Chillambda
import uy.kohesive.elasticsearch.kotlinscript.KotlinScriptPlugin
import uy.kohesive.elasticsearch.kotlinscript.common.ConcreteEsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.EsKotlinScriptTemplate
import java.io.File
import java.net.InetAddress
import java.time.Instant
import kotlin.system.measureTimeMillis


@ESIntegTestCase.ClusterScope(transportClientRatio = 1.0, numDataNodes = 1)
class ScriptExtensionsTests : ESIntegTestCase() {
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
        val INDEX_NAME = "testclient"
    }

    private lateinit var client: Client

    override fun transportClientPlugins(): Collection<Class<out Plugin>> {
        return listOf(ReindexPlugin::class.java)
    }

    override fun nodePlugins(): Collection<Class<out Plugin>> =
            listOf(KotlinScriptPlugin::class.java, ReindexPlugin::class.java)

    fun testNormalQuery() {

        val prep = client.prepareSearch(INDEX_NAME)
                .setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)
        val results = prep.execute().actionGet()

        results.assertHasResults()
        results.printHitsSourceField("title")

    }


    fun testStringScript() {
        val prep = client.prepareSearch(INDEX_NAME)
                .addKotlinScriptField("scriptField1", mapOf("multiplier" to 2), """
                    doc.intVal("number", 1) * param.intVal("multiplier", 1) + _score
                """).setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)

        prep.runManyTimes {
            printHitsField("scriptField1")
        }
    }

    fun testSecurityViolationInStringScript() {
        try {
            val response = client.prepareSearch(INDEX_NAME)
                    .addKotlinScriptField("scriptField1", mapOf("multiplier" to 2), """
                    import java.io.*

                    val f = File("howdy")  // violation!

                    doc.intVal("number", 1) * param.intVal("multiplier", 1) + _score
                    """).setQuery(QueryBuilders.matchQuery("title", "title"))
                    .setFetchSource(true).execute().actionGet()
            fail("security verification should have caught this use of File")
        } catch (ex: Exception) {
            val exceptionStack = generateSequence(ex as Throwable) { it.cause }
            assertTrue(exceptionStack.take(5).any { "java.io.File" in it.message!! })
        }
    }

    fun testLambdaAsScript() {
        val prep = client.prepareSearch(INDEX_NAME)
                .addKotlinScriptField("scriptField1", mapOf("multiplier" to 2)) {
                    doc["number"].asValue(1) * param["multiplier"].asValue(1) + _score
                }.setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)

        prep.runManyTimes {
            printHitsField("scriptField1")
        }
    }

    fun testLambdaAccessingMoreTypes() {
        val prep = client.prepareSearch(INDEX_NAME)
                .addKotlinScriptField("scriptField1", mapOf("multiplier" to 2)) {
                    doc["dtValue"].asValue(1L)         // date as long
                    doc["dtValue"]?.asValue<Instant>() // date as instant
                    doc["number"].asValue(1) * param["multiplier"].asValue(1) * doc["dblValue"].asValue(1.0) + _score
                }.setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)

        prep.runManyTimes {
            printHitsField("scriptField1")
        }
    }

    fun testSecurityViolationInLambdaAsScript() {
        try {
            val response = client.prepareSearch(INDEX_NAME)
                    .addKotlinScriptField("multi", mapOf("multiplier" to 2)) {
                        val f = File("asdf") // security violation
                        doc["number"].asValue(1) * param["multiplier"].asValue(1) + _score
                    }.setQuery(QueryBuilders.matchQuery("title", "title"))
                    .setFetchSource(true).execute().actionGet()
            fail("security verification should have caught this use of File")
        } catch (ex: Chillambda.ClassSerDerViolationsException) {
            assertTrue(ex.violations.all { "java.io File" in it })
        }
    }

    fun testMoreComplexLambdaAsScript() {
        val badCategoryPattern = """^(\w+)\s*\:\s*(.+)$""".toPattern() // Pattern is serializable, Regex is not
        val prep = client.prepareSearch(INDEX_NAME)
                .addKotlinScriptField("scriptField1", emptyMap()) {
                    val currentValue = doc["badContent"].asList<String>()
                    currentValue.map { value -> badCategoryPattern.toRegex().matchEntire(value)?.takeIf { it.groups.size > 2 } }
                            .filterNotNull()
                            .map {
                                val typeName = it.groups[1]!!.value.toLowerCase()
                                it.groups[2]!!.value.split(',')
                                        .map { it.trim().toLowerCase() }
                                        .filterNot { it.isBlank() }
                                        .map { "$typeName: $it" }
                            }.flatten()
                }.setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)

        prep.runManyTimes {
            printHitsField("scriptField1")
        }
    }

    fun testUpdateByQueryAsLambda() {
        val doc1 = client.prepareGet(INDEX_NAME, "test", "1").execute().actionGet()
        val doc2 = client.prepareGet(INDEX_NAME, "test", "2").execute().actionGet()
        val doc5 = client.prepareGet(INDEX_NAME, "test", "5").execute().actionGet()
        assertTrue(doc1.isExists)
        assertTrue(doc2.isExists)
        assertTrue(doc5.isExists)

        val prep = UpdateByQueryAction.INSTANCE.newRequestBuilder(client)
                .setKotlinScript(mapOf("byAmount" to 1)) {
                    val num = _source["number"].asValue(0)
                    if (num >= 4) {
                        ctx["op"] = "delete"
                    } else {
                        _source["number"] = num + param["byAmount"].asValue(0)
                    }
                }.source(INDEX_NAME)
                .filter(QueryBuilders.matchQuery("title", "title"))
                .apply {
                    source().setTypes("test")
                }
        prep.execute().actionGet()

        val newDoc1 = client.prepareGet(INDEX_NAME, "test", "1").execute().actionGet()
        val newDoc2 = client.prepareGet(INDEX_NAME, "test", "2").execute().actionGet()
        val deadDoc5 = client.prepareGet(INDEX_NAME, "test", "5").execute().actionGet()

        assertEquals((doc1.sourceAsMap["number"] as Int) + 1, newDoc1.sourceAsMap["number"] as Int)
        assertEquals((doc2.sourceAsMap["number"] as Int) + 1, newDoc2.sourceAsMap["number"] as Int)
        assertFalse(deadDoc5.isExists)

    }

    // TODO: make these access to _agg easier, and then document.
    fun testScriptedMetricAggregationAsLambda() {
        val prep = client.prepareSearch(INDEX_NAME)
                .setQuery(QueryBuilders.matchQuery("title", "title"))
                .addAggregation(ScriptedMetricAggregationBuilder("totalTransactions")
                        .setKotlinInitScript {
                           _agg["transactions"] = arrayListOf<Double>()
                        }.setKotlinMapScript {
                    if (doc["number"].asValue(1).rem(2) == 0) {
                        val txValue = doc["dblValue"].cast<List<Double>>()?.get(0) ?: 0.0
                        _agg["transactions"].cast<MutableList<Double>>()!!.add(txValue)
                    }
                }.setKotlinCombineScript {
                    _agg["transactions"].cast<List<Double>>()!!.sum()

                }.setKotlinReduceScript {
                    _aggs.cast<List<Double>>()!!.sum()
                })
                .setFetchSource(true)
        val results = prep.execute().actionGet()
        val aggResults = results.aggregations.get<ScriptedMetric>("totalTransactions")
        assertEquals(7.98, aggResults.aggregation())
    }

    fun testFuncRefWithMockContextAndRealDeal() {
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
            ctx = mutableMapOf(), _value = 0, _score = 0.0)

        val expectedResults = listOf("category: history", "category: science", "category: fish")

        assertEquals(expectedResults, mockContext.scriptFunc())

        val prep = client.prepareSearch(INDEX_NAME)
                .addKotlinScriptField("scriptField1", emptyMap(), scriptFunc)
                .setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)

        prep.runManyTimes {
            printHitsField("scriptField1")
        }
    }

    @Before
    fun createTestIndex() {
        // Delete any previously indexed content.
        val testRemote = System.getProperty("localTestRemoteThingy", "false").equals("true", ignoreCase = true)
        client = if (testRemote) {
            PreBuiltTransportClient(Settings.EMPTY, transportClientPlugins()).addTransportAddress(TransportAddress(InetAddress.getLocalHost(), 9300))
        } else {
            ESIntegTestCase.client()
        }

        if (client.admin().indices().prepareExists(INDEX_NAME).get().isExists) {
            client.admin().indices().prepareDelete(INDEX_NAME).get()
        }


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

        waitForGreen(INDEX_NAME)

        val bulk = client.prepareBulk()
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

        client.admin().indices().prepareFlush(INDEX_NAME).execute().actionGet()
        client.admin().indices().prepareRefresh(INDEX_NAME).execute().actionGet()
        waitForGreen(INDEX_NAME)
    }

    fun waitForGreen(index: String) {
        val result = client.admin().cluster().prepareHealth(index).setWaitForGreenStatus().setTimeout("1m").execute().actionGet()
        if (result.isTimedOut) {
            fail("Timed out waiting for index to be green")
        }
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