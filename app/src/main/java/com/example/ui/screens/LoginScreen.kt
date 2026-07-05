package com.example.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.GlobalState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

// Firebase 초기화 확인 헬퍼
fun checkFirebaseInitialized(): Boolean {
    return try {
        com.google.firebase.FirebaseApp.getInstance()
        true
    } catch (e: Exception) {
        false
    }
}

@Composable
fun LoginScreen(onLoginSuccess: (isNewUser: Boolean) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var emailInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val isFirebaseAvailable = remember { checkFirebaseInitialized() }

    val handleLoginSuccess = { email: String ->
        coroutineScope.launch {
            val profile = com.example.repository.FirestoreRepository.loadUserProfile()
            val savedAptId = profile?.get("apartmentId") as? String
            val savedPoints = (profile?.get("points") as? Long)?.toInt() ?: 0
            
            if (!savedAptId.isNullOrEmpty()) {
                GlobalState.currentPoints = savedPoints
                GlobalState.loadApartmentDetails(context, savedAptId)
            }
            
            // mjayj9@gmail.com 관리자 계정은 항상 신규 유저 취소(설정 화면 진입) 처리
            val isNewUser = if (email == "mjayj9@gmail.com") {
                true
            } else {
                savedAptId.isNullOrEmpty()
            }
            
            isLoading = false
            onLoginSuccess(isNewUser)
        }
    }

    // 실제 구글 로그인 연동 런처
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (idToken != null && isFirebaseAvailable) {
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    FirebaseAuth.getInstance().signInWithCredential(credential)
                        .addOnCompleteListener { authTask ->
                            if (authTask.isSuccessful) {
                                val email = authTask.result?.user?.email ?: account.email ?: "mjayj9@gmail.com"
                                GlobalState.userEmail = email
                                Toast.makeText(context, "구글 계정으로 로그인 성공: $email", Toast.LENGTH_SHORT).show()
                                handleLoginSuccess(email)
                            } else {
                                Toast.makeText(context, "Firebase 인증 실패. 시뮬레이터 모드로 로그인합니다.", Toast.LENGTH_SHORT).show()
                                // Fallback
                                val email = account.email ?: "mjayj9@gmail.com"
                                GlobalState.userEmail = email
                                handleLoginSuccess(email)
                            }
                        }
                } else {
                    // Fallback
                    val email = account?.email ?: "mjayj9@gmail.com"
                    GlobalState.userEmail = email
                    Toast.makeText(context, "Google 연동 로그인 (시뮬레이터)", Toast.LENGTH_SHORT).show()
                    handleLoginSuccess(email)
                }
            } catch (e: ApiException) {
                e.printStackTrace()
                // API Exception(예: 12500, 10 등 개발 키 미등록) 발생 시 시뮬레이터 로그인으로 Fallback 허용
                val fallbackEmail = "mjayj9@gmail.com"
                GlobalState.userEmail = fallbackEmail
                Toast.makeText(context, "구글 API 오류로 시뮬레이터 로그인 적용: $fallbackEmail", Toast.LENGTH_LONG).show()
                handleLoginSuccess(fallbackEmail)
            }
        } else {
            isLoading = false
            Toast.makeText(context, "로그인이 취소되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "에코소트",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "AI 기반 배달 쓰레기 분리배출 도우미",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(48.dp))

        if (!isFirebaseAvailable) {
            // Firebase가 초기화되지 않았거나 google-services.json이 없는 빌드 환경용 더미 텍스트 입력창
            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it },
                label = { Text("구글 계정 이메일 연동 (시뮬레이터)") },
                placeholder = { Text("mjayj9@gmail.com") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                isLoading = true
                if (isFirebaseAvailable) {
                    // 실제 구글 로그인 인텐트 실행
                    // web_client_id가 리소스에 없을 경우를 대비하여 문자열 직접 하드코딩 처리 가능
                    val webClientId = try {
                        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                        if (resId != 0) context.getString(resId) else "1083907074478-mockid.apps.googleusercontent.com"
                    } catch (e: Exception) {
                        "1083907074478-mockid.apps.googleusercontent.com"
                    }

                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(webClientId)
                        .requestEmail()
                        .build()
                    val googleSignInClient = GoogleSignIn.getClient(context, gso)
                    
                    // 기존 로그인 세션이 있으면 로그아웃 후 다시 구글 계정 선택창 띄우기
                    coroutineScope.launch {
                        try {
                            googleSignInClient.signOut()
                        } catch (e: Exception) {}
                        val signInIntent = googleSignInClient.signInIntent
                        googleSignInLauncher.launch(signInIntent)
                    }
                } else {
                    // 시뮬레이터 로그인 작동
                    coroutineScope.launch {
                        kotlinx.coroutines.delay(800)
                        val finalEmail = emailInput.ifBlank { "mjayj9@gmail.com" }
                        GlobalState.userEmail = finalEmail
                        Toast.makeText(context, "시뮬레이터 로그인 성공: $finalEmail", Toast.LENGTH_SHORT).show()
                        handleLoginSuccess(finalEmail)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    text = if (isFirebaseAvailable) "Google 계정으로 안전하게 시작하기" else "시뮬레이터 계정으로 시작하기",
                    fontSize = 16.sp
                )
            }
        }
    }
}


