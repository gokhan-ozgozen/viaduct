package viaduct.service.runtime

import com.google.inject.Guice
import com.google.inject.Inject
import com.google.inject.ProvisionException
import com.google.inject.name.Named
import com.google.inject.util.Modules
import graphql.ExecutionInput as GJExecutionInput
import graphql.ExecutionResult
import graphql.ExecutionResultImpl
import graphql.GraphQL
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionId
import graphql.execution.ExecutionStrategy
import graphql.execution.instrumentation.Instrumentation
import graphql.schema.GraphQLSchema
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.TenantAPIBootstrapper.Companion.flatten
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.execution.ResolverErrorBuilder
import viaduct.engine.api.execution.ResolverErrorReporter
import viaduct.engine.runtime.CompositeLocalContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.instrumentation.ResolverInstrumentation
import viaduct.engine.runtime.tenantloading.DispatcherRegistryFactory
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder

/**
 * An immutable implementation of Viaduct interface, it configures and executes queries against the Viaduct runtime
 *
 * Registers two different types of schema:
 * 1. The full schema, which is only exposed to internal Viaduct interfaces such as derived fields and components.
 * 2. Scoped schemas, which have both introspectable and non-introspectable versions. Scoped schemas that are
 *   not already registered when requested will be lazily computed.
 *
 */

class StandardViaduct internal constructor(
    // Internal for testing
    internal val chainedInstrumentation: Instrumentation, // Internal for testing
    private val queryExecutionStrategy: ExecutionStrategy,
    private val mutationExecutionStrategy: ExecutionStrategy,
    private val subscriptionExecutionStrategy: ExecutionStrategy,
    val viaductSchemaRegistry: ViaductSchemaRegistry,
    private val dispatcherRegistry: DispatcherRegistry,
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
    private val fragmentLoader: FragmentLoader,
    private val tenantNameResolver: TenantNameResolver,
    private val resolverInstrumentation: Instrumentation,
    private val flagManager: FlagManager,
) : Viaduct {
    private val engineExecutionContextFactory =
        EngineExecutionContextFactory(
            viaductSchemaRegistry.getFullSchema(),
            dispatcherRegistry,
            fragmentLoader,
            resolverInstrumentation,
            flagManager
        )

    @Inject
    internal constructor(
        @Named("QueryExecutionStrategy") queryExecutionStrategy: ExecutionStrategy,
        @Named("MutationExecutionStrategy") mutationExecutionStrategy: ExecutionStrategy,
        @Named("SubscriptionExecutionStrategy") subscriptionExecutionStrategy: ExecutionStrategy,
        instrumentation: Instrumentation,
        graphqlSchemaRegistry: ViaductSchemaRegistry,
        dispatcherRegistry: DispatcherRegistry,
        coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
        fragmentLoader: FragmentLoader,
        tenantNameResolver: TenantNameResolver,
        resolverInstrumentation: ResolverInstrumentation,
        flagManager: FlagManager,
    ) : this(
        instrumentation,
        queryExecutionStrategy,
        mutationExecutionStrategy,
        subscriptionExecutionStrategy,
        graphqlSchemaRegistry,
        dispatcherRegistry,
        coroutineInterop,
        fragmentLoader,
        tenantNameResolver,
        resolverInstrumentation,
        flagManager
    )

    init {
        viaductSchemaRegistry.registerSchema(
            chainedInstrumentation,
            queryExecutionStrategy,
            mutationExecutionStrategy,
            subscriptionExecutionStrategy
        )
    }

    class Builder {
        private var airbnbModeEnabled: Boolean = false
        private var fragmentLoader: FragmentLoader? = null
        private var instrumentation: Instrumentation? = null
        private var flagManager: FlagManager? = null
        private var checkerExecutorFactory: CheckerExecutorFactory? = null
        private var checkerExecutorFactoryCreator: ((ViaductSchema) -> CheckerExecutorFactory)? = null
        private var dataFetcherExceptionHandler: DataFetcherExceptionHandler? = null
        private var resolverErrorReporter: ResolverErrorReporter? = null
        private var resolverErrorBuilder: ResolverErrorBuilder? = null
        private var coroutineInterop: CoroutineInterop? = null
        private var viaductSchemaRegistryBuilder: ViaductSchemaRegistryBuilder = ViaductSchemaRegistryBuilder()
        private var tenantNameResolver: TenantNameResolver = TenantNameResolver()
        private var tenantAPIBootstrapperBuilders: List<TenantAPIBootstrapperBuilder> = emptyList()
        private var chainInstrumentationWithDefaults: Boolean = false

        fun enableAirbnbBypassDoNotUse(
            fragmentLoader: FragmentLoader,
            tenantNameResolver: TenantNameResolver,
        ): Builder =
            apply {
                this.fragmentLoader = fragmentLoader
                this.tenantNameResolver = tenantNameResolver
                this.airbnbModeEnabled = true
            }

        /** See [withTenantAPIBootstrapperBuilder]. */
        fun withTenantAPIBootstrapperBuilder(builder: TenantAPIBootstrapperBuilder): Builder = withTenantAPIBootstrapperBuilders(listOf(builder))

        /**
         * Adds a TenantAPIBootstrapperBuilder to be used for creating TenantAPIBootstrapper instances.
         * Multiple builders can be added, and all their TenantAPIBootstrapper instances will be used
         * together to bootstrap tenant modules.
         *
         * @param builders The builder instance that will be used to create a TenantAPIBootstrapper
         * @return This Builder instance for method chaining
         */
        fun withTenantAPIBootstrapperBuilders(builders: List<TenantAPIBootstrapperBuilder>): Builder =
            apply {
                tenantAPIBootstrapperBuilders = builders
            }

        /**
         * A convenience function to indicate that no bootstrapper is
         * wanted.  Used for testing purposes.  We want the empty case
         * to be explicit because in almost all non-test scenarios
         * this is a programming error that should be flagged early.
         */
        fun withNoTenantAPIBootstrapper() = apply { withTenantAPIBootstrapperBuilders(emptyList()) }

        fun withCheckerExecutorFactory(checkerExecutorFactory: CheckerExecutorFactory): Builder =
            apply {
                this.checkerExecutorFactory = checkerExecutorFactory
            }

        fun withCheckerExecutorFactoryCreator(factoryCreator: (ViaductSchema) -> CheckerExecutorFactory): Builder =
            apply {
                this.checkerExecutorFactoryCreator = factoryCreator
            }

        fun withSchemaRegistryBuilder(viaductSchemaRegistryBuilder: ViaductSchemaRegistryBuilder): Builder =
            apply {
                this.viaductSchemaRegistryBuilder = viaductSchemaRegistryBuilder
            }

        fun withFlagManager(flagManager: FlagManager): Builder =
            apply {
                this.flagManager = flagManager
            }

        fun withDataFetcherExceptionHandler(dataFetcherExceptionHandler: DataFetcherExceptionHandler): Builder =
            apply {
                this.dataFetcherExceptionHandler = dataFetcherExceptionHandler
            }

        // fun withDataFetcherErrorReporter(dataFetcherErrorReporter: DataFetcherErrorReporter): Builder =
        //     apply {
        //         this.dataFetcherErrorReporter = dataFetcherErrorReporter
        //     }
        //
        // fun withDataFetcherErrorBuilder(dataFetcherErrorBuilder: DataFetcherErrorBuilder): Builder =
        //     apply {
        //         this.dataFetcherErrorBuilder = dataFetcherErrorBuilder
        //     }

        @Deprecated("For advance uses, Airbnb-use only", level = DeprecationLevel.WARNING)
        fun withInstrumentation(
            instrumentation: Instrumentation?,
            chainInstrumentationWithDefaults: Boolean = false
        ): Builder =
            apply {
                this.instrumentation = instrumentation
                this.chainInstrumentationWithDefaults = chainInstrumentationWithDefaults
            }

        fun withCoroutineInterop(coroutineInterop: CoroutineInterop) =
            apply {
                this.coroutineInterop = coroutineInterop
            }

        @Deprecated("For advance uses, Airbnb-use only.", level = DeprecationLevel.WARNING)
        fun getSchemaRegistryBuilder(): ViaductSchemaRegistryBuilder = viaductSchemaRegistryBuilder

        /**
         * Builds the Guice Module within Viaduct and gets Viaduct from the injector.
         *
         * @return a Viaduct Instance ready to execute
         */
        fun build(): StandardViaduct {
            val scopedFuture = coroutineInterop ?: DefaultCoroutineInterop
            val schemaRegistry = viaductSchemaRegistryBuilder.build(scopedFuture)
            val fullSchema = schemaRegistry.getFullSchema()
            checkerExecutorFactory = checkerExecutorFactory ?: checkerExecutorFactoryCreator?.invoke(fullSchema)
            val internalEngineModule = ViaductInternalEngineModule(
                schemaRegistry,
                flagManager,
                scopedFuture,
                checkerExecutorFactory,
                dataFetcherExceptionHandler,
                resolverErrorReporter ?: ResolverErrorReporter.NoOpResolverErrorReporter,
                resolverErrorBuilder ?: ResolverErrorBuilder.NoOpResolverErrorBuilder
            )

            val tenantBootstrapper = tenantAPIBootstrapperBuilders.map { it.create() }.flatten()
            val executionStrategyModuleConfig = ViaductExecutionStrategyModule.Config(
                chainInstrumentationWithDefaults = chainInstrumentationWithDefaults,
            )

            val viaductModule = Modules.combine(
                ViaductExecutionStrategyModule(
                    fullSchema,
                    instrumentation,
                    tenantBootstrapper,
                    fragmentLoader,
                    executionStrategyModuleConfig
                ),
                internalEngineModule
            )
            try {
                return Guice.createInjector(viaductModule).getInstance(StandardViaduct::class.java)
            } catch (e: ProvisionException) {
                val isCausedByDispatcherRegistryFactory = e.cause?.stackTrace?.any {
                    it.className == DispatcherRegistryFactory::class.java.name
                } ?: false

                if (isCausedByDispatcherRegistryFactory) {
                    throw GraphQLBuildError(
                        "Invalid DispatcherRegistryFactory configuration. " +
                            "This is likely invalid schema or fragment configuration.",
                        e
                    )
                }
                throw e
            }
        }
    }

    /**
     * Function to create a new StandardViaduct from an existing StandardViaduct by copying and pasting
     * all the properties from the existing instance except the schema registry builder.
     * Caller is expected to construct the schema register builder then pass it in.
     */
    fun newForSchema(viaductSchemaRegistryBuilder: ViaductSchemaRegistryBuilder) =
        StandardViaduct(
            chainedInstrumentation,
            queryExecutionStrategy,
            mutationExecutionStrategy,
            subscriptionExecutionStrategy,
            viaductSchemaRegistryBuilder.build(coroutineInterop),
            dispatcherRegistry,
            coroutineInterop,
            fragmentLoader,
            tenantNameResolver,
            resolverInstrumentation,
            flagManager
        )

    fun mkSchemaNotFoundError(schemaId: String): CompletableFuture<ExecutionResult> {
        val error: GraphQLError = GraphqlErrorBuilder.newError()
            .message("Schema not found for schemaId=$schemaId")
            .build()
        return CompletableFuture.completedFuture(
            ExecutionResultImpl.newExecutionResult()
                .addError(error)
                .build()
        )
    }

    /**
     * This function asynchronously executes an operation (found in ExecutionInput),
     * returning a completable future that will contain the sorted ExecutionResult
     *
     * @param executionInput the [ExecutionInput] to execute
     * @return [CompletableFuture] of sorted [ExecutionResult]
     */
    override fun executeAsync(executionInput: ExecutionInput): CompletableFuture<ExecutionResult> {
        val engine = viaductSchemaRegistry.getEngine(executionInput.schemaId)
            ?: return mkSchemaNotFoundError(executionInput.schemaId)
        val gjExecutionInput = mkExecutionInput(executionInput)
        return coroutineInterop.enterThreadLocalCoroutineContext {
            val executionResult = engine.executeAsync(gjExecutionInput).await()
            requireNotNull(executionResult) {
                "Unknown GQ Error: ExecutionResult for schemaId=${executionInput.schemaId} cannot be null"
            }
            sortExecutionResult(executionResult)
        }
    }

    /**
     * This is a blocking(!!) function that executes an operation (found in ExecutionInput) and returns
     * a sorted ExecutionResult
     *
     * @param executionInput the [ExecutionInput] to execute
     * @return [CompletableFuture] of sorted [ExecutionResult]
     */
    override fun execute(executionInput: ExecutionInput): ExecutionResult {
        return executeAsync(executionInput).join()
    }

    /**
     * This function is used to get the applied scopes for a given schemaId
     *
     * @param schemaId the id of the schema for which we want a [GraphQLSchema]
     *
     * @return Set of scopes that are applied to the schema
     */
    override fun getAppliedScopes(schemaId: String): Set<String>? {
        @Suppress("DEPRECATION")
        return getSchema(schemaId)?.scopes()
    }

    /**
     * Runs a query against the schema named "" (blank string) using
     * a simplified execution context.  (Intended for testing.)
     */
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult = runQuery("", query, variables)

    /**
     * Runs a query against the schema using a simplified
     * execution context.  (Intended for testing.)
     */

    /** Runs a query. */
    fun runQuery(
        schemaId: String,
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult =
        execute(
            ExecutionInput(
                query = query,
                variables = variables,
                requestContext = Any(),
                schemaId = schemaId,
            )
        )

    /**
     * Creates ExecutionResult from Execution Result and sorts the errors based on a path
     *
     * @param executionResult the ExecutionResult
     *
     * @return the ExecutionResult with the data off the executionResult
     *
     * Internal for Testing
     */
    internal fun sortExecutionResult(executionResult: ExecutionResult): ExecutionResult {
        val sortedErrors: List<GraphQLError> =
            executionResult.errors.sortedWith(
                compareBy({ it.path?.joinToString(separator = ".") ?: "" }, { it.message })
            )

        return ExecutionResultImpl(
            executionResult.getData(),
            sortedErrors,
            executionResult.extensions
        )
    }

    /**
     * This function is used to create the ExecutionInput that is needed to run the engine of GraphQL.
     *
     * @param executionInput The ExecutionInput object that has the data to create the input for execution
     *
     * @return GJExecutionInput created via the data inside the executionInput.
     */
    private fun mkExecutionInput(executionInput: ExecutionInput): GJExecutionInput {
        val executionInputBuilder =
            GJExecutionInput
                .newExecutionInput()
                .executionId(ExecutionId.generate())
                .query(executionInput.query)

        if (executionInput.operationName != null) {
            executionInputBuilder.operationName(executionInput.operationName)
        }
        executionInputBuilder.variables(executionInput.variables)
        val localContext = CompositeLocalContext.withContexts(mkEngineExecutionContext(executionInput.schemaId))

        @Suppress("DEPRECATION")
        return executionInputBuilder
            .context(executionInput.requestContext)
            .localContext(localContext)
            .graphQLContext(GraphQLJavaConfig.default.asMap())
            .build()
    }

    /**
     * Temporary - Will be either private/or somewhere not exposed
     *
     * This function is used to get the GraphQLSchema from the registered scopes.
     *
     * @param schemaId the id of the schema for which we want a [GraphQLSchema]
     *
     * @return GraphQLSchema instance of the registered scope
     */
    @Suppress("DEPRECATION")
    @Deprecated("Will be either private/or somewhere not exposed")
    override fun getSchema(schemaId: String): ViaductSchema? = viaductSchemaRegistry.getSchema(schemaId)

    /**
     * AirBNB only
     *
     * This function is used to get the engine from the GraphQLSchemaRegistry
     * @param schemaId the id of the schema for which we want a [GraphQL] engine
     *
     * @return GraphQL instance of the engine
     */
    fun getEngine(schemaId: String): GraphQL? = viaductSchemaRegistry.getEngine(schemaId)

    /**
     * Creates an instance of EngineExecutionContext. This should be called exactly once
     * per request and set in the graphql-java execution input's local context.
     */
    fun mkEngineExecutionContext(schemaId: String): EngineExecutionContext {
        return engineExecutionContextFactory.create(viaductSchemaRegistry.getSchema(schemaId) ?: throw IllegalArgumentException("Schema not registered for $schemaId"))
    }
}
