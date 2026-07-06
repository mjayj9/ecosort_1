package com.example.repository

import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * 클라이언트는 Firestore에 대해 "자기 프로필 읽기"와 "제한된 프로필 필드 쓰기"만 수행한다.
 * points, totalRecycled, 쿠폰, 원장 등 보상 관련 필드는 전부 Cloud Functions(Admin SDK)가
 * 트랜잭션으로만 변경하며, firestore.rules 에서도 클라이언트 쓰기가 거부된다.
 */
object FirestoreRepository {
    private fun isFirebaseAvailable(): Boolean {
        return try {
            com.google.firebase.FirebaseApp.getInstance()
            true
        } catch (e: Exception) {
            false
        }
    }

    private val firestore by lazy { if (isFirebaseAvailable()) FirebaseFirestore.getInstance() else null }
    private val auth by lazy { if (isFirebaseAvailable()) FirebaseAuth.getInstance() else null }

    // debug 빌드에서 Firebase 미구성일 때만 사용하는 로컬 저장소 (release에서는 진입 불가)
    private fun getDebugPrefs(context: android.content.Context): android.content.SharedPreferences? {
        if (!BuildConfig.DEBUG) return null
        val email = com.example.util.GlobalState.userEmail.ifBlank { "debug" }
        return context.getSharedPreferences("ecosort_debug_${email.replace(".", "_")}", android.content.Context.MODE_PRIVATE)
    }

    suspend fun loadUserProfile(context: android.content.Context): Map<String, Any>? {
        val fs = firestore
        val au = auth
        if (fs == null || au == null || au.currentUser == null) {
            val prefs = getDebugPrefs(context) ?: return null
            val points = prefs.getLong("points", 0L)
            val apartmentId = prefs.getString("apartmentId", "") ?: ""
            return mapOf("points" to points, "apartmentId" to apartmentId)
        }

        val userId = au.currentUser?.uid ?: return null
        val userRef = fs.collection("users").document(userId)

        return try {
            val doc = userRef.get().await()
            if (doc.exists()) {
                doc.data
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun saveUserApartment(context: android.content.Context, apartmentId: String): Boolean {
        val fs = firestore
        val au = auth
        if (fs == null || au == null || au.currentUser == null) {
            val prefs = getDebugPrefs(context) ?: return false
            prefs.edit().putString("apartmentId", apartmentId).apply()
            return true
        }

        val userId = au.currentUser?.uid ?: return false
        val userRef = fs.collection("users").document(userId)

        return try {
            // 규칙상 클라이언트가 만질 수 있는 필드는 apartmentId/displayName 뿐이다.
            userRef.set(mapOf("apartmentId" to apartmentId), com.google.firebase.firestore.SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
