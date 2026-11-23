# Idempotency-Key 실습: 타임아웃 · 재시도 · 중복 저장 방지

클라이언트 **타임아웃 + 재시도** 때문에 생기는 **중복 주문 저장** 문제를 재현하고,
`Idempotency-Key` 헤더로 막는 과정을 정리한 실습 프로젝트입니다.

## 기술 스택

- Java 21 (가상 스레드)
- Spring Boot 4.1 (Web MVC, Data JPA), Spring Framework 7 `RestClient`
- MySQL 8.4 (Docker)
- Lombok, JUnit 5, AssertJ

## 실습 흐름 한눈에 보기

| 단계 | 내용 | 결과 |
|---|---|---|
| 1 | `POST /orders`: 3초 지연 후 주문 저장 | 정상 동작 |
| 2 | 클라이언트 응답 대기 1초 + 재시도 2회 (총 3회) | 클라이언트는 3번 다 실패, **서버엔 주문 3건 저장** (중복 발생) |
| 3 | `Idempotency-Key` 헤더로 중복 방지 | 재시도해도 **주문 1건**, 3회차에 원래 응답 수신 |
| 4 | 가상 스레드 100개로 동시 요청 | `200 × 1`, `409 × 99`, **주문 1건** |

---

## 1. 실행 환경 준비

### MySQL (Docker)

```bash
docker run -d --name idempotency-mysql \
  -e MYSQL_ROOT_PASSWORD=<비밀번호> \
  -e MYSQL_DATABASE=idempotency \
  -p 3306:3306 mysql:8.4
```

> ⚠️ **포트 충돌 주의**
> 로컬(Homebrew) MySQL이 `127.0.0.1:3306`에서 실행 중이면, 앱의 `localhost:3306` 접속이 Docker가 아니라 로컬 MySQL로 붙습니다.
> 이때 `Unknown database 'idempotency'` 같은 에러가 납니다.
> - 해결 1: `brew services stop mysql` (데이터는 그대로 남고, `brew services start mysql`로 복구됩니다)
> - 해결 2: 컨테이너를 `-p 3307:3306`으로 띄우고 URL 포트를 3307로 변경합니다.

### 테이블

`ddl-auto: validate`라서 테이블은 직접 만들어야 합니다.

```sql
CREATE TABLE orders (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    product_name VARCHAR(100) NOT NULL,
    quantity     INT          NOT NULL,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE idempotency_keys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    response_status INT          DEFAULT NULL,
    response_body   TEXT,
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY idempotency_key (idempotency_key)
);
```

### application.yaml

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/idempotency
    username: root
    password: <비밀번호>
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true
```

### 앱 실행

```bash
./gradlew bootRun
```

IntelliJ에서는 `StudyApplication`을 실행하면 됩니다.
프로젝트는 `build.gradle` → **Open as Project**로 열고, SDK와 Gradle JVM을 **21**로 설정합니다.

---

## 2. 프로젝트 구조

```
src/main/java/com/study/study
├── order/
│   ├── Order.java               # orders 엔티티 (created_at은 DB 기본값 사용)
│   ├── OrderRepository.java
│   ├── OrderRequest.java        # { productName, quantity }
│   ├── OrderResponse.java       # { orderId, productName, quantity }
│   ├── OrderController.java     # POST /orders (Idempotency-Key 헤더 필수)
│   └── OrderService.java        # 키 선점 → 3초 처리 → 저장/완료
├── idempotency/
│   ├── IdempotencyKey.java                   # idempotency_keys 엔티티
│   ├── IdempotencyStatus.java                # PROCESSING / COMPLETED
│   ├── IdempotencyKeyRepository.java
│   ├── IdempotencyConflictException.java     # → 409
│   └── IdempotencyKeyMismatchException.java  # → 422
└── client/
    ├── RestClientConfig.java    # 응답 대기 1초 RestClient
    ├── OrderClient.java         # 최초 1회 + 재시도 2회, 같은 키 재사용
    └── ClientController.java    # POST /client/orders (클라이언트 동작 확인용)

src/test/java/com/study/study/order
└── OrderIdempotencyConcurrencyTest.java   # 가상 스레드 100개 동시성 테스트
```

---

## 3. 주문 API

```http
POST /orders
Content-Type: application/json
Idempotency-Key: 3f1c...-uuid

{ "productName": "Keyboard", "quantity": 1 }
```

```json
{ "orderId": 1, "productName": "Keyboard", "quantity": 1 }
```

| 상황 | 응답 |
|---|---|
| 처음 들어온 키 | `200` + 주문 결과 (약 3초 소요) |
| 같은 키, 아직 처리 중 | `409 Conflict` |
| 같은 키, 이미 처리 완료 | `200` + **저장해 둔 응답 그대로** (새로 저장하지 않음) |
| 같은 키, 다른 요청 본문 | `422 Unprocessable Content` |
| `Idempotency-Key` 헤더 없음 | `400 Bad Request` |

서비스 내부에서는 `Thread.sleep(3000)`으로 느린 처리를 흉내 냅니다.

---

## 4. 문제 재현: 타임아웃 + 재시도 = 중복 저장

```java
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setReadTimeout(Duration.ofSeconds(1));   // 응답 대기 1초

RestClient restClient = RestClient.builder()
        .baseUrl("http://localhost:8080")
        .requestFactory(factory)
        .build();
```

서버는 3초가 걸리고 클라이언트는 1초만 기다리기 때문에, 매 요청이 타임아웃 납니다.
클라이언트는 최초 1회 + 재시도 2회, 총 3회를 요청합니다.

**멱등성 처리 전 결과**

```
요청 시도 1/3 → Read timed out
요청 시도 2/3 → Read timed out
요청 시도 3/3 → Read timed out
3회 모두 실패
insert into orders ...   ← 3번 실행됨
```

> **핵심:** 클라이언트 타임아웃은 서버 작업을 **취소하지 않습니다.**
> 클라이언트는 "실패"로 알지만, 서버는 요청을 끝까지 처리해서 같은 주문이 3건 저장됩니다.

### 💡 트러블슈팅: 타임아웃인데 재시도가 안 됨

처음에는 `ResourceAccessException`만 잡아서 재시도했는데, 1번만 시도하고 바로 실패했습니다.

- **원인:** `SimpleClientHttpRequestFactory`는 응답 헤더를 읽는 시점에 타임아웃이 납니다. 이 예외는 `ResourceAccessException`이 아니라 일반 `RestClientException`("Error while extracting response")에 감싸져서 올라옵니다.
- **해결:** 예외의 **cause 체인에 `IOException`(예: `SocketTimeoutException`)이 있으면** 재시도하도록 바꿨습니다. 4xx/5xx 응답은 재시도하지 않습니다.

```java
private boolean isRetryable(RestClientException e) {
    if (e instanceof HttpClientErrorException http) {
        return http.getStatusCode() == HttpStatus.CONFLICT; // 409: 처리 중 → 재시도
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof IOException) return true;          // 타임아웃 등 I/O 오류
    }
    return false;
}
```

---

## 5. 해결: Idempotency-Key

### 클라이언트

**논리적인 요청 1건에 키 1개**를 만들고, 재시도할 때도 **같은 키**를 보냅니다.

```java
String idempotencyKey = UUID.randomUUID().toString();   // 반복문 밖에서 1번만 생성
for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    ...
    .header("Idempotency-Key", idempotencyKey)
    ...
}
```

### 서버: "먼저 저장했는지 확인"으로는 부족한 이유

서버 처리는 3초, 재시도는 1초 뒤에 도착합니다.
재시도가 도착했을 때 첫 요청은 **아직 주문을 저장하지 않은 상태**입니다.
그래서 "이 키로 저장된 주문이 있나?"만 확인하면 중복을 막을 수 없습니다.

그래서 **처리 시작 시점에 키를 먼저 선점**합니다.

```
요청 도착
  │
  ├─ 1. idempotency_keys에 (key, request_hash, PROCESSING) INSERT
  │      └─ UNIQUE 위반 → 이미 들어온 요청
  │           ├─ request_hash 다름 → 422
  │           ├─ PROCESSING      → 409 (처리 중)
  │           └─ COMPLETED       → 저장된 response_body 반환
  │
  ├─ 2. Thread.sleep(3000)
  │
  ├─ 3. [하나의 트랜잭션]
  │      orders INSERT
  │      idempotency_keys → COMPLETED + response_status/response_body 저장
  │
  └─ 실패 시: 선점한 키 삭제 → 같은 키로 다시 시도 가능
```

- **동시성 보장:** 애플리케이션 락이 아니라 **DB의 UNIQUE 제약**에 맡깁니다. 동시에 같은 키가 와도 INSERT는 하나만 성공합니다.
- **request_hash:** 요청 본문의 SHA-256 해시입니다. 같은 키로 **다른 내용**을 보내는 실수를 422로 막습니다.
- **response_body 저장:** 완료된 키로 다시 요청하면 DB를 다시 처리하지 않고 저장된 응답을 그대로 돌려줍니다.
- **트랜잭션 범위:** 3초 대기는 트랜잭션 밖에 둡니다. DB 커넥션을 3초 동안 잡고 있지 않게 하려는 것입니다.

### 💡 JPA 매핑 메모

- **`status` 컬럼:** MySQL에서 `@Enumerated(STRING)`을 쓰면 Hibernate가 `ENUM` 타입 컬럼을 기대해서 `validate`가 실패할 수 있습니다. `@JdbcTypeCode(SqlTypes.VARCHAR)`로 VARCHAR 컬럼과 매핑했습니다.
- **`response_body`(TEXT):** `columnDefinition = "text"`로 매핑했습니다.

### 클라이언트 재시도 정책

| 실패 원인 | 재시도 |
|---|---|
| 타임아웃 등 I/O 오류 | O |
| 409 (같은 키 처리 중) | O |
| 그 외 4xx/5xx | X |

재시도 전에는 **1.5초씩 대기**합니다(`BACKOFF_MILLIS`).
대기 없이 재시도하면 2·3회차가 모두 409를 받습니다. 그러면 주문은 1건 저장됐는데 클라이언트는 실패로 끝납니다.

**멱등성 처리 후 결과**

```
03:44:26.618 시도 1/3 → 서버: 키 선점 성공(PROCESSING), 처리 시작
03:44:27.636 시도 1/3 실패: Read timed out
03:44:29.141 시도 2/3 → 서버: 아직 처리 중 → 409
             (서버) insert into orders ...   ← 1번만 실행
03:44:30.870 시도 3/3 → 서버: 이미 완료 → 저장된 응답 반환
03:44:30.895 요청 성공 (3회차): orderId=15
```

---

## 6. 테스트

### 수동 테스트 (curl)

**① 재시도 시나리오**
클라이언트를 거쳐 요청합니다. 약 4~5초 뒤 200이 오고, 주문은 1건만 저장됩니다.

```bash
curl -i -X POST localhost:8080/client/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Keyboard","quantity":1}'
```

**② 같은 키로 동시에 3번 요청**
기대 결과는 `200 × 1`, `409 × 2`입니다.

```bash
KEY=$(uuidgen)
for i in 1 2 3; do
  curl -s -w "  → HTTP %{http_code}\n" -X POST localhost:8080/orders \
    -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
    -d '{"productName":"Keyboard","quantity":1}' &
done; wait
```

**③ 완료 후 같은 키로 다시 요청**
같은 `orderId`가 반환되는지 확인합니다.

```bash
curl -s -w "  → HTTP %{http_code}\n" -X POST localhost:8080/orders \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"productName":"Keyboard","quantity":1}'
```

**④ 같은 키, 다른 본문 → 422**

```bash
curl -s -w "  → HTTP %{http_code}\n" -X POST localhost:8080/orders \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"productName":"Mouse","quantity":2}'
```

**⑤ 헤더 없음 → 400**

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Keyboard","quantity":1}'
```

**DB 확인**

```bash
docker exec -it idempotency-mysql mysql -uroot -p idempotency -e "
  SELECT COUNT(*) AS orders_count FROM orders;
  SELECT idempotency_key, status, response_status, response_body
  FROM idempotency_keys ORDER BY id DESC LIMIT 5;"
```

### 동시성 테스트: 가상 스레드 100개

`OrderIdempotencyConcurrencyTest`는 다음 조건으로 요청합니다.

- 가상 스레드 **100개** 생성 (`Executors.newVirtualThreadPerTaskExecutor()`)
- **동일한** 멱등성 키
- **동일한** 요청 본문
- `CountDownLatch`로 100개를 **동시에** 출발시켜 `POST /orders` 호출

```java
CountDownLatch ready = new CountDownLatch(100); // 100개 스레드 준비 완료 대기
CountDownLatch start = new CountDownLatch(1);   // 동시 출발 신호

try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
            ready.countDown();
            start.await();
            int status = postOrder(idempotencyKey, request);
            ...
        });
    }
    ready.await();
    start.countDown(); // 출발!
}
```

**검증 항목**

1. 200 응답이 정확히 1개, 409 응답이 99개
2. `orders`가 정확히 1건 증가
3. 해당 키의 상태가 `COMPLETED`

**실행**

```bash
./gradlew test --tests '*OrderIdempotencyConcurrencyTest' -i | grep -E "동시성 테스트|BUILD"
```

**결과**

```
[동시성 테스트] 응답 상태코드별 개수: {200=1, 409=99}
[동시성 테스트] orders: 7 → 8
BUILD SUCCESSFUL
```

> 실제 서버(랜덤 포트)와 실제 MySQL을 사용하는 통합 테스트입니다. 실행할 때마다 주문 1건과 키 1건이 DB에 남습니다.
> 검증은 "증가량" 기준이라 반복 실행해도 문제없습니다.

---

## 7. 한계와 다음 단계

- **PROCESSING 상태로 멈춘 키:** 처리 중에 서버가 죽으면 키가 `PROCESSING`으로 남아, 그 키로는 계속 409만 받습니다. 오래된 `PROCESSING` 키를 정리하는 **만료(TTL) 처리**가 필요합니다.
- **키 보관 기간:** 완료된 키도 계속 쌓이므로 보관 기간을 정해 주기적으로 삭제해야 합니다.
- **request_hash 기준:** 지금은 `productName:quantity` 문자열의 해시입니다. 필드가 늘어나면 요청 본문 전체를 정규화해서 해시해야 합니다.
- **멀티 서버 환경:** DB UNIQUE 제약 방식은 서버가 여러 대여도 같은 DB를 쓰면 그대로 동작합니다. 트래픽이 크면 Redis `SET NX` 같은 방식도 검토할 수 있습니다.
