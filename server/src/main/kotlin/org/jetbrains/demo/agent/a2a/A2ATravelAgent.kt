package org.jetbrains.demo.agent.a2a

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.demo.*
import org.jetbrains.demo.TransportType
import org.jetbrains.demo.agent.a2a.model.TravelPlanResult
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2ATravelAgent")

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val conversationManager = ConversationManager()

@Serializable
data class A2ATravelPlanResponse(
    val success: Boolean,
    val plan: TravelPlanResult? = null,
    val error: String? = null
)

fun Application.a2aTravelAgentRoutes(orchestrator: TravelOrchestratorAgent) {
    routing {
        route("/a2a") {
            post("/plan") {
                try {
                    val journeyForm = call.receive<JourneyForm>()
                    logger.info("Received travel plan request via A2A mesh: ${journeyForm.fromCity} to ${journeyForm.toCity}")

                    val travelPlan = orchestrator.planTravel(journeyForm)

                    call.respond(
                        HttpStatusCode.OK,
                        A2ATravelPlanResponse(success = true, plan = travelPlan)
                    )
                } catch (e: Exception) {
                    logger.error("Error processing A2A travel plan request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        A2ATravelPlanResponse(success = false, error = e.message)
                    )
                }
            }

            sse("/plan/stream") {
                try {
                    val journeyFormJson = call.request.queryParameters["journeyForm"]
                        ?: throw IllegalArgumentException("Missing journeyForm parameter")

                    val journeyForm = json.decodeFromString<JourneyForm>(journeyFormJson)
                    logger.info("Received streaming travel plan request via A2A mesh: ${journeyForm.fromCity} to ${journeyForm.toCity}")

                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.AgentStarted.serializer(), 
                        AgentEvent.AgentStarted(agentId = "a2a-orchestrator", runId = "a2a-run"))))

                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.Message.serializer(),
                        AgentEvent.Message(listOf("Starting A2A mesh orchestration...")))))

                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.Message.serializer(),
                        AgentEvent.Message(listOf("Calling Route Planner Agent...")))))

                    val travelPlan = orchestrator.planTravel(journeyForm)

                    val proposedPlan = ProposedTravelPlan(
                        title = travelPlan.title,
                        plan = travelPlan.plan,
                        days = travelPlan.days,
                        imageLinks = travelPlan.imageLinks,
                        pageLinks = travelPlan.pageLinks,
                        countriesVisited = travelPlan.countriesVisited
                    )

                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.AgentFinished.serializer(),
                        AgentEvent.AgentFinished(
                            agentId = "a2a-orchestrator",
                            runId = "a2a-run",
                            plan = proposedPlan
                        ))))

                } catch (e: Exception) {
                    logger.error("Error in A2A streaming travel plan", e)
                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.AgentError.serializer(),
                        AgentEvent.AgentError(
                            agentId = "a2a-orchestrator",
                            runId = "a2a-run",
                            result = e.message
                        ))))
                }
            }

            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "healthy", "mode" to "a2a-mesh"))
            }

            // Conversational endpoints
            post("/conversation/start") {
                try {
                    val userMessage = call.receive<UserMessage>()
                    logger.info("Starting new conversation")

                    val context = conversationManager.startConversation(userMessage.journeyForm)
                    val updatedContext = conversationManager.addMessage(
                        context.conversationId, "user", userMessage.message
                    )!!

                    val response = when {
                        userMessage.journeyForm != null -> {
                            val clarification = conversationManager.needsClarification(userMessage.journeyForm)
                            if (clarification != null) {
                                val contextWithQuestion = conversationManager.updateConversation(
                                    updatedContext.copy(
                                        state = ConversationState.AWAITING_PREFERENCES,
                                        pendingQuestion = clarification
                                    )
                                )
                                AgentResponse(
                                    conversationId = contextWithQuestion.conversationId,
                                    state = contextWithQuestion.state,
                                    message = "I'd like to help you plan your trip! ${clarification.question}",
                                    question = clarification,
                                    options = clarification.options
                                )
                            } else {
                                AgentResponse(
                                    conversationId = updatedContext.conversationId,
                                    state = ConversationState.AWAITING_CONFIRMATION,
                                    message = "Great! I have all the details. Would you like me to start planning your trip from ${userMessage.journeyForm.fromCity} to ${userMessage.journeyForm.toCity}?",
                                    options = listOf("Yes, start planning", "No, let me modify details")
                                )
                            }
                        }
                        else -> AgentResponse(
                            conversationId = updatedContext.conversationId,
                            state = ConversationState.AWAITING_JOURNEY_DETAILS,
                            message = "Hello! I'm your travel planning assistant. Please provide your journey details including origin, destination, dates, and travelers."
                        )
                    }

                    conversationManager.addMessage(response.conversationId, "assistant", response.message)
                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    logger.error("Error starting conversation", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentResponse(
                            conversationId = "",
                            state = ConversationState.FAILED,
                            message = "Failed to start conversation: ${e.message}"
                        )
                    )
                }
            }

            post("/conversation/{conversationId}/message") {
                try {
                    val conversationId = call.parameters["conversationId"]
                        ?: throw IllegalArgumentException("Missing conversationId")
                    val userMessage = call.receive<UserMessage>()
                    logger.info("Received message for conversation: $conversationId")

                    val context = conversationManager.getConversation(conversationId)
                        ?: throw IllegalArgumentException("Conversation not found: $conversationId")

                    conversationManager.addMessage(conversationId, "user", userMessage.message)

                    val response = handleConversationMessage(context, userMessage, orchestrator)
                    conversationManager.addMessage(conversationId, "assistant", response.message)

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Bad request: ${e.message}")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentResponse(
                            conversationId = call.parameters["conversationId"] ?: "",
                            state = ConversationState.FAILED,
                            message = e.message ?: "Invalid request"
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Error processing message", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentResponse(
                            conversationId = call.parameters["conversationId"] ?: "",
                            state = ConversationState.FAILED,
                            message = "Error: ${e.message}"
                        )
                    )
                }
            }

            get("/conversation/{conversationId}") {
                val conversationId = call.parameters["conversationId"]
                    ?: throw IllegalArgumentException("Missing conversationId")

                val context = conversationManager.getConversation(conversationId)
                if (context != null) {
                    call.respond(HttpStatusCode.OK, context)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Conversation not found"))
                }
            }

            sse("/conversation/{conversationId}/stream") {
                val conversationId = call.parameters["conversationId"]
                    ?: throw IllegalArgumentException("Missing conversationId")

                val context = conversationManager.getConversation(conversationId)
                    ?: throw IllegalArgumentException("Conversation not found")

                if (context.journeyForm == null) {
                    send(ServerSentEvent(
                        event = "error",
                        data = json.encodeToString(AgentResponse.serializer(), AgentResponse(
                            conversationId = conversationId,
                            state = ConversationState.FAILED,
                            message = "No journey details provided"
                        ))
                    ))
                    return@sse
                }

                send(ServerSentEvent(
                    event = "status",
                    data = json.encodeToString(AgentResponse.serializer(), AgentResponse(
                        conversationId = conversationId,
                        state = ConversationState.PLANNING,
                        message = "Starting travel planning..."
                    ))
                ))

                conversationManager.updateConversation(context.copy(state = ConversationState.PLANNING))

                send(ServerSentEvent(
                    event = "progress",
                    data = "Calling Route Planner Agent..."
                ))

                try {
                    val travelPlan = orchestrator.planTravel(context.journeyForm)

                    conversationManager.updateConversation(
                        context.copy(
                            state = ConversationState.COMPLETED,
                            result = travelPlan
                        )
                    )

                    send(ServerSentEvent(
                        event = "complete",
                        data = json.encodeToString(AgentResponse.serializer(), AgentResponse(
                            conversationId = conversationId,
                            state = ConversationState.COMPLETED,
                            message = "Your travel plan is ready!",
                            plan = travelPlan
                        ))
                    ))
                } catch (e: Exception) {
                    logger.error("Error in planning stream", e)
                    conversationManager.updateConversation(context.copy(state = ConversationState.FAILED))
                    send(ServerSentEvent(
                        event = "error",
                        data = json.encodeToString(AgentResponse.serializer(), AgentResponse(
                            conversationId = conversationId,
                            state = ConversationState.FAILED,
                            message = "Planning failed: ${e.message}"
                        ))
                    ))
                }
            }
        }
    }
}

private suspend fun handleConversationMessage(
    context: ConversationContext,
    userMessage: UserMessage,
    orchestrator: TravelOrchestratorAgent
): AgentResponse {
    return when (context.state) {
        ConversationState.AWAITING_JOURNEY_DETAILS -> {
            if (userMessage.journeyForm != null) {
                val updatedContext = conversationManager.updateConversation(
                    context.copy(
                        journeyForm = userMessage.journeyForm,
                        state = ConversationState.AWAITING_PREFERENCES
                    )
                )
                val clarification = conversationManager.needsClarification(userMessage.journeyForm)
                if (clarification != null) {
                    conversationManager.updateConversation(
                        updatedContext.copy(pendingQuestion = clarification)
                    )
                    AgentResponse(
                        conversationId = context.conversationId,
                        state = ConversationState.AWAITING_PREFERENCES,
                        message = clarification.question,
                        question = clarification,
                        options = clarification.options
                    )
                } else {
                    conversationManager.updateConversation(
                        updatedContext.copy(state = ConversationState.AWAITING_CONFIRMATION)
                    )
                    AgentResponse(
                        conversationId = context.conversationId,
                        state = ConversationState.AWAITING_CONFIRMATION,
                        message = "Great! Ready to plan your trip. Shall I proceed?",
                        options = listOf("Yes, start planning", "No, let me change something")
                    )
                }
            } else {
                AgentResponse(
                    conversationId = context.conversationId,
                    state = ConversationState.AWAITING_JOURNEY_DETAILS,
                    message = "Please provide your journey details (origin, destination, dates, travelers)."
                )
            }
        }

        ConversationState.AWAITING_PREFERENCES -> {
            val currentPrefs = context.preferences ?: TravelPreferences()
            val pendingQuestion = context.pendingQuestion

            val updatedPrefs = when (pendingQuestion?.field) {
                "preferences" -> currentPrefs.copy(
                    activityTypes = userMessage.message.split(",").map { it.trim() }
                )
                "transport" -> {
                    val transportType = try {
                        TransportType.valueOf(userMessage.message.trim())
                    } catch (e: IllegalArgumentException) {
                        TransportType.Train // default
                    }
                    val updatedForm = context.journeyForm?.copy(transport = transportType)
                    conversationManager.updateConversation(context.copy(journeyForm = updatedForm))
                    currentPrefs
                }
                else -> currentPrefs.copy(interests = listOf(userMessage.message))
            }

            val updatedContext = conversationManager.updateConversation(
                context.copy(
                    preferences = updatedPrefs,
                    pendingQuestion = null,
                    state = ConversationState.AWAITING_CONFIRMATION
                )
            )

            AgentResponse(
                conversationId = context.conversationId,
                state = ConversationState.AWAITING_CONFIRMATION,
                message = "Perfect! I've noted your preferences. Ready to create your travel plan?",
                options = listOf("Yes, start planning", "No, I want to add more details")
            )
        }

        ConversationState.AWAITING_CONFIRMATION -> {
            val lowerMessage = userMessage.message.lowercase()
            when {
                lowerMessage.contains("yes") || lowerMessage.contains("start") || lowerMessage.contains("proceed") -> {
                    if (context.journeyForm == null) {
                        AgentResponse(
                            conversationId = context.conversationId,
                            state = ConversationState.AWAITING_JOURNEY_DETAILS,
                            message = "I don't have your journey details yet. Please provide them first."
                        )
                    } else {
                        conversationManager.updateConversation(
                            context.copy(state = ConversationState.PLANNING)
                        )
                        AgentResponse(
                            conversationId = context.conversationId,
                            state = ConversationState.PLANNING,
                            message = "Starting to plan your trip! Use the streaming endpoint to follow progress: /a2a/conversation/${context.conversationId}/stream"
                        )
                    }
                }
                lowerMessage.contains("no") || lowerMessage.contains("change") || lowerMessage.contains("modify") -> {
                    conversationManager.updateConversation(
                        context.copy(state = ConversationState.AWAITING_JOURNEY_DETAILS)
                    )
                    AgentResponse(
                        conversationId = context.conversationId,
                        state = ConversationState.AWAITING_JOURNEY_DETAILS,
                        message = "No problem! What would you like to change? You can provide updated journey details."
                    )
                }
                else -> AgentResponse(
                    conversationId = context.conversationId,
                    state = ConversationState.AWAITING_CONFIRMATION,
                    message = "Would you like me to start planning? Please respond with 'yes' or 'no'.",
                    options = listOf("Yes, start planning", "No, let me change something")
                )
            }
        }

        ConversationState.PLANNING -> AgentResponse(
            conversationId = context.conversationId,
            state = ConversationState.PLANNING,
            message = "Planning is in progress. Please check the streaming endpoint for updates."
        )

        ConversationState.COMPLETED -> AgentResponse(
            conversationId = context.conversationId,
            state = ConversationState.COMPLETED,
            message = "Your travel plan is complete! Would you like to start a new plan?",
            plan = context.result
        )

        ConversationState.FAILED -> AgentResponse(
            conversationId = context.conversationId,
            state = ConversationState.FAILED,
            message = "The previous planning attempt failed. Would you like to try again?",
            options = listOf("Yes, try again", "No, start over")
        )

        else -> AgentResponse(
            conversationId = context.conversationId,
            state = context.state,
            message = "I'm not sure how to proceed. Please start a new conversation."
        )
    }
}
