package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.name.Named
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionStrategy
import graphql.execution.instrumentation.Instrumentation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.FieldResolverDispatcherRegistry
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.NodeResolverDispatcherRegistry
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.TypeCheckerDispatcherRegistry
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.instrumentation.ChainedInstrumentation
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.ViaductFragmentLoader
import viaduct.engine.runtime.execution.AccessCheckRunner
import viaduct.engine.runtime.execution.ExecutionParameters
import viaduct.engine.runtime.execution.ViaductExecutionStrategy
import viaduct.engine.runtime.execution.WrappedCoroutineExecutionStrategy
import viaduct.engine.runtime.instrumentation.ResolverInstrumentation
import viaduct.engine.runtime.instrumentation.ScopeInstrumentation
import viaduct.engine.runtime.tenantloading.CheckerSelectionSetsAreProperlyTyped
import viaduct.engine.runtime.tenantloading.DispatcherRegistryFactory
import viaduct.engine.runtime.tenantloading.ExecutorValidator
import viaduct.engine.runtime.tenantloading.FromArgumentVariablesHaveValidPaths
import viaduct.engine.runtime.tenantloading.FromFieldVariablesHaveValidPaths
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreAcyclic
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreSchematicallyValid
import viaduct.engine.runtime.tenantloading.ResolverSelectionSetsAreProperlyTyped
import viaduct.engine.runtime.validation.Validator
import viaduct.engine.runtime.validation.Validator.Companion.flatten
import viaduct.service.api.spi.FlagManager
import viaduct.utils.slf4j.logger

/*
 * This module is responsible for creating the ViaductExecutionStrategy and all of its dependencies.
 */
class ViaductExecutionStrategyModule(
    val schema: ViaductSchema,
    val instrumentation: Instrumentation? = null,
    val tenantBootstrapper: TenantAPIBootstrapper,
    val fragmentLoader: FragmentLoader? = null,
    val config: Config
) : AbstractModule() {
    companion object {
        private val log by logger()
    }

    data class Config(
        val chainInstrumentationWithDefaults: Boolean = false
    )

    @ExperimentalCoroutinesApi
    override fun configure() {
        bind(FieldResolverDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(NodeResolverDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(FieldCheckerDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(TypeCheckerDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(RequiredSelectionSetRegistry::class.java).to(DispatcherRegistry::class.java)
        if (fragmentLoader == null) {
            bind(FragmentLoader::class.java).to(ViaductFragmentLoader::class.java).`in`(Singleton::class.java)
        } else {
            bind(FragmentLoader::class.java).toInstance(fragmentLoader)
        }
    }

    @Provides
    @Singleton
    fun providesExecutorValidator() =
        ExecutorValidator(
            nodeResolverValidator = Validator.Unvalidated,
            fieldResolverExecutorValidator = ResolverSelectionSetsAreProperlyTyped(schema),
            requiredSelectionsValidator = listOf(
                RequiredSelectionsAreSchematicallyValid(schema),
                RequiredSelectionsAreAcyclic(schema),
                FromArgumentVariablesHaveValidPaths(schema),
                FromFieldVariablesHaveValidPaths(schema)
            ).flatten(),
            fieldCheckerExecutorValidator = CheckerSelectionSetsAreProperlyTyped(schema),
        )

    @Provides
    @Singleton
    fun providesDispatcherRegistry(
        validator: ExecutorValidator,
        checkerExecutorFactory: CheckerExecutorFactory,
        @Suppress("UNUSED_PARAMETER") flagManager: FlagManager,
    ): DispatcherRegistry {
        log.info("Creating DispatcherRegistry for Viaduct Modern")
        val startTime = System.currentTimeMillis()
        val dispatcherRegistry = DispatcherRegistryFactory(tenantBootstrapper, validator, checkerExecutorFactory).create(schema)
        val elapsedTime = System.currentTimeMillis() - startTime
        log.info("Created DispatcherRegistry for Viaduct Modern after [$elapsedTime] ms")
        return dispatcherRegistry
    }

    @Provides
    fun providesExecutionStrategyFactory(
        dataFetcherExceptionHandler: DataFetcherExceptionHandler,
        requiredSelectionSetRegistry: RequiredSelectionSetRegistry,
        coroutineInterop: CoroutineInterop,
        fieldCheckerDispatcherRegistry: FieldCheckerDispatcherRegistry,
        typeCheckerDispatcherRegistry: TypeCheckerDispatcherRegistry,
        flagManager: FlagManager,
    ): ViaductExecutionStrategy.Factory {
        return ViaductExecutionStrategy.Factory.Impl(
            dataFetcherExceptionHandler,
            ExecutionParameters.Factory(
                requiredSelectionSetRegistry,
                fieldCheckerDispatcherRegistry,
                typeCheckerDispatcherRegistry,
                flagManager
            ),
            AccessCheckRunner(coroutineInterop),
            coroutineInterop,
        )
    }

    @Provides
    @Singleton
    @Named("QueryExecutionStrategy")
    fun providesQueryExecutionStrategy(
        viaductExecutionStrategyFactory: ViaductExecutionStrategy.Factory,
        coroutineInterop: CoroutineInterop,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler
    ): ExecutionStrategy {
        return WrappedCoroutineExecutionStrategy(
            viaductExecutionStrategyFactory.create(isSerial = false),
            coroutineInterop,
            dataFetcherExceptionHandler
        )
    }

    @Provides
    @Singleton
    @Named("MutationExecutionStrategy")
    fun providesMutationExecutionStrategy(
        viaductExecutionStrategyFactory: ViaductExecutionStrategy.Factory,
        coroutineInterop: CoroutineInterop,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler,
    ): ExecutionStrategy {
        return WrappedCoroutineExecutionStrategy(
            viaductExecutionStrategyFactory.create(isSerial = true),
            coroutineInterop,
            dataFetcherExceptionHandler
        )
    }

    @Provides
    @Singleton
    @Named("SubscriptionExecutionStrategy")
    fun providesSubscriptionExecutionStrategy(
        viaductExecutionStrategyFactory: ViaductExecutionStrategy.Factory,
        coroutineInterop: CoroutineInterop,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler
    ): ExecutionStrategy {
        return WrappedCoroutineExecutionStrategy(
            viaductExecutionStrategyFactory.create(isSerial = true),
            coroutineInterop,
            dataFetcherExceptionHandler
        )
    }

    @Provides
    @Singleton
    fun providesScopeInstrumentation(): ScopeInstrumentation {
        return ScopeInstrumentation()
    }

    @Provides
    @Singleton
    fun providesResolverInstrumentation(
        dispatcherRegistry: DispatcherRegistry,
        coroutineInterop: CoroutineInterop
    ): ResolverInstrumentation {
        return ResolverInstrumentation(
            dispatcherRegistry,
            dispatcherRegistry,
            coroutineInterop
        )
    }

    @Provides
    @Singleton
    fun providesInstrumentation(
        resolverInstrumentation: ResolverInstrumentation,
        scopeInstrumentation: ScopeInstrumentation
    ): Instrumentation {
        val defaultInstrumentations = listOf(
            scopeInstrumentation.asStandardInstrumentation,
            resolverInstrumentation,
        )
        return if (config.chainInstrumentationWithDefaults) {
            ChainedInstrumentation(defaultInstrumentations + listOfNotNull(instrumentation))
        } else {
            instrumentation ?: ChainedInstrumentation(defaultInstrumentations)
        }
    }
}
