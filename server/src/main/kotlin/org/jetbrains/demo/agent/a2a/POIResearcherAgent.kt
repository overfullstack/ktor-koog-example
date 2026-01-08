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
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.agent.a2a.model.POIResearchRequest
import org.jetbrains.demo.agent.a2a.model.POIResearchResult
import org.jetbrains.demo.agent.tools.Tools
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi


const val POI_RESEARCHER_PATH = "/a2a/poi-researcher"
const val POI_RESEARCHER_CARD_PATH = "$POI_RESEARCHER_PATH/agent-card.json"

fun poiResearcherAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "POI Researcher Agent",
    description = "Researches detailed information about points of interest including history, culture, and events",
    version = "1.0.0",
    url = "$baseUrl$POI_RESEARCHER_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$POI_RESEARCHER_PATH",
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
            id = "poi_research",
            name = "Point of Interest Research",
            description = "Researches detailed information about travel destinations including history, culture, art, and events",
            examples = listOf(
                "Research the Eiffel Tower",
                "Find interesting facts about the Colosseum",
                "What events are happening in Barcelona in July?"
            ),
            tags = listOf("travel", "research", "culture", "history", "events")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class POIResearcherAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val tools: Tools
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val agent = poiResearcherAgent(promptExecutor, tools, context, eventProcessor)
        agent.run(context.params.message)
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun poiResearcherAgent(
    promptExecutor: PromptExecutor,
    tools: Tools,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("poi-researcher") {
            system {
                +"""
                You are a travel research expert specializing in cultural and historical information.
                Your task is to research points of interest and provide rich, detailed information.
                
                Focus on:
                - Interesting stories about art, culture, and famous people
                - Historical significance and context
                - Events happening during the travel dates
                - Practical visitor information
                - High-quality images that showcase the location
                
                Use web search tools to find accurate, up-to-date information.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 15
    )

    val toolRegistry = ToolRegistry {
        tools(tools.googleMaps.tools)
        tools(tools.searchTool)
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = poiResearcherStrategy(),
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
private fun poiResearcherStrategy() = strategy<A2AMessage, Unit>("poi-researcher-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, POIResearchRequest> { message ->
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        json.decodeFromString<POIResearchRequest>(textContent)
    }

    val createTask by node<POIResearchRequest, POIResearchRequest> { input ->
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

    val setupContextAndResearch by node<POIResearchRequest, POIResearchResult> { request ->
        llm.writeSession {
            appendPrompt {
                user {
                    markdown {
                        +"Research the following point of interest."
                        +"Consider interesting stories about art and culture and famous people."
                        +"Details from the traveler: ${request.travelers}."
                        +"Dates to consider: departure from ${request.startDate} to ${request.endDate}."
                        +"If any particularly important events are happening here during this time, mention them and list specific dates."
                        header(1, "Point of interest to research")
                        bulleted {
                            item("Name: ${request.pointOfInterest.name}")
                            item("Location: ${request.pointOfInterest.location}")
                            item("From ${request.pointOfInterest.fromDate} to ${request.pointOfInterest.toDate}")
                            item("Description: ${request.pointOfInterest.description}")
                        }
                    }
                }
            }
            requestLLMStructured<POIResearchResult>().getOrThrow().data
        }
    }

    val sendResult by node<POIResearchResult, Unit> { researchResult ->
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "poi-research",
                    parts = listOf(
                        TextPart(json.encodeToString(POIResearchResult.serializer(), researchResult))
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

    nodeStart then parseInput then createTask then setupContextAndResearch then sendResult then nodeFinish
}
