package org.hildan.krossbow.websocket.spring

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hildan.krossbow.websocket.WebSocketConnectionException
import org.hildan.krossbow.websocket.WebSocketConnectionWithPingPong
import org.hildan.krossbow.websocket.WebSocketFrame
import org.hildan.krossbow.websocket.WebSocketListenerFlowAdapter
import org.springframework.web.socket.*
import org.springframework.web.socket.client.jetty.JettyWebSocketClient
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport
import org.springframework.web.socket.sockjs.client.SockJsClient
import org.springframework.web.socket.sockjs.client.Transport
import org.springframework.web.socket.sockjs.client.WebSocketTransport
import java.net.URI
import java.nio.ByteBuffer
import org.hildan.krossbow.websocket.WebSocketClient as KrossbowWebSocketClient
import org.springframework.web.socket.WebSocketSession as SpringWebSocketSession
import org.springframework.web.socket.client.WebSocketClient as SpringWebSocketClient

/**
 * Adapts this Spring [WebSocketClient][SpringWebSocketClient] to the Krossbow
 * [WebSocketClient][KrossbowWebSocketClient] interface.
 */
@Suppress("DEPRECATION")
fun SpringWebSocketClient.asKrossbowWebSocketClient(): KrossbowWebSocketClient = SpringWebSocketClientAdapter(this)

@Deprecated(
    message = "The SpringDefaultWebSocketClient object is made redundant by the public adapter extension" +
            ".asKrossbowWebSocketClient(), prefer using that instead.",
    replaceWith = ReplaceWith(
        expression = "StandardWebSocketClient().asKrossbowWebSocketClient()",
        imports = [
            "org.springframework.web.socket.client.standard.StandardWebSocketClient",
            "org.hildan.krossbow.websocket.spring.asKrossbowWebSocketClient",
        ],
    )
)
@Suppress("DEPRECATION")
object SpringDefaultWebSocketClient : SpringWebSocketClientAdapter(StandardWebSocketClient())

@Deprecated(
    message = "The JettyWebSocketClient is deprecated for removal in Spring itself, prefer the StandardWebSocketClient.",
    replaceWith = ReplaceWith(
        expression = "StandardWebSocketClient().asKrossbowWebSocketClient()",
        imports = [
            "org.springframework.web.socket.client.standard.StandardWebSocketClient",
            "org.hildan.krossbow.websocket.spring.asKrossbowWebSocketClient",
        ],
    )
)
@Suppress("DEPRECATION")
object SpringJettyWebSocketClient : SpringWebSocketClientAdapter(JettyWebSocketClient().apply { start() })

@Deprecated(
    message = "The SpringSockJSWebSocketClient object is made redundant by the public adapter extension" +
            ".asKrossbowWebSocketClient(), prefer using that instead.",
    replaceWith = ReplaceWith(
        expression = "SockJsClient(listOf(WebSocketTransport(StandardWebSocketClient()), RestTemplateXhrTransport())).asKrossbowWebSocketClient()",
        imports = [
            "org.springframework.web.socket.client.standard.StandardWebSocketClient",
            "org.springframework.web.socket.sockjs.client.SockJsClient",
            "org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport",
            "org.springframework.web.socket.sockjs.client.WebSocketTransport",
            "org.hildan.krossbow.websocket.spring.asKrossbowWebSocketClient",
        ],
    )
)
@Suppress("DEPRECATION")
object SpringSockJSWebSocketClient : SpringWebSocketClientAdapter(SockJsClient(defaultWsTransports()))

private fun defaultWsTransports(): List<Transport> = listOf(
    WebSocketTransport(StandardWebSocketClient()),
    RestTemplateXhrTransport()
)

@Deprecated(
    message = "This class is internal and will become invisible in future versions, " +
            "prefer the adapter extension asKrossbowWebSocketClient().",
)
open class SpringWebSocketClientAdapter(private val client: SpringWebSocketClient) : KrossbowWebSocketClient {

    override suspend fun connect(url: String, headers: Map<String, String>): WebSocketConnectionWithPingPong {
        try {
            val handler = KrossbowToSpringHandlerAdapter()
            val handshakeHeaders = WebSocketHttpHeaders().apply {
                headers.forEach { (name, value) ->
                    put(name, listOf(value))
                }
            }
            val springSession = client.doHandshake(handler, handshakeHeaders, URI(url)).completable().await()
            return SpringSessionToKrossbowConnectionAdapter(springSession, handler.channelListener.incomingFrames)
        } catch (e: CancellationException) {
            throw e // this is an upstream exception that we don't want to wrap here
        } catch (e: Exception) {
            // javax.websocket.DeploymentException (when the handshake fails)
            //   Caused by DeploymentException (again, for some reason)
            //     Caused by:
            //      - java.nio.channels.UnresolvedAddressException (if host is not resolved)
            //      - org.glassfish.tyrus.client.auth.AuthenticationException: Authentication failed. (on 401)
            throw WebSocketConnectionException(
                url = url,
                httpStatusCode = null,
                additionalInfo = e.toString(),
                cause = e,
            )
        }
    }
}

private class KrossbowToSpringHandlerAdapter : WebSocketHandler {

    val channelListener: WebSocketListenerFlowAdapter = WebSocketListenerFlowAdapter()

    override fun afterConnectionEstablished(session: SpringWebSocketSession) {}

    override fun handleMessage(session: SpringWebSocketSession, message: WebSocketMessage<*>) {
        runBlocking {
            when (message) {
                is TextMessage -> channelListener.onTextMessage(message.payload, message.isLast)
                is BinaryMessage -> channelListener.onBinaryMessage(message.payload.array(), message.isLast)
                is PingMessage -> channelListener.onPing(message.payload.array())
                is PongMessage -> channelListener.onPong(message.payload.array())
                else -> channelListener.onError("Unsupported Spring websocket message type: ${message.javaClass}")
            }
        }
    }

    override fun handleTransportError(session: SpringWebSocketSession, exception: Throwable) {
        channelListener.onError(exception)
    }

    override fun afterConnectionClosed(session: SpringWebSocketSession, closeStatus: CloseStatus) {
        // Note: afterConnectionClosed is synchronously called by Tyrus implementation during a session.close() call.
        // It is not called when receiving the server close frame if the closure is initiated on the client side.
        // Source: org.glassfish.tyrus.core.ProtocolHandler.close()
        // This means that if no receiver is listening on the incoming frames channel, onClose() here may suspend
        // forever (if the buffer is full).
        runBlocking {
            channelListener.onClose(closeStatus.code, closeStatus.reason)
        }
    }

    override fun supportsPartialMessages(): Boolean = true
}

private class SpringSessionToKrossbowConnectionAdapter(
    private val session: SpringWebSocketSession,
    override val incomingFrames: Flow<WebSocketFrame>,
) : WebSocketConnectionWithPingPong {

    private val mutex = Mutex()

    override val url: String
        get() = session.uri?.toString()!!

    override val canSend: Boolean
        get() = session.isOpen

    override suspend fun sendText(frameText: String) {
        mutex.withLock {
            session.sendMessage(TextMessage(frameText, true))
        }
    }

    override suspend fun sendBinary(frameData: ByteArray) {
        mutex.withLock {
            session.sendMessage(BinaryMessage(frameData, true))
        }
    }

    override suspend fun sendPing(frameData: ByteArray) {
        mutex.withLock {
            session.sendMessage(PingMessage(ByteBuffer.wrap(frameData)))
        }
    }

    override suspend fun sendPong(frameData: ByteArray) {
        mutex.withLock {
            session.sendMessage(PongMessage(ByteBuffer.wrap(frameData)))
        }
    }

    override suspend fun close(code: Int, reason: String?) {
        mutex.withLock {
            session.close(CloseStatus(code, reason))
        }
    }
}
