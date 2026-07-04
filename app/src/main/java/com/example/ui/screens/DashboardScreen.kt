package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AdBanner
import com.example.util.GlobalState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    var topApartments by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val apartmentId = GlobalState.apartmentId
    val apartmentName = GlobalState.apartmentName.ifBlank { apartmentId }
    val totalAptRecycled = GlobalState.totalAppRecycled
    val myRecycled = GlobalState.currentCount
    
    // Firebase 사용 가능 여부 확인
    val isFirebaseAvailable = remember {
        try {
            com.google.firebase.FirebaseApp.getInstance()
            true
        } catch (e: Exception) {
            false
        }
    }

    LaunchedEffect(Unit) {
        if (isFirebaseAvailable) {
            isLoading = true
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                db.collection("apartments")
                    .orderBy("totalRecycled", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(3)
                    .get()
                    .addOnSuccessListener { result ->
                        val list = mutableListOf<Pair<String, Long>>()
                        for (doc in result) {
                            val name = doc.getString("apartmentName") ?: doc.id
                            val count = doc.getLong("totalRecycled") ?: 0L
                            list.add(Pair(name, count))
                        }
                        topApartments = list
                        isLoading = false
                    }
                    .addOnFailureListener {
                        isLoading = false
                    }
            } catch (e: Exception) {
                isLoading = false
                e.printStackTrace()
            }
        }
    }

    // 환산 상수
    val costSavedPerItem = 50 
    val waterSavedPerItem = 2 

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("단지 리더보드 & 대시보드") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("소속: $apartmentName", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            
            // 단지 간 순위 (경쟁 구도 유도)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("단지 간 경쟁 리더보드", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else if (topApartments.isNotEmpty()) {
                        topApartments.forEachIndexed { index, pair ->
                            val isMe = pair.first == apartmentName
                            Text(
                                text = "${index + 1}위. ${pair.first} (${pair.second}건)",
                                color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    } else {
                        // Firebase 미연동 시 더미 리더보드 표시
                        Text("1위. 자이 리사이클뷰 (15,200건)")
                        Text("2위. $apartmentName (${totalAptRecycled}건)", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("3위. 푸르지오 그린타운 (8,900건)")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 사회적 가치 환산 대시보드
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("우리가 만든 사회적 가치", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("🌱 절약된 폐기물 처리 비용: 추정 ${totalAptRecycled * costSavedPerItem}원", fontWeight = FontWeight.SemiBold)
                    Text("💧 낭비 방지된 수자원: 추정 ${totalAptRecycled * waterSavedPerItem}L", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("(*1건당 처리 비용 50원, 헹굼 물 2L 기준으로 환산)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 개인 기여도
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("나의 환경 기여도", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("이번 달 나의 분리배출: $myRecycled 건")
                    Text("우리 단지 내 순위: 상위 15%")
                }
            }
        }
    }
}
