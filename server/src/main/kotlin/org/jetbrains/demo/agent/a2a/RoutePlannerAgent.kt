package org.jetbrains.demo.agent.a2a

import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.agents.a2a.core.A2AMessage
import ai.koog.agents.a2a.server.feature.A2AAgentServer
import ai.koog.agents.a2a.server.feature.withA2AAgentServer
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.markdown.markdown
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.jetbrains.demo.JourneyForm
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.agent.a2a.model.ItineraryIdeasResult
import org.jetbrains.demo.agent.tools.Tools
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

const val ROUTE_PLANNER_PATH = "/a2a/route-planner"
const val ROUTE_PLANNER_CARD_PATH = "$ROUTE_PLANNER_PATH/agent-card.json"

fun routePlannerAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Route Planner Agent",
    description = "Plans travel routes and identifies points of interest based on journey details",
    version = "1.0.0",
    url = "$baseUrl$ROUTE_PLANNER_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$ROUTE_PLANNER_PATH",
            transport = TransportProtocol.JSONRPC,
        )
    ),
    capabilities = AgentCapabilities(
        streaming = true,
        pushNotifications = false,
        stateTransitionHistory = false,
    ),
    defaultInputModes = listOf("text"),
    defaultOutputModes = listOf("text"),
    skills = listOf(
        AgentSkill(
            id = "route_planning",
            name = "Route Planning",
            description = "Plans travel routes and identifies points of interest for a journey",
            examples = listOf(
                "Plan a route from Paris to Rome",
                "Find interesting stops between London and Edinburgh",
                "Suggest itinerary points for a family trip"
            ),
            tags = listOf("travel", "routing", "points-of-interest", "itinerary")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class RoutePlannerAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val tools: Tools
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val agent = routePlannerAgent(promptExecutor, tools, context, eventProcessor)
        agent.run(context.params.message)
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun routePlannerAgent(
    promptExecutor: PromptExecutor,
    tools: Tools,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("route-planner") {
            system {
                +"""
                You are a travel route planning expert. Your task is to analyze journey details and 
                identify relevant points of interest along the route.
                
                Consider:
                - The travelers' preferences and needs
                - Transportation method constraints
                - Seasonal considerations
                - Geographic and cultural attractions
                - Practical routing for minimal travel time
                
                Use mapping and weather tools to make informed decisions.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 15
    )

    val toolRegistry = ToolRegistry {
        tools(tools.googleMaps.tools)
        tools(tools.weatherTool)
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = routePlannerStrategy(),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun routePlannerStrategy() = strategy<A2AMessage, Unit>("route-planner-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, JourneyForm> { message ->
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        json.decodeFromString<JourneyForm>(textContent)
    }

    val setupContextAndPlan by node<JourneyForm, ItineraryIdeasResult> { journeyForm ->
        llm.writeSession {
            appendPrompt {
                user {
                    markdown {
                        header(1, "Task description")
                        bulleted {
                            item("Find points of interest that are relevant to the travel journey and travelers.")
                            item("Use mapping tools to consider appropriate order and put a rough date range for each point of interest.")
                        }
                        header(2, "Details")
                        bulleted {
                            item("The travelers are ${journeyForm.travelers}.")
                            item("Travelling from ${journeyForm.fromCity} to ${journeyForm.toCity}.")
                            item("Leaving on ${journeyForm.startDate}, and returning on ${journeyForm.endDate}.")
                            item("The preferred transportation method is ${journeyForm.transport}.")
                        }
                    }
                }
            }
            requestLLMStructured<ItineraryIdeasResult>().getOrThrow().data
        }
    }

    val sendResult by node<ItineraryIdeasResult, Unit> { itinerary ->
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "itinerary-ideas",
                    parts = listOf(
                        TextPart(json.encodeToString(ItineraryIdeasResult.serializer(), itinerary))
                    )
                ),
            )
            eventProcessor.sendTaskEvent(artifactUpdate)

            val taskStatusUpdate = TaskStatusUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                status = TaskStatus(
                    state = TaskState.Completed,
                    timestamp = Clock.System.now(),
                ),
                final = true,
            )
            eventProcessor.sendTaskEvent(taskStatusUpdate)
        }
    }

    val createTask by node<JourneyForm, JourneyForm> { input ->
        withA2AAgentServer {
            val userInput = context.params.message
            val task = Task(
                id = context.taskId,
                contextId = context.contextId,
                status = TaskStatus(
                    state = TaskState.Working,
                    message = userInput,
                    timestamp = Clock.System.now(),
                ),
            )
            eventProcessor.sendTaskEvent(task)
        }
        input
    }

    nodeStart then parseInput then createTask then setupContextAndPlan then sendResult then nodeFinish
}
