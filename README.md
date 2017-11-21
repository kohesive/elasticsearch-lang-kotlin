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
val prep = client.prepareSearch("myIndex")
        .addKotlinScriptField("scriptField1", Script(ScriptType.INLINE, "kotlin", 
          """
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
        .setQuery(...)
```

And a lambda version allows you to use a lambda or function reference as a script (this sample uses the `elasticsearch-lang-kotlin-client`
client library to make this easier and transparent):

```kotlin
val query = client.prepareSearch("myIndex")
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
            }.setQuery(...)
```

or another example of update by query:

```kotlin
val updateQuery = UpdateByQueryAction.INSTANCE.newRequestBuilder(client)
                    .setKotlinScript {
                        val num = _source["number"].asValue(0)
                        if (num >= 4) {
                            ctx["op"] = "delete"
                        } else {
                            _source["number"] = num + 1
                        }
                    }.source("myIndex").filter(...)
```

To see what helper methods are available to access values from the document, view the base class that your script will 
inherit:  [ScriptTemplate.kt](elasticsearch-lang-kotlin-common/src/main/kotlin/uy/kohesive/elasticsearch/kotlinscript/common/ScriptTemplate.kt)

Some tips for accessing common properties:

|field|syntax|notes|
|-----|------|-----|
|ctx._source|_source|This always checks internally `ctx._source` then `_source` so you do not have to special case which one is correct|
|ctx.value|ctx["value"]|Reference as a hash lookup|
|_source|_source|same, direct reference|
|doc["field"]|doc["field"]|Reference as a hash lookup.|
|doc.field|doc["field"]|Reference as a hash lookup|
|_source.field|_source["field"]|Reference as a hash lookup|
|script parameters|param["name"]|Reference as a hash lookup|
|_score|_score|Double value|
|_value|_value|Any? value|

When using a value you can access it as either single value `asValue()` or `asValue(default)` and as
a list with `asList()`.  If type cannot be infered, add the type on the call, for example `asValue<Int>()`. 
You can also just use the value directly but then you will need to cast it to the correct type.  `asValue()` 
will do a conversion on basic types if possible.

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
uy.kohesive.elasticsearch:elasticsearch-lang-kotlin-client:1.0.0-BETA-03.02
```

_(note that the version number might slightly differ as updates are added to the client that do not require a new server plugin update)_

# TODO:

TODO: more to come

# Development:

NOTE for running tests, you need to run tests with:
```
 -Djava.security.policy=elasticsearch-lang-kotlin-plugin/src/main/plugin-metadata/plugin-security.policy
```

