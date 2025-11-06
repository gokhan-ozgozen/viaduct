package viaduct.engine.runtime

import graphql.GraphQLError
import graphql.execution.FetchedValue

/**
 * A fetched value with extensions.
 */
class FetchedValueWithExtensions(
    fetchedValue: Any?,
    errors: List<GraphQLError>,
    localContext: CompositeLocalContext,
    val extensions: Map<Any, Any?>,
) : FetchedValue(fetchedValue, errors, localContext)
