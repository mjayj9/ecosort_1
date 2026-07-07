# 에코소트 (EcoSort)

AI 기반 배달 쓰레기 분리배출 도우미. 사진으로 오염도를 판독하고, 2차 배출 인증을 거쳐
에코 포인트를 적립하고, 단지별 리더보드와 포인트샵(쿠폰 교환)을 제공한다.

## 보안 아키텍처 (중요)

```
Android 앱 ──(Firebase Auth ID token 자동 첨부)──▶ Cloud Functions ──▶ Gemini API
                                                        │
                                                        └──(Admin SDK 트랜잭션)──▶ Firestore
```

- **앱에는 Gemini API 키/모델명/프롬프트가 존재하지 않는다.** 앱은 Callable Functions
  (`analyzeImage`, `verifyDisposal`, `grantPoints`, `redeemCoupon`, `deleteAccount`)만 호출한다.
- Gemini API 키는 **Firebase Secret Manager**(`GEMINI_API_KEY`)에만 저장한다.
- 포인트 지급, 쿠폰 발급, 단지 랭킹 증가는 **서버 트랜잭션 전용**이며,
  `firestore.rules`가 클라이언트 쓰기를 차단한다 (`pointLedger` 불변 원장 포함).
- 관리자 권한은 이메일 비교가 아니라 **Firebase custom claim(`admin: true`)** 으로만 판정한다.
- 시뮬레이션(테스트) 로그인은 **debug 빌드 + Firebase 미구성 환경에서만** 동작한다.
- 회원 탈퇴는 `deleteAccount` 함수가 Firestore 개인정보 삭제/익명화 후 Auth 계정을 삭제한다.

## Firestore 컬렉션

| 컬렉션 | 용도 | 클라이언트 권한 |
|---|---|---|
| `users/{uid}` | 프로필(포인트 포함) | 본인 읽기 / `apartmentId`,`displayName`만 쓰기 |
| `verifications/{sha256}` | 배출 인증 기록(중복 차단 근거) | 본인 것 읽기 전용 |
| `pointLedger/{entryId}` | 포인트 증감 불변 원장 | 본인 것 읽기 전용 |
| `apartments/{apartmentId}` | 단지 리더보드 | 읽기 전용 |
| `apartmentStats/{aptId_yyyyMM}` | 월별 단지 집계 | 읽기 전용 |
| `couponInventory/{couponId}` | 실쿠폰 재고 | 접근 금지 (서버 전용) |
| `redemptions/{redemptionId}` | 쿠폰 교환 내역 | 본인 것 읽기 전용 |
| `scans/{scanId}`, `usage/{uid_date}` | 분석 요약 / 일일 한도 | 서버 전용 |

## 로컬 빌드 (Android)

**필요:** Android Studio (JDK 17+ 포함), Firebase 프로젝트의 `app/google-services.json`

```bash
./gradlew assembleDebug   # 디버그 빌드
./gradlew test            # 단위 테스트
./gradlew lint            # 린트
```

앱 빌드에 API 키는 필요 없다. `.env` 파일도 앱에는 필요 없다 (`.env.example` 참고).

## 서버 배포 (Cloud Functions)

```bash
cd functions
npm install

# 1) Gemini API 키를 Secret Manager에 등록 (앱/저장소에 절대 넣지 말 것)
firebase functions:secrets:set GEMINI_API_KEY

# 2) 함수 + Firestore 규칙 + 복합 인덱스 배포
#    (인덱스를 빼면 배출 인증/쿠폰 교환의 서버 쿼리가 런타임에 실패한다)
firebase deploy --only functions,firestore:rules,firestore:indexes
```

> `firestore.indexes.json`에는 `verifications(uid, createdAt)`,
> `couponInventory(itemId, status)` 복합 인덱스가 정의되어 있으며,
> 각각 `verifyDisposal`의 최근 인증 조회와 `redeemCoupon`의 재고 조회에 필요하다.

## 관리자 지정 (custom claim)

관리자는 콘솔이 아니라 Admin SDK 스크립트로 claim을 부여한다:

```js
// Node.js (서비스 계정 자격증명 필요)
const admin = require("firebase-admin");
admin.initializeApp();
admin.auth().getUserByEmail("admin@example.com")
  .then(u => admin.auth().setCustomUserClaims(u.uid, { admin: true }));
```

## 남은 운영 체크리스트

- [ ] 과거 노출된 Gemini API 키 전부 revoke (Google AI Studio / Cloud Console)
- [ ] Firebase 콘솔에서 Android OAuth Client 생성 (`google-services.json`의 `oauth_client` 채우기)
- [ ] App Check(Play Integrity) 등록 후 functions의 `enforceAppCheck: true` 활성화
- [ ] 실쿠폰 재고를 `couponInventory`에 등록 (없으면 DEMO 워터마크 쿠폰 발급)
