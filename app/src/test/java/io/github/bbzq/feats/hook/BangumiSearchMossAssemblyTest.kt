package io.github.bbzq.feats.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        fun addItems(item: AssemblySearchItem) {
            items += item
        }

        fun itemCount() = items.size
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

class NoItemsResponse private constructor() {
    companion object {
        @JvmStatic
        fun newBuilder() = Builder()
    }

    class Builder {
        fun build() = NoItemsResponse()
    }
}

class BangumiSearchMossAssemblyTest {
    @Test
    fun `assembles repeated item through addItems message method`() {
        val response = AddItemsResponse.newBuilder()
        val assembly = requireNotNull(response.createSearchItemAssembly())

        assertEquals("addItems", assembly.mode)
        assertTrue(assembly.commit(assembly.newBuilder()))
        assertEquals(1, response.itemCount())
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
}
