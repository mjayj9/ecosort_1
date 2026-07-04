package com.example.ui.screens

import android.location.Geocoder
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.GlobalState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

data class Apartment(val code: String, val name: String, val address: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApartmentSelectionScreen(onApartmentSelected: (String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var allApartments by remember { mutableStateOf<List<Apartment>>(emptyList()) }
    var filteredApartments by remember { mutableStateOf<List<Apartment>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    
    var isDataLoading by remember { mutableStateOf(true) }
    var isGeocoding by remember { mutableStateOf(false) }

    // 비동기로 JSON 로드 및 파싱
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val assetManager = context.assets
                val inputStream = assetManager.open("apartments.json")
                val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                val jsonStr = reader.use { it.readText() }
                val jsonArray = JSONArray(jsonStr)
                
                val list = mutableListOf<Apartment>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        Apartment(
                            code = obj.getString("c"),
                            name = obj.getString("n"),
                            address = obj.getString("a")
                        )
                    )
                }
                allApartments = list
                filteredApartments = list.take(50) // 초기엔 50개만 노출
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isDataLoading = false
            }
        }
    }

    // 검색어 필터링
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            filteredApartments = allApartments.take(50)
        } else {
            // 성능 최적화: 검색어와 대조하여 상위 50개만 필터링해서 바로 반환
            filteredApartments = allApartments.filter {
                it.name.contains(searchQuery, ignoreCase = true) || 
                it.address.contains(searchQuery, ignoreCase = true)
            }.take(50)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("우리 아파트 단지 선택") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isDataLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("대한민국 아파트 단지 정보 로드 중...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("아파트 이름 또는 주소 검색 (예: 에코팰리스)") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "검색") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (searchQuery.isBlank()) "인기 아파트 단지 목록" else "검색 결과 (최대 50개 표시)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (filteredApartments.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("검색 결과와 일치하는 아파트 단지가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredApartments) { apt ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isGeocoding) {
                                            isGeocoding = true
                                            coroutineScope.launch {
                                                var lat = 37.5665 // 기본 서울시청 위도
                                                var lng = 126.9780 // 기본 서울시청 경도
                                                var geocodeSuccess = false
                                                
                                                if (apt.address.isNotBlank()) {
                                                    try {
                                                        // Geocoding 주소 좌표 변환
                                                        val geocoder = Geocoder(context, Locale.KOREA)
                                                        val addresses = withContext(Dispatchers.IO) {
                                                            geocoder.getFromLocationName(apt.address, 1)
                                                        }
                                                        if (!addresses.isNullOrEmpty()) {
                                                            val address = addresses[0]
                                                            lat = address.latitude
                                                            lng = address.longitude
                                                            geocodeSuccess = true
                                                        }
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                                
                                                // 전역 상태에 선택 정보 설정
                                                GlobalState.apartmentId = apt.code
                                                GlobalState.apartmentName = apt.name
                                                GlobalState.apartmentAddress = apt.address
                                                GlobalState.apartmentLatitude = lat
                                                GlobalState.apartmentLongitude = lng
                                                
                                                isGeocoding = false
                                                if (geocodeSuccess) {
                                                    Toast.makeText(context, "${apt.name} 단지가 선택되었습니다.", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "${apt.name} 선택 (위치 확인 불가로 기본 좌표 할당)", Toast.LENGTH_LONG).show()
                                                }
                                                onApartmentSelected(apt.name)
                                            }
                                        },
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Home,
                                                contentDescription = "아파트 아이콘",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column {
                                                Text(text = apt.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(text = apt.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Icon(Icons.Default.ArrowForward, contentDescription = "선택")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isGeocoding) {
                Surface(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("아파트 단지 위치 좌표 확인 중...", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
    }
}
