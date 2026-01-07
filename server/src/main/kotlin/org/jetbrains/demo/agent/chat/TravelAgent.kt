package org.jetbrains.demo.agent.chat

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseExporter
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.message.Message
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.catch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.jetbrains.demo.AgentEvent
import org.jetbrains.demo.AgentEvent.*
import org.jetbrains.demo.AgentEvent.Tool
import org.jetbrains.demo.AppConfig
import org.jetbrains.demo.JourneyForm
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.agent.chat.strategy.*
import org.jetbrains.demo.agent.koog.ktor.StreamingAIAgent
import org.jetbrains.demo.agent.koog.ktor.StreamingAIAgent.Event.*
import org.jetbrains.demo.agent.koog.ktor.sseAgent
import org.jetbrains.demo.agent.koog.ktor.withMaxAgentIterations
import org.jetbrains.demo.agent.koog.ktor.withSystemPrompt
import org.jetbrains.demo.agent.tools.tools
import org.slf4j.LoggerFactory
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Clock

private val logger = LoggerFactory.getLogger("TravelAgent")

private const val ANSI_CYAN = "\u001B[36m"
private const val ANSI_GREEN = "\u001B[32m"
private const val ANSI_RESET = "\u001B[0m"

fun Route.sse(
    path: String,
    method: HttpMethod = HttpMethod.Get,
    handler: suspend ServerSSESession.() -> Unit
): Route = route(path, method) { sse(handler) }

fun Application.agent(config: AppConfig) {
    val deferredTools = async { tools(config) }

    routing {
        sse("/plan", HttpMethod.Post) {
            val form = call.receive<JourneyForm>()
            val tools = deferredTools.await()
            sseAgent(
                planner(tools),
                LLM_MODEL,
                tools.registry() + ToolRegistry {
                    tool(ItineraryIdeasProvider)
                    tool(ResearchedPointOfInterestProvider)
                    tool(ProposedTravelPlanProvider)
                },
                configureAgent = {
                    it.withSystemPrompt(prompt("travel-assistant-agent") {
                        system(markdown {
                            "Today's date is ${
                                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                            }."
                            +"You're an expert travel assistant helping users reach their destination in a reliable way."
                            header(1, "Task description:")
                            +"You can only call tools. Figure out the accurate information from calling the google-maps tool, and the weather tool."
                        })
                    }).withMaxAgentIterations(300)
                },
                installFeatures = {
                    install(OpenTelemetry) {
                        setVerbose(true)
                        addLangfuseExporter(
                            langfuseUrl = config.langfuseUrl,
                            langfusePublicKey = config.langfusePublicKey,
                            langfuseSecretKey = config.langfuseSecretKey
                        )
                    }
                }
            ).run(form)
                .catch { e ->
                    // Log OpenTelemetry span errors gracefully without crashing
                    if (e is IllegalStateException && e.message?.contains("Span with id") == true) {
                        logger.warn("OpenTelemetry span error (non-fatal): ${e.message}")
                    } else {
                        logger.error("Agent error: ${e.message}", e)
                        send(data = Json.encodeToString(AgentEvent.serializer(), AgentError("unknown", "unknown", e.message)))
                    }
                }
                .collect { event: StreamingAIAgent.Event<JourneyForm, ProposedTravelPlan> ->
                    val result = event.toDomainEventOrNull()

                    if (result != null) {
                        try {
                            send(data = Json.encodeToString(AgentEvent.serializer(), result))
                        } catch (e: Exception) {
                            // Client disconnected - stop sending events
                            application.environment.log.error(Json.encodeToString(AgentEvent.serializer(), result))
                        }
                    }
                }
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private fun StreamingAIAgent.Event<JourneyForm, ProposedTravelPlan>.toDomainEventOrNull(): AgentEvent? {
    val inputTokens = AtomicInt(0)
    val outputTokens = AtomicInt(0)
    val totalTokens = AtomicInt(0)

    return when (this) {
        is Agent -> when (this) {
            is OnAgentFinished -> AgentFinished(
                agentId = agentId,
                runId = runId,
                result.toDomain()
            )

            is OnAgentRunError ->
                AgentError(agentId = agentId, runId = runId, throwable.message)

            is OnBeforeAgentStarted -> AgentStarted(context.agentId, context.runId)
        }


        is StreamingAIAgent.Event.Tool -> when (this) {
            is OnToolCallResult if toolName == ItineraryIdeasProvider.name ->
                Step1(Json.decodeFromJsonElement(ItineraryIdeas.serializer(), toolArgs).pointsOfInterest)

            is OnToolCallResult if toolName == ResearchedPointOfInterestProvider.name ->
                Step2(Json.decodeFromJsonElement(ResearchedPointOfInterest.serializer(), toolArgs).toDomain())

            is OnToolCallResult if toolName == ProposedTravelPlanProvider.name -> null
            else -> Tool(
                id = toolCallId!!,
                name = toolName,
                type = Tool.Type.fromToolName(toolName),
                state = when (this) {
                    is OnToolCall -> Tool.State.Running
                    is OnToolCallResult -> Tool.State.Succeeded
                    is OnToolCallFailure,
                    is OnToolValidationError -> Tool.State.Failed
                }
            )
        }

        is OnBeforeLLMCall -> {
            logger.info("$ANSI_CYAN=== LLM INPUT (Prompt) ===$ANSI_RESET")
            logger.info("${ANSI_CYAN}Model: ${model.id}$ANSI_RESET")
            prompt.messages.forEach { message ->
                logger.info("${ANSI_CYAN}Role: ${message.role}$ANSI_RESET")
                logger.info("${ANSI_CYAN}Request Content: ${message.content}$ANSI_RESET")
                logger.info("${ANSI_CYAN}---$ANSI_RESET")
            }
            null
        }

        is OnAfterLLMCall -> {
            val input = responses.sumOf { it.metaInfo.inputTokensCount ?: 0 }
            val output = responses.sumOf { it.metaInfo.outputTokensCount ?: 0 }
            val total = responses.sumOf { it.metaInfo.totalTokensCount ?: 0 }

            inputTokens.update { it + input }
            outputTokens.update { it + output }
            totalTokens.updateAndFetch { it + total }

            logger.info("$ANSI_GREEN=== LLM OUTPUT (Response) ===$ANSI_RESET")
            logger.info("${ANSI_GREEN}Response types: ${responses.map { it::class.simpleName }}$ANSI_RESET")
            responses.forEach { response ->
                when (response) {
                    is Message.Tool.Call -> {
                        logger.info("${ANSI_GREEN}Tool called: ${response.tool} (id: ${response.id})$ANSI_RESET")
                        logger.info("${ANSI_GREEN}Tool args: ${response.content}$ANSI_RESET")
                    }
                    is Message.Assistant -> {
                        logger.info("${ANSI_GREEN}Assistant: ${response.content}$ANSI_RESET")
                    }
                    is Message.Reasoning -> {
                        logger.info("${ANSI_GREEN}Reasoning: ${response.content}$ANSI_RESET")
                    }
                }
            }
            logger.info("${ANSI_GREEN}Input tokens: ${inputTokens.load()}, output tokens: ${outputTokens.load()}, total tokens: ${totalTokens.load()}$ANSI_RESET")
            logger.info("${ANSI_GREEN}========================$ANSI_RESET")

            val assistantContents = responses.filterIsInstance<Message.Assistant>().map { it.content }
            if (assistantContents.isNotEmpty()) Message(assistantContents)
            else null
        }

        is OnNodeExecutionError,
        is OnAfterNode,
        is OnBeforeNode,
        is OnStrategyFinished<*, *>,
        is OnStrategyStarted<*, *> -> null
    }
}
