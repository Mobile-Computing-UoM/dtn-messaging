package com.oppmesh.dtn.util

import android.content.Context
import java.security.SecureRandom

class NodeIdentityManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("oppmesh_identity", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_NODE_ID = "local_node_id"
        private const val KEY_LAST_TARGET_ID = "last_target_node_id"
        // 32 unambiguous alphanumeric characters (excludes 0, O, 1, I to prevent misreading)
        private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }

    fun getOrCreateNodeId(): String {
        var id = prefs.getString(KEY_NODE_ID, null)
        if (id.isNullOrBlank()) {
            id = generateUnique8CharId()
            prefs.edit().putString(KEY_NODE_ID, id).commit()
        }
        return id
    }

    fun regenerateNodeId(): String {
        val id = generateUnique8CharId()
        prefs.edit().putString(KEY_NODE_ID, id).commit()
        return id
    }

    fun setNodeId(customId: String) {
        val cleaned = customId.trim().uppercase()
        if (cleaned.isNotBlank()) {
            prefs.edit().putString(KEY_NODE_ID, cleaned).commit()
        }
    }

    fun getLastTargetNodeId(): String {
        return prefs.getString(KEY_LAST_TARGET_ID, "") ?: ""
    }

    fun setLastTargetNodeId(targetId: String) {
        val cleaned = targetId.trim().uppercase()
        prefs.edit().putString(KEY_LAST_TARGET_ID, cleaned).commit()
    }

    private fun generateUnique8CharId(): String {
        val random = SecureRandom()
        val sb = StringBuilder(8)
        for (i in 0 until 8) {
            val idx = random.nextInt(ALPHABET.length)
            sb.append(ALPHABET[idx])
        }
        return sb.toString()
    }
}
