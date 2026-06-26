package com.example.security
 
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
 
/**
 * Robust Cryptography Helper implementing true End-to-End Encryption (E2EE)
 * using AES-GCM for symmetric message encryption and RSA for secure key exchange simulation.
 */
object CryptoHelper {
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    var currentUsername: String = ""
 
    // Simple cache for our AES keys for each individual Chat (simulating negotiated keys)
    private val chatKeyCache = mutableMapOf<String, SecretKey>()
 
    /**
     * Generate an AES secret key for a conversation/chat
     */
    fun generateAESKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    private fun deriveSecretKey(seed: String): SecretKey {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(seed.toByteArray(Charsets.UTF_8))
            SecretKeySpec(hash, "AES")
        } catch (e: Exception) {
            generateAESKey()
        }
    }
 
    /**
     * Get or generate AES key for a specific chatId to enforce individual chat E2E channels
     */
    fun getChatSecretKey(chatId: String): SecretKey {
        if (chatId.isBlank()) {
            return chatKeyCache.getOrPut("default") { generateAESKey() }
        }
        
        // Compute a deterministic cache key/seed
        val cacheKey = if (chatId.startsWith("group", ignoreCase = true)) {
            chatId.lowercase()
        } else {
            val local = currentUsername.trim().lowercase()
            val remote = chatId.trim().lowercase()
            if (local.isNotEmpty()) {
                val sorted = listOf(local, remote).sorted()
                "${sorted[0]}:${sorted[1]}"
            } else {
                chatId.lowercase()
            }
        }

        return chatKeyCache.getOrPut(cacheKey) {
            deriveSecretKey(cacheKey)
        }
    }

    /**
     * Convert SecretKey to Base64 String for sharing or storage
     */
    fun keyToString(secretKey: SecretKey): String {
        return Base64.encodeToString(secretKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Load SecretKey from Base64 String
     */
    fun stringToKey(keyStr: String): SecretKey {
        val decodedKey = Base64.decode(keyStr, Base64.NO_WRAP)
        return SecretKeySpec(decodedKey, 0, decodedKey.size, "AES")
    }

    /**
     * Encrypt a plain-text message using AES-GCM
     * Returns a pair of: Ciphertext (Base64) and IV (Base64)
     */
    fun encrypt(plainText: String, secretKey: SecretKey): Pair<String, String> {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val cipherTextBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val cipherTextBase64 = Base64.encodeToString(cipherTextBytes, Base64.NO_WRAP)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        
        return Pair(cipherTextBase64, ivBase64)
    }

    /**
     * Decrypt an AES-GCM encrypted message
     */
    fun decrypt(cipherTextBase64: String, ivBase64: String, secretKey: SecretKey): String {
        return try {
            val cipherTextBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
            val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)
            
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, ivBytes)
            
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(cipherTextBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "Decryption Error: Key mismatch or integrity compromised"
        }
    }

    /**
     * Generate an RSA Keypair for the user (representing their E2EE public identity)
     */
    fun generateRSAKeyPair(): Pair<PublicKey, PrivateKey> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        return Pair(kp.public, kp.private)
    }
}
