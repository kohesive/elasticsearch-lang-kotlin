# Elasticsearch-Lang-Kotlin

Kotlin scripting language for Elasticsearch.  This includes both text scripts and also
binary scripts (serialized lambdas sent from the client to execute in the Elasticsearch
engine).

A client library is provided that adds extension functions for the Elasticsearch client
making it easy to send lambdas as scripts.

TODO: more to come

see example video: https://vimeo.com/apatrida/es-lang-kotlin

NOTE for running tests, need to run tests with:
 -Djava.security.policy=src/test/resources/test-plugin-security.policy