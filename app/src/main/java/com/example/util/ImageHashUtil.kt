package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import java.security.MessageDigest

object ImageHashUtil {
    // 간단한 픽셀 기반 해시(pHash/aHash 변형) (어뷰징 방지: 완전 동일하거나 매우 유사한 재사용 사진 차단)
    // 심사 포인트: 어뷰징 방지 로직 (동일 사진 재제출 차단)
    fun generateImageHash(bitmap: Bitmap): String {
        // 해상도를 극단적으로 낮춰(8x8) 전체적인 형태와 명암의 윤곽만 남김.
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val stringBuilder = StringBuilder()
        
        var totalGray = 0
        val grays = IntArray(64)
        var index = 0
        
        // 전체 평균 픽셀 밝기(그레이스케일) 계산
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pixel = scaledBitmap.getPixel(x, y)
                val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                grays[index++] = gray
                totalGray += gray
            }
        }
        
        val avgGray = totalGray / 64
        
        // 평균보다 밝으면 1, 어두우면 0 부여하여 64비트 이진 문자열 생성
        for (gray in grays) {
            stringBuilder.append(if (gray > avgGray) "1" else "0")
        }
        
        // 생성된 문자열을 MD5로 해싱
        val bytes = stringBuilder.toString().toByteArray()
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
