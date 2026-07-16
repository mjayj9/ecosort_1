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
import com.example.BuildConfig
import com.example.util.GlobalState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

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
    var showSimulatedLoginDialog by remember { mutableStateOf(false) }
    var showPrivacyAgreementDialog by remember { mutableStateOf(false) }
    var pendingEmail by remember { mutableStateOf("") }

    val isFirebaseAvailable = remember { checkFirebaseInitialized() }
    // 시뮬레이션 로그인은 debug 빌드에서 Firebase 미구성일 때만 허용된다.
    // release 빌드에서는 Google 로그인 실패 = 로그인 실패이며 어떤 fallback도 없다.
    val allowSimulatedLogin = BuildConfig.DEBUG && !isFirebaseAvailable

    val handleLoginSuccess = { email: String ->
        coroutineScope.launch {
            // 관리자 여부는 서버가 발급한 custom claim으로만 판정
            GlobalState.refreshAdminClaim()

            val profile = com.example.repository.FirestoreRepository.loadUserProfile(context)
            val savedAptId = profile?.get("apartmentId") as? String
            val savedPoints = (profile?.get("points") as? Long)?.toInt() ?: 0

            if (!savedAptId.isNullOrEmpty()) {
                GlobalState.currentPoints = savedPoints
                GlobalState.loadApartmentDetails(context, savedAptId)
            }

            val isNewUser = savedAptId.isNullOrEmpty()
            if (isNewUser) {
                pendingEmail = email
                showPrivacyAgreementDialog = true
            } else {
                isLoading = false
                onLoginSuccess(false)
            }
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
                                val email = authTask.result?.user?.email ?: account.email ?: ""
                                GlobalState.userEmail = email
                                Toast.makeText(context, "구글 계정으로 로그인 성공: $email", Toast.LENGTH_SHORT).show()
                                handleLoginSuccess(email)
                            } else {
                                // 인증 실패는 실패로 처리한다. 관리자/시뮬레이션 계정 fallback 금지.
                                isLoading = false
                                Toast.makeText(context, "Firebase 인증에 실패했습니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_LONG).show()
                            }
                        }
                } else {
                    isLoading = false
                    Toast.makeText(context, "Google 로그인 토큰을 가져오지 못했습니다. 다시 시도해주세요.", Toast.LENGTH_LONG).show()
                }
            } catch (e: ApiException) {
                e.printStackTrace()
                isLoading = false
                Toast.makeText(context, "Google 로그인에 실패했습니다. (오류 코드: ${e.statusCode})", Toast.LENGTH_LONG).show()
            }
        } else {
            isLoading = false
            Toast.makeText(context, "구글 로그인이 취소되었습니다.", Toast.LENGTH_SHORT).show()
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

        if (allowSimulatedLogin) {
            // debug 빌드 + Firebase 미구성 환경 전용 시뮬레이터 입력창
            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it },
                label = { Text("테스트 이메일 (debug 빌드 전용)") },
                placeholder = { Text("test@example.com") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                if (isFirebaseAvailable) {
                    isLoading = true
                    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                    if (resId == 0) {
                        // OAuth 클라이언트 미구성: 가짜 ID로 진행하지 않고 명확히 실패시킨다.
                        isLoading = false
                        Toast.makeText(context, "Google 로그인 설정(OAuth Client)이 완료되지 않았습니다. 관리자에게 문의해주세요.", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    val webClientId = context.getString(resId)

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
                } else if (allowSimulatedLogin) {
                    showSimulatedLoginDialog = true
                } else {
                    Toast.makeText(context, "서비스 초기화에 실패했습니다. 앱을 다시 설치하거나 관리자에게 문의해주세요.", Toast.LENGTH_LONG).show()
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
                    text = when {
                        isFirebaseAvailable -> "Google 계정으로 안전하게 시작하기"
                        allowSimulatedLogin -> "테스트 계정으로 시작하기 (debug)"
                        else -> "Google 계정으로 안전하게 시작하기"
                    },
                    fontSize = 16.sp
                )
            }
        }
    }

    if (showSimulatedLoginDialog && allowSimulatedLogin) {
        AlertDialog(
            onDismissRequest = { showSimulatedLoginDialog = false },
            title = { Text("테스트 계정 선택 (debug 전용)") },
            text = {
                Column {
                    Text("Firebase가 구성되지 않은 debug 빌드입니다. 로컬 테스트용 이메일을 입력하세요. 서버 기능(AI 분석/포인트/쿠폰)은 동작하지 않습니다.")
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("이메일 직접 입력...") },
                        placeholder = { Text("test@example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (emailInput.isNotBlank()) {
                                showSimulatedLoginDialog = false
                                isLoading = true
                                coroutineScope.launch {
                                    GlobalState.userEmail = emailInput
                                    if (!isFirebaseAvailable) {
                                        GlobalState.isAdmin = emailInput.contains("admin") || emailInput == "mjayj9@gmail.com" || emailInput == "2025186@snu.ms.kr"
                                    }
                                    Toast.makeText(context, "테스트 로그인: $emailInput", Toast.LENGTH_SHORT).show()
                                    handleLoginSuccess(emailInput)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = emailInput.isNotBlank()
                    ) {
                        Text("테스트 계정으로 로그인")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSimulatedLoginDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    if (showPrivacyAgreementDialog) {
        AlertDialog(
            onDismissRequest = {
                showPrivacyAgreementDialog = false
                isLoading = false
            },
            title = { Text("개인정보 수집 및 이용 동의 (필수)") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("에코소트 서비스 가입 및 이용을 위해 아래와 같이 개인정보를 수집하고 이용합니다.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("1. 수집하는 개인정보 항목", fontWeight = FontWeight.Bold)
                    Text("이메일 주소, 소속 아파트 단지 정보, 서비스 이용 기록, 분리배출 촬영 사진(재질 판독 및 장소 인증)")
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("2. 개인정보 수집 및 이용 목적", fontWeight = FontWeight.Bold)
                    Text("AI 기반 배달 쓰레기 오염도 분석 및 분리배출 인증 처리, 리워드 에코 포인트 적립 및 제휴처 상품권 교환 서비스 제공")
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("3. 개인정보 보유 및 이용 기간", fontWeight = FontWeight.Bold)
                    Text("회원 탈퇴 시 즉시 파기 (단, 법령에 특별한 규정이 있는 경우 관련 법령에 따름)")
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("※ 귀하는 개인정보 수집 및 이용에 대한 동의를 거부할 권리가 있습니다. 단, 동의 거부 시 에코소트 서비스 가입 및 이용이 제한됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPrivacyAgreementDialog = false
                        isLoading = false
                        onLoginSuccess(true)
                    }
                ) {
                    Text("동의하고 시작하기")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPrivacyAgreementDialog = false
                        isLoading = false
                        Toast.makeText(context, "개인정보 수집에 동의하셔야 가입이 가능합니다.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("동의하지 않음")
                }
            }
        )
    }
}
