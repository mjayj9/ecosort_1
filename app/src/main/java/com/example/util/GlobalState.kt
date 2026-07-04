package com.example.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object GlobalState {
    var userEmail by mutableStateOf("")
    var apartmentId by mutableStateOf("")
    var apartmentName by mutableStateOf("")
    var apartmentAddress by mutableStateOf("")
    var apartmentLatitude by mutableStateOf(0.0)
    var apartmentLongitude by mutableStateOf(0.0)
    var currentPoints by mutableIntStateOf(0)
    var targetGoal by mutableIntStateOf(10)
    var currentCount by mutableIntStateOf(0)
    var totalAppRecycled by mutableIntStateOf(12450)
    
    val isAdmin: Boolean
        get() = userEmail == "mjayj9@gmail.com" || userEmail == "2025186@snu.ms.kr"
    
    // 악용 방지용 일일 한도 및 이미지 해시 중복 저장 관리
    var lastActiveDate by mutableStateOf("")
    var dailyCount by mutableIntStateOf(0)
    val usedHashes = mutableSetOf<String>()

    fun canRecycleToday(): Boolean {
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        if (lastActiveDate != todayStr) {
            lastActiveDate = todayStr
            dailyCount = 0
        }
        return dailyCount < 10 // 하루 10회 제한
    }
        
    fun addRecycle(material: String, isSuccess: Boolean) {
        if (isSuccess && canRecycleToday()) {
            dailyCount++
            currentCount++
            totalAppRecycled++
            
            // 기본 보상
            var reward = 50
            
            // 목표 달성 시 추가 보상 (목표치에 비례)
            if (currentCount == targetGoal) {
                reward += targetGoal * 20 // 예: 10개면 200 추가, 100개면 2000 추가
                targetGoal += 10 // 목표 상향
            }
            
            currentPoints += reward
        }
    }
}
