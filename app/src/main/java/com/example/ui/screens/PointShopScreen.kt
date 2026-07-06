package com.example.ui.screens

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
import com.example.BuildConfig
import com.example.repository.AiVisionRepository
import com.example.util.GlobalState
import com.example.ui.components.AdBanner
import kotlinx.coroutines.launch
import org.json.JSONObject

// id는 서버(functions/index.js)의 COUPON_CATALOG 키와 일치해야 한다.
data class ShopItem(val id: String, val name: String, val cost: Int)

data class IssuedCoupon(val itemName: String, val code: String, val isDemo: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointShopScreen() {
    val coroutineScope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var issuedCoupon by remember { mutableStateOf<IssuedCoupon?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val items = listOf(
        ShopItem("cu1000", "CU 모바일 상품권 1,000원권", 5000),
        ShopItem("gs2000", "GS25 모바일 상품권 2,000원권", 9500),
        ShopItem("mega_americano", "메가커피 아메리카노(HOT)", 10000)
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

            // 관리자 수동 지급: debug 빌드 + 서버 custom claim 보유자에게만 노출되며,
            // 실제 지급 여부는 서버(grantPoints)가 claim을 재검증해 결정한다.
            if (BuildConfig.DEBUG && GlobalState.isAdmin) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isProcessing = true
                            val result = JSONObject(AiVisionRepository.grantPoints(5000, "debug_admin_topup"))
                            isProcessing = false
                            message = if (result.has("error")) {
                                result.getString("error")
                            } else {
                                GlobalState.currentPoints = result.optInt("totalPoints", GlobalState.currentPoints)
                                "관리자 지급 완료 (서버 검증)"
                            }
                        }
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("관리자(debug): +5,000P 지급 요청")
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
                                    coroutineScope.launch {
                                        isProcessing = true
                                        message = null
                                        // 포인트 차감 + 쿠폰 발급은 서버 트랜잭션에서 원자적으로 수행된다.
                                        val result = try {
                                            JSONObject(AiVisionRepository.redeemCoupon(item.id))
                                        } catch (e: Exception) {
                                            JSONObject().put("error", "교환 처리 중 오류가 발생했습니다.")
                                        }
                                        isProcessing = false
                                        if (result.has("error")) {
                                            message = result.getString("error")
                                        } else {
                                            GlobalState.currentPoints = result.optInt("remainingPoints", GlobalState.currentPoints)
                                            issuedCoupon = IssuedCoupon(
                                                itemName = result.optString("itemName", item.name),
                                                code = result.optString("code", ""),
                                                isDemo = result.optBoolean("isDemo", true)
                                            )
                                        }
                                    }
                                },
                                enabled = !isProcessing && GlobalState.currentPoints >= item.cost
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

    issuedCoupon?.let { coupon ->
        AlertDialog(
            onDismissRequest = { issuedCoupon = null },
            title = { Text("쿠폰 발급 완료") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(coupon.itemName)
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(coupon.code, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    if (coupon.isDemo) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "⚠️ DEMO 쿠폰입니다. 실제 매장에서 사용할 수 없습니다.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("발급 내역은 마이페이지 > 교환 내역에서 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { issuedCoupon = null }) {
                    Text("닫기")
                }
            }
        )
    }
}
