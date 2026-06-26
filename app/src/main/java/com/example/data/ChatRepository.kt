package com.example.data

import com.example.security.CryptoHelper
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(private val chatDao: ChatDao) {

    val allUsers: Flow<List<UserEntity>> = chatDao.getAllUsers()
    val allChats: Flow<List<ChatEntity>> = chatDao.getAllChats()
    val allStatuses: Flow<List<StatusEntity>> = chatDao.getAllStatuses()
    val allCallLogs: Flow<List<CallLogEntity>> = chatDao.getAllCallLogs()

    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>> = chatDao.getMessagesForChat(chatId)

    suspend fun insertUser(user: UserEntity) = chatDao.insertUser(user)
    suspend fun insertChat(chat: ChatEntity) = chatDao.insertChat(chat)
    suspend fun insertStatus(status: StatusEntity) = chatDao.insertStatus(status)
    suspend fun insertCallLog(callLog: CallLogEntity) = chatDao.insertCallLog(callLog)

    /**
     * High-fidelity E2EE send helper. Encrypts message before persisting.
     */
    suspend fun sendEncryptedMessage(
        chatId: String,
        senderId: String,
        senderName: String,
        plainText: String,
        mediaUrl: String? = null,
        mediaType: String? = null
    ): MessageEntity {
        // 1. Get E2EE key for this specific conversation
        val secretKey = CryptoHelper.getChatSecretKey(chatId)
        
        // 2. Encrypt message body
        val (ciphertext, iv) = CryptoHelper.encrypt(plainText, secretKey)
        
        // 3. Create message record
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            senderId = senderId,
            senderName = senderName,
            ciphertext = ciphertext,
            iv = iv,
            isEncrypted = true,
            timestamp = System.currentTimeMillis(),
            mediaUrl = mediaUrl,
            mediaType = mediaType
        )
        
        // 4. Save to DB
        chatDao.insertMessage(message)
        
        // 5. Update parent Chat summary
        val chat = chatDao.getChatById(chatId)
        if (chat != null) {
            val previewText = when {
                mediaType == "image" -> "📷 Photo"
                mediaType == "audio" -> "🎵 Voice note"
                else -> plainText
            }
            chatDao.insertChat(
                chat.copy(
                    lastMessageText = previewText,
                    lastMessageTimestamp = message.timestamp
                )
            )
        }
        
        return message
    }

    /**
     * Method to decrypt an encrypted message for display
     */
    fun decryptMessage(message: MessageEntity): String {
        if (!message.isEncrypted) return message.ciphertext
        val secretKey = CryptoHelper.getChatSecretKey(message.chatId)
        return CryptoHelper.decrypt(message.ciphertext, message.iv, secretKey)
    }

    suspend fun getChatById(chatId: String): ChatEntity? = chatDao.getChatById(chatId)
    suspend fun getUserById(userId: String): UserEntity? = chatDao.getUserById(userId)

    suspend fun updateMessageStatus(id: String, status: String) {
        chatDao.updateMessageStatus(id, status)
    }

    suspend fun markIncomingMessagesAsRead(chatId: String) {
        chatDao.markIncomingMessagesAsRead(chatId)
    }

    suspend fun getUnreadIncomingMessages(chatId: String): List<MessageEntity> {
        return chatDao.getUnreadIncomingMessages(chatId)
    }
}
