# Backend Load/Stress Test Guide

이 문서는 `Backend/autowing_car` 서버의 현재 구조를 기준으로 **어느 정도 트래픽에서 느려지고, 어느 지점부터 장애가 날 가능성이 큰지**를 빠르게 검증하기 위한 가이드다.

## 1. 왜 이 시나리오가 필요한가?

현재 백엔드는 다음 특성 때문에 단순 기능 테스트만으로는 안정성을 판단하기 어렵다.

- 지도 조회는 요청마다 전체 노드/엣지를 다시 읽고, 각 엣지의 waypoint JSON을 반복 파싱한다.
- 경로 탐색은 A* 내부에서 노드/엣지 잠금과 DB 조회를 함께 사용하므로 동시 요청 시 지연 폭이 커질 수 있다.
- STOMP 브로커는 Spring `enableSimpleBroker()` 기반이라 애플리케이션 인스턴스 메모리와 CPU에 직접 부하가 걸린다.
- DB 풀은 Hikari `maximum-pool-size: 50` 으로 지정되어 있어, 실제 병목이 애플리케이션보다 먼저 DB connection saturation으로 나타날 수 있다.

즉, 이 프로젝트는 **HTTP API + 경로 탐색 + WebSocket fan-out + DB/MQTT 연계**가 동시에 걸리는 순간의 한계를 별도 검증해야 한다.

## 2. 우선 측정해야 할 핵심 목표

### 관찰 지표

- `p50 / p95 / p99` 응답시간
- HTTP 에러율 (`5xx`, `401/403` 비율 분리)
- DB 커넥션 풀 사용률
- JVM heap / GC pause / CPU 사용률
- WebSocket 세션 수, 초당 outbound message 수
- MQTT inbound/outbound 처리 지연

### 판정 기준 제안

- **정상 운영 구간**: `p95 < 500ms`, 에러율 `< 1%`, CPU `< 70%`
- **주의 구간**: `p95 500~1500ms`, 에러율 `1~5%`, DB pool 사용률 `> 80%`
- **장애 직전 구간**: `p99 > 2s`, 에러율 `> 5%`, 재시도/타임아웃 급증
- **실패 구간(뻗는 시점)**: 5xx 급증, WebSocket disconnect 폭증, DB pool timeout, OOM 또는 프로세스 재시작

## 3. 현재 코드 기준 예상 병목

### 3-1. 가장 먼저 의심할 지점

1. **지도 조회 API (`GET /api/map/info`)**
   - 요청마다 `findAllNodes()`, `findAllEdges()` 를 호출한다.
   - 각 엣지의 `waypoints` 문자열을 다시 JSON 파싱한다.
   - 지도가 커질수록 CPU와 DB read 부하가 정비례로 증가한다.

2. **경로 탐색 API/서비스**
   - A* 루프 내부에서 `usageManager` lock 상태를 매번 확인한다.
   - `heuristic()` 는 호출 때마다 `mapInfoRepository.findByBasicMapTrue()` 를 통해 최대속도를 다시 읽는다.
   - `findNearestNode()` 는 context 없이 호출되면 전체 노드 full scan 이다.

3. **WebSocket 브로드캐스트**
   - `enableSimpleBroker("/topic", "/queue")` 는 단일 인스턴스 메모리 기반 브로커다.
   - 사용자 수와 broadcast fan-out 이 증가할수록 앱 서버가 직접 메시지 복제 비용을 떠안는다.

4. **보안/안정성 측면의 과부하 증폭 요소**
   - WebSocket endpoint 는 `setAllowedOriginPatterns("*")` 이라서 원치 않는 origin 에서도 연결 시도가 가능하다.
   - STOMP authorization TODO 가 주석 처리되어 있어, 인증 후 메시지 수준 권한 검증이 약하다.
   - `/api/map/**` 와 `/ws-server/**` 가 `permitAll()` 이라 공개 엔드포인트로 트래픽이 집중되기 쉽다.

## 4. 서버가 어느 정도 버틸지 추정하는 방법

아직 실측 데이터가 없으므로 아래처럼 **구간별로 ceiling** 을 찾는 방식이 현실적이다.

### 단계 A. Baseline (안전 구간 찾기)

- 5 VU, 1분: `GET /api/map/info`
- 20 VU, 5분: 지도 조회 + 미션 조회 + 항공편 조회 혼합
- 목표: 평소 운영 트래픽에서 p95가 안정적인지 확인

### 단계 B. Load Test (지속 가능한 상한 찾기)

- 50 VU → 100 VU → 150 VU 를 5분씩 유지
- 목표: CPU, DB pool, heap, websocket session 증가에도 `p95 < 1s` 유지되는지 확인
- 이 단계에서 **첫 번째 성능 한계치**를 기록한다.

### 단계 C. Stress Test (뻗는 시점 찾기)

- 초당 요청수(RPS)를 30 → 80 → 120 이상으로 올린다.
- 5xx, timeout, DB pool timeout, socket disconnect 가 늘어나는 순간을 기록한다.
- 장애가 시작되는 첫 구간을 `soft limit`, 완전히 회복 불가능해지는 지점을 `hard limit` 로 정의한다.

### 단계 D. Soak Test (누적 누수 확인)

- 정상 운영의 60~70% 수준 트래픽으로 1~4시간 유지
- 메모리 증가 추세, websocket 세션 정리 누락, Redis/MQTT backlog 유무 확인

## 5. 제공하는 k6 스크립트

`load-and-stress.js` 는 아래 세 가지 시나리오를 함께 실행한다.

- `smoke_map`: 공개 지도 조회 API가 가벼운 트래픽에서 정상 동작하는지 확인
- `mixed_load`: 지도 조회 + 인증 기반 API를 섞어서 실사용 패턴에 가깝게 검증
- `stress_load`: 도착률 기반으로 RPS를 올려 soft limit / hard limit 탐색

### 실행 예시

```bash
cd Backend/autowing_car/perf
k6 run load-and-stress.js \
  -e BASE_URL=http://localhost:8080 \
  -e PILOT_TOKEN=<jwt> \
  -e SUSTAIN_VUS=50 \
  -e STRESS_RPS_3=120
```

> `PILOT_TOKEN` 이 없으면 `/api/flight/info` 와 `/api/mission` 은 `401/403` 으로 측정되며, 이 비율은 `auth_failure_rate` 로 분리 기록된다.

## 6. 실제로 뻗는 순간에 확인할 체크리스트

### 애플리케이션

- Tomcat worker thread 고갈 여부
- GC pause 증가 여부
- 로그 I/O 폭증 여부
- WebSocket 세션 수 급증 여부

### 데이터 계층

- Hikari active connection / pending thread
- 느린 SQL 및 N+1
- Redis/MQTT 처리 backlog

### 장애 징후

- `/api/map/info` 응답시간이 가장 먼저 튀는지
- 미션 승인/브로드캐스트 지연이 연쇄적으로 늘어나는지
- websocket reconnect storm 가 발생하는지

## 7. 개선 우선순위

### 즉시 해야 하는 것

1. `GET /api/map/info` 결과를 캐시하거나 ETag 기반으로 고정 맵 응답을 재사용
2. `MapService.heuristic()` 에서 `MapInfo` 재조회 제거
3. `findNearestNode()` 의 전체 스캔을 공간 인덱스 또는 캐시 구조로 대체
4. WebSocket origin 을 운영 도메인으로 제한
5. STOMP message-level authorization TODO 복구

### 부하가 커질 때 준비할 것

1. Spring simple broker → 외부 broker relay(RabbitMQ/Redis pub-sub 등) 검토
2. read-heavy API 에 대한 캐시 계층 도입
3. actuator/metrics/prometheus/grafana 대시보드 구성
4. DB pool, Tomcat thread, JVM heap 을 실측 기반으로 재튜닝

### 장애 내성을 높이기 위한 것

1. 브로드캐스트 fan-out 경로에 rate limit / batching 적용
2. 공개 엔드포인트(`/api/map/**`, websocket handshake`)에 대한 rate limiting/WAF 적용
3. MQTT/WebSocket 재시도 정책과 circuit breaker 설계

## 8. 추천 결론

현재 구조만 보면 이 서버의 첫 번째 한계는 **복잡한 경로 탐색보다 먼저 `지도 조회 + in-memory websocket broadcast + DB connection saturation` 조합에서 나타날 가능성**이 높다.

따라서 다음 순서로 검증하는 것이 좋다.

1. `/api/map/info` 단독 부하 테스트로 read path ceiling 확인
2. 인증 API 혼합 부하 테스트로 DB pool saturation 지점 확인
3. WebSocket 동시 접속/브로드캐스트 테스트로 simple broker 한계 확인
4. 마지막으로 미션 승인 이벤트를 섞어 end-to-end 스트레스 테스트 수행

이 과정을 거치면 “몇 명까지는 안정적”, “어느 구간부터 느려짐”, “몇 RPS 부근에서 장애 시작”을 수치로 말할 수 있다.
