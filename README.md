# AutoStock - 업비트 코인 자동매매 프로그램

Spring Boot 기반의 업비트(Upbit) 거래소 연동 암호화폐 자동매매 시스템입니다. 다양한 기술적 분석 전략을 지원하며, 백테스팅, 실시간 자동매매, 분할매매, 포지션 관리 기능을 제공합니다.

---

## 주요 기능

### 1. 업비트 API 완전 연동
- JWT 기반 인증 (nonce/query hash 검증)
- 계좌 잔고 조회 (KRW 및 코인 보유량)
- 마켓 및 시세 데이터 조회
- 분봉 데이터 (1분, 3분, 5분, 15분, 30분, 60분, 240분)
- 시장가/지정가 주문 (매수/매도)
- 주문 취소 및 상태 조회
- 체결 내역 조회
- 호가창(Orderbook) 데이터 접근

### 2. 트레이딩 전략 (14개 이상)

| 전략명 | 설명 |
|--------|------|
| RSI Strategy | RSI 과매도(25 이하 매수)/과매수(75 이상 매도) 기반 |
| Golden Cross Strategy | 이동평균선 골든크로스/데드크로스 (5일선 × 20일선) |
| Bollinger Band Strategy | 볼린저 밴드 상하단 터치 기반 (1분봉 최적화) |
| Scaled Trading Strategy | 3단계 분할매수/매도 전략 |
| MACD Strategy | MACD 라인 크로스오버 기반 |
| Trend Following Strategy | 추세 추종 전략 |
| Momentum Scalping Strategy | 모멘텀 스캘핑 |
| Volatility Breakout Strategy | 변동성 돌파 전략 |
| Bollinger Band GPT Strategy | GPT 기반 볼린저 밴드 변형 |
| Data-Driven Strategy | 데이터 기반 전략 |

### 3. 기술적 지표 라이브러리
- **SMA** (Simple Moving Average) - 단순 이동평균
- **EMA** (Exponential Moving Average) - 지수 이동평균
- **RSI** (Relative Strength Index) - 상대강도지수
- **Bollinger Bands** - 볼린저 밴드 (설정 가능한 표준편차)
- **MACD** (Moving Average Convergence Divergence) - 이동평균 수렴확산
- **ATR** (Average True Range) - 평균 진폭 범위 (동적 손절/익절 설정용)

### 4. 다중 전략 투표 시스템
- 여러 전략의 신호를 투표로 결합 (2개 이상 동의 시 매매)
- DEFAULT 모드: 모든 전략이 투표
- SCALED_TRADING 모드: 분할매매 전문 전략

### 5. 백테스팅 엔진
- 단일/다중 코인 백테스팅
- 개별 전략 테스트
- 전략 간 비교 분석
- 설정 가능한 파라미터:
  - 초기 자본금 (기본: 1,000,000원)
  - 캔들 단위 (1, 3, 5, 15, 30, 60, 240분)
  - 캔들 개수 (최대 200개)
- 수수료 포함 손익 계산 (0.05%)
- 거래 내역 추적
- 승률 계산
- 매수 후 보유(Buy & Hold) 벤치마크 비교
- 청산 사유 기록 (손절, 익절, 트레일링 스탑, 신호, 타임아웃)

### 6. 실시간 매매 시스템

**분할매수 전략 (Scaled Entry):**
| 단계 | 비율 | 조건 |
|------|------|------|
| 1차 진입 | 30% | 매수 신호 발생 시 |
| 2차 진입 | 30% | 1차 대비 1.5% 하락 시 |
| 3차 진입 | 40% | 1차 대비 2.5% 하락 시 |

**분할매도 전략 (Scaled Exit):**
| 단계 | 비율 | 조건 |
|------|------|------|
| 1차 익절 | 50% | 2.5% 수익 도달 시 |
| 트레일링 스탑 | 나머지 | 3% 수익 도달 후 활성화 |
| 최종 청산 | 전체 | 손절가 또는 신호 발생 시 |

### 7. 리스크 관리
- 포지션 사이징 (계좌 잔고 대비 비율)
- 최대 동시 포지션 제한
- 일일 손실 한도
- 신호 강도 검증 (최소 50/100)
- 슬리피지 허용치 (0.3%)
- ATR 기반 동적 손절/익절

### 8. 사용자 관리 및 인증
- 회원가입/로그인 (JWT 토큰)
- 이메일/사용자명 중복 검증
- 비밀번호 암호화 (BCrypt)
- 업비트 API 키 저장 (AES-256-GCM 암호화)
- 사용자별 자동매매 활성화/비활성화
- 사용자 역할 (USER, ADMIN)

### 9. 스케줄링 및 자동화
- **매매 스케줄러** (@Scheduled Cron 기반)
  - 설정 가능한 실행 간격 (기본 1분)
  - 사용자별 개별 실행
  - 매시간 상태 출력
  - 매일 새벽 3시 데이터 정리
- 다중 사용자 동시 실행 지원

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| Framework | Spring Boot 3.4.2 |
| Language | Java 17 |
| Database | MariaDB / MySQL |
| ORM | Spring Data JPA + Hibernate |
| Security | Spring Security, JWT, OAuth2 |
| HTTP Client | Spring WebFlux (WebClient) |
| Build Tool | Gradle |
| API | Upbit Open API |

### 주요 라이브러리
```gradle
dependencies {
    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'

    // JWT
    implementation 'io.jsonwebtoken:jjwt-api:0.11.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.11.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.11.5'

    // Database
    runtimeOnly 'org.mariadb.jdbc:mariadb-java-client'
    runtimeOnly 'com.mysql:mysql-connector-j:8.0.33'

    // Utility
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    implementation 'com.fasterxml.uuid:java-uuid-generator:4.3.0'
}
```

---

## 프로젝트 구조

```
src/main/java/autostock/taesung/com/autostock/
├── exchange/                        # 거래소 연동 레이어
│   ├── upbit/                       # 업비트 API 클라이언트
│   │   ├── UpbitApiService.java           # 메인 API 서비스
│   │   ├── UserUpbitApiService.java       # 사용자별 API 서비스
│   │   ├── UpbitOrderbookService.java     # 호가창 데이터
│   │   └── dto/                           # 데이터 전송 객체
│   │       ├── Account.java               # 계좌 잔고
│   │       ├── Ticker.java                # 현재가
│   │       ├── Candle.java                # OHLCV 캔들
│   │       ├── Market.java                # 마켓 메타데이터
│   │       ├── OrderResponse.java         # 주문 응답
│   │       ├── ClosedOrder.java           # 체결 주문
│   │       └── Orderbook.java             # 호가창
│   └── koreaInvestment/             # 한국투자증권 API (대체)
│
├── strategy/                        # 트레이딩 전략
│   ├── TradingStrategy.java               # 전략 인터페이스
│   ├── TechnicalIndicator.java            # 기술적 지표 (SMA, EMA, RSI, BB, MACD, ATR)
│   └── impl/                              # 전략 구현체
│       ├── RSIStrategy.java               # RSI 기반
│       ├── GoldenCrossStrategy.java       # 골든/데드 크로스
│       ├── BollingerBandStrategy.java     # 볼린저 밴드
│       ├── ScaledTradingStrategy.java     # 분할매매
│       ├── MACDStrategy.java              # MACD 크로스오버
│       ├── TrendFollowingStrategy.java    # 추세 추종
│       ├── MomentumScalpingStrategy.java  # 모멘텀 스캘핑
│       ├── VolatilityBreakoutStrategy.java # 변동성 돌파
│       ├── BollingerBandGPTStrategy.java  # GPT 기반 변형
│       └── DataDrivenStrategy.java        # 데이터 기반
│
├── backtest/                        # 백테스팅 엔진
│   ├── BacktestService.java               # 백테스트 서비스
│   ├── context/
│   │   └── BacktestContext.java           # 스레드 로컬 컨텍스트
│   └── dto/
│       ├── BacktestResult.java            # 단일 전략 결과
│       ├── MultiCoinBacktestResult.java   # 다중 코인 결과
│       ├── TradeRecord.java               # 개별 거래
│       ├── BacktestPosition.java          # 포지션 추적
│       └── ExitReason.java                # 청산 사유 enum
│
├── realtrading/                     # 실매매 실행 엔진
│   ├── engine/
│   │   └── RealTradingEngine.java         # 신호 처리 및 포지션 관리
│   ├── strategy/
│   │   ├── ScaledEntryStrategy.java       # 3단계 분할매수
│   │   └── ScaledExitStrategy.java        # 부분 익절 + 트레일링 스탑
│   ├── service/
│   │   ├── ExecutionService.java          # 주문 실행
│   │   └── TradingStatisticsService.java  # 손익 추적
│   ├── risk/
│   │   └── RiskManager.java               # 리스크 평가 및 제한
│   ├── entity/
│   │   ├── Position.java                  # 포지션 상태 머신
│   │   └── ExecutionLog.java              # 실행 이력
│   ├── repository/
│   │   ├── PositionRepository.java
│   │   └── ExecutionLogRepository.java
│   ├── controller/
│   │   └── RealTradingController.java     # /api/realtrading/* API
│   └── config/
│       └── RealTradingConfig.java         # 매매 파라미터
│
├── api/controller/                  # REST API 컨트롤러
│   ├── UpbitController.java               # /api/upbit/* - 계좌, 주문, 매매
│   ├── BacktestController.java            # /api/backtest/* - 백테스트 실행
│   ├── BacktestVisualizationController.java # 시각화 데이터
│   ├── OrderController.java               # 주문 관리
│   ├── TradeHistoryController.java        # 거래 내역 조회
│   ├── UserStrategyController.java        # 사용자 전략 관리
│   ├── StrategyParameterController.java   # 파라미터 튜닝
│   ├── ApiKeyController.java              # API 키 관리 (암호화)
│   ├── DashboardController.java           # 대시보드 데이터
│   ├── PriceAlertController.java          # 가격 알림
│   ├── RebalanceController.java           # 포트폴리오 리밸런싱
│   ├── StrategyOptimizerController.java   # 전략 최적화
│   └── LogController.java                 # 로그 조회
│
├── service/                         # 서비스 레이어
│   ├── AuthService.java                   # 회원가입/로그인
│   ├── UserStrategyService.java           # 사용자 전략 관리
│   ├── StrategyParameterService.java      # 파라미터 저장/조회
│   ├── AsyncSimulationService.java        # 비동기 백테스트
│   ├── BacktestVisualizationService.java
│   ├── DashboardService.java
│   ├── PriceAlertService.java
│   ├── RebalanceService.java
│   ├── StrategyOptimizerService.java
│   ├── LogService.java
│   ├── ApiKeyService.java
│   └── TradeProfitService.java
│
├── entity/                          # JPA 엔티티 (데이터베이스 모델)
│   ├── User.java                          # 사용자 (암호화된 API 키)
│   ├── TradeHistory.java                  # 거래 기록
│   ├── CandleData.java                    # 저장된 캔들 데이터
│   ├── TickerData.java                    # 저장된 시세 데이터
│   ├── UserStrategy.java                  # 사용자 선택 전략
│   ├── StrategyParameter.java             # 파라미터 커스터마이징
│   └── SimulationTask.java                # 비동기 백테스트 작업 추적
│
├── repository/                      # Spring Data JPA 레포지토리
│   ├── UserRepository.java
│   ├── TradeHistoryRepository.java
│   ├── CandleDataRepository.java
│   ├── TickerDataRepository.java
│   ├── UserStrategyRepository.java
│   ├── StrategyParameterRepository.java
│   └── SimulationTaskRepository.java
│
├── scheduler/                       # 스케줄러
│   ├── UpbitTradingScheduler.java         # @Scheduled 자동매매 실행
│   └── DataCleanupScheduler.java          # 주기적 DB 정리
│
├── controller/                      # 기본 컨트롤러
│   ├── AuthController.java                # /auth/* - 로그인/회원가입
│   └── UserController.java                # /user/* - 사용자 관리
│
├── security/                        # Spring Security
│   ├── JwtUtil.java                       # JWT 토큰 생성/검증
│   ├── JwtAuthenticationFilter.java       # JWT 필터
│   ├── SecurityConfig.java                # 보안 설정
│   └── CustomUserDetailsService.java      # UserDetails 제공
│
├── config/                          # 애플리케이션 설정
│   ├── AsyncConfig.java                   # 비동기 실행자 설정
│   └── CorsConfig.java                    # CORS 설정
│
├── util/                            # 유틸리티 클래스
│   └── EncryptionUtil.java                # AES-256-GCM 암호화
│
├── trading/                         # 매매 서비스
│   ├── AutoTradingService.java            # 레거시 자동매매 서비스
│   └── UserAutoTradingService.java
│
├── dto/                             # 데이터 전송 객체
│   ├── TradeProfitDto.java
│   └── auth/
│       ├── LoginRequest.java
│       ├── RegisterRequest.java
│       └── AuthResponse.java
│
└── AutoStockApplication.java        # Spring Boot 진입점 (@EnableScheduling)
```

---

## REST API 엔드포인트

### 인증
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/auth/register` | 회원가입 |
| POST | `/auth/login` | 로그인 |

### 계좌 및 시장 정보
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/upbit/accounts` | 계좌 잔고 조회 |
| GET | `/api/upbit/markets` | 마켓 목록 조회 |
| GET | `/api/upbit/ticker?markets=` | 현재가 조회 |
| GET | `/api/upbit/candles/minutes/{unit}?market=&count=` | 분봉 조회 (1,3,5,15,30,60,240) |
| GET | `/api/upbit/candles/days?market=&count=` | 일봉 조회 |

### 주문
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/upbit/orders/buy/market` | 시장가 매수 |
| POST | `/api/upbit/orders/sell/market` | 시장가 매도 |
| POST | `/api/upbit/orders/buy/limit` | 지정가 매수 |
| POST | `/api/upbit/orders/sell/limit` | 지정가 매도 |
| DELETE | `/api/upbit/orders/{uuid}` | 주문 취소 |
| GET | `/api/upbit/orders/{uuid}` | 주문 상세 조회 |

### 자동매매
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/upbit/trading/execute` | 자동매매 수동 실행 |
| GET | `/api/upbit/trading/status` | 보유 현황 조회 |

### 백테스팅
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/backtest/run` | 통합 전략 백테스트 |
| GET | `/api/backtest/run/{strategy}` | 개별 전략 백테스트 |
| GET | `/api/backtest/compare` | 전략 비교 |
| GET | `/api/backtest/compare/summary` | 전략 비교 요약 |
| GET | `/api/backtest/strategies` | 전략 목록 |

#### 백테스팅 파라미터
| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `market` | KRW-BTC | 마켓 코드 |
| `initialBalance` | 1000000 | 초기 자본금 (원) |
| `candleUnit` | 15 | 캔들 단위 (분) - 1, 3, 5, 15, 30, 60, 240 |
| `candleCount` | 200 | 캔들 개수 (최대 200) |

### 실매매
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/realtrading/positions` | 현재 포지션 조회 |
| GET | `/api/realtrading/statistics` | 손익 통계 |
| POST | `/api/realtrading/signal` | 수동 시그널 주입 |
| GET | `/api/realtrading/logs` | 실행 로그 |

### 전략 관리
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/strategy/parameters` | 파라미터 조회 |
| POST | `/api/strategy/parameters` | 파라미터 업데이트 |
| GET | `/api/strategy/optimization` | 최적화 실행 |
| GET | `/api/user-strategies` | 사용자 전략 조회 |
| POST | `/api/user-strategies` | 사용자 전략 설정 |

### 대시보드 및 도구
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/dashboard/summary` | 전체 요약 |
| GET | `/api/trade-history` | 거래 내역 |
| GET | `/api/price-alerts` | 가격 알림 |
| GET | `/api/rebalance` | 포트폴리오 리밸런싱 |
| GET | `/api/logs` | 애플리케이션 로그 |

---

## 데이터베이스 스키마

### 주요 테이블

#### users - 사용자 정보
```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,     -- BCrypt 암호화
    email VARCHAR(100),
    upbit_access_key VARCHAR(500),      -- AES-256-GCM 암호화
    upbit_secret_key VARCHAR(500),      -- AES-256-GCM 암호화
    auto_trading_enabled BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    role VARCHAR(20) DEFAULT 'USER',
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

#### trade_history - 거래 내역
```sql
CREATE TABLE trade_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    market VARCHAR(20),
    trade_type VARCHAR(10),             -- BUY/SELL
    trade_date DATE,
    trade_time TIME,
    amount DECIMAL(20, 8),
    volume DECIMAL(20, 8),
    price DECIMAL(20, 8),
    fee DECIMAL(20, 8),
    order_uuid VARCHAR(100),
    strategy_name VARCHAR(100),
    target_price DECIMAL(20, 8),
    highest_price DECIMAL(20, 8),
    half_sold BOOLEAN,
    is_stop_loss BOOLEAN,
    created_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

#### real_position - 실매매 포지션
```sql
CREATE TABLE real_position (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    market VARCHAR(20),
    status VARCHAR(20),                 -- PENDING/ENTERING/ACTIVE/EXITING/CLOSED
    entry_phase INT,
    exit_phase INT,
    total_quantity DECIMAL(20, 8),
    total_invested DECIMAL(20, 8),
    avg_entry_price DECIMAL(20, 8),
    entry1_price DECIMAL(20, 8),
    entry1_quantity DECIMAL(20, 8),
    entry1_time TIMESTAMP,
    entry2_price DECIMAL(20, 8),
    entry2_quantity DECIMAL(20, 8),
    entry2_time TIMESTAMP,
    entry3_price DECIMAL(20, 8),
    entry3_quantity DECIMAL(20, 8),
    entry3_time TIMESTAMP,
    partial_exit_quantity DECIMAL(20, 8),
    partial_exit_price DECIMAL(20, 8),
    partial_exit_time TIMESTAMP,
    final_exit_quantity DECIMAL(20, 8),
    final_exit_price DECIMAL(20, 8),
    final_exit_time TIMESTAMP,
    stop_loss_price DECIMAL(20, 8),
    target_price DECIMAL(20, 8),
    trailing_high_price DECIMAL(20, 8),
    trailing_stop_price DECIMAL(20, 8),
    realized_pnl DECIMAL(20, 8),
    total_fees DECIMAL(20, 8),
    total_slippage DECIMAL(20, 8),
    strategy_name VARCHAR(100),
    signal_strength INT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    INDEX idx_market (market),
    INDEX idx_status (status),
    INDEX idx_user_id (user_id)
);
```

#### candle_data - 캔들 데이터
```sql
CREATE TABLE candle_data (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market VARCHAR(20),
    candle_date_time_utc TIMESTAMP,
    candle_date_time_kst TIMESTAMP,
    opening_price DECIMAL(20, 8),
    high_price DECIMAL(20, 8),
    low_price DECIMAL(20, 8),
    trade_price DECIMAL(20, 8),
    candle_acc_trade_volume DECIMAL(30, 8),
    unit INT,
    created_at TIMESTAMP,
    INDEX idx_market_time (market, candle_date_time_kst)
);
```

---

## 설정

### application.properties 주요 설정

```properties
# 서버 설정
spring.application.name=AutoStock
server.port=8080
server.tomcat.connection-timeout=300000
spring.mvc.async.request-timeout=300000

# 데이터베이스
spring.datasource.driver-class-name=org.mariadb.jdbc.Driver
spring.datasource.url=jdbc:mariadb://localhost:3306/autostock
spring.datasource.username=root
spring.datasource.password=password
spring.jackson.time-zone=Asia/Seoul
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect

# 업비트 API
upbit.access-key=YOUR_ACCESS_KEY
upbit.secret-key=YOUR_SECRET_KEY

# 매매 설정
trading.enabled=true
trading.target-market=KRW-BTC
trading.multi-market-enabled=true
trading.target-markets=KRW-XRP,KRW-SOL,KRW-DOGE,KRW-ADA,KRW-AVAX
trading.excluded-markets=KRW-BTC,KRW-ETH,KRW-GRS
trading.auto-select-top=100
trading.investment-ratio=0.1
trading.min-order-amount=6000
trading.stop-loss-enabled=true
trading.stop-loss-rate=-0.02
trading.strategy-mode=DEFAULT  # 또는 SCALED_TRADING

# 스케줄러 (Cron)
trading.schedule.cron=0 */1 * * * *           # 1분마다 실행
trading.schedule.status-cron=0 0 * * * *      # 매시간 상태 출력
trading.schedule.cleanup-cron=0 0 3 * * *     # 새벽 3시 데이터 정리

# 분할매매 설정
realtrading.entry-ratio1=0.30                 # 1차 진입 비율
realtrading.entry-ratio2=0.30                 # 2차 진입 비율
realtrading.entry-ratio3=0.40                 # 3차 진입 비율
realtrading.entry2-drop-threshold=0.015       # 2차 진입 하락률 (1.5%)
realtrading.entry3-drop-threshold=0.025       # 3차 진입 하락률 (2.5%)
realtrading.partial-take-profit-rate=0.025    # 부분 익절 수익률 (2.5%)
realtrading.partial-exit-ratio=0.50           # 부분 익절 비율 (50%)
realtrading.take-profit-atr-multiplier=1.5    # 익절 ATR 배수
realtrading.stop-loss-atr-multiplier=1.5      # 손절 ATR 배수
realtrading.max-stop-loss-rate=-0.03          # 최대 손절률 (-3%)
realtrading.min-stop-loss-rate=-0.01          # 최소 손절률 (-1%)
realtrading.trailing-activation-threshold=0.03 # 트레일링 활성화 수익률 (3%)
realtrading.trailing-atr-multiplier=2.0       # 트레일링 ATR 배수
realtrading.trailing-stop-rate=0.015          # 트레일링 스탑 비율 (1.5%)

# JWT 및 암호화
jwt.secret=autostock-secret-key-for-jwt-token-generation
jwt.expiration=86400000                        # 24시간
jwt.refresh-expiration=604800000               # 7일
encryption.secret-key=autostock-api-key-encryption-secret-key
```

### 프로파일
- `dev` - 개발 환경
- `prod` - 운영 환경
- `prod2` - 대체 운영 환경
- `real` - 실매매 환경 (기본 자동매매 비활성화)

---

## 설정 방법

### 1. 업비트 API 키 발급

1. [업비트](https://upbit.com) 로그인
2. 마이페이지 > Open API 관리
3. API 키 발급 (필요 권한: 자산조회, 주문조회, 주문하기)

### 2. 데이터베이스 설정

```sql
CREATE DATABASE autostock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 3. application.properties 설정

```properties
# 업비트 API 키
upbit.access-key=발급받은_ACCESS_KEY
upbit.secret-key=발급받은_SECRET_KEY

# 데이터베이스
spring.datasource.url=jdbc:mariadb://localhost:3306/autostock
spring.datasource.username=your_username
spring.datasource.password=your_password

# 자동매매 설정
trading.enabled=false                   # 처음에는 false로 테스트
trading.target-market=KRW-BTC
trading.investment-ratio=0.1
```

---

## 빌드 및 실행

### 요구사항
- Java 17+
- MariaDB 또는 MySQL
- Upbit API 키 (Access Key, Secret Key)

### 빌드
```bash
./gradlew build
```

### 실행
```bash
# 개발 환경
./gradlew bootRun --args='--spring.profiles.active=dev'

# 운영 환경
./gradlew bootRun --args='--spring.profiles.active=prod'

# JAR 실행
java -jar build/libs/autostock-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

---

## 사용 예시

### 백테스팅

```bash
# 기본 백테스팅 (KRW-BTC, 100만원, 15분봉 200개)
curl "http://localhost:8080/api/backtest/run"

# 이더리움 백테스팅 (500만원, 1시간봉)
curl "http://localhost:8080/api/backtest/run?market=KRW-ETH&initialBalance=5000000&candleUnit=60"

# RSI 전략만 백테스팅
curl "http://localhost:8080/api/backtest/run/RSI%20Strategy"

# 모든 전략 비교
curl "http://localhost:8080/api/backtest/compare/summary"
```

### 백테스팅 결과 예시

```json
{
  "market": "KRW-BTC",
  "strategy": "Combined (All Strategies)",
  "startDate": "2025-01-04T10:00:00",
  "endDate": "2025-01-04T20:00:00",
  "initialBalance": 1000000,
  "finalTotalAsset": 1025000,
  "totalProfitRate": 2.5,
  "buyAndHoldRate": 1.2,
  "totalTrades": 8,
  "winRate": 62.5,
  "tradeHistory": [...]
}
```

---

## 포지션 상태 흐름

```
PENDING → ENTERING → ACTIVE → EXITING → CLOSED
   │         │         │         │
   │         │         │         └─ 최종 청산 완료
   │         │         └─ 포지션 활성 상태 (보유 중)
   │         └─ 분할매수 진행 중 (1차 → 2차 → 3차)
   └─ 매수 신호 대기 중
```

---

## 아키텍처 설계 패턴

| 패턴 | 적용 위치 |
|------|----------|
| Strategy Pattern | 트레이딩 전략 (TradingStrategy 인터페이스) |
| Factory Pattern | 전략 인스턴스화 및 선택 |
| Repository Pattern | 데이터 접근 추상화 |
| Service Layer Pattern | 비즈니스 로직 분리 |
| State Machine | 포지션 생명주기 관리 |
| DTO Pattern | API 계약 분리 |
| Template Method | 백테스트 실행 흐름 |

---

## 보안

- **API 키 암호화**: AES-256-GCM으로 DB에 암호화 저장
- **JWT 토큰**: 인증/인가 (만료 및 리프레시 토큰 지원)
- **비밀번호**: BCrypt 해싱
- **CORS**: 설정 가능한 CORS 정책
- **OAuth2**: 서드파티 인증 지원
- **요청 타임아웃**: 장시간 비동기 작업용 5분 타임아웃
- **HTTPS**: 업비트 API 통신 시 SSL/TLS 사용

---

## 주의사항

- 이 프로그램은 투자 조언을 제공하지 않습니다
- 암호화폐 투자는 높은 위험을 수반합니다
- 실제 자금으로 거래하기 전 반드시 백테스팅을 수행하세요
- API 키는 안전하게 관리하시기 바랍니다
- `trading.enabled=false` 상태에서 먼저 로그를 확인하며 테스트하는 것을 권장합니다
- 자동매매로 인한 손실에 대해 책임지지 않습니다

---

## 최근 개발 현황

- GPT 기반 트레이딩 전략 추가
- 다중 서버 배포 지원 (마켓 분석 분산)
- 전략 로직 병렬 처리 최적화
- 시뮬레이션 일자 필터링 및 비동기 시뮬레이션 지원
- 버그 수정 및 유지보수

---

## 라이선스

이 프로젝트는 개인 프로젝트입니다.

---

## 기여

버그 리포트, 기능 제안, PR 환영합니다.

## 연락처

문의사항이 있으시면 이슈를 생성해 주세요.