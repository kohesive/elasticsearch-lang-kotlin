package uy.kohesive.elasticsearch.kotlinscript.client

import org.elasticsearch.index.fielddata.ScriptDocValues

class MockStringDocValues(val wrap: List<String>) : ScriptDocValues<String>() {
    override fun setNextDocId(docId: Int) {
        TODO("Not implemented")
    }

    override val size: Int
        get() = wrap.size

    override operator fun get(index: Int): String {
        return wrap[index]
    }

}