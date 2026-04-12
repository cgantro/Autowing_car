# High Frequency Validation (Reproducible Harness)

## 목적
사용자가 제시한 조건(500대, 10Hz, 최대 초당 5,000건)을 코드로 재현하고,
다음 항목을 반복 가능한 방식으로 검증한다.

1. RDP 적용 전/후 전송 페이로드 변화
2. 좌표 수 기준 저장(write) 부담 변화
3. 고정 조건 비동기 버퍼 처리의 무손실 재현성 및 E2E 분포

## 실행 명령
```bash
cd Backend/autowing_car
python3 perf/high_frequency_validation.py
```

## 최근 실행 결과 (UTC 2026-03-30)

### RDP Reduction
- messages: 5,000
- raw payload: 7,392,820 bytes
- simplified payload: 712,690 bytes
- **payload reduction: 90.36%**
- raw points: 200,000
- simplified points: 20,000
- **write reduction: 90.00%**

### Async Buffered Reproducibility (5회 반복)
- processed: 매회 5,000
- loss: 매회 0
- checksum: 매회 12,497,500 (동일)
- p95 범위: **115.93ms ~ 256.37ms**
- p95 평균: 206.32ms

## 해석
- 데이터 구조 단순화(RDP) 효과는 매우 크게 재현됨.
- 동일 입력/처리순서 기준으로 무손실 처리와 반복 가능성(동일 checksum)이 확인됨.
- 단, 이 Python 스레드 기반 하네스에서는 E2E가 14~28ms로 내려가지 않았고,
  환경/런타임 특성(GIL 포함) 영향으로 p95가 100ms+로 관측됨.

## 참고
- 본 검증은 운영 인프라(실 Redis, 실제 네트워크, 실제 DB I/O) 대신
  **재현 가능한 로컬 하네스**를 사용한 기능/구조 검증이다.
- 실제 운영 수치 검증은 Java 서비스 프로세스 + Redis + DB를 포함한 통합 부하테스트(k6/JMeter)로 별도 확인이 필요하다.
