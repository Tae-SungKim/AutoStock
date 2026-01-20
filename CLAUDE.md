# CLAUDE.md

이 파일은 Claude Code (claude.ai/code)가 이 저장소에서 작업할 때 참고하는 가이드입니다.

## 빌드 및 실행 명령어

```bash
# 빌드
./gradlew build

# 개발 환경 실행
./gradlew bootRun --args='--spring.profiles.active=dev'

# 운영 환경 실행
./gradlew bootRun --args='--spring.profiles.active=prod'

# 테스트 실행
./gradlew test

# 단일 테스트 클래스 실행
./gradlew test --tests "LogServiceTest"
```

## 아키텍처 개요

업비트 거래소 연동 암호화폐 자동매매 시스템. Spring Boot 3.4.2, Java 17, MariaDB 사용.

### 핵심 레이어

1. **거래소 연동 레이어** (`exchange/upbit/`)
   - `UpbitApiService` - 핵심 API 클라이언트 (캔들, 주문, 계좌, JWT 인증)
   - `UserUpbitApiService` - 사용자별 암호화된 API 키로 API 호출
   - `UpbitOrderbookService` - 호가창 데이터 조회

2. **전략 레이어** (`strategy/`)
   - `TradingStrategy` 인터페이스 - `analyze()`, `analyzeForBacktest()` 메서드
   - `TechnicalIndicator` - 기술적 지표 유틸리티 (SMA, EMA, RSI, 볼린저밴드, MACD, ATR)
   - `strategy/impl/`에 12개 전략 구현:
     - RSIStrategy, GoldenCrossStrategy, MACDStrategy
     - BollingerBandStrategy, BollingerBandSafeStrategy, BollingerBandGPTStrategy, BollingerBandCustomStrategy
     - ScaledTradingStrategy, TrendFollowingStrategy
     - MomentumScalpingStrategy, VolatilityBreakoutStrategy, DataDrivenStrategy

3. **백테스트 레이어** (`backtest/`)
   - `BacktestService` - 단일/멀티 코인 백테스팅
     - **병렬 처리**: `runMultiCoinBacktestFromDb()`에서 `CompletableFuture`로 마켓별 동시 처리
     - **성능 최적화**: `executeBacktestSingleStrategy()`가 O(n) 시간 복잡도 (미리 역순 리스트 생성)
     - **날짜 필터링**: `yyyyMMdd` 형식 지원, 자동으로 `yyyy-MM-dd`로 변환
   - `BacktestContext` - ThreadLocal로 종료 사유 추적
   - `BacktestPosition` - 시뮬레이션 중 포지션 상태

4. **실시간 매매 레이어** (`realtrading/`)
   - 포지션 상태 머신: PENDING → ENTERING → ACTIVE → EXITING → CLOSED
   - `RiskManager` - 포지션 사이징, 최대 포지션 수, 일일 손실 한도
   - `ScaledEntryStrategy` / `ScaledExitStrategy` - 분할매수/분할매도

5. **API 레이어** (`api/controller/`)
   - `BacktestController` - `/api/backtest/*`
   - `UpbitController` - `/api/upbit/*`
   - `RealTradingController` - `/api/realtrading/*`

### 주요 Repository 메서드

**CandleDataRepository** - 날짜 범위 조회:
```java
// 날짜 형식: yyyy-MM-dd (endDateNext는 미포함)
findByMarketAndDateRange(market, startDate, endDateNext)
findByMarketAndUnitAndDateRange(market, unit, startDate, endDateNext)
```

### 주요 패턴

- **Strategy 패턴**: 모든 매매 전략이 `TradingStrategy` 인터페이스 구현
- **상태 머신**: 실시간 매매에서 포지션 생명주기 관리
- **ThreadLocal 컨텍스트**: `BacktestContext`로 스레드 안전한 전략 상태 관리
- **병렬 처리**: `CompletableFuture`로 멀티 코인 백테스팅
- **암호화 저장**: API 키를 AES-256-GCM으로 암호화 (`EncryptionUtil`)

### 데이터베이스 (MariaDB)

주요 테이블:
- `users` - 사용자 계정 (암호화된 API 키 포함)
- `trade_history` - 거래 내역
- `real_position` - 실시간 매매 포지션
- `candle_data` - OHLCV 캔들 데이터 (candleDateTimeKst 형식: `yyyy-MM-ddTHH:mm:ss`)

### 스케줄링

- 매매 실행: 1분마다 (`UpbitTradingScheduler`)
- 데이터 정리: 매일 새벽 3시 (`DataCleanupScheduler`)

## 설정 파일

- `application.properties` - 메인 설정 (서버 포트 8080)
- `application-dev.properties` - 개발 DB 설정
- `application-prod.properties` - 운영 설정
- 로그 파일: `src/main/resources/spring_boot.log`

## API 엔드포인트

### 백테스트
- `GET /api/backtest/run` - 전략 조합 백테스트
- `GET /api/backtest/run/{strategy}` - 특정 전략 백테스트
- `GET /api/backtest/compare` - 전체 전략 비교
- `GET /api/backtest/multi/db` - DB 기반 멀티 코인 백테스트 (날짜 필터 지원)
  - 파라미터: `markets`, `strategy`, `unit`, `startDate` (yyyyMMdd), `endDate` (yyyyMMdd)

### 매매
- `GET /api/upbit/accounts` - 계좌 잔고 조회
- `GET /api/upbit/candles/minutes/{unit}` - 분봉 캔들 조회
- `POST /api/upbit/orders/buy/market` - 시장가 매수
- `POST /api/upbit/orders/sell/market` - 시장가 매도