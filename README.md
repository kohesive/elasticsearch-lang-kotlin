# Elasticsearch-Lang-Kotlin

Kotlin scripting language for Elasticsearch.  This includes both text scripts and also
binary scripts (serialized lambdas sent from the client to execute in the Elasticsearch
engine).

A client library is provided that adds extension functions for the Elasticsearch client
making it easy to send lambdas as scripts.

see example video: https://vimeo.com/apatrida/es-lang-kotlin (older, but the idea is valid)
 
**BETA release, use at your own risk, for testing purposes only.**

# Guide

**TODO:** *this is rough guide, but will get you started.*

With the plugin enabled, you can run Kotlin scripts in one of two ways:

* Text based script, which is same as most scripting languages in Elasticsearch.
* Inline Lambda that is shipped to the server as binary and executed.

In both models, the scripts are validated against the same permission policies as Painless scripting language uses, with
some extensions allowing Kotlin stdlib to work.  In fact Kotlin stdlib is first validated against the Painless whitelist
and anything that passes is allowed to be used.

An example of using a Text based script (works from any language, here the example is in Kotlin but could easily be
and development language, or even CURL):

```kotlin
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
```

And a lambda version allows you to use a lambda or function reference as a script (this sample uses the `elasticsearch-lang-kotlin-client`
client library to make this easier and transparent):

```kotlin
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
```

To see what helper methods are available to access values from the document, view the base class that your script will 
inherit:  [ScriptTemplate.kt](elasticsearch-lang-kotlin-common/src/main/kotlin/uy/kohesive/elasticsearch/kotlinscript/common/ScriptTemplate.kt)

Both the client library and the script template will surely change, so expect large changes as they are used to build
out realistic test cases for every script type in Elasticsearch.  Consider these two elements more ALPHA while the rest
of the engine is more BETA.


# Server Installation:

|Plugin Version|Elasticsearch Version|Kotlin Version|
|--------------|---------------------|--------------|
|Version 1.0.0-ES-5.6.4-BETA-03|**Elasticsearch 5.6.4**|**Kotlin 1.51**|

More information is available under [each release](https://github.com/kohesive/elasticsearch-lang-kotlin/releases).

Install on server using (replace version number with the one you intend to use):

```
bin/elasticsearch-plugin install https://github.com/kohesive/elasticsearch-lang-kotlin/releases/download/v1.0.0-ES-5.6.4-BETA-03/lang-kotlin-1.0.0-ES-5.6.4-BETA-03.zip
```

**NOTE:**  *You must accept the security permissions, these are only used for the compiler and not scripts which do not run with any extra privileges.*

A Elasticsearch cluster must enable this setting to use inline Kotlin scripts (and also for Lambdas which appear to be inline scripts to Elasticsearch):
```
script.engine.kotlin.inline: true
```

*(Read [script security settings](https://www.elastic.co/guide/en/elasticsearch/reference/5.6/modules-scripting-security.html) for more information.)*

# Client Extensions (for Kotlin):

For client library containing extension functions for Kotlin, use the follow artifact from Gradle or Maven:
```
uy.kohesive.elasticsearch:elasticsearch-lang-kotlin-client:1.0.0-BETA-03
```

# TODO:

TODO: more to come

# Development:

NOTE for running tests, you need to run tests with:
```
 -Djava.security.policy=elasticsearch-lang-kotlin-plugin/src/main/plugin-metadata/plugin-security.policy
```

