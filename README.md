# AutoStock - 업비트 코인 자동매매 프로그램

Spring Boot 기반의 업비트 API 연동 암호화폐 자동매매 시스템입니다.

## 주요 기능

- 업비트 Open API 연동 (JWT 인증)
- 실시간 시세 조회 및 캔들 데이터 분석
- 다중 매매 전략 지원 (RSI, 골든크로스, 볼린저밴드)
- 스케줄러 기반 자동매매 실행
- REST API를 통한 수동 매매 및 모니터링

## 기술 스택

- Java 17
- Spring Boot 3.4.2
- Spring WebFlux (WebClient)
- JWT (jjwt)
- Lombok

## 프로젝트 구조

```
src/main/java/autostock/taesung/com/autostock/
├── exchange/upbit/
│   ├── UpbitApiService.java          # 업비트 API 서비스
│   └── dto/
│       ├── Account.java              # 계좌 정보
│       ├── Market.java               # 마켓 정보
│       ├── Ticker.java               # 시세 정보
│       ├── Candle.java               # 캔들 데이터
│       └── OrderResponse.java        # 주문 응답
├── strategy/
│   ├── TradingStrategy.java          # 전략 인터페이스
│   ├── TechnicalIndicator.java       # 기술적 지표 계산 (SMA, EMA, RSI, BB, MACD)
│   └── impl/
│       ├── RSIStrategy.java          # RSI 전략
│       ├── GoldenCrossStrategy.java  # 골든크로스 전략
│       └── BollingerBandStrategy.java # 볼린저밴드 전략
├── trading/
│   └── AutoTradingService.java       # 자동매매 서비스
├── backtest/
│   ├── BacktestService.java          # 백테스팅 서비스
│   └── dto/
│       ├── BacktestResult.java       # 백테스팅 결과
│       └── TradeRecord.java          # 거래 기록
├── scheduler/
│   └── UpbitTradingScheduler.java    # 스케줄러 (15분 간격)
└── api/controller/
    ├── UpbitController.java          # REST API 컨트롤러
    └── BacktestController.java       # 백테스팅 API 컨트롤러
```

## 설정 방법

### 1. 업비트 API 키 발급

1. [업비트](https://upbit.com) 로그인
2. 마이페이지 > Open API 관리
3. API 키 발급 (자산조회, 주문조회, 주문하기 권한 필요)

### 2. application.properties 설정

```properties
# 업비트 API 키
upbit.access-key=발급받은_ACCESS_KEY
upbit.secret-key=발급받은_SECRET_KEY

# 자동매매 설정
trading.enabled=true                    # 자동매매 활성화
trading.target-market=KRW-BTC          # 거래 대상 마켓
trading.investment-ratio=0.1           # 투자 비율 (10%)
trading.min-order-amount=5000          # 최소 주문 금액

# 스케줄러 설정
trading.schedule.cron=0 */15 * * * *   # 15분마다 실행
```

## 매매 전략

### RSI (Relative Strength Index)
- RSI 30 이하: 과매도 구간 → 매수 신호
- RSI 70 이상: 과매수 구간 → 매도 신호

### 골든크로스 / 데드크로스
- 5일 이동평균선이 20일선 상향 돌파: 매수 (골든크로스)
- 5일 이동평균선이 20일선 하향 돌파: 매도 (데드크로스)

### 볼린저밴드
- 가격이 하단밴드 터치: 매수 신호
- 가격이 상단밴드 터치: 매도 신호

### 매매 실행 조건
- 3개 전략 중 **과반수(2개) 이상**이 동일 신호일 때 매매 실행

## REST API

### 계좌 및 시세 조회

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| GET | `/api/upbit/accounts` | 전체 계좌 조회 |
| GET | `/api/upbit/markets` | 마켓 목록 조회 |
| GET | `/api/upbit/ticker?markets=KRW-BTC` | 현재가 조회 |
| GET | `/api/upbit/candles/minutes/{unit}?market=KRW-BTC&count=100` | 분봉 조회 |
| GET | `/api/upbit/candles/days?market=KRW-BTC&count=100` | 일봉 조회 |

### 주문

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| POST | `/api/upbit/orders/buy/market?market=KRW-BTC&price=10000` | 시장가 매수 |
| POST | `/api/upbit/orders/sell/market?market=KRW-BTC&volume=0.001` | 시장가 매도 |
| POST | `/api/upbit/orders/buy/limit?market=KRW-BTC&volume=0.001&price=50000000` | 지정가 매수 |
| POST | `/api/upbit/orders/sell/limit?market=KRW-BTC&volume=0.001&price=60000000` | 지정가 매도 |
| DELETE | `/api/upbit/orders/{uuid}` | 주문 취소 |
| GET | `/api/upbit/orders/{uuid}` | 주문 조회 |

### 자동매매

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| POST | `/api/upbit/trading/execute` | 자동매매 수동 실행 |
| GET | `/api/upbit/trading/status` | 보유 현황 조회 |

### 백테스팅 (시뮬레이션)

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| GET | `/api/backtest/run` | 전략 조합 백테스팅 실행 |
| GET | `/api/backtest/run/{strategy}` | 특정 전략 백테스팅 |
| GET | `/api/backtest/compare` | 모든 전략 비교 백테스팅 |
| GET | `/api/backtest/compare/summary` | 전략 비교 요약 |
| GET | `/api/backtest/strategies` | 사용 가능한 전략 목록 |

#### 백테스팅 파라미터

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `market` | KRW-BTC | 마켓 코드 |
| `initialBalance` | 1000000 | 초기 자본금 (원) |
| `candleUnit` | 15 | 캔들 단위 (분) - 1, 3, 5, 15, 30, 60, 240 |
| `candleCount` | 200 | 캔들 개수 (최대 200) |

#### 백테스팅 예시

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

#### 백테스팅 결과 예시

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

## 실행 방법

```bash
# 빌드
./gradlew build

# 실행
./gradlew bootRun
```

## 주의사항

- 본 프로그램은 투자 참고용이며, 투자 손실에 대한 책임은 사용자에게 있습니다.
- 실제 자금으로 거래하기 전 테스트를 충분히 진행하세요.
- API 키는 외부에 노출되지 않도록 주의하세요.
- `trading.enabled=false` 상태에서 먼저 로그를 확인하며 테스트하는 것을 권장합니다.

## 라이선스

MIT License
