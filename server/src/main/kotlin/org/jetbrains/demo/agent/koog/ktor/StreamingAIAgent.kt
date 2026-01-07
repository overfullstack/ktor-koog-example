package org.jetbrains.demo.agent.koog.ktor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.GraphAIAgent.FeatureContext
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
import ai.koog.agents.core.feature.model.AIAgentError
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.ktor.Koog
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import io.ktor.server.application.pluginOrNull
import io.ktor.server.sse.ServerSSESession
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toDeprecatedClock
import java.lang.IllegalStateException
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

suspend fun <Input, Output> ServerSSESession.sseAgent(
    inputType: KType,
    outputType: KType,
    strategy: AIAgentGraphStrategy<Input, Output>,
    model: LLModel,
    tools: ToolRegistry = ToolRegistry.EMPTY,
    clock: Clock = Clock.System,
    // TODO We need to create a proper `AgentConfig` builder inside of Ktor that allows overriding global configuration.
    configureAgent: (AIAgentConfig) -> (AIAgentConfig) = { it },
    installFeatures: FeatureContext.() -> Unit = {}
): StreamingAIAgent<Input, Output> {
    val plugin = requireNotNull(call.application.pluginOrNull(Koog)) { "Plugin $Koog is not configured" }

    @Suppress("invisible_reference", "invisible_member")
    return StreamingAIAgent(
        inputType = inputType,
        outputType = outputType,
        promptExecutor = plugin.promptExecutor,
        strategy = strategy,
        agentConfig = plugin.agentConfig(model).let(configureAgent),
        toolRegistry = plugin.agentConfig.toolRegistry + tools,
        clock = clock,
        installFeatures = installFeatures
    )
}

fun AIAgentConfig.withSystemPrompt(prompt: Prompt): AIAgentConfig =
    AIAgentConfig(prompt, model, maxAgentIterations, missingToolsConversionStrategy)


fun AIAgentConfig.withMaxAgentIterations(maxAgentIterations: Int): AIAgentConfig =
    AIAgentConfig(prompt, model, maxAgentIterations, missingToolsConversionStrategy)

suspend inline fun <reified Input, reified Output> ServerSSESession.sseAgent(
    strategy: AIAgentGraphStrategy<Input, Output>,
    model: LLModel,
    tools: ToolRegistry = ToolRegistry.EMPTY,
    clock: Clock = Clock.System,
    noinline configureAgent: (AIAgentConfig) -> (AIAgentConfig) = { it },
    noinline installFeatures: FeatureContext.() -> Unit = {}
): StreamingAIAgent<Input, Output> = sseAgent(
    typeOf<Input>(),
    typeOf<Output>(),
    strategy,
    model,
    tools,
    clock,
    configureAgent,
    installFeatures
)

@OptIn(ExperimentalUuidApi::class)
class StreamingAIAgent<Input, Output>(
    inputType: KType,
    outputType: KType,
    promptExecutor: PromptExecutor,
    strategy: AIAgentGraphStrategy<Input, Output>,
    override val agentConfig: AIAgentConfig,
    override val id: String = Uuid.random().toString(),
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    clock: Clock = Clock.System,
    installFeatures: FeatureContext.() -> Unit = {},
) : AIAgent<Input, Flow<StreamingAIAgent.Event<Input, Output>>> {

    override suspend fun getState(): AIAgent.Companion.State<Flow<Event<Input, Output>>> =
        AIAgent.Companion.State.NotStarted()

    override suspend fun close() {
        agent.close()
    }

    sealed interface Event<out Input, out Output> {
        val runId: String

        sealed interface Agent<Input, Output> : Event<Input, Output> {
            val agentId: String
        }

        data class OnBeforeAgentStarted<Input, Output>(
            val agent: AIAgent<Input, Output>,
            override val runId: String,
            val context: AIAgentContext
        ) : Agent<Input, Output> {
            override val agentId: String
                get() = agent.id
        }

        data class OnAgentFinished<Output>(
            override val agentId: String,
            override val runId: String,
            val result: Output,
        ) : Agent<Nothing, Output>

        data class OnAgentRunError(
            override val agentId: String,
            override val runId: String,
            val throwable: Throwable
        ) : Agent<Nothing, Nothing>

        sealed interface Strategy<Input, Output> : Event<Input, Output>

        data class OnStrategyStarted<Input, Output>(
            override val runId: String,
            val strategyName: String,
        ) : Strategy<Input, Output>

        data class OnStrategyFinished<Input, Output>(
            override val runId: String,
            val strategyName: String,
            val result: Output,
            val resultType: KType,
        ) : Strategy<Input, Output>

        sealed interface Node : Event<Nothing, Nothing> {
            val node: AIAgentNodeBase<*, *>
            val context: AIAgentGraphContextBase
        }

        data class OnBeforeNode(
            override val node: AIAgentNodeBase<*, *>,
            override val context: AIAgentGraphContextBase,
            val input: Any?,
            val inputType: KType,
        ) : Node {
            override val runId: String
                get() = context.runId
        }

        data class OnAfterNode(
            override val node: AIAgentNodeBase<*, *>,
            override val context: AIAgentGraphContextBase,
            val input: Any?,
            val output: Any?,
            val inputType: KType,
            val outputType: KType,
        ) : Node {
            override val runId: String
                get() = context.runId
        }

        data class OnNodeExecutionError(
            override val node: AIAgentNodeBase<*, *>,
            override val context: AIAgentGraphContextBase,
            val throwable: Throwable
        ) : Node {
            override val runId: String
                get() = context.runId
        }

        sealed interface LLM : Event<Nothing, Nothing> {
            val prompt: Prompt
            val model: LLModel
            val tools: List<ToolDescriptor>
        }

        data class OnBeforeLLMCall(
            override val runId: String,
            override val prompt: Prompt,
            override val model: LLModel,
            override val tools: List<ToolDescriptor>,
        ) : LLM

        data class OnAfterLLMCall(
            override val runId: String,
            override val prompt: Prompt,
            override val model: LLModel,
            override val tools: List<ToolDescriptor>,
            val responses: List<Message.Response>,
            val moderationResponse: ModerationResult?
        ) : LLM

        sealed interface Tool : Event<Nothing, Nothing> {
            val toolCallId: String?
            val toolName: String
            val toolArgs: JsonObject
        }

        data class OnToolCall(
            override val runId: String,
            override val toolCallId: String?,
            override val toolName: String,
            override val toolArgs: JsonObject
        ) : Tool

        data class OnToolValidationError(
            override val runId: String,
            override val toolCallId: String?,
            override val toolName: String,
            override val toolArgs: JsonObject,
            val error: AIAgentError
        ) : Tool

        data class OnToolCallFailure(
            override val runId: String,
            override val toolCallId: String?,
            override val toolName: String,
            override val toolArgs: JsonObject,
            val error: AIAgentError?
        ) : Tool

        data class OnToolCallResult(
            override val runId: String,
            override val toolCallId: String?,
            override val toolName: String,
            override val toolArgs: JsonObject,
            val result: JsonElement?
        ) : Tool
    }

    private var channel: ProducerScope<Event<Input, Output>>? = null
    private var isRunning = false
    private val runningMutex = Mutex()

    private suspend fun send(agent: Event<Input, Output>) =
        requireNotNull(channel) { "Race condition detected: SSEAgent2 is not running anymore" }
            .send(agent)

    private val agent = GraphAIAgent(
        inputType = inputType,
        outputType = outputType,
        promptExecutor = promptExecutor,
        agentConfig = agentConfig,
        strategy = strategy,
        toolRegistry = toolRegistry,
        id = id,
        clock = clock.toDeprecatedClock()
    ) {
        installFeatures()
        @Suppress("UNCHECKED_CAST")
        install(EventHandler) {
            onAgentStarting { ctx ->
                send(
                    Event.OnBeforeAgentStarted<Input, Output>(
                        ctx.agent as AIAgent<Input, Output>,
                        ctx.runId,
                        ctx.context
                    )
                )
            }
            onAgentCompleted { ctx ->
                send(
                    Event.OnAgentFinished(
                        ctx.agentId,
                        ctx.runId,
                        ctx.result as Output
                    )
                )
            }
            onAgentExecutionFailed { ctx -> send(Event.OnAgentRunError(ctx.agentId, ctx.runId, ctx.throwable)) }

            onStrategyStarting { ctx ->
                send(
                    Event.OnStrategyStarted(
                        ctx.context.runId,
                        ctx.strategy.name
                    )
                )
            }
            onStrategyCompleted { ctx ->
                send(
                    Event.OnStrategyFinished(
                        ctx.context.runId,
                        ctx.strategy.name,
                        ctx.result as Output,
                        ctx.resultType
                    )
                )
            }

            onNodeExecutionStarting { ctx -> send(Event.OnBeforeNode(ctx.node, ctx.context, ctx.input, ctx.inputType)) }
            onNodeExecutionCompleted { ctx ->
                send(
                    Event.OnAfterNode(
                        ctx.node,
                        ctx.context,
                        ctx.input,
                        ctx.output,
                        ctx.inputType,
                        ctx.outputType
                    )
                )
            }
            onNodeExecutionFailed { ctx -> send(Event.OnNodeExecutionError(ctx.node, ctx.context, ctx.throwable)) }

            onLLMCallStarting { ctx -> send(Event.OnBeforeLLMCall(ctx.runId, ctx.prompt, ctx.model, ctx.tools)) }
            onLLMCallCompleted { ctx ->
                send(
                    Event.OnAfterLLMCall(
                        ctx.runId,
                        ctx.prompt,
                        ctx.model,
                        ctx.tools,
                        ctx.responses,
                        ctx.moderationResponse
                    )
                )
            }

            onToolCallStarting { ctx -> send(Event.OnToolCall(ctx.runId, ctx.toolCallId, ctx.toolName, ctx.toolArgs)) }
            onToolValidationFailed { ctx ->
                send(
                    Event.OnToolValidationError(
                        ctx.runId,
                        ctx.toolCallId,
                        ctx.toolName,
                        ctx.toolArgs,
                        ctx.error
                    )
                )
            }
            onToolCallFailed { ctx ->
                send(
                    Event.OnToolCallFailure(
                        ctx.runId,
                        ctx.toolCallId,
                        ctx.toolName,
                        ctx.toolArgs,
                        ctx.error
                    )
                )
            }
            onToolCallCompleted { ctx ->
                send(
                    Event.OnToolCallResult(
                        ctx.runId,
                        ctx.toolCallId,
                        ctx.toolName,
                        ctx.toolArgs,
                        ctx.toolResult
                    )
                )
            }
        }
    }

    override suspend fun run(agentInput: Input): Flow<Event<Input, Output>> =
        channelFlow<Event<Input, Output>> {
            runningMutex.withLock {
                if (isRunning) {
                    throw IllegalStateException("Agent is already running")
                }

                isRunning = true
            }
            this@StreamingAIAgent.channel = this
            agent.run(agentInput)
            this@StreamingAIAgent.channel = null
            runningMutex.withLock { isRunning = false }
        }
}
