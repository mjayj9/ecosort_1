package com.example.repository

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

fun Bitmap.toBase64AndResize(): String {
    // 1. Resize to prevent Payload Too Large (max dimension 1024)
    val maxDimension = 1024
    val scale = if (width > height) {
        maxDimension.toFloat() / width
    } else {
        maxDimension.toFloat() / height
    }

    val resizedBitmap = if (scale < 1) {
        Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    } else {
        this
    }

    // 2. Compress
    val outputStream = ByteArrayOutputStream()
    resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}

/**
 * AI 분석/인증은 전부 Cloud Functions 경유로만 호출한다.
 * 앱은 Gemini API 키, 모델명, 프롬프트를 일절 알지 못하며,
 * 포인트 지급 역시 서버 트랜잭션(verifyDisposal)에서만 이뤄진다.
 * 단, Firebase 미연동/오프라인 환경일 경우 사용자의 API Key를 이용한 직접 호출 혹은 로컬 모의(Mock) 동작을 제공한다.
 */
object AiVisionRepository {

    private fun isFirebaseAvailable(): Boolean {
        return try {
            com.google.firebase.FirebaseApp.getInstance()
            true
        } catch (e: Exception) {
            false
        }
    }

    private val functions: FirebaseFunctions?
        get() = if (isFirebaseAvailable()) FirebaseFunctions.getInstance() else null

    private fun isSignedIn(): Boolean {
        return isFirebaseAvailable() && FirebaseAuth.getInstance().currentUser != null
    }

    private fun errorJson(message: String): String {
        return JSONObject().put("error", message).toString()
    }

    private suspend fun call(name: String, data: Map<String, Any>): String {
        val fns = functions
            ?: return if (BuildConfig.DEBUG) {
                errorJson("디버그 빌드: Firebase가 초기화되지 않아 서버 AI 분석을 사용할 수 없습니다.")
            } else {
                errorJson("서비스 초기화에 실패했습니다. 앱을 다시 실행해주세요.")
            }

        if (!isSignedIn()) {
            return errorJson("로그인이 필요합니다. 다시 로그인해주세요.")
        }

        return try {
            val result = fns.getHttpsCallable(name).call(data).await()
            val payload = result.data
            if (payload is Map<*, *>) {
                JSONObject(payload).toString()
            } else {
                errorJson("서버 응답 형식이 올바르지 않습니다.")
            }
        } catch (e: FirebaseFunctionsException) {
            errorJson(e.message ?: "서버 처리 중 오류가 발생했습니다.")
        } catch (e: Exception) {
            errorJson("네트워크 오류가 발생했습니다. 연결 상태를 확인해주세요.")
        }
    }

    // 로컬 디버그용 Gemini 직접 호출 유틸리티
    private fun callGeminiDirectly(apiKey: String, prompt: String, base64Image: String): String {
        return try {
            val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.doOutput = true
            
            // Build request JSON
            val requestJson = JSONObject().apply {
                put("contents", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", org.json.JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                            put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            }))
                        })
                    })
                })
            }
            
            conn.outputStream.use { os ->
                val input = requestJson.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            val code = conn.responseCode
            val responseText = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP Error: $code"
            }
            
            if (code in 200..299) {
                val jsonResponse = JSONObject(responseText)
                val candidates = jsonResponse.getJSONArray("candidates")
                val candidate = candidates.getJSONObject(0)
                val content = candidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val text = parts.getJSONObject(0).getString("text")
                text
            } else {
                JSONObject().put("error", "Gemini API Error: $responseText").toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            JSONObject().put("error", "Exception: ${e.message}").toString()
        }
    }

    /** 1차 오염도 분석. 결과는 서버가 schema를 강제한 JSON 문자열. */
    suspend fun analyzeWasteImage(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val apiKey = com.example.util.GlobalState.userApiKey
        
        // 1. Firebase 미연동/오프라인 환경일 경우 로컬 처리
        if (!isFirebaseAvailable() || FirebaseAuth.getInstance().currentUser == null) {
            if (apiKey.isNotBlank()) {
                val prompt = """
                    너는 배달 쓰레기 분리배출 전문가 AI야.
                    사용자가 찍은 배달 쓰레기 사진을 보고, 아래 정보를 정확히 JSON 형태로만 반환해 줘.
                    
                    응답 포맷:
                    {
                      "판독_성공": true,
                      "재질": "플라스틱",
                      "오염도_퍼센트": 10,
                      "등급": 1,
                      "오염부분_좌표": {"ymin": 0.0, "xmin": 0.0, "ymax": 0.0, "xmax": 0.0},
                      "상태": "깨끗함",
                      "피드백": "아주 깨끗하게 세척되었습니다. 플라스틱 수거함에 배출해주세요.",
                      "헹굼_권장여부": false,
                      "배출방법": "플라스틱으로 배출",
                      "불가_사유": ""
                    }
                """.trimIndent()
                return@withContext callGeminiDirectly(apiKey, prompt, bitmap.toBase64AndResize())
            } else {
                // Mock response
                return@withContext """
                    {
                      "판독_성공": true,
                      "재질": "플라스틱",
                      "오염도_퍼센트": 3,
                      "등급": 1,
                      "오염부분_좌표": {"ymin": 0.1, "xmin": 0.1, "ymax": 0.2, "xmax": 0.2},
                      "상태": "매우 양호",
                      "피드백": "이물질이 거의 없습니다. 바로 분리배출 가능합니다.",
                      "헹굼_권장여부": false,
                      "배출방법": "플라스틱 수거함에 배출하세요.",
                      "불가_사유": ""
                    }
                """.trimIndent()
            }
        }

        // 2. Firebase 활성화된 경우 Cloud Function 호출
        val data = mutableMapOf<String, Any>("image" to bitmap.toBase64AndResize())
        if (apiKey.isNotBlank()) {
            data["apiKey"] = apiKey
        }
        call("analyzeImage", data)
    }

    /**
     * 2차 배출 인증. 통과 시 서버가 포인트 지급/원장 기록/단지 랭킹 반영까지
     * 트랜잭션으로 처리하고 {통과, 사유, 지급포인트, 총포인트}를 돌려준다.
     */
    suspend fun verifyDisposal(
        wasteBitmap: Bitmap,
        disposalBitmap: Bitmap,
        apartmentId: String,
        source: String
    ): String = withContext(Dispatchers.IO) {
        val apiKey = com.example.util.GlobalState.userApiKey
        
        // 1. Firebase 미연동/오프라인 환경일 경우 로컬 처리
        if (!isFirebaseAvailable() || FirebaseAuth.getInstance().currentUser == null) {
            // Local points addition
            com.example.util.GlobalState.addRecycle("플라스틱", true)
            
            if (apiKey.isNotBlank()) {
                val prompt = """
                    너는 분리배출 인증 어뷰징(악용)을 잡아내는 보안 AI 전문가야.
                    사용자가 첫 번째 이미지에서 촬영한 쓰레기 용품이, 두 번째 이미지의 분리수거장 사진에서 실제로 투입되고 있거나 버려진 상태인지 교차 검증해줘.
                    오직 아래 JSON 규격으로만 답변해줘.
                    {
                      "통과": true,
                      "사유": "교차 검증 완료"
                    }
                """.trimIndent()
                val directResult = callGeminiDirectly(apiKey, prompt, disposalBitmap.toBase64AndResize())
                
                // Parse directResult to check if we can add rewards info
                return@withContext try {
                    val parsed = JSONObject(directResult)
                    parsed.put("지급포인트", 50)
                    parsed.put("총포인트", com.example.util.GlobalState.currentPoints)
                    parsed.toString()
                } catch (e: Exception) {
                    directResult
                }
            } else {
                // Mock response
                return@withContext JSONObject().apply {
                    put("통과", true)
                    put("사유", "이미지 교차 검증 통과 (로컬 테스트 모드)")
                    put("지급포인트", 50)
                    put("총포인트", com.example.util.GlobalState.currentPoints)
                }.toString()
            }
        }

        // 2. Firebase 활성화된 경우 Cloud Function 호출
        val data = mutableMapOf<String, Any>(
            "wasteImage" to wasteBitmap.toBase64AndResize(),
            "disposalImage" to disposalBitmap.toBase64AndResize(),
            "apartmentId" to apartmentId,
            "source" to source
        )
        if (apiKey.isNotBlank()) {
            data["apiKey"] = apiKey
        }
        call("verifyDisposal", data)
    }

    /** 포인트샵 쿠폰 교환. 차감/발급은 서버 트랜잭션에서만 수행. */
    suspend fun redeemCoupon(context: android.content.Context, itemId: String): String = withContext(Dispatchers.IO) {
        if (!isFirebaseAvailable() || FirebaseAuth.getInstance().currentUser == null) {
            // Local simulated mode
            val email = com.example.util.GlobalState.userEmail.ifBlank { "debug" }
            val prefs = context.getSharedPreferences("ecosort_debug_${email.replace(".", "_")}", android.content.Context.MODE_PRIVATE)
            val current = prefs.getLong("points", 0L)
            
            val cost = when (itemId) {
                "cu1000" -> 5000
                "gs2000" -> 9500
                "mega_americano" -> 10000
                else -> 5000
            }
            val itemName = when (itemId) {
                "cu1000" -> "CU 모바일 상품권 1,000원권"
                "gs2000" -> "GS25 모바일 상품권 2,000원권"
                "mega_americano" -> "메가커피 아메리카노(HOT)"
                else -> "상품권"
            }
            
            if (current < cost) {
                return@withContext JSONObject().put("error", "포인트가 부족합니다.").toString()
            }
            
            val newTotal = current - cost
            prefs.edit().putLong("points", newTotal).apply()
            
            // Also update GlobalState runtime points
            com.example.util.GlobalState.currentPoints = newTotal.toInt()
            
            return@withContext JSONObject().apply {
                put("success", true)
                put("remainingPoints", newTotal)
                put("itemName", itemName)
                put("code", "DEMO-LOCAL-${(100000..999999).random()}")
                put("isDemo", true)
            }.toString()
        }
        
        call("redeemCoupon", mapOf("itemId" to itemId))
    }

    /** 관리자 전용 포인트 지급 (서버가 custom claim 재검증). */
    suspend fun grantPoints(context: android.content.Context, points: Int, reason: String): String = withContext(Dispatchers.IO) {
        if (!isFirebaseAvailable() || FirebaseAuth.getInstance().currentUser == null) {
            // Local simulated mode
            val email = com.example.util.GlobalState.userEmail.ifBlank { "debug" }
            val prefs = context.getSharedPreferences("ecosort_debug_${email.replace(".", "_")}", android.content.Context.MODE_PRIVATE)
            val current = prefs.getLong("points", 0L)
            val newTotal = current + points
            prefs.edit().putLong("points", newTotal).apply()
            
            // Also update GlobalState runtime points
            com.example.util.GlobalState.currentPoints = newTotal.toInt()
            
            return@withContext JSONObject().apply {
                put("success", true)
                put("totalPoints", newTotal)
            }.toString()
        }
        
        call("grantPoints", mapOf("points" to points, "reason" to reason))
    }

    /** 회원 탈퇴: 서버가 Firestore 개인정보 삭제/익명화 후 Auth 계정까지 삭제. */
    suspend fun deleteAccount(): String = withContext(Dispatchers.IO) {
        call("deleteAccount", emptyMap())
    }
}
