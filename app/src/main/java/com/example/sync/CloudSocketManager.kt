package com.example.sync

import android.app.Application
import android.util.Log
import com.example.data.*
import com.example.sync.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudSocketManager(
    private val application: Application,
    private val chatDao: ChatDao
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep connection alive indefinitely
        .build()

    private var webSocket: WebSocket? = null
    private var connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isConnectionActive = false
    private var reconnectJob: Job? = null

    private var currentUrl: String = ""
    private var currentUsername: String = ""

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    fun connect(serverUrl: String, username: String) {
        if (serverUrl.isBlank() || username.isBlank()) {
            _connectionState.value = ConnectionState.ERROR
            return
        }

        currentUrl = serverUrl.trim()
        currentUsername = username.trim().lowercase()
        isConnectionActive = true

        _connectionState.value = ConnectionState.CONNECTING
        reconnectJob?.cancel()
        establishConnection()
    }

    fun disconnect() {
        isConnectionActive = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun establishConnection() {
        if (!isConnectionActive) return

        // Formulate WebSocket URL: replace http(s) with ws(s) and append /ws
        var wsUrl = currentUrl
        if (wsUrl.startsWith("http://")) {
            wsUrl = wsUrl.replace("http://", "ws://")
        } else if (wsUrl.startsWith("https://")) {
            wsUrl = wsUrl.replace("https://", "wss://")
        }

        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            wsUrl = "ws://$wsUrl"
        }

        // Add query parameters
        wsUrl = if (wsUrl.contains("?")) {
            "$wsUrl&username=$currentUsername"
        } else {
            // Check trailing slash
            val separator = if (wsUrl.endsWith("/")) "" else "/"
            // If it doesn't contain /ws, inject it
            val wsPath = if (wsUrl.contains("/ws")) "" else "${separator}ws"
            "$wsUrl$wsPath?username=$currentUsername"
        }

        Log.d("CloudSocketManager", "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("CloudSocketManager", "WebSocket connected successfully!")
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("CloudSocketManager", "WebSocket message received: $text")
                handleInboundMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("CloudSocketManager", "WebSocket closing: $code / $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("CloudSocketManager", "WebSocket closed: $code / $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                triggerReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("CloudSocketManager", "WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.ERROR
                triggerReconnect()
            }
        })
    }

    private fun triggerReconnect() {
        if (!isConnectionActive) return

        reconnectJob?.cancel()
        reconnectJob = connectionScope.launch {
            delay(5000) // Retry every 5 seconds
            if (isConnectionActive) {
                Log.d("CloudSocketManager", "Attempting automatic WebSocket reconnection...")
                _connectionState.value = ConnectionState.CONNECTING
                establishConnection()
            }
        }
    }

    private fun handleInboundMessage(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type")

            if (type == "receive_message") {
                val data = json.getJSONObject("data")
                val messageId = data.getString("messageId")
                var chatId = data.getString("chatId")
                val senderId = data.getString("senderId")
                val senderName = data.getString("senderName")
                val ciphertext = data.getString("ciphertext")
                val iv = data.getString("iv")
                val mediaUrl = data.optString("mediaUrl", null).let { if (it == "null" || it.isEmpty()) null else it }
                val mediaType = data.optString("mediaType", null).let { if (it == "null" || it.isEmpty()) null else it }
                val timestamp = data.optLong("timestamp", System.currentTimeMillis())

                // Skip if message was sent by the current device
                if (senderId.lowercase() == currentUsername || senderId == "me") {
                    return
                }

                // If this is a direct/one-on-one message (not group), override chatId to be the sender's ID
                if (!chatId.startsWith("group") && senderId.isNotEmpty()) {
                    chatId = senderId
                }

                connectionScope.launch {
                    // Check if message is already in database
                    val existing = chatDao.getMessageById(messageId)
                    if (existing == null) {
                        // Ensure sender contact is created in DB
                        val contact = chatDao.getUserById(senderId)
                        if (contact == null && senderId != "me") {
                            chatDao.insertUser(
                                UserEntity(
                                    id = senderId,
                                    name = senderName,
                                    avatarUrl = "img_profile_avatar",
                                    publicKey = "RSA-PUB-KEY-${senderName.uppercase()}",
                                    isMe = false
                                )
                            )
                        }

                        // Ensure Chat exists in DB
                        val chat = chatDao.getChatById(chatId)
                        if (chat == null) {
                            chatDao.insertChat(
                                ChatEntity(
                                    id = chatId,
                                    name = senderName,
                                    isGroup = false,
                                    avatarUrl = "img_profile_avatar",
                                    groupOwnerId = null,
                                    lastMessageText = if (mediaType == "image") "📷 Photo" else (if (mediaType == "audio") "🎵 Voice Note" else "Encrypted Message"),
                                    lastMessageTimestamp = timestamp,
                                    unreadCount = 1
                                )
                            )
                        } else {
                            // Update last message parameters
                            chatDao.insertChat(
                                chat.copy(
                                    lastMessageText = if (mediaType == "image") "📷 Photo" else (if (mediaType == "audio") "🎵 Voice Note" else "Encrypted Message"),
                                    lastMessageTimestamp = timestamp
                                )
                            )
                        }

                        // Insert message
                        val messageEntity = MessageEntity(
                            id = messageId,
                            chatId = chatId,
                            senderId = senderId,
                            senderName = senderName,
                            ciphertext = ciphertext,
                            iv = iv,
                            isEncrypted = true,
                            timestamp = timestamp,
                            mediaUrl = mediaUrl,
                            mediaType = mediaType
                        )
                        chatDao.insertMessage(messageEntity)

                        // Trigger notifications based on preferences
                        val sharedPrefs = application.getSharedPreferences("whatschat_prefs", android.content.Context.MODE_PRIVATE)
                        val notifyReceived = sharedPrefs.getBoolean("notify_received", true)
                        if (notifyReceived) {
                            val decryptedText = try {
                                val secretKey = com.example.security.CryptoHelper.getChatSecretKey(chatId)
                                com.example.security.CryptoHelper.decrypt(ciphertext, iv, secretKey)
                            } catch (e: Exception) {
                                "Received secure E2EE message"
                            }
                            val displayBody = if (mediaType == "image") "📷 Photo" else if (mediaType == "audio") "🎵 Voice Note" else decryptedText

                            NotificationHelper.showMessageNotification(
                                application,
                                senderName,
                                displayBody,
                                chatId
                            )
                        }
                    }
                }
            } else if (type == "presence_change") {
                val data = json.getJSONObject("data")
                val username = data.getString("username")
                val isOnline = data.getBoolean("isOnline")
                Log.d("CloudSocketManager", "Contact @$username online status changed: $isOnline")
            }
        } catch (e: Exception) {
            Log.e("CloudSocketManager", "Error parsing incoming raw WebSocket packet", e)
        }
    }

    fun sendSecureMessage(
        messageId: String,
        chatId: String,
        senderId: String,
        senderName: String,
        ciphertext: String,
        iv: String,
        mediaUrl: String? = null,
        mediaType: String? = null,
        timestamp: Long
    ) {
        val socket = webSocket
        if (socket == null || _connectionState.value != ConnectionState.CONNECTED) {
            Log.e("CloudSocketManager", "Cannot send socket message: WebSocket is not active.")
            return
        }

        try {
            val messageData = JSONObject().apply {
                put("messageId", messageId)
                put("chatId", chatId)
                put("senderId", senderId)
                put("senderName", senderName)
                put("ciphertext", ciphertext)
                put("iv", iv)
                put("mediaUrl", mediaUrl ?: "")
                put("mediaType", mediaType ?: "")
                put("timestamp", timestamp)
            }

            val packet = JSONObject().apply {
                put("type", "send_message")
                put("data", messageData)
            }

            socket.send(packet.toString())
            Log.d("CloudSocketManager", "Sent secure message over WebSocket: $messageId")
        } catch (e: Exception) {
            Log.e("CloudSocketManager", "Failed to construct or transmit secure message packet", e)
        }
    }
}
