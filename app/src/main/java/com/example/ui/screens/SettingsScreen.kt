package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.GlobalState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var newGoal by remember { mutableStateOf(GlobalState.targetGoal.toString()) }
    var message by remember { mutableStateOf("") }
    var isDeleting by remember { mutableStateOf(false) }

    fun clearLocalState() {
        GlobalState.userEmail = ""
        GlobalState.apartmentId = ""
        GlobalState.apartmentName = ""
        GlobalState.apartmentAddress = ""
        GlobalState.apartmentLatitude = 0.0
        GlobalState.apartmentLongitude = 0.0
        GlobalState.currentCount = 0
        GlobalState.currentPoints = 0
        GlobalState.isAdmin = false
    }
    
    val isFirebaseAvailable = remember {
        try {
            com.google.firebase.FirebaseApp.getInstance()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정 및 마이페이지") },
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
            Text("사용자 정보", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("계정: ${GlobalState.userEmail}")
            Text("소속 단지: ${GlobalState.apartmentName.ifBlank { GlobalState.apartmentId }}")
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("목표 설정 (보상 차등 지급)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "분리배출 목표 개수를 높게 설정할수록, 달성 시 더 많은 보너스 포인트가 지급됩니다.", 
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = newGoal,
                    onValueChange = { newGoal = it.filter { char -> char.isDigit() } },
                    label = { Text("목표 개수") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val goal = newGoal.toIntOrNull()
                        if (goal != null && goal > GlobalState.currentCount) {
                            GlobalState.targetGoal = goal
                            message = "목표가 ${goal}개로 설정되었습니다!"
                        } else {
                            message = "현재 진행률보다 높은 목표를 설정해주세요."
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("저장")
                }
            }
            if(message.isNotEmpty()){
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text("현재 진행률: ${GlobalState.currentCount} / ${GlobalState.targetGoal} 개")
            LinearProgressIndicator(
                progress = { (GlobalState.currentCount.toFloat() / GlobalState.targetGoal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = {
                    if (isFirebaseAvailable) {
                        try {
                            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    clearLocalState()
                    onLogout()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("로그아웃")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (!isFirebaseAvailable) {
                        clearLocalState()
                        onLogout()
                        return@OutlinedButton
                    }
                    // 탈퇴는 서버(deleteAccount)가 Firestore 개인정보 삭제/익명화까지 처리한 뒤
                    // Auth 계정을 삭제한다. 클라이언트 단독 Auth 삭제는 DB에 개인정보를 남긴다.
                    coroutineScope.launch {
                        isDeleting = true
                        val result = try {
                            org.json.JSONObject(com.example.repository.AiVisionRepository.deleteAccount())
                        } catch (e: Exception) {
                            org.json.JSONObject().put("error", "탈퇴 처리 중 오류가 발생했습니다.")
                        }
                        isDeleting = false
                        if (result.has("error")) {
                            message = "회원 탈퇴 실패: ${result.getString("error")}"
                        } else {
                            try {
                                com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            clearLocalState()
                            onLogout()
                        }
                    }
                },
                enabled = !isDeleting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(if (isDeleting) "탈퇴 처리 중..." else "회원 탈퇴 (DB 정보 삭제)")
            }
        }
    }
}
