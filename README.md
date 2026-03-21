# ✈️ 스마트 토잉카 관제 시스템 (Smart Towing Car Control System)

> **S14P11A402** | 자율주행 토잉카를 이용한 차세대 공항 관제 시스템

## 📖 프로젝트 개요 (Project Overview)

**스마트 토잉카 관제 시스템**은 공항 내 항공기 견인 작업을 자동화하고, 관제탑-토잉카-기장-마샬러 간의 유기적인 협업을 지원하는 통합 관제 플랫폼입니다.

기존의 수동 견인 방식에서 벗어나 **자율주행 토잉카(Orin Car)** 가 항공기를 목적지까지 안전하게 이송하며, **관제탑(Web Control)** 은 전체 상황을 실시간으로 모니터링하고 제어합니다. **AI 비전 기술**을 통해 마샬러의 수신호를 인식하고, 항공기 랜딩기어와 자동으로 도킹하는 지능형 시스템을 구축하였습니다.

---

## 🏗️ 시스템 아키텍처 (System Architecture)

본 프로젝트는 4개의 핵심 파트가 유기적으로 연결되어 동작합니다.

### 🏛️ Frontend (Masterboard UI)

- **Path**: `Frontend/`
- **Tech Stack**: React, Vite, TypeScript, Tailwind CSS, Zustand
- **Role**: 관제탑 역할을 수행하는 웹 대시보드입니다.
  - **3-Zone Layout**: 상황판(Left), 메인 전장(Center), 상세 분석실(Right)로 구성된 직관적인 UI
  - **Real-time Visualization**: WebSocket/MQTT를 통해 항공기 및 토잉카의 위치, 경로, 상태를 실시간으로 시각화 (SVG/Canvas)
  - **Design**: "Dark Ops + Neon Laser" 컨셉의 전문적인 관제 시스템 디자인 (Glassmorphism 적용)

### 📡 Backend (Control Server)

- **Path**: `Backend/autowing_car/`
- **Tech Stack**: Java 17, Spring Boot, JPA, MySQL, Redis, MQTT
- **Role**: 시스템의 중추 역할을 수행하는 관제 서버입니다.
  - **Mission Control**: 항공기 견인 미션 생성, 할당, 상태 관리
  - **Data Hub**: 토잉카, 관제탑, 기장 앱 간의 메시지 및 데이터 중계
  - **Alert System**: 충돌 경고, 비상 정지 등 긴급 알림 처리

### 🚗 Embedded (Towing Car & Path Planning)

- **Path**: `embedded/`
- **Tech Stack**: Python, C/C++ (STM32), ROS/ROS2, Jetson Orin
- **Role**: 실제 항공기를 견인하는 하드웨어 및 제어 소프트웨어입니다.
  - **Orin Car**: 자율주행 알고리즘 수행, 상위 제어 판, 센서 데이터 처리
  - **STM32**: DC 모터 제어, 하드웨어 구동 (Actuator Control)
  - **Path Planner**: 공항 지도 기반 최적 경로 탐색 (A\* 알고리즘 등)

### 🤖 AI (Vision & Intelligence)

- **Path**: `AI/`
- **Tech Stack**: Python, OpenCV, YOLOv8
- **Role**: 시각 정보를 분석하여 지능형 판단을 수행합니다.
  - **Docking AI**: 항공기 전륜(랜딩기어)을 인식하고 정밀하게 접근하여 도킹 수행
  - **Gesture AI**: 마샬러(유도요원)의 수신호(출발, 정지 등)를 인식하여 토잉카 제어

---

## 🎬 주요 시나리오 (Key Scenarios)

### 1. 토잉카-항공기 도킹 (Automated Docking)

1.  관제탑에서 도킹 명령 하달
2.  토잉카가 항공기 전방으로 이동
3.  **AI Vision**이 항공기 바퀴(매칭 포인트)를 인식
4.  정밀 주행으로 접근하여 랜딩기어 결착 (Locking)

### 2. 이동 승인 및 주행 (Mission & Navigation)

1.  기장이 이동 요청 (Pushback/Towing)
2.  관제탑이 최적 경로 생성 및 할당
3.  기장 및 마샬러의 최종 승인 (Safety Check)
4.  토잉카가 할당된 경로를 따라 자율주행 시작

### 3. 비상 상황 및 수동 제어 (Emergency Handling)

1.  주행 중 장애물 감지 또는 마샬러의 **[정지]** 수신호 인식
2.  토잉카 즉시 정지 및 비상 알림 전송
3.  필요 시 관제탑 또는 비상 운전수가 **수동 모드**로 전환하여 제어

---

## 🚀 시작하기 (Getting Started & Usage)

### 🔧 사전 요구 사항 (Prerequisites)

- **Common**: Docker, Git
- **Frontend**: Node.js v22+, pnpm
- **Backend**: JDK 17+, MySQL, Redis
- **AI/Embedded**: Python 3.8+, ROS2 environment

### 📈 성능/과부하 테스트

- 백엔드 부하 테스트 가이드 및 k6 스크립트는 `Backend/autowing_car/perf/README.md` 와 `Backend/autowing_car/perf/load-and-stress.js` 를 참고하세요.

### 💻 로컬 실행 (Local Development)

#### 1. Frontend

```bash
cd Frontend
npm install -g pnpm
pnpm install
pnpm run dev
```

- 접속: `http://localhost:5173`

#### 2. Backend

```bash
cd Backend/autowing_car
# src/main/resources/application.yml 설정 확인 (DB, Redis 정보)
./gradlew bootRun
```

#### 3. AI / Embedded

```bash
cd AI
pip install -r requirements.txt
python main.py
```

---

## ☁️ 배포 환경 (Deployment Environment)

본 시스템은 **AWS EC2** 환경에서 **Docker** 컨테이너 기반으로 배포되어 운영됩니다.

### Infrastructure

- **Server**: AWS EC2 (Ubuntu 22.04 LTS)
- **Network**: `autowing-net` (Docker Network)를 통해 컨테이너 간 안전한 통신
- **Database**: MySQL, Redis (Docker Container)

### CI/CD Pipeline (GitLab)

- **Automated Build**: `master` 또는 `dev` 브랜치에 코드가 푸시되면 GitLab CI가 동작합니다.
- **Docker Registry**: 빌드된 이미지는 Docker Hub 또는 Private Registry에 저장됩니다.
- **Deploy**: SSH를 통해 운영 서버에 접속하여 최신 이미지를 받아오고 컨테이너를 재실행합니다.

---

## 🤝 기여 가이드 (Contributing)

프로젝트에 기여하고 싶으신가요? `docs/CONTRIBUTING.md`를 먼저 확인해 주세요!

### 핵심 규칙 (Quick Rules)

1.  **Setting**: 작업 전 `sh scripts/init_setting.sh`를 실행하여 Git Hook을 설치하세요.
2.  **Branch**: `feat/기능명-지라티켓번호` (예: `feat/login-S14P11A402-12-fe`)
3.  **Commit**: `[티켓번호] 태그: 내용` (예: `[S14P11A402-12] ✨ Feat: 로그인 페이지 구현`)
4.  **MR**: `develop` 브랜치로 Merge Request를 생성하고, 리뷰어 1명 이상의 승인을 받아야 합니다.

---
