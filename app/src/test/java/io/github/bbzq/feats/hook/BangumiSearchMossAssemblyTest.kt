package io.github.bbzq.feats.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class AssemblySearchItem private constructor() {
    companion object {
        @JvmStatic
        fun newBuilder() = Builder()
    }

    class Builder {
        fun build() = AssemblySearchItem()
    }
}

class AddItemsResponse private constructor() {
    companion object {
        @JvmStatic
        fun newBuilder() = Builder()
    }

    class Builder {
        private val items = mutableListOf<AssemblySearchItem>()
        private var builderItems = 0

        fun addItems(item: AssemblySearchItem.Builder) {
            builderItems++
        }

        fun addItems(item: AssemblySearchItem) {
            items += item
        }

        fun itemCount() = items.size
        fun builderItemCount() = builderItems
    }
}

class AddItemsBuilderResponse private constructor() {
    companion object {
        @JvmStatic
        fun newBuilder() = Builder()
    }

    class Builder {
        fun addItemsBuilder() = AssemblySearchItem.newBuilder()
    }
}

class EmptyResponse private constructor(
    val keyword: String,
    val pages: Int,
) {
    companion object {
        @JvmStatic
        fun newBuilder() = Builder()
    }

    class Builder {
        private var keyword = ""
        private var pages = 0

        fun addItems(item: AssemblySearchItem) = Unit

        fun setKeyword(value: String) {
            keyword = value
        }

        fun setPages(value: Int) {
            pages = value
        }

        fun build() = EmptyResponse(keyword, pages)
    }

    fun getItemsCount() = 0
}

class EmptyResponseWithBuilderItems private constructor(
    val keyword: String,
    val pages: Int,
) {
    companion object {
        @JvmStatic
        fun newBuilder() = Builder()
    }

    class Builder {
        private var keyword = ""
        private var pages = 0

        fun addItemsBuilder() = AssemblySearchItem.newBuilder()

        fun setKeyword(value: String) {
            keyword = value
        }

        fun setPages(value: Int) {
            pages = value
        }

        fun build() = EmptyResponseWithBuilderItems(keyword, pages)
    }

    fun getItemsCount() = 0
}

class NoItemsResponse private constructor() {
    companion object {
        @JvmStatic
        fun newBuilder() = Builder()
    }

    class Builder {
        fun build() = NoItemsResponse()
    }
}

interface TypedSearchCallback<T> {
    fun onNext(value: T)
    fun onCompleted()
}

class EmptyResponseCallback : TypedSearchCallback<EmptyResponse> {
    override fun onNext(value: EmptyResponse) = Unit
    override fun onCompleted() = Unit
}

class BangumiSearchMossAssemblyTest {
    @Test
    fun `assembles repeated item through addItems message method`() {
        val response = AddItemsResponse.newBuilder()
        val assembly = requireNotNull(response.createSearchItemAssembly())

        assertEquals("addItems(AssemblySearchItem)", assembly.mode)
        assertTrue(assembly.commit(assembly.newBuilder()))
        assertEquals(1, response.itemCount())
        assertEquals(0, response.builderItemCount())
    }

    @Test
    fun `keeps compatibility with addItemsBuilder`() {
        val assembly = requireNotNull(AddItemsBuilderResponse.newBuilder().createSearchItemAssembly())

        assertEquals("addItemsBuilder", assembly.mode)
        assertNotNull(assembly.newBuilder())
        assertTrue(assembly.commit(assembly.newBuilder()))
    }

    @Test
    fun `rejects response without repeated item API`() {
        assertNull(NoItemsResponse.newBuilder().createSearchItemAssembly())
    }

    @Test
    fun `builds empty response for addItems message shape`() {
        val response = requireNotNull(EmptyResponse.newBuilder().createEmptySearchResponse("query")) as EmptyResponse

        assertEquals("query", response.keyword)
        assertEquals(1, response.pages)
        assertEquals(0, response.getItemsCount())
    }

    @Test
    fun `builds empty response for addItemsBuilder shape`() {
        val response = requireNotNull(
            EmptyResponseWithBuilderItems.newBuilder().createEmptySearchResponse("query"),
        ) as EmptyResponseWithBuilderItems

        assertEquals("query", response.keyword)
        assertEquals(1, response.pages)
        assertEquals(0, response.getItemsCount())
    }

    @Test
    fun `resolves response type from generic callback`() {
        assertEquals(EmptyResponse::class.java, EmptyResponseCallback().searchCallbackResponseType())
    }

    @Test
    fun `delivery gate allows only one concurrent finisher`() {
        val gate = SearchDeliveryGate()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map {
                executor.submit(Callable { gate.tryFinish() })
            }.map { it.get() }
            assertEquals(1, results.count { it })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `completion-only fallback does not call onNext`() {
        val events = mutableListOf<String>()
        deliverSearchCallback(
            response = null,
            onNext = { events += "next" },
            onCompleted = { events += "completed" },
        )
        assertEquals(listOf("completed"), events)
    }

}
