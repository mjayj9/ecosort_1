const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");

// 2세대 HTTPS Cloud Function: 프록시 서버 역할 (요청을 통째로 전달)
exports.analyzeImage = onRequest({ cors: true, timeoutSeconds: 120 }, async (req, res) => {
  try {
    // 1. Firebase Functions 환경변수에서 GEMINI_API_KEY 로드
    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) {
      logger.error("GEMINI_API_KEY is not set in environment variables.");
      res.status(500).send("Server API Key configuration error.");
      return;
    }

    // 2. 클라이언트가 보낸 바디(GenerateContentRequest 형식)를 그대로 Gemini API로 전달
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

    if (!response.ok) {
      const errorText = await response.text();
      logger.error("Gemini API error response:", errorText);
      res.status(response.status).send(`Gemini API error: ${errorText}`);
      return;
    }

    const data = await response.json();
    res.json(data);
  } catch (error) {
    logger.error("Fatal function execution error:", error);
    res.status(500).send(`Server error: ${error.message}`);
  }
});
