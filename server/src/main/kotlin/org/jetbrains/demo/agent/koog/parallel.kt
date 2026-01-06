package org.jetbrains.demo.agent.koog

import ai.koog.agents.core.agent.context.AIAgentContextBase
import ai.koog.agents.core.agent.context.getAgentContextData
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.reflect.KType
import kotlin.reflect.typeOf

private val logger = LoggerFactory.getLogger("ParallelNode")

private fun isOpenTelemetrySpanError(e: Throwable): Boolean =
    e is IllegalStateException && (
        e.message?.contains("Span with id") == true ||
        e.message?.contains("already added") == true ||
        e.message?.contains("span") == true
    )

fun <IncomingInput, Wrapped, Value, OutgoingOutput> parallel(
    transform: suspend (Wrapped) -> List<Value>,
    input: AIAgentNodeBase<IncomingInput, Wrapped>,
    node: AIAgentNodeBase<Value, OutgoingOutput>,
    inputType: KType,
    outputType: KType,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    name: String? = null,
): AIAgentNodeDelegate<IncomingInput, List<OutgoingOutput>> =
    AIAgentParallelNodeBuilder(
        transform,
        input,
        node,
        inputType,
        outputType,
        dispatcher
    ).let { AIAgentNodeDelegate(name, it) }

inline fun <reified IncomingInput, Wrapped, Value, reified OutgoingOutput> parallel(
    input: AIAgentNodeBase<IncomingInput, Wrapped>,
    node: AIAgentNodeBase<Value, OutgoingOutput>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    name: String? = null,
    noinline transform: suspend (Wrapped) -> List<Value>,
): AIAgentNodeDelegate<IncomingInput, List<OutgoingOutput>> =
    parallel(
        transform,
        input,
        node,
        typeOf<IncomingInput>(),
        typeOf<OutgoingOutput>(),
        dispatcher,
        name
    )

@OptIn(InternalAgentsApi::class)
private class AIAgentParallelNodeBuilder<IncomingInput, Wrapped, Value, OutgoingOutput>(
    transform: suspend (Wrapped) -> List<Value>,
    val inputNode: AIAgentNodeBase<IncomingInput, Wrapped>,
    val node: AIAgentNodeBase<Value, OutgoingOutput>,
    inputType: KType,
    outputType: KType,
    private val dispatcher: CoroutineDispatcher
) : AIAgentNodeBuilder<IncomingInput, List<OutgoingOutput>>(
    inputType = inputType,
    outputType = outputType,
    execute = { input: IncomingInput ->
        val initialContext: AIAgentContextBase = this
        val nodeContext = initialContext.fork()
        
        // Execute input node with OTel error protection
        val inputNodeResult = try {
            inputNode.execute(nodeContext, input)
        } catch (e: Exception) {
            if (isOpenTelemetrySpanError(e)) {
                logger.debug("OpenTelemetry span error ignored in input node: ${e.message}")
                null
            } else {
                throw e
            }
        }
        val nodeOutput = inputNodeResult?.let { transform(it) }.orEmpty()

        val nodeResults = supervisorScope {
            nodeOutput.mapIndexed { index, value ->
                async(dispatcher) {
                    val nodeContext = initialContext.fork()
                    val nodeOutput = try {
                        node.execute(nodeContext, value)
                    } catch (e: Exception) {
                        if (isOpenTelemetrySpanError(e)) {
                            // Ignore OpenTelemetry span errors in parallel execution
                            logger.debug("OpenTelemetry span error ignored in parallel node[$index]: ${e.message}")
                            null
                        } else {
                            throw e
                        }
                    }

                    if (nodeOutput == null) {
                        if (nodeContext.getAgentContextData() != null) {
                            throw IllegalStateException(
                                "Checkpoints are not supported in parallel execution. Node: ${node.name}, Context: ${nodeContext.getAgentContextData()}"
                            )
                        }
                        // Skip this result if node returned null (e.g., due to OTel error)
                        return@async null
                    }

                    @Suppress("UNCHECKED_CAST")
                    val executionResult =
                        ParallelNodeExecutionResult(nodeOutput as OutgoingOutput, nodeContext)
                    ParallelResult(node.name, input, executionResult)
                }
            }.awaitAll().filterNotNull()
        }

        val mergeContext = AIAgentParallelNodesMergeContext(this, nodeResults)
        val result = with(mergeContext) {
            fold(ArrayList<OutgoingOutput>(nodeOutput.size)) { acc, result ->
                acc.add(result)
                acc
            }
        }
        replace(result.context)
        result.output
    }
)
