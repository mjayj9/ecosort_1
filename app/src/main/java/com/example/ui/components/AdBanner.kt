package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.AdConfig

@Composable
fun AdBanner() {
    val context = LocalContext.current
    
    // 만약 광고 연동이 비활성화 되어 있으면 공간을 차지하지 않도록 숨김
    if (!AdConfig.isAdEnabled) {
        return
    }
    
    // UI 개선: 단순 회색 박스 대신 세련된 HSL 에코 그라데이션 및 네온 보더 적용
    val adGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF071E10), // Deep Forest Green
            Color(0xFF142D1C)  // Slightly lighter eco forest
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .background(adGradient, shape = RoundedCornerShape(8.dp))
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ads.google.com"))
                context.startActivity(intent)
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF00E676).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .border(0.5.dp, Color(0xFF00E676), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "AD",
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "자연을 지키는 OO기업 리필 스테이션 ♻️ [이동]",
                color = Color(0xFFE0E8E3),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

