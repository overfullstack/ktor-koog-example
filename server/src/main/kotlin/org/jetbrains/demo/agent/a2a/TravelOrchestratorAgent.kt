package org.jetbrains.demo.agent.a2a

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.*
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.demo.JourneyForm
import org.jetbrains.demo.agent.a2a.model.ItineraryIdeasResult
import org.jetbrains.demo.agent.a2a.model.POIResearchRequest
import org.jetbrains.demo.agent.a2a.model.POIResearchResult
import org.jetbrains.demo.agent.a2a.model.TravelPlanRequest
import org.jetbrains.demo.agent.a2a.model.TravelPlanResult
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = LoggerFactory.getLogger("TravelOrchestratorAgent")

data class A2AAgentEndpoints(
    val routePlannerUrl: String,
    val poiResearcherUrl: String,
    val planComposerUrl: String
)

class TravelOrchestratorAgent(
    private val endpoints: A2AAgentEndpoints
) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun planTravel(journeyForm: JourneyForm): TravelPlanResult = coroutineScope {
        logger.info("Starting A2A mesh travel planning orchestration")

        // Step 1: Call Route Planner Agent to get itinerary ideas
        logger.info("Step 1: Calling Route Planner Agent")
        val itineraryIdeas = callRoutePlannerAgent(journeyForm)
        logger.info("Route Planner returned ${itineraryIdeas.pointsOfInterest.size} points of interest")

        // Step 2: Call POI Researcher Agent in parallel for each point of interest
        logger.info("Step 2: Calling POI Researcher Agent for ${itineraryIdeas.pointsOfInterest.size} POIs in parallel")
        val researchResults = itineraryIdeas.pointsOfInterest.map { poi ->
            async {
                callPOIResearcherAgent(
                    POIResearchRequest(
                        pointOfInterest = poi,
                        travelers = journeyForm.travelers.joinToString { it.name },
                        startDate = journeyForm.startDate.toString(),
                        endDate = journeyForm.endDate.toString()
                    )
                )
            }
        }.awaitAll()
        logger.info("POI Researcher returned ${researchResults.size} research results")

        // Step 3: Call Plan Composer Agent to create the final travel plan
        logger.info("Step 3: Calling Plan Composer Agent")
        val travelPlanRequest = TravelPlanRequest(
            journeyDetails = buildJourneyDetails(journeyForm),
            researchedPoints = researchResults
        )
        val travelPlan = callPlanComposerAgent(travelPlanRequest)
        logger.info("Plan Composer returned travel plan: ${travelPlan.title}")

        travelPlan
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callRoutePlannerAgent(journeyForm: JourneyForm): ItineraryIdeasResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.routePlannerUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.routePlannerUrl.substringBefore(ROUTE_PLANNER_PATH),
            path = ROUTE_PLANNER_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(JourneyForm.serializer(), journeyForm))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "itinerary-ideas")
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callPOIResearcherAgent(request: POIResearchRequest): POIResearchResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.poiResearcherUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.poiResearcherUrl.substringBefore(POI_RESEARCHER_PATH),
            path = POI_RESEARCHER_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(POIResearchRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "poi-research")
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callPlanComposerAgent(request: TravelPlanRequest): TravelPlanResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.planComposerUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.planComposerUrl.substringBefore(PLAN_COMPOSER_PATH),
            path = PLAN_COMPOSER_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(TravelPlanRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "travel-plan")
        } finally {
            transport.close()
        }
    }

    private inline fun <reified T> extractArtifact(
        responses: List<ai.koog.a2a.transport.Response<Event>>,
        artifactId: String
    ): T {
        val artifacts = mutableMapOf<String, Artifact>()

        responses.forEach { response ->
            when (val event = response.data) {
                is Task -> event.artifacts?.forEach { artifacts[it.artifactId] = it }
                is TaskArtifactUpdateEvent -> {
                    if (event.append == true) {
                        val existing = artifacts[event.artifact.artifactId]
                        if (existing != null) {
                            artifacts[event.artifact.artifactId] = existing.copy(
                                parts = existing.parts + event.artifact.parts
                            )
                        } else {
                            artifacts[event.artifact.artifactId] = event.artifact
                        }
                    } else {
                        artifacts[event.artifact.artifactId] = event.artifact
                    }
                }
                else -> {}
            }
        }

        val artifact = artifacts[artifactId]
            ?: throw IllegalStateException("Artifact '$artifactId' not found in response")

        val textContent = artifact.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        return json.decodeFromString<T>(textContent)
    }

    private fun buildJourneyDetails(journeyForm: JourneyForm): String = """
        Travelers: ${journeyForm.travelers.joinToString { "${it.name}${it.about?.let { a -> " ($a)" } ?: ""}" }}
        From: ${journeyForm.fromCity}
        To: ${journeyForm.toCity}
        Transport: ${journeyForm.transport}
        Departure: ${journeyForm.startDate}
        Return: ${journeyForm.endDate}
        ${journeyForm.details?.let { "Additional details: $it" } ?: ""}
    """.trimIndent()
}
