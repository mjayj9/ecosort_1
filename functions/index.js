const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");

// 배열의 요소를 무작위로 섞는 헬퍼 함수 (피셔-예이츠 셔플)
function shuffleArray(array) {
  const arr = [...array];
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

// 2세대 HTTPS Cloud Function: 프록시 서버 역할 (API 키 풀 및 자동 롤링/재시도 내장)
exports.analyzeImage = onRequest({ cors: true, timeoutSeconds: 120 }, async (req, res) => {
  try {
    // 1. 환경 변수에서 쉼표로 구분된 API 키 풀 로드
    const apiKeyString = process.env.GEMINI_API_KEYS;
    if (!apiKeyString) {
      logger.error("GEMINI_API_KEYS is not set in environment variables.");
      res.status(500).send("Server API Key configuration error.");
      return;
    }

    const apiKeyPool = apiKeyString.split(",")
      .map(k => k.trim())
      .filter(k => k.length > 0);

    if (apiKeyPool.length === 0) {
      logger.error("API key pool is empty.");
      res.status(500).send("Server API Key pool is empty.");
      return;
    }

    // 2. 키 풀을 무작위로 섞어서 순차적으로 시도
    const keysToTry = shuffleArray(apiKeyPool);
    let lastError = null;

    for (let i = 0; i < keysToTry.length; i++) {
      const apiKey = keysToTry[i];
      const maskedKey = apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length - 4);
      
      try {
        logger.info(`Attempting Gemini API call (Key ${i + 1}/${keysToTry.length}: ${maskedKey})`);
        
        const response = await fetch(
          `https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${apiKey}`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json"
            },
            body: JSON.stringify(req.body)
          }
        );

        // API 호출 성공
        if (response.ok) {
          const data = await response.json();
          res.json(data);
          return; // 성공 시 즉시 종료
        }

        const errorText = await response.text();
        logger.warn(`Gemini API key failed (${maskedKey}): Status ${response.status}. Response: ${errorText}`);

        // 429 (Rate Limit) 혹은 5xx 서버 오류인 경우에만 다른 키로 계속 진행
        if (response.status === 429 || response.status >= 500) {
          lastError = { status: response.status, text: errorText };
          continue; 
        } else {
          // 400 Bad Request 등 클라이언트 데이터 포맷 에러는 재시도 없이 즉시 반환
          res.status(response.status).send(errorText);
          return;
        }
      } catch (err) {
        logger.error(`Network or parsing error with key ${maskedKey}:`, err);
        lastError = err;
        continue;
      }
    }

    // 모든 키를 시도했으나 실패한 경우
    logger.error("All Gemini API keys in the pool failed.");
    const errMsg = lastError ? (lastError.text || lastError.message) : "Unknown error";
    res.status(500).send(`All API keys failed. Last error: ${errMsg}`);
  } catch (error) {
    logger.error("Fatal function execution error:", error);
    res.status(500).send(`Server error: ${error.message}`);
  }
});
