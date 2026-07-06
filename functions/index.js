const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

// Gemini API 키는 절대 클라이언트에 내려가지 않고 Secret Manager에서만 읽는다.
// 배포 전: firebase functions:secrets:set GEMINI_API_KEY
const geminiApiKey = defineSecret("GEMINI_API_KEY");

const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-3-flash-preview";
const GEMINI_ENDPOINT = (model) =>
  `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;

// 이미지 payload 상한 (base64 문자열 길이 기준, 약 3MB 원본)
const MAX_IMAGE_BASE64_LENGTH = 4 * 1024 * 1024;
// 어뷰징 방지 서버 한도
const DAILY_ANALYZE_LIMIT = 30;
const DAILY_VERIFY_LIMIT = 10;
const MIN_SECONDS_BETWEEN_VERIFY = 3;
const BASE_REWARD_POINTS = 50;
const MAX_ADMIN_GRANT = 10000;

// 서버가 관리하는 포인트샵 카탈로그 (클라이언트 표시용 이름/가격과 반드시 일치)
const COUPON_CATALOG = {
  cu1000: { name: "CU 모바일 상품권 1,000원권", cost: 5000 },
  gs2000: { name: "GS25 모바일 상품권 2,000원권", cost: 9500 },
  mega_americano: { name: "메가커피 아메리카노(HOT)", cost: 10000 },
};

// ---------------------------------------------------------------------------
// 공통 유틸
// ---------------------------------------------------------------------------

function requireAuth(request) {
  // TODO(App Check): 콘솔에서 App Check(Play Integrity) 등록 후 각 onCall 옵션에
  // enforceAppCheck: true 를 추가해 변조 앱/스크립트 호출을 차단할 것.
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
  }
  return request.auth.uid;
}

function requireAdmin(request) {
  const uid = requireAuth(request);
  if (request.auth.token.admin !== true) {
    throw new HttpsError("permission-denied", "관리자 권한이 필요합니다.");
  }
  return uid;
}

function validateImage(base64, fieldName) {
  if (typeof base64 !== "string" || base64.length === 0) {
    throw new HttpsError("invalid-argument", `${fieldName} 이미지가 없습니다.`);
  }
  if (base64.length > MAX_IMAGE_BASE64_LENGTH) {
    throw new HttpsError("invalid-argument", "이미지가 너무 큽니다. (최대 약 3MB)");
  }
  return base64;
}

function todayKey() {
  // Asia/Seoul 기준 일자
  return new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" }).replace(/-/g, "");
}

function currentPeriodId() {
  // 월 단위 집계 (yyyyMM)
  return todayKey().substring(0, 6);
}

// usage/{uid_yyyymmdd} 문서 기반 사용자별 일일 호출 한도 (서버 기록이므로 재설치로 초기화 불가)
async function enforceDailyLimit(uid, kind, limit) {
  const ref = db.collection("usage").doc(`${uid}_${todayKey()}`);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const count = snap.exists ? snap.get(kind) || 0 : 0;
    if (count >= limit) {
      throw new HttpsError(
        "resource-exhausted",
        `오늘 ${kind === "verify" ? "인증" : "분석"} 한도(${limit}회)를 초과했습니다. 내일 다시 시도해주세요.`
      );
    }
    tx.set(ref, { [kind]: count + 1, updatedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
  });
}

// Gemini 호출: 서버에서만 프롬프트를 만들고 JSON schema를 강제한다.
async function callGemini(parts, responseSchema) {
  const body = {
    contents: [{ parts }],
    generationConfig: {
      temperature: 0.2,
      responseMimeType: "application/json",
      responseSchema,
    },
  };

  let response;
  try {
    response = await fetch(GEMINI_ENDPOINT(GEMINI_MODEL), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": geminiApiKey.value(),
      },
      body: JSON.stringify(body),
    });
  } catch (err) {
    logger.error("Gemini network error", err);
    throw new HttpsError("unavailable", "AI 서버 연결에 실패했습니다. 잠시 후 다시 시도해주세요.");
  }

  if (!response.ok) {
    const errText = await response.text().catch(() => "");
    logger.error(`Gemini API error: status=${response.status} body=${errText.substring(0, 500)}`);
    if (response.status === 429) {
      throw new HttpsError("resource-exhausted", "AI 분석 요청이 많습니다. 잠시 후 다시 시도해주세요.");
    }
    throw new HttpsError("internal", "AI 분석 중 오류가 발생했습니다.");
  }

  const data = await response.json();
  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) {
    throw new HttpsError("internal", "AI 응답이 비어 있습니다. 다시 시도해주세요.");
  }

  // schema를 강제했지만 방어적으로 코드펜스 제거 후 파싱, 실패 시 앱 크래시 대신 오류 반환
  const clean = text.replace(/```json/g, "").replace(/```/g, "").trim();
  try {
    return JSON.parse(clean);
  } catch (err) {
    logger.warn("Gemini JSON parse failure", { raw: clean.substring(0, 300) });
    throw new HttpsError("internal", "AI 응답 해석에 실패했습니다. 다시 촬영해 주세요.");
  }
}

function sha256(str) {
  return crypto.createHash("sha256").update(str).digest("hex");
}

// ---------------------------------------------------------------------------
// 1. analyzeImage: 1차 오염도 분석 (인증 필수, 프롬프트/schema는 서버 소유)
// ---------------------------------------------------------------------------

const ANALYZE_SCHEMA = {
  type: "object",
  properties: {
    "판독_성공": { type: "boolean" },
    "재질": { type: "string" },
    "오염도_퍼센트": { type: "integer" },
    "등급": { type: "integer" },
    "오염부분_좌표": {
      type: "object",
      properties: {
        ymin: { type: "number" },
        xmin: { type: "number" },
        ymax: { type: "number" },
        xmax: { type: "number" },
      },
    },
    "상태": { type: "string" },
    "피드백": { type: "string" },
    "헹굼_권장여부": { type: "boolean" },
    "배출방법": { type: "string" },
    "불가_사유": { type: "string" },
  },
  required: ["판독_성공"],
};

const ANALYZE_PROMPT = `
너는 배달 쓰레기 분리배출 전문가 AI야.
사용자가 찍은 배달 쓰레기 사진을 보고, 아래 정보를 정확히 JSON 형태로만 반환해 줘.
만약 사진이 흔들렸거나, 쓰레기가 명확히 보이지 않거나, 판독하기 불가능하다면 "판독_성공"을 false로 두고 "불가_사유" 필드에 원인과 다시 찍어달라는 멘트를 적어줘.

[중요 촬영 조건 검증]
* 반드시 용기 안쪽(음식물이 닿았던 내부 면적 및 오염 상태)이 카메라에 잘 보이도록 열린 상태로 촬영되어야 합니다.
* 만약 용기의 뚜껑이 닫혀 있어서 내부가 전혀 보이지 않거나, 외부/밑면만 찍어서 안쪽 오염도 확인이 완전히 불가능한 구도의 사진이라면 "판독_성공"을 false로 두고, "불가_사유" 필드에 "용기 내부가 보이지 않습니다. 뚜껑을 열거나 내부가 보이도록 다시 촬영해 주세요."라고 구체적인 거절 사유를 명시해 주세요.

[오염도 및 통과 등급 기준]
[등급 0: 통과] ──> 전체 면적의 1% 미만 오염 (투명, 기포, 단순 물방울)
[등급 1: 통과] ──> 전체 면적의 5% 미만 오염 (옅은 물자국, 쉽게 지워지는 먼지)
─── [ 자동 거절(Reject) 기준선 ] ───
[등급 2: 거절] ──> 전체 면적의 5% 이상 오염 또는 국소 부위의 짙은 얼룩 (양념, 고추기름때)
[등급 3: 거절] ──> 내용물이 남아있음 (잔여 음료수, 고체 음식물 찌꺼기)

좌표는 0.0 ~ 1.0 비율로, 가장 오염이 심한 곳 혹은 객체 전체 바운딩 박스를 의미해.
오염도가 낮다면 0~5 퍼센트로 판단하고(등급 0~1), 오염도가 높다면 그 이상으로 판단해(등급 2~3).
피드백 필드에는 어떻게 닦아야 하는지, 또는 왜 버려야 하는지 구체적인 팁을 제공해줘.
`.trim();

exports.analyzeImage = onCall(
  { secrets: [geminiApiKey], timeoutSeconds: 120, memory: "512MiB" },
  async (request) => {
    const uid = requireAuth(request);
    const image = validateImage(request.data?.image, "분석 대상");

    await enforceDailyLimit(uid, "analyze", DAILY_ANALYZE_LIMIT);

    const result = await callGemini(
      [
        { text: ANALYZE_PROMPT },
        { inlineData: { mimeType: "image/jpeg", data: image } },
      ],
      ANALYZE_SCHEMA
    );

    // 분석 요약을 남겨 2차 인증(verifyDisposal)에서 1차 분석 존재 여부를 검증할 수 있게 한다.
    const imageHash = sha256(image);
    await db.collection("scans").doc(`${uid}_${imageHash.substring(0, 24)}`).set({
      uid,
      imageHash,
      grade: result["등급"] ?? null,
      material: result["재질"] ?? null,
      success: result["판독_성공"] === true,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return { ...result, scanId: `${uid}_${imageHash.substring(0, 24)}` };
  }
);

// ---------------------------------------------------------------------------
// 2. verifyDisposal: 2차 배출 인증 + 포인트 지급 (서버 트랜잭션 단일 경로)
// ---------------------------------------------------------------------------

const VERIFY_SCHEMA = {
  type: "object",
  properties: {
    "통과": { type: "boolean" },
    "사유": { type: "string" },
  },
  required: ["통과", "사유"],
};

const VERIFY_PROMPT = `
너는 분리배출 인증 어뷰징(악용)을 잡아내는 보안 AI 전문가야.

[미션]
사용자가 첫 번째 이미지(스캔 단계)에서 촬영한 쓰레기 용품(이미지 1)이, 두 번째 이미지(배출 단계)의 분리수거장/쓰레기통 사진(이미지 2)에서 실제로 투입되고 있거나 버려진 상태인지 교차 검증해줘.

[어뷰징 판정 가이드라인]
1. 쓰레기 일치 여부 대조: 이미지 1의 쓰레기(용기의 크기, 형태, 색상, 고유 라벨 등 시각적 특징)가 이미지 2의 배출장 사진 속에 반드시 포함되어 있어야 해. 전혀 엉뚱한 쓰레기통만 찍어 보내거나 이미지 1의 쓰레기가 이미지 2에서 확인되지 않으면 거절("통과": false) 처리해줘.
2. 올바른 배출 공간 대조: 이미지 2는 반드시 아파트 분리수거장, 수거함, 종량제 봉투 배출구역 등 공용 쓰레기 수거장 배경이어야 해. 일반 침실, 책상, 거실 등 개인적인 실내 공간이 배경이면 거절("통과": false)해줘.
3. 이미지 도용/재사용 대조: 이미지 1과 이미지 2가 완전히 똑같은 사진이거나 쓰레기통의 단순 재탕이면 거절해줘.

"사유"에는 판정 이유를 구체적으로 한글로 적어줘.
`.trim();

exports.verifyDisposal = onCall(
  { secrets: [geminiApiKey], timeoutSeconds: 120, memory: "512MiB" },
  async (request) => {
    const uid = requireAuth(request);
    const wasteImage = validateImage(request.data?.wasteImage, "1차 스캔");
    const disposalImage = validateImage(request.data?.disposalImage, "배출 인증");
    const apartmentId = typeof request.data?.apartmentId === "string" ? request.data.apartmentId.trim() : "";
    const source = request.data?.source === "gallery" ? "gallery" : "camera";

    if (!apartmentId || apartmentId.length > 100) {
      throw new HttpsError("invalid-argument", "소속 단지 정보가 없습니다. 설정에서 단지를 먼저 선택해주세요.");
    }

    const wasteHash = sha256(wasteImage);
    const disposalHash = sha256(disposalImage);

    if (wasteHash === disposalHash) {
      throw new HttpsError("failed-precondition", "동일한 사진으로는 배출 인증을 할 수 없습니다.");
    }

    // 중복 제출 차단: 인증 사진 해시를 verification record의 문서 ID로 사용 (전역 불변 기록)
    const verificationRef = db.collection("verifications").doc(disposalHash);
    const existing = await verificationRef.get();
    if (existing.exists) {
      throw new HttpsError("already-exists", "이미 배출 인증에 사용된 사진입니다. 중복 제출할 수 없습니다.");
    }

    // 최근 인증과의 최소 시간 간격 검증 (서버 기록 기반)
    const recent = await db
      .collection("verifications")
      .where("uid", "==", uid)
      .orderBy("createdAt", "desc")
      .limit(1)
      .get();
    if (!recent.empty) {
      const last = recent.docs[0].get("createdAt");
      if (last && Date.now() - last.toMillis() < MIN_SECONDS_BETWEEN_VERIFY * 1000) {
        throw new HttpsError("failed-precondition", "인증 요청이 너무 빠릅니다. 잠시 후 다시 시도해주세요.");
      }
    }

    await enforceDailyLimit(uid, "verify", DAILY_VERIFY_LIMIT);

    const aiResult = await callGemini(
      [
        { text: VERIFY_PROMPT },
        { inlineData: { mimeType: "image/jpeg", data: wasteImage } },
        { inlineData: { mimeType: "image/jpeg", data: disposalImage } },
      ],
      VERIFY_SCHEMA
    );

    const passed = aiResult["통과"] === true;
    const reason = String(aiResult["사유"] || "");

    if (!passed) {
      await verificationRef.set({
        uid,
        apartmentId,
        source,
        wasteHash,
        status: "rejected",
        reason,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      return { "통과": false, "사유": reason || "배출 인증이 거절되었습니다." };
    }

    // 통과: 포인트 지급 + 원장 기록 + 단지 랭킹 반영을 하나의 트랜잭션으로 처리
    const userRef = db.collection("users").doc(uid);
    const aptRef = db.collection("apartments").doc(apartmentId);
    const statsRef = db.collection("apartmentStats").doc(`${apartmentId}_${currentPeriodId()}`);
    const ledgerRef = db.collection("pointLedger").doc();
    const reward = BASE_REWARD_POINTS;

    const totalPoints = await db.runTransaction(async (tx) => {
      const userSnap = await tx.get(userRef);
      const currentPoints = userSnap.exists ? userSnap.get("points") || 0 : 0;
      const newTotal = currentPoints + reward;

      tx.set(
        userRef,
        {
          points: newTotal,
          apartmentId,
          totalVerified: admin.firestore.FieldValue.increment(1),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
      tx.set(
        aptRef,
        {
          apartmentName: apartmentId,
          totalRecycled: admin.firestore.FieldValue.increment(1),
        },
        { merge: true }
      );
      tx.set(
        statsRef,
        {
          apartmentId,
          periodId: currentPeriodId(),
          totalRecycled: admin.firestore.FieldValue.increment(1),
        },
        { merge: true }
      );
      tx.set(verificationRef, {
        uid,
        apartmentId,
        source,
        wasteHash,
        status: "approved",
        reason,
        pointsAwarded: reward,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      tx.set(ledgerRef, {
        uid,
        type: "verify_reward",
        amount: reward,
        refId: verificationRef.id,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      return newTotal;
    });

    return { "통과": true, "사유": reason, "지급포인트": reward, "총포인트": totalPoints };
  }
);

// ---------------------------------------------------------------------------
// 3. grantPoints: 관리자 전용 수동 포인트 지급 (custom claim 검증)
// ---------------------------------------------------------------------------

exports.grantPoints = onCall({ timeoutSeconds: 30 }, async (request) => {
  const adminUid = requireAdmin(request);
  const targetUid = typeof request.data?.uid === "string" && request.data.uid ? request.data.uid : adminUid;
  const points = Number(request.data?.points);
  const reason = String(request.data?.reason || "admin_grant").substring(0, 200);

  if (!Number.isInteger(points) || points <= 0 || points > MAX_ADMIN_GRANT) {
    throw new HttpsError("invalid-argument", `지급 포인트는 1~${MAX_ADMIN_GRANT} 사이 정수여야 합니다.`);
  }

  const userRef = db.collection("users").doc(targetUid);
  const ledgerRef = db.collection("pointLedger").doc();

  const totalPoints = await db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const current = snap.exists ? snap.get("points") || 0 : 0;
    const newTotal = current + points;
    tx.set(userRef, { points: newTotal, updatedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
    tx.set(ledgerRef, {
      uid: targetUid,
      type: "admin_grant",
      amount: points,
      grantedBy: adminUid,
      reason,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    return newTotal;
  });

  return { success: true, totalPoints };
});

// ---------------------------------------------------------------------------
// 4. redeemCoupon: 포인트 차감 + 쿠폰 발급을 하나의 트랜잭션으로 처리
// ---------------------------------------------------------------------------

exports.redeemCoupon = onCall({ timeoutSeconds: 30 }, async (request) => {
  const uid = requireAuth(request);
  const itemId = String(request.data?.itemId || "");
  const item = COUPON_CATALOG[itemId];
  if (!item) {
    throw new HttpsError("invalid-argument", "존재하지 않는 상품입니다.");
  }

  const userRef = db.collection("users").doc(uid);
  const redemptionRef = db.collection("redemptions").doc();
  const ledgerRef = db.collection("pointLedger").doc();

  const result = await db.runTransaction(async (tx) => {
    const userSnap = await tx.get(userRef);
    const currentPoints = userSnap.exists ? userSnap.get("points") || 0 : 0;
    if (currentPoints < item.cost) {
      throw new HttpsError("failed-precondition", "포인트가 부족합니다.");
    }

    // couponInventory에서 미사용 실쿠폰을 먼저 소진하고, 재고가 없으면 DEMO 쿠폰 발급
    const inventoryQuery = db
      .collection("couponInventory")
      .where("itemId", "==", itemId)
      .where("status", "==", "available")
      .limit(1);
    const inventorySnap = await tx.get(inventoryQuery);

    let code;
    let isDemo;
    if (!inventorySnap.empty) {
      const couponDoc = inventorySnap.docs[0];
      code = couponDoc.get("code");
      isDemo = false;
      tx.update(couponDoc.ref, {
        status: "redeemed",
        redeemedBy: uid,
        redeemedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    } else {
      // 실제 제휴 쿠폰 재고가 없을 때는 데모임이 명확한 워터마크 쿠폰만 발급 (실사용 불가)
      code = `DEMO-${crypto.randomBytes(6).toString("hex").toUpperCase()}`;
      isDemo = true;
    }

    const remainingPoints = currentPoints - item.cost;
    tx.update(userRef, { points: remainingPoints, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
    tx.set(redemptionRef, {
      uid,
      itemId,
      itemName: item.name,
      cost: item.cost,
      code,
      isDemo,
      status: "issued",
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    tx.set(ledgerRef, {
      uid,
      type: "coupon_redeem",
      amount: -item.cost,
      refId: redemptionRef.id,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return { code, isDemo, remainingPoints };
  });

  return { success: true, ...result, itemName: item.name };
});

// ---------------------------------------------------------------------------
// 5. deleteAccount: Auth 삭제 + Firestore 개인정보 삭제/익명화
// ---------------------------------------------------------------------------

async function anonymizeCollection(collectionName, uid) {
  // 감사/통계 목적 기록(포인트 원장, 인증 기록)은 삭제 대신 uid를 익명화해 보존
  const snap = await db.collection(collectionName).where("uid", "==", uid).get();
  const chunks = [];
  for (let i = 0; i < snap.docs.length; i += 400) {
    chunks.push(snap.docs.slice(i, i + 400));
  }
  for (const chunk of chunks) {
    const batch = db.batch();
    for (const doc of chunk) {
      batch.update(doc.ref, { uid: "deleted", anonymizedAt: admin.firestore.FieldValue.serverTimestamp() });
    }
    await batch.commit();
  }
  return snap.size;
}

exports.deleteAccount = onCall({ timeoutSeconds: 120 }, async (request) => {
  const uid = requireAuth(request);

  // 1. 개인 식별 문서 삭제
  await db.collection("users").doc(uid).delete();

  // 2. 사용 기록 익명화 (포인트 원장/인증/교환/스캔 기록)
  await anonymizeCollection("pointLedger", uid);
  await anonymizeCollection("verifications", uid);
  await anonymizeCollection("redemptions", uid);
  await anonymizeCollection("scans", uid);

  // 3. 일일 사용량 문서 삭제
  const usagePrefix = `${uid}_`;
  const usageSnap = await db.collection("usage")
    .where(admin.firestore.FieldPath.documentId(), ">=", usagePrefix)
    .where(admin.firestore.FieldPath.documentId(), "<", usagePrefix + "\uf8ff")
    .get();
  if (!usageSnap.empty) {
    const batch = db.batch();
    usageSnap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }

  // 4. Firebase Auth 계정 삭제 (마지막에 수행해 실패 시 재시도 가능하게 함)
  await admin.auth().deleteUser(uid);

  logger.info(`Account deleted and anonymized: uid=${uid.substring(0, 8)}...`);
  return { success: true };
});
