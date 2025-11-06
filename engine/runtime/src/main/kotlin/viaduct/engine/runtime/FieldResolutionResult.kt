package viaduct.engine.runtime

import graphql.GraphQLError
import graphql.execution.FetchedValue

/**
 * Data class to hold the engine result along with the fetched value.
 *
 * @property engineResult The engine result after fetching.
 * @property originalSource The fetched value from the data fetcher.
 */
data class FieldResolutionResult(
    val engineResult: Any?,
    val errors: List<GraphQLError>,
    val localContext: CompositeLocalContext,
    val extensions: Map<Any, Any?>,
    val originalSource: Any?,
) {
    companion object {
        private val Any?.asCompositeLocalContext: CompositeLocalContext
            get() = when (val ctx = this) {
                null -> CompositeLocalContext.empty
                is CompositeLocalContext -> ctx
                else ->
                    throw IllegalStateException("Expected CompositeLocalContext but found ${ctx::class}")
            }

        fun fromFetchedValue(
            engineResult: Any?,
            fetchedValue: FetchedValue
        ) = FieldResolutionResult(
            engineResult,
            fetchedValue.errors,
            fetchedValue.localContext.asCompositeLocalContext,
            when (fetchedValue) {
                is FetchedValueWithExtensions -> fetchedValue.extensions
                else -> emptyMap()
            },
            fetchedValue.fetchedValue,
        )
    }
}
