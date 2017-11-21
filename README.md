# Elasticsearch-Lang-Kotlin

Kotlin scripting language for Elasticsearch.  This includes both text scripts and also
binary scripts (serialized lambdas sent from the client to execute in the Elasticsearch
engine).

A client library is provided that adds extension functions for the Elasticsearch client
making it easy to send lambdas as scripts.

see example video: https://vimeo.com/apatrida/es-lang-kotlin (older, but the idea is valid)
 
**BETA release, use at your own risk, for testing purposes only.**

A Elasticsearch cluster must enable this setting to use inline Kotlin scripts (and also for Lambdas which appear to be inline scripts to Elasticsearch):
```
script.engine.kotlin.inline: true
```

*(Read [script security settings](https://www.elastic.co/guide/en/elasticsearch/reference/5.6/modules-scripting-security.html) for more information.)*


# Installation:

|Plugin Version|Elasticsearch Version|Kotlin Version|
|--------------|---------------------|--------------|
|Version 1.0.0-ES-5.6.4-BETA-03|**Elasticsearch 5.6.4**|**Kotlin 1.51**|

More information is available under [each release](https://github.com/kohesive/elasticsearch-lang-kotlin/releases).

Install on server using (replace version number with the one you intend to use):

```
bin/elasticsearch-plugin install https://github.com/kohesive/elasticsearch-lang-kotlin/releases/download/v1.0.0-ES-5.6.4-BETA-03/lang-kotlin-1.0.0-ES-5.6.4-BETA-03.zip
```

**NOTE:**  *You must accept the security permissions, these are only used for the compiler and not scripts which do not run with any extra privileges.*

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

