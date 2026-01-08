package org.jetbrains.demo.agent.a2a

import kotlinx.serialization.Serializable
import org.jetbrains.demo.JourneyForm
import org.jetbrains.demo.agent.a2a.model.TravelPlanResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class ConversationState {
    AWAITING_JOURNEY_DETAILS,
    AWAITING_PREFERENCES,
    AWAITING_POI_SELECTION,
    AWAITING_CONFIRMATION,
    PLANNING,
    COMPLETED,
    FAILED
}

@Serializable
data class ClarificationQuestion(
    val questionId: String,
    val question: String,
    val options: List<String>? = null,
    val field: String? = null
)

@Serializable
data class ConversationContext(
    val conversationId: String,
    val state: ConversationState,
    val journeyForm: JourneyForm? = null,
    val preferences: TravelPreferences? = null,
    val pendingQuestion: ClarificationQuestion? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val result: TravelPlanResult? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class TravelPreferences(
    val activityTypes: List<String> = emptyList(),
    val budget: String? = null,
    val pace: String? = null,
    val interests: List<String> = emptyList()
)

@Serializable
data class ConversationMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class UserMessage(
    val conversationId: String? = null,
    val message: String,
    val journeyForm: JourneyForm? = null
)

@Serializable
data class AgentResponse(
    val conversationId: String,
    val state: ConversationState,
    val message: String,
    val question: ClarificationQuestion? = null,
    val plan: TravelPlanResult? = null,
    val options: List<String>? = null
)

class ConversationManager {
    private val conversations = ConcurrentHashMap<String, ConversationContext>()

    @OptIn(ExperimentalUuidApi::class)
    fun startConversation(journeyForm: JourneyForm? = null): ConversationContext {
        val conversationId = Uuid.random().toString()
        val initialState = if (journeyForm != null) {
            ConversationState.AWAITING_PREFERENCES
        } else {
            ConversationState.AWAITING_JOURNEY_DETAILS
        }
        
        val context = ConversationContext(
            conversationId = conversationId,
            state = initialState,
            journeyForm = journeyForm,
            messages = listOf(
                ConversationMessage(
                    role = "system",
                    content = "Conversation started"
                )
            )
        )
        conversations[conversationId] = context
        return context
    }

    fun getConversation(conversationId: String): ConversationContext? {
        return conversations[conversationId]
    }

    fun updateConversation(context: ConversationContext): ConversationContext {
        conversations[context.conversationId] = context
        return context
    }

    fun addMessage(conversationId: String, role: String, content: String): ConversationContext? {
        val context = conversations[conversationId] ?: return null
        val updatedContext = context.copy(
            messages = context.messages + ConversationMessage(role = role, content = content)
        )
        conversations[conversationId] = updatedContext
        return updatedContext
    }

    fun needsClarification(journeyForm: JourneyForm): ClarificationQuestion? {
        return when {
            journeyForm.travelers.isEmpty() -> ClarificationQuestion(
                questionId = "travelers",
                question = "Who will be traveling? Please provide names and any relevant details.",
                field = "travelers"
            )
            journeyForm.details.isNullOrBlank() && journeyForm.travelers.size > 1 -> ClarificationQuestion(
                questionId = "preferences",
                question = "What type of activities would the group enjoy? Select all that apply:",
                options = listOf("Cultural & Historical", "Nature & Adventure", "Food & Wine", "Relaxation", "Shopping", "Nightlife"),
                field = "preferences"
            )
            else -> null
        }
    }

    fun cleanupOldConversations(maxAgeMs: Long = 3600000) {
        val now = System.currentTimeMillis()
        conversations.entries.removeIf { (_, context) ->
            now - context.createdAt > maxAgeMs
        }
    }
}
