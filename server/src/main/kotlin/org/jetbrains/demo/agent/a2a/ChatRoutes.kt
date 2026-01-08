package org.jetbrains.demo.agent.a2a

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.server.websocket.*
import io.ktor.sse.ServerSentEvent
import io.ktor.websocket.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ChatRoutes")

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val message: String
)

@Serializable
data class SessionResponse(
    val session: ChatSession
)

@Serializable
data class MessagesResponse(
    val sessionId: String,
    val messages: List<ChatMessage>
)

fun Application.chatRoutes(chatService: ChatService) {
    routing {
        route("/chat") {
            
            post("/session") {
                val session = chatService.createSession()
                call.respond(HttpStatusCode.Created, CreateSessionResponse(
                    sessionId = session.sessionId,
                    message = "Chat session created"
                ))
            }

            get("/session/{sessionId}") {
                val sessionId = call.parameters["sessionId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId"))
                
                val session = chatService.getSession(sessionId)
                if (session != null) {
                    call.respond(HttpStatusCode.OK, SessionResponse(session = session))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
                }
            }

            get("/session/{sessionId}/messages") {
                val sessionId = call.parameters["sessionId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId"))
                
                val messages = chatService.getMessages(sessionId)
                call.respond(HttpStatusCode.OK, MessagesResponse(sessionId = sessionId, messages = messages))
            }

            post("/message") {
                try {
                    val request = call.receive<ChatRequest>()
                    logger.info("Chat message received: ${request.message.take(50)}...")
                    
                    var lastEvent: ChatStreamEvent? = null
                    chatService.chat(request)
                        .onEach { event -> lastEvent = event }
                        .catch { e -> 
                            logger.error("Error in chat flow", e)
                            lastEvent = ChatStreamEvent(
                                sessionId = request.sessionId ?: "",
                                type = "error",
                                content = e.message
                            )
                        }
                        .collect()
                    
                    if (lastEvent != null) {
                        call.respond(HttpStatusCode.OK, lastEvent!!)
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No response generated"))
                    }
                } catch (e: Exception) {
                    logger.error("Error processing chat message", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            sse("/stream") {
                val sessionId = call.request.queryParameters["sessionId"]
                val message = call.request.queryParameters["message"]
                    ?: return@sse send(ServerSentEvent(
                        event = "error",
                        data = """{"error": "Missing message parameter"}"""
                    ))
                
                val journeyFormJson = call.request.queryParameters["journeyForm"]
                val journeyForm = journeyFormJson?.let { 
                    try {
                        json.decodeFromString<org.jetbrains.demo.JourneyForm>(it)
                    } catch (e: Exception) {
                        logger.warn("Failed to parse journeyForm: ${e.message}")
                        null
                    }
                }

                val request = ChatRequest(
                    sessionId = sessionId,
                    message = message,
                    journeyForm = journeyForm
                )

                logger.info("SSE chat stream started for session: $sessionId")
                
                chatService.chat(request)
                    .onEach { event ->
                        send(ServerSentEvent(
                            event = event.type,
                            data = json.encodeToString(ChatStreamEvent.serializer(), event)
                        ))
                    }
                    .catch { e ->
                        logger.error("Error in SSE stream", e)
                        send(ServerSentEvent(
                            event = "error",
                            data = """{"error": "${e.message}"}"""
                        ))
                    }
                    .collect()
            }

            webSocket("/ws/{sessionId?}") {
                val sessionId = call.parameters["sessionId"] 
                    ?: chatService.createSession().sessionId
                
                logger.info("WebSocket connected for session: $sessionId")
                
                send(Frame.Text(json.encodeToString(ChatStreamEvent.serializer(), ChatStreamEvent(
                    sessionId = sessionId,
                    type = "connected",
                    content = "Connected to chat session"
                ))))

                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                logger.debug("WebSocket received: $text")
                                
                                val request = try {
                                    json.decodeFromString<ChatRequest>(text)
                                } catch (e: Exception) {
                                    ChatRequest(sessionId = sessionId, message = text)
                                }

                                val chatRequest = request.copy(sessionId = sessionId)
                                
                                chatService.chat(chatRequest)
                                    .onEach { event ->
                                        send(Frame.Text(json.encodeToString(ChatStreamEvent.serializer(), event)))
                                    }
                                    .catch { e ->
                                        logger.error("Error in WebSocket chat", e)
                                        send(Frame.Text(json.encodeToString(ChatStreamEvent.serializer(), ChatStreamEvent(
                                            sessionId = sessionId,
                                            type = "error",
                                            content = e.message
                                        ))))
                                    }
                                    .collect()
                            }
                            is Frame.Close -> {
                                logger.info("WebSocket closed for session: $sessionId")
                            }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    logger.error("WebSocket error for session $sessionId", e)
                } finally {
                    logger.info("WebSocket disconnected for session: $sessionId")
                }
            }
        }
    }
}
