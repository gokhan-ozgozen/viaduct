package viaduct.engine.api.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import viaduct.engine.api.CheckerDispatcher

open class ChainedModernGJInstrumentation(
    val gjInstrumentations: List<ViaductModernGJInstrumentation>
) : ChainedInstrumentation(gjInstrumentations), ViaductModernGJInstrumentation {
    override fun beginFetchObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Map<String, Any?>>? =
        ChainedInstrumentationContext(
            gjInstrumentations.map { instr ->
                instr.beginFetchObject(parameters, getState(instr, state))
            }
        )

    override fun beginCompleteObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            gjInstrumentations.map { instr ->
                instr.beginCompleteObject(parameters, getState(instr, state))
            }
        )

    override fun instrumentAccessCheck(
        checkerDispatcher: CheckerDispatcher,
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerDispatcher {
        var instrumentedChecker = checkerDispatcher
        for (instr in gjInstrumentations) {
            instrumentedChecker = instr.instrumentAccessCheck(instrumentedChecker, parameters, getState(instr, state))
        }

        return instrumentedChecker
    }
}
