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
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.agent.a2a.model.TravelPlanRequest
import org.jetbrains.demo.agent.a2a.model.TravelPlanResult
import org.jetbrains.demo.agent.tools.Tools
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi


private const val IMAGE_WIDTH = 400
private const val WORD_COUNT = 200

const val PLAN_COMPOSER_PATH = "/a2a/plan-composer"
const val PLAN_COMPOSER_CARD_PATH = "$PLAN_COMPOSER_PATH/agent-card.json"

fun planComposerAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Plan Composer Agent",
    description = "Composes detailed travel plans from researched points of interest",
    version = "1.0.0",
    url = "$baseUrl$PLAN_COMPOSER_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$PLAN_COMPOSER_PATH",
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
            id = "plan_composition",
            name = "Travel Plan Composition",
            description = "Creates comprehensive travel plans with detailed itineraries, routing, and recommendations",
            examples = listOf(
                "Create a travel plan for our European tour",
                "Compose an itinerary for family vacation",
                "Build a detailed road trip plan"
            ),
            tags = listOf("travel", "planning", "itinerary", "composition")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class PlanComposerAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val tools: Tools
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val agent = planComposerAgent(promptExecutor, tools, context, eventProcessor)
        agent.run(context.params.message)
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun planComposerAgent(
    promptExecutor: PromptExecutor,
    tools: Tools,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("plan-composer") {
            system {
                +"""
                You are a travel plan composition expert. Your task is to create comprehensive, 
                detailed travel plans from researched points of interest.
                
                Your plans should:
                - Have a catchy, memorable title
                - Minimize travel time while maximizing experiences
                - Consider weather and seasonal factors
                - Include interesting stories about locations
                - Be well-formatted in markdown with proper headings
                - Include relevant images and links
                - Provide practical routing information
                
                Use mapping and weather tools to verify distances and conditions.
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
        strategy = planComposerStrategy(),
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
private fun planComposerStrategy() = strategy<A2AMessage, Unit>("plan-composer-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, TravelPlanRequest> { message ->
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        json.decodeFromString<TravelPlanRequest>(textContent)
    }

    val createTask by node<TravelPlanRequest, TravelPlanRequest> { input ->
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

    val setupContextAndCompose by node<TravelPlanRequest, TravelPlanResult> { request ->
        llm.writeSession {
            appendPrompt {
                user {
                    +"""
                    Given the following travel brief, create a detailed plan.
                    Give it a brief, catchy title that doesn't include dates, but may consider season, mood or relate to travelers's interests.

                    Plan the journey to minimize travel time.
                    However, consider any important events or places of interest along the way that might inform routing.
                    Include total distances.

                    ${request.journeyDetails}
                    Consider the weather in your recommendations. Use mapping tools to consider distance of driving or walking.

                    Write up in $WORD_COUNT words or less.
                    Include links in text where appropriate and in the links field.
                    
                    The Day field locationAndCountry field should be in the format <location,+Country> e.g. Ghent,+Belgium

                    Put image links where appropriate in text and also in the links field.

                    Recount at least one interesting story about a famous person associated with an area.
                    
                    Include natural headings and paragraphs in MARKDOWN format.
                    Use unordered lists as appropriate.
                    Start any headings at Header 4
                    Embed images in text, with max width of ${IMAGE_WIDTH}px.
                    Be sure to include informative caption and alt text for each image.

                    Consider the following researched points of interest:
                    ${
                        request.researchedPoints.joinToString("\n") {
                            """
                            ${it.pointOfInterest.name}
                            ${it.research}
                            ${it.links.joinToString { link -> "${link.url}: ${link.summary}" }}
                            Images: ${it.imageLinks.joinToString { link -> "${link.url}: ${link.summary}" }}
                            """.trimIndent()
                        }
                    }
                    """.trimIndent()
                }
            }
            requestLLMStructured<TravelPlanResult>().getOrThrow().data
        }
    }

    val sendResult by node<TravelPlanResult, Unit> { planResult ->
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "travel-plan",
                    parts = listOf(
                        TextPart(json.encodeToString(TravelPlanResult.serializer(), planResult))
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

    nodeStart then parseInput then createTask then setupContextAndCompose then sendResult then nodeFinish
}
