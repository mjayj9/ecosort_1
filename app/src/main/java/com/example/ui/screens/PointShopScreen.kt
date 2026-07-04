package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import com.example.util.GlobalState
import com.example.ui.components.AdBanner
import kotlinx.coroutines.launch

data class ShopItem(val id: Int, val name: String, val cost: Int, val isCu: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointShopScreen() {
    val coroutineScope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var showBarcodeDialog by remember { mutableStateOf(false) }
    
    val items = listOf(
        ShopItem(1, "CU 모바일 상품권 1,000원권", 5000, true),
        ShopItem(2, "GS25 모바일 상품권 2,000원권", 9500),
        ShopItem(3, "메가커피 아메리카노(HOT)", 10000)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("에코 포인트 샵") },
                actions = {
                    Text(
                        text = "${GlobalState.currentPoints}P", 
                        modifier = Modifier.padding(16.dp), 
                        fontWeight = FontWeight.Bold, 
                        color = MaterialTheme.colorScheme.primary
                    )
                },
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
            Text("보유 포인트로 리워드를 교환하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (GlobalState.isAdmin) {
                Button(
                    onClick = {
                        GlobalState.currentPoints += 5000
                        message = "관리자 권한: 5,000P 충전 완료!"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("관리자 전용: +5,000P 무한 충전")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("${item.cost}P", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = {
                                    if (GlobalState.currentPoints >= item.cost) {
                                        coroutineScope.launch {
                                            val success = com.example.repository.FirestoreRepository.exchangeCoupon(item.cost)
                                            if (success) {
                                                GlobalState.currentPoints -= item.cost
                                                if (item.isCu) {
                                                    showBarcodeDialog = true
                                                } else {
                                                    message = "'${item.name}' 교환 성공! 쿠폰함을 확인해주세요."
                                                }
                                            } else {
                                                message = "교환 중 오류가 발생했거나 포인트가 부족합니다."
                                            }
                                        }
                                    } else {
                                        message = "포인트가 부족합니다."
                                    }
                                },
                                enabled = GlobalState.currentPoints >= item.cost
                            ) {
                                Text("교환")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            AdBanner()
        }
    }
    
    if (showBarcodeDialog) {
        AlertDialog(
            onDismissRequest = { showBarcodeDialog = false },
            title = { Text("쿠폰 발급 완료") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CU 1,000원 모바일 상품권 바코드")
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
                        Text("||| | ||| |||| | | ||", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.Black)
                    }
                    Text("5090 8025 1560 00", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(onClick = { showBarcodeDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }
}
