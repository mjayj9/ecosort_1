package com.example.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

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

    private fun getSimPrefs(context: android.content.Context): android.content.SharedPreferences {
        val email = com.example.util.GlobalState.userEmail.ifBlank { "mjayj9@gmail.com" }
        return context.getSharedPreferences("ecosort_sim_data_${email.replace(".", "_")}", android.content.Context.MODE_PRIVATE)
    }

    suspend fun verifyAndReward(context: android.content.Context, apartmentId: String, points: Int = 50): Boolean {
        val fs = firestore
        val au = auth
        if (fs == null || au == null || au.currentUser == null) {
            // Simulator Mode
            val prefs = getSimPrefs(context)
            val currentPoints = prefs.getLong("points", 0L)
            prefs.edit().putLong("points", currentPoints + points).apply()
            return true
        }

        val userId = au.currentUser?.uid ?: return false
        val userRef = fs.collection("users").document(userId)
        val aptRef = fs.collection("apartments").document(apartmentId)

        return try {
            fs.runTransaction { transaction ->
                val userSnapshot = transaction.get(userRef)
                
                // 1. 유저 문서 데이터 업데이트
                if (!userSnapshot.exists()) {
                    transaction.set(userRef, hashMapOf("points" to points.toLong(), "apartmentId" to apartmentId))
                } else {
                    val currentPoints = userSnapshot.getLong("points") ?: 0L
                    transaction.update(userRef, "points", currentPoints + points)
                }

                // 2. 소속 단지(아파트)의 누적 처리량 동시 업데이트
                val aptSnapshot = transaction.get(aptRef)
                if (!aptSnapshot.exists()) {
                    transaction.set(aptRef, hashMapOf("totalRecycled" to 1L, "apartmentName" to apartmentId))
                } else {
                    transaction.update(aptRef, "totalRecycled", FieldValue.increment(1))
                }
            }.await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun exchangeCoupon(context: android.content.Context, pointsCost: Int): Boolean {
        val fs = firestore
        val au = auth
        if (fs == null || au == null || au.currentUser == null) {
            // Simulator Mode
            val prefs = getSimPrefs(context)
            val currentPoints = prefs.getLong("points", 0L)
            if (currentPoints >= pointsCost) {
                prefs.edit().putLong("points", currentPoints - pointsCost).apply()
                return true
            }
            return false
        }

        val userId = au.currentUser?.uid ?: return false
        val userRef = fs.collection("users").document(userId)
        
        return try {
            fs.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                val currentPoints = snapshot.getLong("points") ?: 0L
                if (currentPoints >= pointsCost) {
                    transaction.update(userRef, "points", currentPoints - pointsCost)
                } else {
                    throw Exception("Not enough points")
                }
            }.await()
            true
        } catch(e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun loadUserProfile(context: android.content.Context): Map<String, Any>? {
        val fs = firestore
        val au = auth
        if (fs == null || au == null || au.currentUser == null) {
            // Simulator Mode
            val prefs = getSimPrefs(context)
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
            // Simulator Mode
            val prefs = getSimPrefs(context)
            prefs.edit().putString("apartmentId", apartmentId).apply()
            return true
        }
        
        val userId = au.currentUser?.uid ?: return false
        val userRef = fs.collection("users").document(userId)
        
        return try {
            fs.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                if (!snapshot.exists()) {
                    transaction.set(userRef, hashMapOf("points" to 0L, "apartmentId" to apartmentId))
                } else {
                    transaction.update(userRef, "apartmentId", apartmentId)
                }
            }.await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
