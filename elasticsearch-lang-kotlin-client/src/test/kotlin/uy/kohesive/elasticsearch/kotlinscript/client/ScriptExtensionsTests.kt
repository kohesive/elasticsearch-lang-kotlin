package uy.kohesive.elasticsearch.kotlinscript.client

import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.Client
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.xcontent.XContentFactory
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.search.SearchHit
import org.elasticsearch.test.ESIntegTestCase
import org.junit.Before
import uy.klutter.core.common.toIsoString
import uy.kohesive.chillamda.Chillambda
import uy.kohesive.elasticsearch.kotlinscript.KotlinScriptPlugin
import uy.kohesive.elasticsearch.kotlinscript.common.ConcreteEsKotlinScriptTemplate
import uy.kohesive.elasticsearch.kotlinscript.common.EsKotlinScriptTemplate
import java.io.File
import java.time.Instant
import kotlin.system.measureTimeMillis


@ESIntegTestCase.ClusterScope(transportClientRatio = 1.0, numDataNodes = 1)
class ScriptExtensionsTests : ESIntegTestCase() {
    override fun nodeSettings(nodeOrdinal: Int): Settings {
        val temp = super.nodeSettings(nodeOrdinal)
        val builder = Settings.builder().apply {
            temp.asMap.forEach {
                put(it.key, it.value)
            }
            //put("script.painless.regex.enabled", true)
        }
        return builder.build()
    }

    companion object {
        val INDEX_NAME = "test-client"
    }

    private lateinit var client: Client

    override fun nodePlugins(): Collection<Class<out Plugin>> =
            listOf(KotlinScriptPlugin::class.java)

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
                .addScriptField("scriptField1", mapOf("multiplier" to 2), """
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
                    .addScriptField("scriptField1", mapOf("multiplier" to 2), """
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
                .addScriptField("scriptField1", mapOf("multiplier" to 2)) {
                    doc["number"].asValue(1) * param["multiplier"].asValue(1) + _score
                }.setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)

        prep.runManyTimes {
            printHitsField("scriptField1")
        }
    }

    fun testLambdaAccessingMoreTypes() {
        val prep = client.prepareSearch(INDEX_NAME)
                .addScriptField("scriptField1", mapOf("multiplier" to 2)) {
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
                    .addScriptField("multi", mapOf("multiplier" to 2)) {
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
                .addScriptField("scriptField1", emptyMap()) {
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
                doc = mutableMapOf("badContent" to mutableListOf<Any>("category:  History, Science, Fish")),
                ctx = mutableMapOf(), _value = 0, _score = 0.0)

        val expectedResults = listOf("category: history", "category: science", "category: fish")

        assertEquals(expectedResults, mockContext.scriptFunc())

        val prep = client.prepareSearch(INDEX_NAME)
                .addScriptField("scriptField1", emptyMap(), scriptFunc)
                .setQuery(QueryBuilders.matchQuery("title", "title"))
                .setFetchSource(true)

        prep.runManyTimes {
            printHitsField("scriptField1")
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
        """).execute().actionGet()

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


    fun SearchHit.printHitSourceField(fieldName: String) {
        println("${id} => ${sourceAsMap()["title"].toString()}")
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