package com.example.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.tasks.await

object GlobalState {
    var userEmail by mutableStateOf("")
    var apartmentId by mutableStateOf("")
    var apartmentName by mutableStateOf("")
    var apartmentAddress by mutableStateOf("")
    var apartmentLatitude by mutableStateOf(0.0)
    var apartmentLongitude by mutableStateOf(0.0)
    var currentPoints by mutableIntStateOf(0)
    var targetGoal by mutableIntStateOf(10)
    var currentCount by mutableIntStateOf(0)
    var totalAppRecycled by mutableIntStateOf(12450)
    
    // 관리자 여부는 이메일 비교가 아니라 Firebase custom claim(admin=true)으로만 결정된다.
    // 로그인 직후 refreshAdminClaim()이 ID 토큰의 claim을 읽어 갱신한다.
    var isAdmin by mutableStateOf(false)

    suspend fun refreshAdminClaim() {
        isAdmin = try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user == null) {
                false
            } else {
                val result = user.getIdToken(true).await()
                result.claims["admin"] == true
            }
        } catch (e: Exception) {
            false
        }
    }

    // 클라이언트 측 한도/중복 체크는 UX용 사전 필터일 뿐이며,
    // 실제 어뷰징 차단은 서버(verifications/usage 컬렉션)가 수행한다.
    var lastActiveDate by mutableStateOf("")
    var dailyCount by mutableIntStateOf(0)
    val usedHashes = mutableSetOf<String>()

    fun canRecycleToday(): Boolean {
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        if (lastActiveDate != todayStr) {
            lastActiveDate = todayStr
            dailyCount = 0
        }
        return dailyCount < 10 // 하루 10회 제한
    }
        
    // 포인트 지급은 서버(verifyDisposal 트랜잭션) 전담. 여기서는 화면 표시용 카운터만 갱신한다.
    fun addRecycle(material: String, isSuccess: Boolean) {
        if (isSuccess && canRecycleToday()) {
            dailyCount++
            currentCount++
            totalAppRecycled++

            if (currentCount == targetGoal) {
                targetGoal += 10 // 목표 상향 (표시용)
            }
        }
    }

    // 소속 아파트 코드를 기반으로 자산 JSON에서 세부 정보 자동 매핑 로드
    fun loadApartmentDetails(context: android.content.Context, code: String) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("apartments.json")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream, "UTF-8"))
            val jsonStr = reader.use { it.readText() }
            val jsonArray = org.json.JSONArray(jsonStr)
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("c") == code) {
                    apartmentId = code
                    apartmentName = obj.getString("n")
                    apartmentAddress = obj.getString("a")
                    
                    // 주소 좌표 Geocoding
                    try {
                        val geocoder = android.location.Geocoder(context, java.util.Locale.KOREA)
                        val addresses = geocoder.getFromLocationName(apartmentAddress, 1)
                        if (!addresses.isNullOrEmpty()) {
                            apartmentLatitude = addresses[0].latitude
                            apartmentLongitude = addresses[0].longitude
                        } else {
                            apartmentLatitude = 37.5665
                            apartmentLongitude = 126.9780
                        }
                    } catch (e: Exception) {
                        apartmentLatitude = 37.5665
                        apartmentLongitude = 126.9780
                    }
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
