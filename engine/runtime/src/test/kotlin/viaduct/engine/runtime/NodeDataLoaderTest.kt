package viaduct.engine.runtime

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl

class NodeDataLoaderTest {
    private val id1 = "1"
    private val id2 = "2"
    val schema = mkSchema(
        """
        type Query { test: Test }
        interface Node { id: ID! }
        type Test implements Node { id: ID! foo: Foo bar: String}
        type Foo { a: String }
        """.trimIndent()
    )
    private val selectionSetFactory = RawSelectionSetFactoryImpl(schema)

    @Test
    fun `covers returns true for exact match`() {
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id bar", emptyMap())
        )
        assertTrue(selector.covers(selector))
    }

    @Test
    fun `covers returns true for larger selection set`() {
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id bar foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "foo { a } bar", emptyMap())
        )
        assertTrue(selector.covers(other))
    }

    @Test
    fun `covers returns false for different ID`() {
        val selections = selectionSetFactory.rawSelectionSet("Test", "id bar", emptyMap())
        val selector = NodeResolverExecutor.Selector("id1", selections)
        val other = NodeResolverExecutor.Selector("id2", selections)
        assertFalse(selector.covers(other))
    }

    @Test
    fun `covers returns false for smaller selection set`() {
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id foo { a } bar", emptyMap())
        )
        assertFalse(selector.covers(other))
    }
}
