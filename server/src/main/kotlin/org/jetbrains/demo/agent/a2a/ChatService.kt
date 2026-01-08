package org.jetbrains.demo.agent.a2a

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import org.jetbrains.demo.JourneyForm
import org.jetbrains.demo.agent.a2a.model.TravelPlanResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

@Serializable
enum class MessageType {
    TEXT, THINKING, TOOL_USE, TOOL_RESULT, PLAN_RESULT, ERROR
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String>? = null
)

@Serializable
data class ChatSession(
    val sessionId: String,
    val messages: List<ChatMessage> = emptyList(),
    val journeyForm: JourneyForm? = null,
    val planResult: TravelPlanResult? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ChatRequest(
    val sessionId: String? = null,
    val message: String,
    val journeyForm: JourneyForm? = null
)

@Serializable
data class ChatStreamEvent(
    val sessionId: String,
    val type: String,
    val content: String? = null,
    val message: ChatMessage? = null,
    val done: Boolean = false,
    val planResult: TravelPlanResult? = null
)

class ChatService(private val orchestrator: TravelOrchestratorAgent) {
    private val sessions = ConcurrentHashMap<String, ChatSession>()

    @OptIn(ExperimentalUuidApi::class)
    fun createSession(): ChatSession {
        val sessionId = Uuid.random().toString()
        val session = ChatSession(sessionId = sessionId)
        sessions[sessionId] = session
        return session
    }

    fun getSession(sessionId: String): ChatSession? = sessions[sessionId]

    fun getMessages(sessionId: String): List<ChatMessage> {
        return sessions[sessionId]?.messages ?: emptyList()
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun addMessage(sessionId: String, role: MessageRole, content: String, type: MessageType = MessageType.TEXT): ChatMessage {
        val message = ChatMessage(
            id = Uuid.random().toString(),
            role = role,
            content = content,
            type = type
        )
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(
                messages = session.messages + message,
                updatedAt = System.currentTimeMillis()
            )
        }
        return message
    }

    fun updateJourneyForm(sessionId: String, journeyForm: JourneyForm) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(journeyForm = journeyForm, updatedAt = System.currentTimeMillis())
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun chat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        val sessionId = request.sessionId ?: createSession().sessionId
        val session = sessions[sessionId] ?: createSession().also { sessions[it.sessionId] = it }
        
        // Add user message
        val userMessage = addMessage(sessionId, MessageRole.USER, request.message)
        emit(ChatStreamEvent(sessionId = sessionId, type = "user_message", message = userMessage))

        // Update journey form if provided
        if (request.journeyForm != null) {
            updateJourneyForm(sessionId, request.journeyForm)
        }

        val currentSession = sessions[sessionId]!!
        val journeyForm = request.journeyForm ?: currentSession.journeyForm

        // Determine response based on context
        when {
            journeyForm == null -> {
                // No journey details yet - ask for them
                val assistantMessage = addMessage(
                    sessionId,
                    MessageRole.ASSISTANT,
                    "Hello! I'm your AI travel planning assistant. To create a personalized travel plan, I'll need some details:\n\n" +
                    "• **Origin city** - Where are you starting from?\n" +
                    "• **Destination** - Where do you want to go?\n" +
                    "• **Dates** - When do you want to travel?\n" +
                    "• **Travelers** - Who's going on this trip?\n" +
                    "• **Transport preference** - How do you prefer to travel?\n\n" +
                    "You can provide these details in your next message, or send them as structured data."
                )
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
                emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
            }
            
            request.message.lowercase().let { 
                it.contains("plan") || it.contains("start") || it.contains("yes") || it.contains("go") 
            } -> {
                // User wants to start planning
                emit(ChatStreamEvent(sessionId = sessionId, type = "thinking", content = "Analyzing your travel requirements..."))
                
                val thinkingMessage = addMessage(
                    sessionId, 
                    MessageRole.ASSISTANT, 
                    "Let me plan your trip from **${journeyForm.fromCity}** to **${journeyForm.toCity}**...",
                    MessageType.THINKING
                )
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = thinkingMessage))

                // Stream planning progress
                emit(ChatStreamEvent(sessionId = sessionId, type = "tool_use", content = "🗺️ Calling Route Planner Agent..."))
                emit(ChatStreamEvent(sessionId = sessionId, type = "thinking", content = "Finding points of interest along your route..."))
                
                try {
                    // Call the A2A orchestrator
                    val travelPlan = orchestrator.planTravel(journeyForm)
                    
                    // Update session with result
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(planResult = travelPlan, updatedAt = System.currentTimeMillis())
                    }

                    emit(ChatStreamEvent(sessionId = sessionId, type = "tool_result", content = "✅ Route planning complete"))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "tool_use", content = "🔍 Researching points of interest..."))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "tool_result", content = "✅ Research complete"))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "tool_use", content = "📝 Composing your travel plan..."))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "tool_result", content = "✅ Plan composed"))

                    // Final response with plan
                    val planSummary = buildString {
                        appendLine("# ${travelPlan.title}")
                        appendLine()
                        appendLine(travelPlan.plan)
                        appendLine()
                        if (travelPlan.days.isNotEmpty()) {
                            appendLine("## Daily Itinerary")
                            travelPlan.days.forEachIndexed { index, day ->
                                appendLine("### Day ${index + 1}: ${day.locationAndCountry} (${day.date})")
                                appendLine()
                            }
                        }
                    }

                    val resultMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        planSummary,
                        MessageType.PLAN_RESULT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "plan_result", 
                        message = resultMessage,
                        planResult = travelPlan
                    ))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
                    
                } catch (e: Exception) {
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "I encountered an error while planning: ${e.message}\n\nWould you like me to try again?",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage, content = e.message))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
                }
            }
            
            else -> {
                // General conversation - acknowledge and confirm details
                val response = buildString {
                    appendLine("I've noted your message. Here's what I have so far:")
                    appendLine()
                    appendLine("• **From:** ${journeyForm.fromCity}")
                    appendLine("• **To:** ${journeyForm.toCity}")
                    appendLine("• **Dates:** ${journeyForm.startDate} to ${journeyForm.endDate}")
                    appendLine("• **Transport:** ${journeyForm.transport}")
                    appendLine("• **Travelers:** ${journeyForm.travelers.joinToString { it.name }}")
                    journeyForm.details?.let { appendLine("• **Notes:** $it") }
                    appendLine()
                    appendLine("Ready to plan your trip? Just say **\"start planning\"** or **\"yes\"**!")
                }
                
                val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
                emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
            }
        }
    }

    fun cleanupOldSessions(maxAgeMs: Long = 3600000) {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) ->
            now - session.createdAt > maxAgeMs
        }
    }
}
