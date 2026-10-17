# DrivingLog Redis Streams write-behind (후속 개선)

## 범위

- 대상: 빈도가 높은 `driving_log` 운행 이력에 한정
- 변경 전: MQTT 모니터링 메시지마다 `DrivingLogRepository.save()`를 호출
- 변경 후(옵트인): 상태 DB 트랜잭션 성공 → Redis Stream `XADD` → 별도 소비자 `JdbcTemplate.batchUpdate` → **DB 커밋 후** `XACK`, `XDEL`
- 차량의 현재 상태, 미션 상태, 명령 관련 DB 저장은 **기존 동기 트랜잭션 유지**
- `TowingCarCacheService`의 최신 차량 상태 캐시는 **Write-back 구현이 아님**

## 활성화

기본값은 **disabled**입니다. 부하 및 장애 테스트 후 명시적으로 활성화해야 합니다.

```bash
export DRIVING_LOG_WRITE_BEHIND_ENABLED=true
export DRIVING_LOG_WRITE_BEHIND_BATCH_SIZE=500
export DRIVING_LOG_WRITE_BEHIND_MAX_BATCHES=20
export DRIVING_LOG_WRITE_BEHIND_POLL_MS=250
export DRIVING_LOG_WRITE_BEHIND_CONSUMER=writer-1
```

`application-dev.yml`, `application-prod.yml`에서 같은 환경변수를 사용합니다.

**단일 쓰기 소비자 프로세스** 가정입니다. 동일한 그룹의 복수 인스턴스 운영, 소비자 재할당, 장기간 장애 복구를 배포 요구사항으로 삼는다면 consumer lease, `XAUTOCLAIM`, dead-letter 정책부터 추가해야 합니다.

## 데이터 및 중복 방지

- Redis Stream: `towing_car:driving_log:stream`
- Consumer Group: `driving-log-db-writer`
- Stream ID로 음수 `log_id`를 결정적으로 생성하고 원래의 `created_at`을 그대로 저장
- 기존 JPA 시퀀스가 생성한 양수 ID와 구별
- TimescaleDB의 기존 복합 기본키 `(created_at, log_id)`에 `ON CONFLICT ... DO NOTHING`을 적용
- DB 성공 후 ACK 실패/재시작으로 같은 레코드를 재처리해도 두 번째 INSERT는 생략
- Redis Stream sequence가 밀리초당 1,000,000 이상이면 안전하게 거부. 이 한계를 바꾸려면 식별자 스키마 재검토 필요
- 기존 `driving_log` SQL 컬럼은 `posx`, `posy` (스키마 확인 완료)

## 장애 정책과 중요한 제한

1. DB 오류: 롤백 후 ACK를 하지 않음. 안정적인 소비자 이름 `writer-1`의 pending 메시지를 다음 주기에 먼저 재처리.
2. DB 저장 성공, ACK 실패: 복합 PK로 중복 INSERT 방지.
3. ACK 성공, XDEL 실패: 기록은 이미 영속화했지만 Redis Stream 메모리 정리가 지연. 운영 점검 필요.
4. **DB 상태 커밋과 Redis XADD는 서로 다른 시스템이므로 원자적이지 않음.** 두 동작 사이 Redis 장애가 나면 운행 로그를 잃을 수 있음. **완전 무손실 보장이 필요한 경우 PostgreSQL Transactional Outbox 도입**.
5. Redis 메모리 정책/영속화(AOF)·DB/Redis 장애 시나리오에 따라 실제 내구성이 달라짐. 기록을 모두 저장한다는 정량 성과는 통합 테스트 전까지 주장 금지.
6. 큐가 성장하면 Redis 메모리가 소진될 수 있음. `XLEN`, `XPENDING`, 배치 지연과 실패 로그 모니터링 필수. Pending 레코드가 있는 Stream을 임의 `XTRIM`하여 손실시키지 말 것.
7. 현재 `processCarMonitoring`은 여전히 매 메시지 차량 엔터티를 조회하고 현재 상태를 DB 트랜잭션으로 저장할 수 있음. 운행 로그 INSERT를 배치화했다고 **전체 DB 쓰기가 일괄 배치화된 것은 아님**.

## 검증 절차

1. 로컬 PostgreSQL/TimescaleDB와 Redis 구동, 기존 스키마 확인.
2. 원본 코드 대비 500대 × 10Hz 등 동일 부하에서 DB INSERT 횟수, TPS, CPU, 처리 지연 비교.
3. 1초 동안 운행 메시지 수신 후 `XLEN towing_car:driving_log:stream`, `XPENDING towing_car:driving_log:stream driving-log-db-writer` 확인.
4. DB를 일시 중지한 상태에서 메시지를 쌓고, 재개 후 모든 로그가 정확하게 저장되는지 확인.
5. DB 저장 직후 ACK 전에 프로세스를 종료해 같은 Stream ID 재처리 시 중복 행이 없는지 확인.
6. Redis 연결이 중단되는 경우의 로그 손실 가능성 및 운영 정책 확인.
7. Spring Boot 단위 테스트: `./gradlew test --tests '*DrivingLogWriteBehindServiceTest'`.
8. **통합 검증 전에는 실제 서비스 배포에서 ENABLED=false 유지.**

## 개발 기록

- 원 프로젝트 기간(2026년 1~2월)과 별개인 **후속 코드 개선**
- 실제 변경 및 커밋 날짜 보존. 과거 프로젝트 수행 기간으로 소급하지 않음
