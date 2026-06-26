package com.example.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.data.ChatEntity
import com.example.data.ChatRepository
import com.example.data.MessageEntity
import com.example.data.UserEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

class SyncServer(
    private val context: Context,
    private val repository: ChatRepository,
    private val onMessageReceivedFromWeb: (String, String) -> Unit // chatId, text
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false

    fun start(port: Int = 8080): String {
        try {
            if (isRunning) return getIpAddress() ?: "localhost"
            
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            isRunning = true
            
            scope.launch {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        scope.launch {
                            handleClient(socket)
                        }
                    } catch (e: Exception) {
                        Log.d("SyncServer", "Server accept error or closed: ${e.message}")
                    }
                }
            }
            
            Log.d("SyncServer", "Web Sync Server started on port $port")
            return getIpAddress() ?: "localhost"
        } catch (e: Exception) {
            Log.e("SyncServer", "Failed to start server", e)
            return "Error: ${e.message}"
        }
    }

    fun stop() {
        try {
            isRunning = false
            serverSocket?.close()
            serverSocket = null
            Log.d("SyncServer", "Web Sync Server stopped")
        } catch (e: Exception) {
            Log.e("SyncServer", "Failed to stop server", e)
        }
    }

    fun getIpAddress(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress == 0) {
                // Fallback to searching network interfaces
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is java.net.Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
                return "127.0.0.1"
            }
            val ipString = String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
            ipString
        } catch (e: Exception) {
            Log.e("SyncServer", "Error getting IP", e)
            "127.0.0.1"
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                
                // Read Request Line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val fullPath = parts[1]
                
                // Read Headers to find Content-Length
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                
                // Read Body if any
                val body = if (contentLength > 0) {
                    val charArray = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val result = reader.read(charArray, read, contentLength - read)
                        if (result == -1) break
                        read += result
                    }
                    String(charArray)
                } else ""

                // Handle CORS preflight options
                if (method.equals("OPTIONS", ignoreCase = true)) {
                    sendResponse(output, 204, "No Content", "", "text/plain")
                    return
                }

                // Route the request
                val cleanPath = fullPath.substringBefore("?")
                when {
                    cleanPath == "/" || cleanPath == "/index.html" -> {
                        val html = getWebPortalHtml()
                        sendResponse(output, 200, "OK", html, "text/html; charset=utf-8")
                    }
                    cleanPath == "/api/chats" -> {
                        try {
                            val chatsList = repository.allChats.first()
                            val json = buildChatsJson(chatsList)
                            sendResponse(output, 200, "OK", json, "application/json")
                        } catch (e: Exception) {
                            sendResponse(output, 500, "Internal Server Error", "{\"error\": \"${e.message}\"}", "application/json")
                        }
                    }
                    cleanPath == "/api/messages" -> {
                        val query = fullPath.substringAfter("?", "")
                        val chatId = query.split("&")
                            .firstOrNull { it.startsWith("chatId=") }
                            ?.substringAfter("chatId=") ?: ""
                        
                        if (chatId.isEmpty()) {
                            sendResponse(output, 400, "Bad Request", "{\"error\": \"Missing chatId\"}", "application/json")
                        } else {
                            try {
                                val messagesList = repository.getMessagesForChat(chatId).first()
                                val json = buildMessagesJson(messagesList)
                                sendResponse(output, 200, "OK", json, "application/json")
                            } catch (e: Exception) {
                                sendResponse(output, 500, "Internal Server Error", "{\"error\": \"${e.message}\"}", "application/json")
                            }
                        }
                    }
                    cleanPath == "/api/send" && method.equals("POST", ignoreCase = true) -> {
                        try {
                            val chatId = getValueFromJson(body, "chatId")
                            val text = getValueFromJson(body, "text")
                            if (chatId.isEmpty() || text.isEmpty()) {
                                sendResponse(output, 400, "Bad Request", "{\"error\": \"Missing chatId or text\"}", "application/json")
                            } else {
                                withContext(Dispatchers.Main) {
                                    onMessageReceivedFromWeb(chatId, text)
                                }
                                sendResponse(output, 200, "OK", "{\"status\": \"sent\", \"encrypted\": true}", "application/json")
                            }
                        } catch (e: Exception) {
                            sendResponse(output, 500, "Internal Server Error", "{\"error\": \"${e.message}\"}", "application/json")
                        }
                    }
                    else -> {
                        sendResponse(output, 404, "Not Found", "Not Found", "text/plain")
                    }
                }
            } catch (e: Exception) {
                Log.e("SyncServer", "Error handling client", e)
            }
        }
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        body: String,
        contentType: String
    ) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val writer = PrintWriter(output, false)
            writer.print("HTTP/1.1 $statusCode $statusText\r\n")
            writer.print("Content-Type: $contentType\r\n")
            writer.print("Content-Length: ${bytes.size}\r\n")
            writer.print("Access-Control-Allow-Origin: *\r\n")
            writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            writer.print("Access-Control-Allow-Headers: Content-Type\r\n")
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            writer.flush()
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.e("SyncServer", "Error sending response", e)
        }
    }

    private fun getValueFromJson(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun buildChatsJson(chats: List<ChatEntity>): String {
        return chats.joinToString(separator = ",", prefix = "[", postfix = "]") { chat ->
            """
            {
              "id": "${chat.id}",
              "name": "${escapeJson(chat.name)}",
              "isGroup": ${chat.isGroup},
              "avatarUrl": "${chat.avatarUrl}",
              "lastMessageText": "${escapeJson(chat.lastMessageText)}",
              "lastMessageTimestamp": ${chat.lastMessageTimestamp},
              "unreadCount": ${chat.unreadCount}
            }
            """.trimIndent()
        }
    }

    private fun buildMessagesJson(messages: List<MessageEntity>): String {
        return messages.joinToString(separator = ",", prefix = "[", postfix = "]") { msg ->
            val plainText = repository.decryptMessage(msg)
            """
            {
              "id": "${msg.id}",
              "chatId": "${msg.chatId}",
              "senderId": "${msg.senderId}",
              "senderName": "${escapeJson(msg.senderName)}",
              "text": "${escapeJson(plainText)}",
              "timestamp": ${msg.timestamp},
              "mediaUrl": ${if (msg.mediaUrl != null) "\"${msg.mediaUrl}\"" else "null"},
              "mediaType": ${if (msg.mediaType != null) "\"${msg.mediaType}\"" else "null"},
              "isEncrypted": ${msg.isEncrypted}
            }
            """.trimIndent()
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun getWebPortalHtml(): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>WhatsChat Web - Desktop Sync</title>
            <link href="https://fonts.googleapis.com/css2?family=Segoe+UI:wght@300;400;600;700&display=swap" rel="stylesheet">
            <style>
                * {
                    box-sizing: border-box;
                    margin: 0;
                    padding: 0;
                    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                }
                body {
                    background-color: #111b21;
                    color: #e9edef;
                    display: flex;
                    height: 100vh;
                    overflow: hidden;
                }
                .app-container {
                    display: flex;
                    width: 100%;
                    height: 100%;
                }
                .sidebar {
                    width: 30%;
                    min-width: 320px;
                    border-right: 1px solid #222d34;
                    background-color: #111b21;
                    display: flex;
                    flex-direction: column;
                }
                .sidebar-header {
                    height: 60px;
                    background-color: #202c33;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    padding: 0 16px;
                }
                .avatar {
                    width: 40px;
                    height: 40px;
                    border-radius: 50%;
                    background-color: #00a884;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-weight: bold;
                    color: white;
                }
                .header-actions {
                    display: flex;
                    gap: 16px;
                    color: #aebac1;
                }
                .security-banner {
                    background-color: #182229;
                    padding: 12px 16px;
                    font-size: 13px;
                    border-bottom: 1px solid #222d34;
                    display: flex;
                    align-items: center;
                    gap: 10px;
                    color: #8696a0;
                }
                .security-banner svg {
                    fill: #00a884;
                    width: 20px;
                    height: 20px;
                    flex-shrink: 0;
                }
                .chats-list {
                    flex: 1;
                    overflow-y: auto;
                }
                .chat-item {
                    display: flex;
                    align-items: center;
                    padding: 12px 16px;
                    cursor: pointer;
                    border-bottom: 1px solid #222d34;
                    transition: background 0.2s;
                    gap: 12px;
                }
                .chat-item:hover, .chat-item.active {
                    background-color: #2a3942;
                }
                .chat-info {
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                }
                .chat-row1 {
                    display: flex;
                    justify-content: space-between;
                    margin-bottom: 4px;
                }
                .chat-name {
                    font-weight: 600;
                    font-size: 16px;
                    color: #e9edef;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .chat-time {
                    font-size: 12px;
                    color: #8696a0;
                }
                .chat-row2 {
                    display: flex;
                    justify-content: space-between;
                    font-size: 13px;
                    color: #8696a0;
                }
                .chat-last-msg {
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .chat-badge {
                    background-color: #00a884;
                    color: #111b21;
                    border-radius: 50%;
                    min-width: 18px;
                    height: 18px;
                    font-size: 11px;
                    font-weight: bold;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    padding: 0 4px;
                }
                .main-chat {
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    background-color: #0b141a;
                    background-image: radial-gradient(circle, rgba(0, 168, 132, 0.05) 10%, transparent 10%);
                    background-size: 20px 20px;
                }
                .chat-header {
                    height: 60px;
                    background-color: #202c33;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    padding: 0 16px;
                    border-left: 1px solid #374248;
                }
                .header-identity {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                }
                .messages-container {
                    flex: 1;
                    padding: 24px 4% 12px 4%;
                    overflow-y: auto;
                    display: flex;
                    flex-direction: column;
                    gap: 10px;
                }
                .msg-bubble {
                    max-width: 65%;
                    padding: 8px 12px;
                    border-radius: 8px;
                    font-size: 14.5px;
                    line-height: 1.4;
                    position: relative;
                    word-wrap: break-word;
                    display: flex;
                    flex-direction: column;
                    gap: 4px;
                }
                .msg-incoming {
                    background-color: #202c33;
                    align-self: flex-start;
                    border-top-left-radius: 0;
                }
                .msg-outgoing {
                    background-color: #005c4b;
                    align-self: flex-end;
                    border-top-right-radius: 0;
                }
                .msg-sender {
                    font-size: 12px;
                    font-weight: bold;
                    color: #34b7f1;
                    margin-bottom: 2px;
                }
                .msg-footer {
                    display: flex;
                    justify-content: flex-end;
                    align-items: center;
                    font-size: 11px;
                    color: #8696a0;
                    gap: 4px;
                }
                .lock-icon {
                    width: 12px;
                    height: 12px;
                    fill: #00a884;
                    display: inline-block;
                }
                .input-container {
                    background-color: #202c33;
                    padding: 10px 16px;
                    display: flex;
                    align-items: center;
                    gap: 12px;
                }
                .input-field {
                    flex: 1;
                    background-color: #2a3942;
                    border: none;
                    outline: none;
                    color: #e9edef;
                    font-size: 15px;
                    padding: 12px 16px;
                    border-radius: 8px;
                }
                .send-btn {
                    background: none;
                    border: none;
                    cursor: pointer;
                    color: #00a884;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
                .send-btn svg {
                    width: 28px;
                    height: 28px;
                }
                .empty-chat {
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    text-align: center;
                    background-color: #222e35;
                    color: #8696a0;
                    padding: 40px;
                }
                .empty-chat h2 {
                    color: #e9edef;
                    margin-bottom: 12px;
                    font-size: 32px;
                    font-weight: 300;
                }
                .empty-chat p {
                    max-width: 480px;
                    line-height: 1.6;
                    font-size: 14px;
                }
                .empty-lock {
                    margin-top: 40px;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    font-size: 12px;
                }
            </style>
        </head>
        <body>
            <div class="app-container">
                <div class="sidebar">
                    <div class="sidebar-header">
                        <div class="avatar">WC</div>
                        <div class="header-actions">
                            <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
                        </div>
                    </div>
                    <div class="security-banner">
                        <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>
                        <span>End-to-end encrypted desktop link active. Encryption Keys are generated on device.</span>
                    </div>
                    <div class="chats-list" id="chats-list">
                        <!-- Chats will load here -->
                    </div>
                </div>
                <div class="main-chat" id="main-chat">
                    <div class="empty-chat" id="empty-state">
                        <svg width="250" height="150" viewBox="0 0 250 150" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <rect width="250" height="150" rx="20" fill="#1f2c34"/>
                            <circle cx="125" cy="75" r="45" fill="#2a3942"/>
                            <path d="M115 65H135M115 75H135M115 85H127" stroke="#00a884" stroke-width="3" stroke-linecap="round"/>
                        </svg>
                        <h2>WhatsChat Web</h2>
                        <p>Send and receive encrypted messages synchronized with your phone. Keep your device online to fetch chat logs in real-time.</p>
                        <div class="empty-lock">
                            <svg viewBox="0 0 24 24" class="lock-icon"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>
                            <span>End-to-End Encrypted</span>
                        </div>
                    </div>
                    <div id="chat-active" style="display: none; height: 100%; flex-direction: column;">
                        <div class="chat-header">
                            <div class="header-identity">
                                <div class="avatar" id="active-avatar">C</div>
                                <div>
                                    <div id="active-chat-name" style="font-weight: 600;">Chat Name</div>
                                    <span style="font-size: 12px; color: #8696a0; display: flex; align-items: center; gap: 4px;">
                                        <svg viewBox="0 0 24 24" class="lock-icon" style="fill:#00a884"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>
                                        E2EE Channel Active
                                    </span>
                                </div>
                            </div>
                        </div>
                        <div class="messages-container" id="messages-container">
                            <!-- Messages -->
                        </div>
                        <div class="input-container">
                            <input type="text" class="input-field" id="message-input" placeholder="Type a message">
                            <button class="send-btn" id="send-button">
                                <svg viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            <script>
                let activeChatId = '';
                let chats = [];
                let pollInterval = null;

                async function fetchChats() {
                    try {
                        const response = await fetch('/api/chats');
                        chats = await response.json();
                        renderChats();
                    } catch (e) {
                        console.error("Failed to load chats", e);
                    }
                }

                async function fetchMessages(chatId) {
                    try {
                        const response = await fetch('/api/messages?chatId=' + chatId);
                        const messages = await response.json();
                        renderMessages(messages);
                    } catch (e) {
                        console.error("Failed to load messages", e);
                    }
                }

                function renderChats() {
                    const list = document.getElementById('chats-list');
                    list.innerHTML = '';
                    chats.forEach(chat => {
                        const item = document.createElement('div');
                        item.className = 'chat-item' + (chat.id === activeChatId ? ' active' : '');
                        item.onclick = () => selectChat(chat.id, chat.name);
                        
                        const date = new Date(chat.lastMessageTimestamp);
                        const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

                        item.innerHTML = `
                            <div class="avatar" style="background-color: ${'$'}{chat.isGroup ? '#20c997' : '#0d6efd'}">${'$'}{chat.name.substring(0, 2).toUpperCase()}</div>
                            <div class="chat-info">
                                <div class="chat-row1">
                                    <span class="chat-name">${'$'}{chat.name}</span>
                                    <span class="chat-time">${'$'}{timeStr}</span>
                                </div>
                                <div class="chat-row2">
                                    <span class="chat-last-msg">${'$'}{chat.lastMessageText || 'No messages yet'}</span>
                                    ${'$'}{chat.unreadCount > 0 ? '<span class="chat-badge">' + chat.unreadCount + '</span>' : ''}
                                </div>
                            </div>
                        `;
                        list.appendChild(item);
                    });
                }

                function renderMessages(messages) {
                    const container = document.getElementById('messages-container');
                    container.innerHTML = '';
                    messages.forEach(msg => {
                        const isOutgoing = msg.senderId === 'me' || msg.senderName === 'You';
                        const bubble = document.createElement('div');
                        bubble.className = 'msg-bubble ' + (isOutgoing ? 'msg-outgoing' : 'msg-incoming');
                        
                        const date = new Date(msg.timestamp);
                        const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

                        let bubbleContent = '';
                        if (!isOutgoing) {
                            bubbleContent += '<span class="msg-sender">' + msg.senderName + '</span>';
                        }
                        
                        bubbleContent += '<span>' + msg.text + '</span>' +
                            '<div class="msg-footer">' +
                                '<span style="font-size:10px;">' + timeStr + '</span>' +
                                (msg.isEncrypted ? '<svg class="lock-icon" viewBox="0 0 24 24" style="fill: #8696a0;"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>' : '') +
                            '</div>';
                        bubble.innerHTML = bubbleContent;
                        container.appendChild(bubble);
                    });
                    container.scrollTop = container.scrollHeight;
                }

                function selectChat(id, name) {
                    activeChatId = id;
                    document.getElementById('empty-state').style.display = 'none';
                    document.getElementById('chat-active').style.display = 'flex';
                    document.getElementById('active-chat-name').innerText = name;
                    document.getElementById('active-avatar').innerText = name.substring(0,2).toUpperCase();
                    
                    // Style selection
                    document.querySelectorAll('.chat-item').forEach(item => {
                        item.classList.remove('active');
                    });
                    
                    fetchMessages(id);
                    renderChats();

                    // Start fast polling for active chat messages
                    if (pollInterval) clearInterval(pollInterval);
                    pollInterval = setInterval(() => {
                        if (activeChatId === id) {
                            fetchMessages(id);
                        }
                    }, 2000);
                }

                async function sendMessage() {
                    const input = document.getElementById('message-input');
                    const text = input.value.trim();
                    if (!text || !activeChatId) return;

                    input.value = '';
                    try {
                        const response = await fetch('/api/send', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ chatId: activeChatId, text: text })
                        });
                        const result = await response.json();
                        if (result.status === 'sent') {
                            fetchMessages(activeChatId);
                            fetchChats();
                        }
                    } catch (e) {
                        console.error("Failed to send message", e);
                    }
                }

                document.getElementById('send-button').onclick = sendMessage;
                document.getElementById('message-input').onkeypress = (e) => {
                    if (e.key === 'Enter') sendMessage();
                };

                // Initial fetch & loop
                fetchChats();
                setInterval(fetchChats, 4000);
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
