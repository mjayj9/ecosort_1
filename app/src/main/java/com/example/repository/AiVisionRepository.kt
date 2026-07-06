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

    /** 1차 오염도 분석. 결과는 서버가 schema를 강제한 JSON 문자열. */
    suspend fun analyzeWasteImage(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        call("analyzeImage", mapOf("image" to bitmap.toBase64AndResize()))
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
        call(
            "verifyDisposal",
            mapOf(
                "wasteImage" to wasteBitmap.toBase64AndResize(),
                "disposalImage" to disposalBitmap.toBase64AndResize(),
                "apartmentId" to apartmentId,
                "source" to source
            )
        )
    }

    /** 포인트샵 쿠폰 교환. 차감/발급은 서버 트랜잭션에서만 수행. */
    suspend fun redeemCoupon(itemId: String): String = withContext(Dispatchers.IO) {
        call("redeemCoupon", mapOf("itemId" to itemId))
    }

    /** 관리자 전용 포인트 지급 (서버가 custom claim 재검증). */
    suspend fun grantPoints(points: Int, reason: String): String = withContext(Dispatchers.IO) {
        call("grantPoints", mapOf("points" to points, "reason" to reason))
    }

    /** 회원 탈퇴: 서버가 Firestore 개인정보 삭제/익명화 후 Auth 계정까지 삭제. */
    suspend fun deleteAccount(): String = withContext(Dispatchers.IO) {
        call("deleteAccount", emptyMap())
    }
}
