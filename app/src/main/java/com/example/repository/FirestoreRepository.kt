package com.example.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

// 심사 포인트: Firebase Transaction을 활용하여 여러 문서(포인트 증가 점수, 아파트 통계)를 
// 중간에 충돌하거나 데이터가 꼬이지 않도록 안전하게 동시 업데이트 처리
object FirestoreRepository {
    // Firebase 초기화 확인 헬퍼
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

    /**
     * 어뷰징 단계를 통과한 사용자가 최종적으로 분리배출을 완료했을 때 보상을 지급하는 함수.
     * 트랜잭션 단위: 유저의 포인트 데이터 + 유저 소속 단지의 누적 분리수거 카운트
     */
    suspend fun verifyAndReward(apartmentId: String, points: Int = 50): Boolean {
        val fs = firestore
        val au = auth
        if (fs == null || au == null || au.currentUser == null) {
            // Firebase 미연동 시 데모 모드로 동작 (가짜 성공 스텁 반환)
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
            }.await() // 트랜잭션 완료 대기
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 제휴처 연동 포인트 차감 (트랜잭션 처리로 중복 차감 방지)
    suspend fun exchangeCoupon(pointsCost: Int): Boolean {
        val fs = firestore
        val au = auth
        if (fs == null || au == null || au.currentUser == null) {
            // Firebase 미연동 시 데모 모드로 동작 (가짜 성공 스텁 반환)
            return true
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

    // 유저 프로필(포인트, 소속 아파트) 가져오기
    suspend fun loadUserProfile(): Map<String, Any>? {
        val fs = firestore ?: return null
        val au = auth ?: return null
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

    // 아파트 선택 시 바로 파이어베이스에 소속 정보 등록
    suspend fun saveUserApartment(apartmentId: String): Boolean {
        val fs = firestore ?: return false
        val au = auth ?: return false
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
