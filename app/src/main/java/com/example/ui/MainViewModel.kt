package com.example.ui

import com.example.BuildConfig
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.CallLogEntity
import com.example.data.ChatEntity
import com.example.data.ChatRepository
import com.example.data.MessageEntity
import com.example.data.StatusEntity
import com.example.data.UserEntity
import com.example.security.BiometricHelper
import com.example.sync.NotificationHelper
import com.example.sync.SyncServer
import com.example.sync.CloudSocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChatRepository
    private var syncServer: SyncServer? = null
    private var cloudSocketManager: CloudSocketManager? = null
    private val sharedPrefs = application.getSharedPreferences("whatschat_prefs", android.content.Context.MODE_PRIVATE)

    // Registration state
    private val _isRegistered = MutableStateFlow(sharedPrefs.getBoolean("is_registered", false))
    val isRegistered = _isRegistered.asStateFlow()

    private val _currentUserEmail = MutableStateFlow(sharedPrefs.getString("user_email", ""))
    val currentUserEmail = _currentUserEmail.asStateFlow()

    private val _currentUsername = MutableStateFlow(sharedPrefs.getString("user_username", ""))
    val currentUsername = _currentUsername.asStateFlow()

    // Cloud connection states
    private val _cloudServerUrl = MutableStateFlow(sharedPrefs.getString("cloud_server_url", BuildConfig.DEFAULT_CLOUD_SERVER_URL) ?: BuildConfig.DEFAULT_CLOUD_SERVER_URL)
    val cloudServerUrl = _cloudServerUrl.asStateFlow()

    private val _cloudSyncEnabled = MutableStateFlow(sharedPrefs.getBoolean("cloud_sync_enabled", true))
    val cloudSyncEnabled = _cloudSyncEnabled.asStateFlow()

    private val _cloudConnectionState = MutableStateFlow(CloudSocketManager.ConnectionState.DISCONNECTED)
    val cloudConnectionState = _cloudConnectionState.asStateFlow()

    // Flows from DB
    val allChats: StateFlow<List<ChatEntity>>
    val allStatuses: StateFlow<List<StatusEntity>>
    val allCallLogs: StateFlow<List<CallLogEntity>>
    val allUsers: StateFlow<List<UserEntity>>

    // Selection State
    private val _selectedChatId = MutableStateFlow<String?>(null)
    val selectedChatId = _selectedChatId.asStateFlow()

    // Active Message Feed
    val activeMessages: StateFlow<List<MessageEntity>>

    // App Lock State
    private val _isLocked = MutableStateFlow(true)
    val isLocked = _isLocked.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(true)
    val biometricEnabled = _biometricEnabled.asStateFlow()

    private val _notificationOnReceived = MutableStateFlow(sharedPrefs.getBoolean("notify_received", true))
    val notificationOnReceived = _notificationOnReceived.asStateFlow()

    private val _notificationOnSent = MutableStateFlow(sharedPrefs.getBoolean("notify_sent", false))
    val notificationOnSent = _notificationOnSent.asStateFlow()

    // Active Calling State
    private val _activeCall = MutableStateFlow<CallLogEntity?>(null)
    val activeCall = _activeCall.asStateFlow()

    private val _callDuration = MutableStateFlow(0)
    val callDuration = _callDuration.asStateFlow()

    // Local Server Info
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning = _isServerRunning.asStateFlow()

    private val _serverIpAddress = MutableStateFlow<String?>(null)
    val serverIpAddress = _serverIpAddress.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ChatRepository(database.chatDao())

        // Setup base Flows
        allChats = repository.allChats.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        allStatuses = repository.allStatuses.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        allCallLogs = repository.allCallLogs.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        allUsers = repository.allUsers.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

        activeMessages = _selectedChatId.flatMapLatest { chatId ->
            if (chatId != null) {
                repository.getMessagesForChat(chatId)
            } else {
                MutableStateFlow(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Initialize Cloud Client Manager
        cloudSocketManager = CloudSocketManager(application, database.chatDao())
        
        // Flow the connection state to the UI Flow
        viewModelScope.launch {
            cloudSocketManager?.connectionState?.collect { state ->
                _cloudConnectionState.value = state
            }
        }

        // Automatic link-up if cloud sync is pre-enabled
        val username = _currentUsername.value
        if (!username.isNullOrBlank()) {
            com.example.security.CryptoHelper.currentUsername = username
        }
        val isCloudActive = _cloudSyncEnabled.value
        val savedCloudUrl = _cloudServerUrl.value
        if (isCloudActive && !username.isNullOrBlank()) {
            cloudSocketManager?.connect(savedCloudUrl, username)
        }

        // Start local Desktop Sync Web Server
        setupSyncServer()

        // Seed initial content if DB is empty
        viewModelScope.launch {
            seedInitialData()
        }
    }

    private fun setupSyncServer() {
        syncServer = SyncServer(getApplication(), repository) { chatId, text ->
            // Message received from local web sync client
            viewModelScope.launch {
                // Save and encrypt message as Sent from Web Portal
                repository.sendEncryptedMessage(chatId, "me", "You", text)
                NotificationHelper.showMessageNotification(
                    getApplication(),
                    "Web Portal Sync",
                    "Message synchronized and sent securely",
                    chatId
                )

                // Trigger a simulated automatic E2EE reply to demonstrate real-time low-latency response
                delay(1200)
                val chat = repository.getChatById(chatId) ?: return@launch
                val responderName = if (chat.isGroup) "Alice" else chat.name
                val responderId = if (chat.isGroup) "alice" else chat.id
                
                val replyText = getE2EESimulatedResponse(text, chat.isGroup)
                repository.sendEncryptedMessage(chatId, responderId, responderName, replyText)
                
                NotificationHelper.showMessageNotification(
                    getApplication(),
                    responderName,
                    replyText,
                    chatId
                )
            }
        }
        startSyncServer()
    }

    fun startSyncServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = syncServer?.start()
            _serverIpAddress.value = ip
            _isServerRunning.value = !ip.isNullOrEmpty() && !ip.contains("Error")
        }
    }

    fun stopSyncServer() {
        syncServer?.stop()
        _isServerRunning.value = false
        _serverIpAddress.value = null
    }

    private fun getE2EESimulatedResponse(text: String, isGroup: Boolean): String {
        val lower = text.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi") -> "Hi there! This channel is fully end-to-end encrypted. Dynamic keys verified!"
            lower.contains("key") || lower.contains("encrypt") -> "Yes, our messages are encrypted using AES-256-GCM. You can inspect our security cards!"
            lower.contains("group") -> "Group chats are fully supported! Multiple keys are generated on-the-fly."
            else -> "Got your sync message! E2EE payload decrypted successfully on-device."
        }
    }

    fun selectChat(chatId: String?) {
        _selectedChatId.value = chatId
    }

    fun unlockApp() {
        _isLocked.value = false
    }

    fun setBiometricEnabled(enabled: Boolean) {
        _biometricEnabled.value = enabled
    }

    fun lockApp() {
        if (_biometricEnabled.value) {
            _isLocked.value = true
        }
    }

    fun decryptMessage(message: MessageEntity): String {
        return repository.decryptMessage(message)
    }

    /**
     * Send E2EE message from the phone UI
     */
    fun sendMessage(chatId: String, text: String, mediaUrl: String? = null, mediaType: String? = null) {
        viewModelScope.launch {
            val message = repository.sendEncryptedMessage(chatId, "me", "You", text, mediaUrl, mediaType)

            // Trigger notification for sent message if enabled
            if (_notificationOnSent.value) {
                val displayBody = if (mediaType == "image") "📷 Photo" else if (mediaType == "audio") "🎵 Voice Note" else text
                NotificationHelper.showMessageNotification(
                    getApplication(),
                    "You (Sent)",
                    displayBody,
                    chatId
                )
            }

            // If Cloud Sync is enabled, transmit payload over real-time WebSocket
            if (_cloudSyncEnabled.value && !_currentUsername.value.isNullOrBlank()) {
                cloudSocketManager?.sendSecureMessage(
                    messageId = message.id,
                    chatId = message.chatId,
                    senderId = _currentUsername.value ?: "me",
                    senderName = _currentUsername.value ?: "You",
                    ciphertext = message.ciphertext,
                    iv = message.iv,
                    mediaUrl = message.mediaUrl,
                    mediaType = message.mediaType,
                    timestamp = message.timestamp
                )
            } else {
                // Auto simulated answer (only if cloud mode is disabled)
                delay(1500)
                val chat = repository.getChatById(chatId) ?: return@launch
                val responderName = if (chat.isGroup) "Alice" else chat.name
                val responderId = if (chat.isGroup) "alice" else chat.id

                val replyText = when {
                    mediaType == "image" -> "That's a beautiful photo! Decrypted successfully on my end."
                    mediaType == "audio" -> "Loved your voice note! Fully encrypted audio packets received."
                    text.lowercase().contains("call") -> "Sure, let's start a voice call! Ringing you now..."
                    else -> getE2EESimulatedResponse(text, chat.isGroup)
                }

                repository.sendEncryptedMessage(chatId, responderId, responderName, replyText)
                NotificationHelper.showMessageNotification(
                    getApplication(),
                    responderName,
                    replyText,
                    chatId
                )
            }
        }
    }

    /**
     * Post a new text/media Status Update (Story)
     */
    fun postStatus(text: String, mediaUrl: String? = null, mediaType: String? = "text") {
        viewModelScope.launch {
            val status = StatusEntity(
                id = UUID.randomUUID().toString(),
                userId = "me",
                userName = "You",
                userAvatarUrl = "img_profile_avatar",
                text = text,
                mediaUrl = mediaUrl,
                mediaType = mediaType,
                timestamp = System.currentTimeMillis()
            )
            repository.insertStatus(status)
        }
    }

    /**
     * Start voice calling sequence
     */
    fun startVoiceCall(chatId: String) {
        viewModelScope.launch {
            val chat = repository.getChatById(chatId) ?: return@launch
            val call = CallLogEntity(
                id = UUID.randomUUID().toString(),
                callerId = "me",
                callerName = "You",
                receiverId = chat.id,
                receiverName = chat.name,
                isVideo = false,
                timestamp = System.currentTimeMillis(),
                duration = 0,
                status = "Outgoing"
            )
            _activeCall.value = call
            _callDuration.value = 0
            
            // Log call to database
            repository.insertCallLog(call)

            // Simulate call connection after ringing
            delay(2500)
            if (_activeCall.value?.id == call.id) {
                // Update active call state to "Connected" or starting duration timer
                scopeCallTimer(call)
            }
        }
    }

    fun receiveSimulatedIncomingCall(callerName: String, callerId: String) {
        viewModelScope.launch {
            val call = CallLogEntity(
                id = UUID.randomUUID().toString(),
                callerId = callerId,
                callerName = callerName,
                receiverId = "me",
                receiverName = "You",
                isVideo = false,
                timestamp = System.currentTimeMillis(),
                duration = 0,
                status = "Incoming"
            )
            _activeCall.value = call
            _callDuration.value = 0
        }
    }

    fun acceptIncomingCall() {
        val currentCall = _activeCall.value ?: return
        scopeCallTimer(currentCall)
    }

    private fun scopeCallTimer(call: CallLogEntity) {
        viewModelScope.launch {
            // Log call connecting and counting duration
            while (_activeCall.value?.id == call.id) {
                delay(1000)
                _callDuration.value += 1
            }
        }
    }

    fun hangUpCall() {
        val call = _activeCall.value ?: return
        viewModelScope.launch {
            val finalCall = call.copy(duration = _callDuration.value.toLong())
            repository.insertCallLog(finalCall)
            _activeCall.value = null
            _callDuration.value = 0
        }
    }

    private suspend fun seedInitialData() {
        // Verify if users are already seeded
        val currentChats = repository.allChats.first()
        if (currentChats.isNotEmpty()) return

        // 1. Create Users
        val me = UserEntity("me", "You", "img_avatar_me", "RSA-PUB-KEY-ME-1290381029", isMe = true)
        val alice = UserEntity("alice", "Alice (E2EE)", "img_avatar_alice", "RSA-PUB-KEY-ALICE-38190283", isMe = false)
        val bob = UserEntity("bob", "Bob (E2EE)", "img_avatar_bob", "RSA-PUB-KEY-BOB-49204920", isMe = false)
        val group = UserEntity("group1", "Security Architecture Group", "img_avatar_group", "RSA-MULTIPLE-KEYS", isMe = false)

        repository.insertUser(me)
        repository.insertUser(alice)
        repository.insertUser(bob)

        // 2. Create Chats
        val chatAlice = ChatEntity(
            id = "alice",
            name = "Alice (E2EE)",
            isGroup = false,
            avatarUrl = "img_avatar_alice",
            groupOwnerId = null,
            lastMessageText = "End-to-End Encryption activated.",
            lastMessageTimestamp = System.currentTimeMillis() - 3600000,
            unreadCount = 0
        )
        val chatBob = ChatEntity(
            id = "bob",
            name = "Bob (E2EE)",
            isGroup = false,
            avatarUrl = "img_avatar_bob",
            groupOwnerId = null,
            lastMessageText = "Let's test the web sync!",
            lastMessageTimestamp = System.currentTimeMillis() - 7200000,
            unreadCount = 1
        )
        val chatGroup = ChatEntity(
            id = "group1",
            name = "Security Architecture Group",
            isGroup = true,
            avatarUrl = "img_avatar_group",
            groupOwnerId = "alice",
            lastMessageText = "Bob: Secure cryptographic signatures active.",
            lastMessageTimestamp = System.currentTimeMillis() - 1800000,
            unreadCount = 0
        )

        repository.insertChat(chatAlice)
        repository.insertChat(chatBob)
        repository.insertChat(chatGroup)

        // 3. Seed messages
        repository.sendEncryptedMessage("alice", "alice", "Alice (E2EE)", "Hi! Welcome to WhatsChat.")
        repository.sendEncryptedMessage("alice", "me", "You", "Hello Alice! I enabled end-to-end encryption successfully.")
        
        repository.sendEncryptedMessage("bob", "bob", "Bob (E2EE)", "Let's test the web sync!")

        repository.sendEncryptedMessage("group1", "alice", "Alice (E2EE)", "Hello everyone, did you deploy our keys?")
        repository.sendEncryptedMessage("group1", "bob", "Bob (E2EE)", "Yes! Secure cryptographic signatures active.")

        // 4. Seed Status Updates
        val statusAlice = StatusEntity(
            id = "status_1",
            userId = "alice",
            userName = "Alice (E2EE)",
            userAvatarUrl = "img_avatar_alice",
            text = "Coding the synchronization system!",
            mediaUrl = null,
            mediaType = "text",
            timestamp = System.currentTimeMillis() - 7200000
        )
        val statusBob = StatusEntity(
            id = "status_2",
            userId = "bob",
            userName = "Bob (E2EE)",
            userAvatarUrl = "img_avatar_bob",
            text = "Beautiful day in the lab ☕",
            mediaUrl = null,
            mediaType = "text",
            timestamp = System.currentTimeMillis() - 14400000
        )
        repository.insertStatus(statusAlice)
        repository.insertStatus(statusBob)

        // 5. Seed Call logs
        val call1 = CallLogEntity(
            id = "call_1",
            callerId = "alice",
            callerName = "Alice (E2EE)",
            receiverId = "me",
            receiverName = "You",
            isVideo = false,
            timestamp = System.currentTimeMillis() - 86400000,
            duration = 182,
            status = "Incoming"
        )
        val call2 = CallLogEntity(
            id = "call_2",
            callerId = "me",
            callerName = "You",
            receiverId = "bob",
            receiverName = "Bob (E2EE)",
            isVideo = false,
            timestamp = System.currentTimeMillis() - 172800000,
            duration = 0,
            status = "Missed"
        )
        repository.insertCallLog(call1)
        repository.insertCallLog(call2)
    }

    fun signUpUser(
        email: String,
        username: String,
        useCloudServer: Boolean,
        cloudUrl: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val trimmedEmail = email.trim()
        val trimmedUsername = username.trim()

        if (trimmedEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            onResult(false, "Please enter a valid email address.")
            return
        }

        if (trimmedUsername.length < 3) {
            onResult(false, "Username must be at least 3 characters.")
            return
        }

        if (trimmedUsername.contains(" ")) {
            onResult(false, "Username cannot contain spaces.")
            return
        }

        // Check uniqueness against existing seeded contacts
        val lowerUser = trimmedUsername.lowercase()
        if (lowerUser == "alice" || lowerUser == "bob" || lowerUser == "you" || lowerUser == "me" || lowerUser == "security architecture group") {
            onResult(false, "This username is already taken. Please try another one.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val pubKey = "RSA-PUB-KEY-${trimmedUsername.uppercase()}-${UUID.randomUUID().toString().take(8)}"

            // If using Cloud Server, perform REST registration
            if (useCloudServer && cloudUrl.isNotBlank()) {
                try {
                    val client = OkHttpClient()
                    val payload = JSONObject().apply {
                        put("username", trimmedUsername)
                        put("email", trimmedEmail)
                        put("publicKey", pubKey)
                    }
                    val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    
                    // Standardize cloudUrl trailing slash
                    val formattedUrl = if (cloudUrl.endsWith("/")) cloudUrl.dropLast(1) else cloudUrl
                    val request = Request.Builder()
                        .url("$formattedUrl/api/users/signup")
                        .post(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: ""
                            val errorMessage = try {
                                JSONObject(errorBody).getString("error")
                            } catch (e: Exception) {
                                "Cloud enrollment failed (HTTP ${response.code})."
                            }
                            withContext(Dispatchers.Main) {
                                onResult(false, errorMessage)
                            }
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        onResult(false, "Could not connect to cloud server: ${e.message}. Is your server online?")
                    }
                    return@launch
                }
            }

            try {
                // Update or insert "me" user in the Room Database
                val meUser = UserEntity(
                    id = "me",
                    name = trimmedUsername,
                    avatarUrl = "img_profile_avatar",
                    publicKey = pubKey,
                    isMe = true
                )
                repository.insertUser(meUser)

                // Save to SharedPreferences
                sharedPrefs.edit()
                    .putBoolean("is_registered", true)
                    .putString("user_email", trimmedEmail)
                    .putString("user_username", trimmedUsername)
                    .putString("cloud_server_url", cloudUrl.trim())
                    .putBoolean("cloud_sync_enabled", useCloudServer)
                    .apply()

                withContext(Dispatchers.Main) {
                    _currentUserEmail.value = trimmedEmail
                    _currentUsername.value = trimmedUsername
                    com.example.security.CryptoHelper.currentUsername = trimmedUsername
                    _cloudServerUrl.value = cloudUrl.trim()
                    _cloudSyncEnabled.value = useCloudServer
                    _isRegistered.value = true

                    // Immediately connect WebSocket if cloud is enabled
                    if (useCloudServer) {
                        cloudSocketManager?.connect(cloudUrl, trimmedUsername)
                    }

                    onResult(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "An unexpected error occurred during registration.")
                }
            }
        }
    }

    /**
     * Create a secure direct E2EE chat with another user username.
     * Optionally queries the cloud database to find and exchange their actual public key.
     */
    fun createDirectChat(contactUsername: String, onResult: (Boolean, String?) -> Unit) {
        val trimmed = contactUsername.trim().lowercase()
        if (trimmed.length < 3) {
            onResult(false, "Username must be at least 3 characters.")
            return
        }
        if (trimmed == _currentUsername.value?.lowercase()) {
            onResult(false, "You cannot chat with yourself.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // First check if the chat already exists
            val existingChat = repository.getChatById(trimmed)
            if (existingChat != null) {
                withContext(Dispatchers.Main) {
                    onResult(true, "Chat already exists!")
                }
                return@launch
            }

            var contactName = contactUsername.trim()
            var publicKey = "RSA-PUB-KEY-${trimmed.uppercase()}"
            if (_cloudSyncEnabled.value && !_cloudServerUrl.value.isNullOrBlank()) {
                try {
                    val client = OkHttpClient()
                    val formattedUrl = if (_cloudServerUrl.value.endsWith("/")) _cloudServerUrl.value.dropLast(1) else _cloudServerUrl.value
                    val request = Request.Builder()
                        .url("$formattedUrl/api/users/$trimmed")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string()
                            if (!bodyStr.isNullOrBlank()) {
                                val userObj = JSONObject(bodyStr)
                                contactName = userObj.optString("username", contactUsername)
                                val pub = userObj.optString("publicKey", "")
                                if (pub.isNotBlank()) {
                                    publicKey = pub
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Could not fetch user key from cloud: ${e.message}")
                }
            }

            try {
                // Insert User Entity
                val userEntity = UserEntity(
                    id = trimmed,
                    name = contactName,
                    avatarUrl = "img_profile_avatar",
                    publicKey = publicKey,
                    isMe = false
                )
                repository.insertUser(userEntity)

                // Insert Chat Entity
                val chatEntity = ChatEntity(
                    id = trimmed,
                    name = contactName,
                    isGroup = false,
                    avatarUrl = "img_profile_avatar",
                    groupOwnerId = null,
                    lastMessageText = "Secure E2EE chat initiated. Direct keys exchanged!",
                    lastMessageTimestamp = System.currentTimeMillis(),
                    unreadCount = 0
                )
                repository.insertChat(chatEntity)

                // Send a helper system message
                repository.sendEncryptedMessage(trimmed, trimmed, contactName, "Secure E2EE channel opened. Greetings!")

                withContext(Dispatchers.Main) {
                    onResult(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Failed to create chat locally.")
                }
            }
        }
    }

    fun updateCloudServerSettings(url: String, enabled: Boolean) {
        val trimmedUrl = url.trim()
        sharedPrefs.edit()
            .putString("cloud_server_url", trimmedUrl)
            .putBoolean("cloud_sync_enabled", enabled)
            .apply()

        _cloudServerUrl.value = trimmedUrl
        _cloudSyncEnabled.value = enabled

        val username = _currentUsername.value
        if (enabled && !username.isNullOrBlank()) {
            cloudSocketManager?.connect(trimmedUrl, username)
        } else {
            cloudSocketManager?.disconnect()
        }
    }

    fun updateNotificationSettings(received: Boolean, sent: Boolean) {
        sharedPrefs.edit()
            .putBoolean("notify_received", received)
            .putBoolean("notify_sent", sent)
            .apply()
        _notificationOnReceived.value = received
        _notificationOnSent.value = sent
    }

    override fun onCleared() {
        super.onCleared()
        syncServer?.stop()
    }
}
