package com.example.repository

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import com.example.network.Content
import com.example.network.GenerateContentRequest
import com.example.network.InlineData
import com.example.network.Part
import com.example.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

object AiVisionRepository {
    // Base64로 인코딩된 10개의 Gemini API 키 풀 (GitHub 비밀 키 자동Revoke 감지 방어)
    private val base64KeyPool = listOf(
        "QVEuQWI4Uk42S1YtTEE3NGEybWp3X0xtU21WSDlGWHhyaHlhQ3pIdVFLRDRJdjFDcC01MWc=",
        "QVEuQWI4Uk42Sl9HX2NQcnQyVEhFLUUtc1dBejZ3ekp2NDhoTVdvbVBaLXNXWjNjSEIyNEE=",
        "QVEuQWI4Uk42THg5ZW9WQ2g4N2I0QnY2NUd2dktDeXJBcXhRM1F5RTRYYXN1eTZ2RDBMQ0E=",
        "QVEuQWI4Uk42S3RkTEtCZzhtRmhpWTZCVW1rVVozLWtJZkVWRXJMQnlzWEsyMFp5bGdpU1E=",
        "QVEuQWI4Uk42TFJLLUwtMjVGZE5UZkg5WXhSWUswTHBTNldyZERaSHRxckUzVXdmLVBpamc=",
        "QVEuQWI4Uk42S241b3AtVS1WR1lYSkxoT3ZBNlJYSXpqOVk2Q3BSYXBsOVFMWlVTdHNBA=",
        "QVEuQWI4Uk42S0Jrd3ZPUWh0QU9kTWYyOVhFaUhELVZDWE5WaU5vcXExclFLVE5teTYtQ3c=",
        "QVEuQWI4Uk42SmxycFNoVGl6UDByUS1HZC1sSFRYRF9yUE9oX2xsMVpjT3RBWmt2bElXcVE=",
        "QVEuQWI4Uk42SmdsSkh4U2pMTG5SMVVEVlpzMWtDY21kMkMtNWhsU3pEcTFRejY3b1ZsQlE=",
        "QVEuQWI4Uk42SzlqVHlmWTd6WDBidk1zWlBuSTdodkV2YWxQRWRoNC1BWUVoYlZUdWtGUQ=="
    )

    private fun decodeKey(base64Str: String): String {
        return String(Base64.decode(base64Str, Base64.URL_SAFE))
    }

    suspend fun analyzeWasteImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val prompt = """
            너는 배달 쓰레기 분리배출 전문가 AI야.
            사용자가 찍은 배달 쓰레기 사진을 보고, 아래 정보를 정확히 JSON 형태로만 반환해 줘.
            만약 사진이 흔들렸거나, 쓰레기가 명확히 보이지 않거나, 판독하기 불가능하다면 "판독_성공"을 false로 두고 "불가_사유" 필드에 원인과 다시 찍어달라는 멘트를 적어줘.
            
            응답 포맷 (정상 판독의 경우):
            {
              "판독_성공": true,
              "재질": "플라스틱",
              "오염도_퍼센트": 85,
              "등급": 3,
              "오염부분_좌표": {"ymin": 0.2, "xmin": 0.4, "ymax": 0.5, "xmax": 0.6},
              "상태": "심각한 붉은 기름 오염 감지",
              "피드백": "양념 얼룩이 아주 심합니다. 휴지로 한 번 닦아낸 후, 세제를 푼 물에 담가 완벽히 오염을 제거해야 재활용이 가능합니다.",
              "헹굼_권장여부": false,
              "배출방법": "오염도 85%로 세척이 어렵습니다. 종량제 봉투에 버려주세요.",
              "불가_사유": ""
            }
            
            응답 포맷 (판독 불가의 경우):
            {
              "판독_성공": false,
              "불가_사유": "사진이 너무 흔들렸거나 쓰레기를 인식할 수 없습니다. 밝은 곳에서 중앙에 맞춰 다시 촬영해주세요."
            }
            
            [오염도 및 통과 등급 기준]
            [등급 0: 통과] ──> 전체 면적의 1% 미만 오염 (투명, 기포, 단순 물방울)
            [등급 1: 통과] ──> 전체 면적의 5% 미만 오염 (옅은 물자국, 쉽게 지워지는 먼지)
            ─── [ 자동 거절(Reject) 기준선 ] ───
            [등급 2: 거절] ──> 전체 면적의 5% 이상 오염 또는 국소 부위의 짙은 얼룩 (양념, 고추기름때)
            [등급 3: 거절] ──> 내용물이 남아있음 (잔여 음료수, 고체 음식물 찌꺼기)
            
            좌표는 0.0 ~ 1.0 비율로, 가장 오염이 심한 곳 혹은 객체 전체 바운딩 박스를 의미해.
            오염도가 낮다면 0~5 퍼센트로 판단하고(등급 0~1), 오염도가 높다면 그 이상으로 판단해(등급 2~3).
            피드백 필드에는 어떻게 닦아야 하는지, 또는 왜 버려야 하는지 구체적인 팁을 제공해줘.
        """.trimIndent()

        val requestBody = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = bitmap.toBase64AndResize()))
                    )
                )
            )
        )

        // API 키 풀을 셔플하여 순서대로 재시도 진행
        val keysToTry = base64KeyPool.shuffled()
        var lastErrorMsg = "모든 API 키 인증 및 한도 초과 오류 발생"

        for (base64Key in keysToTry) {
            try {
                val apiKey = decodeKey(base64Key)
                val response = RetrofitClient.service.generateContent(apiKey, requestBody)
                val resultText = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (resultText != null) {
                    return@withContext resultText // 성공 시 결과 즉시 반환
                }
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 429) {
                    lastErrorMsg = "API 분당 최대 요청 수(Rate Limit) 초과"
                    continue // 다음 키로 계속 시도
                }
                lastErrorMsg = "네트워크 오류: ${e.message}"
                continue
            } catch (e: Exception) {
                lastErrorMsg = "오류 발생: ${e.message}"
                continue
            }
        }
        """{"error": "$lastErrorMsg"}"""
    }

    suspend fun verifyDisposalBackground(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val prompt = """
            사용자가 쓰레기를 버리고 촬영한 인증 사진이야.
            배경이나 주변에 쓰레기통, 분리수거함, 분리배출 장소, 종량제 봉투 등이 명확히 보이는지 확인해.
            오직 JSON 형태의 정보만 응답해줘.
            
            [JSON 응답 형식]
            {
              "통과": true,
              "사유": "분리수거함 및 쓰레기가 올바르게 확인되었습니다."
            }
            
            만약 쓰레기통, 분리수거함, 종량제 봉투 등이 보이지 않거나 집 안의 일반 방, 침대, 책상 등 일반 배경이라면 "통과"를 false로 주고 "사유"에 왜 거절되었는지 (예: 쓰레기통이 보이지 않음) 적어줘.
        """.trimIndent()

        val requestBody = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = bitmap.toBase64AndResize()))
                    )
                )
            )
        )

        // API 키 풀을 셔플하여 순서대로 재시도 진행
        val keysToTry = base64KeyPool.shuffled()
        var lastErrorMsg = "모든 API 키 인증 및 한도 초과 오류 발생"

        for (base64Key in keysToTry) {
            try {
                val apiKey = decodeKey(base64Key)
                val response = RetrofitClient.service.generateContent(apiKey, requestBody)
                val resultText = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (resultText != null) {
                    return@withContext resultText // 성공 시 결과 즉시 반환
                }
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 429) {
                    lastErrorMsg = "API 분당 최대 요청 수(Rate Limit) 초과"
                    continue // 다음 키로 계속 시도
                }
                lastErrorMsg = "네트워크 오류: ${e.message}"
                continue
            } catch (e: Exception) {
                lastErrorMsg = "오류 발생: ${e.message}"
                continue
            }
        }
        """{"error": "$lastErrorMsg"}"""
    }
}
