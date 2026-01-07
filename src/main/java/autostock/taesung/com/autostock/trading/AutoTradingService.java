package autostock.taesung.com.autostock.trading;

import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.entity.TradeHistory.TradeType;
import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Account;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.exchange.upbit.dto.Market;
import autostock.taesung.com.autostock.exchange.upbit.dto.OrderResponse;
import autostock.taesung.com.autostock.exchange.upbit.dto.Ticker;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoTradingService {

    private final UpbitApiService upbitApiService;
    private final List<TradingStrategy> strategies;
    private final TradeHistoryRepository tradeHistoryRepository;

    // 업비트 수수료율 (0.05%)
    private static final double UPBIT_FEE_RATE = 0.0005;

    // 단일 마켓 설정 (하위 호환)
    @Value("${trading.target-market:KRW-BTC}")
    private String targetMarket;

    // 멀티 마켓 설정 (쉼표로 구분, 예: KRW-XRP,KRW-SOL,KRW-DOGE)
    @Value("${trading.target-markets:}")
    private String targetMarketsStr;

    // 제외할 마켓 (쉼표로 구분, 예: KRW-BTC,KRW-ETH)
    @Value("${trading.excluded-markets:}")
    private String excludedMarketsStr;

    // 멀티 마켓 자동매매 활성화
    @Value("${trading.multi-market-enabled:false}")
    private boolean multiMarketEnabled;

    // 상위 N개 마켓 자동 선택 (0이면 비활성화)
    @Value("${trading.auto-select-top:0}")
    private int autoSelectTop;

    @Value("${trading.investment-ratio:0.1}")
    private double investmentRatio;  // 투자 비율 (예: 0.1 = 10%)

    @Value("${trading.min-order-amount:6000}")
    private double minOrderAmount;   // 최소 주문 금액 (업비트 최소 6000원)

    // 손절 설정
    @Value("${trading.stop-loss-rate:-0.02}")
    private double stopLossRate;     // 손절률 (기본 -2%)

    @Value("${trading.stop-loss-enabled:true}")
    private boolean stopLossEnabled; // 손절 활성화 여부

    private final int minuteInterval = 10;
    private final int candleCount = 200;

    private List<String> targetMarkets = new ArrayList<>();
    private List<String> excludedMarkets = new ArrayList<>();

    @PostConstruct
    public void init() {
        // 제외 마켓 파싱
        if (excludedMarketsStr != null && !excludedMarketsStr.trim().isEmpty()) {
            excludedMarkets = Arrays.stream(excludedMarketsStr.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());
            log.info("제외 마켓: {}", excludedMarkets);
        }

        // 멀티 마켓 파싱
        if (targetMarketsStr != null && !targetMarketsStr.trim().isEmpty()) {
            targetMarkets = Arrays.stream(targetMarketsStr.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .filter(m -> !excludedMarkets.contains(m))
                    .collect(Collectors.toList());
        }

        log.info("자동매매 설정 - 멀티마켓: {}, 대상: {}, 제외: {}",
                multiMarketEnabled, targetMarkets.isEmpty() ? targetMarket : targetMarkets, excludedMarkets);
    }

    /**
     * 마켓이 거래 가능한지 확인
     */
    public boolean isMarketAllowed(String market) {
        return !excludedMarkets.contains(market.toUpperCase());
    }

    /**
     * 거래 대상 마켓 목록 조회
     */
    public List<String> getActiveMarkets() {
        if (!multiMarketEnabled) {
            // 단일 마켓 모드
            if (isMarketAllowed(targetMarket)) {
                return List.of(targetMarket);
            }
            return List.of();
        }

        // 상위 N개 자동 선택
        if (autoSelectTop > 0) {
            try {
                List<Market> markets = upbitApiService.getMarkets();
                return markets.stream()
                        .filter(m -> m.getMarket().startsWith("KRW-"))
                        .filter(m -> !"CAUTION".equals(m.getMarketWarning()))
                        .map(Market::getMarket)
                        .filter(this::isMarketAllowed)
                        .limit(autoSelectTop)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("마켓 목록 조회 실패: {}", e.getMessage());
            }
        }

        // 수동 지정 마켓
        if (!targetMarkets.isEmpty()) {
            return targetMarkets.stream()
                    .filter(this::isMarketAllowed)
                    .collect(Collectors.toList());
        }

        // 기본값
        return isMarketAllowed(targetMarket) ? List.of(targetMarket) : List.of();
    }

    /**
     * 자동매매 실행 (멀티 마켓 지원)
     */
    public void executeAutoTrading() {
        List<String> markets = getActiveMarkets();

        if (markets.isEmpty()) {
            log.warn("거래 가능한 마켓이 없습니다. 제외 마켓: {}", excludedMarkets);
            return;
        }

        log.info("========== 자동매매 시작 ==========");
        log.info("대상 마켓 {}개: {}", markets.size(), markets);
        log.info("제외 마켓: {}", excludedMarkets);
        log.info("손절 설정: {} ({}%)", stopLossEnabled ? "활성화" : "비활성화",
                String.format("%.1f", stopLossRate * 100));

        // 1. 먼저 보유 코인 손절 체크
        if (stopLossEnabled) {
            checkAndExecuteStopLoss();
        }

        // 2. 마켓별 전략 분석 및 매매
        for (String market : markets) {
            try {
                executeAutoTradingForMarket(market);
                Thread.sleep(200);  // API 속도 제한 방지
            } catch (Exception e) {
                log.error("[{}] 자동매매 실행 중 오류: {}", market, e.getMessage());
            }
        }

        log.info("========== 자동매매 종료 ==========\n");
    }

    /**
     * 보유 코인 손절 체크 및 실행
     */
    private void checkAndExecuteStopLoss() {
        log.info("----- 손절 체크 시작 -----");

        try {
            List<Account> accounts = upbitApiService.getAccounts();

            for (Account account : accounts) {
                // KRW는 스킵
                if ("KRW".equals(account.getCurrency())) {
                    continue;
                }

                double balance = Double.parseDouble(account.getBalance());
                if (balance <= 0) {
                    continue;
                }

                double avgBuyPrice = Double.parseDouble(account.getAvgBuyPrice());
                if (avgBuyPrice <= 0) {
                    continue;
                }

                String market = "KRW-" + account.getCurrency();

                // 제외 마켓이면 손절도 스킵
                if (!isMarketAllowed(market)) {
                    continue;
                }

                // 현재가 조회
                try {
                    List<Ticker> tickers = upbitApiService.getTicker(market);
                    if (tickers == null || tickers.isEmpty()) {
                        continue;
                    }

                    double currentPrice = tickers.get(0).getTradePrice().doubleValue();
                    double profitRate = (currentPrice - avgBuyPrice) / avgBuyPrice;

                    log.info("[{}] 손익률 체크 - 평균매수가: {}, 현재가: {}, 손익률: {}%",
                            market,
                            String.format("%.2f", avgBuyPrice),
                            String.format("%.2f", currentPrice),
                            String.format("%.2f", profitRate * 100));

                    // 손절 조건: 손익률이 손절률 이하
                    if (profitRate <= stopLossRate) {
                        log.warn("[{}] ⚠️ 손절 실행! 손익률 {}% <= 손절기준 {}%",
                                market,
                                String.format("%.2f", profitRate * 100),
                                String.format("%.1f", stopLossRate * 100));

                        executeStopLoss(market, currentPrice, balance, profitRate);
                    }

                    Thread.sleep(200);  // API 속도 제한 방지

                } catch (Exception e) {
                    log.debug("[{}] 현재가 조회 실패 (상장폐지 등): {}", market, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("손절 체크 중 오류: {}", e.getMessage());
        }

        log.info("----- 손절 체크 완료 -----");
    }

    /**
     * 손절 매도 실행
     */
    private void executeStopLoss(String market, double currentPrice, double coinBalance, double profitRate) {
        try {
            if(currentPrice * coinBalance < 5000){
                log.warn("5000원 미만 매도는 불가.");
                return;
            }
            OrderResponse order = upbitApiService.sellMarketOrder(market, coinBalance);
            log.warn("[{}] 🔴 손절 매도 완료! UUID: {}, 수량: {}, 손익률: {}%",
                    market, order.getUuid(), coinBalance, String.format("%.2f", profitRate * 100));

            // 매도 금액 계산
            double sellAmount = coinBalance * currentPrice;

            // 거래 내역 저장
            saveTradeHistory(market, TradeType.SELL, sellAmount, currentPrice, order.getUuid(),
                    String.format("Stop-Loss (%.1f%%)", stopLossRate * 100), null);

        } catch (Exception e) {
            log.error("[{}] 손절 매도 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 단일 마켓 자동매매 실행
     */
    private void executeAutoTradingForMarket(String market) {
        if (!isMarketAllowed(market)) {
            log.info("[{}] 제외된 마켓입니다. 스킵.", market);
            return;
        }

        log.info("----- [{}] 분석 시작 -----", market);

        try {
            // 1. 캔들 데이터 조회
            List<Candle> candles = upbitApiService.getMinuteCandles(market, minuteInterval, candleCount);
            if (candles == null || candles.size() < 50) {
                log.warn("[{}] 캔들 데이터 부족", market);
                return;
            }

            // 2. 현재가 조회
            List<Ticker> tickers = upbitApiService.getTicker(market);
            double currentPrice = tickers.get(0).getTradePrice().doubleValue();
            log.info("[{}] 현재가: {}", market, String.format("%.0f", currentPrice));

            // 3. 전략 분석 (다수결)
            int buySignals = 0;
            int sellSignals = 0;
            List<String> buyStrategies = new ArrayList<>();
            List<String> sellStrategies = new ArrayList<>();
            Double targetPrice = null;  // 목표 판매가

            for (TradingStrategy strategy : strategies) {
                try {
                    int signal = strategy.analyze(market, candles);
                    if (signal == 1) {
                        buySignals++;
                        buyStrategies.add(strategy.getStrategyName());
                        // 목표가가 있는 전략에서 목표가 수집 (볼린저밴드 등)
                        Double strategyTarget = strategy.getTargetPrice(market);
                        if (strategyTarget != null && targetPrice == null) {
                            targetPrice = strategyTarget;
                        }
                    } else if (signal == -1) {
                        sellSignals++;
                        sellStrategies.add(strategy.getStrategyName());
                    }
                } catch (Exception e) {
                    // 분석 실패 무시
                }
            }

            log.info("[{}] 전략 분석 - 매수: {}/{}, 매도: {}/{}",
                    market, buySignals, strategies.size(), sellSignals, strategies.size());

            // 4. 매매 실행 (과반수 이상 동의 시)
            int threshold = (strategies.size() / 2) + 1;

            if (buySignals >= threshold) {
                log.info("[{}] 매수 신호! 동의 전략: {}, 목표가: {}", market, buyStrategies,
                        targetPrice != null ? String.format("%.0f", targetPrice) : "없음");
                executeBuyForMarket(market, currentPrice, String.join(", ", buyStrategies), targetPrice);
            } else if (sellSignals >= threshold) {
                log.info("[{}] 매도 신호! 동의 전략: {}", market, sellStrategies);
                executeSellForMarket(market, currentPrice, String.join(", ", sellStrategies));
            } else {
                log.info("[{}] 관망 - 매매 조건 미충족", market);
            }

        } catch (Exception e) {
            log.error("[{}] 분석 중 오류: {}", market, e.getMessage());
        }
    }

    /**
     * 매수 실행 (특정 마켓)
     */
    private void executeBuyForMarket(String market, double currentPrice, String strategyName, Double targetPrice) {
        if (!isMarketAllowed(market)) {
            log.warn("[{}] 제외된 마켓입니다. 매수 취소.", market);
            return;
        }

        try {
            double krwBalance = upbitApiService.getKrwBalance();

            // 멀티 마켓인 경우 마켓 수로 나눔
            List<String> activeMarkets = getActiveMarkets();
            double marketRatio = multiMarketEnabled && activeMarkets.size() > 1
                    ? investmentRatio / activeMarkets.size()
                    : investmentRatio;

            double orderAmount = krwBalance * marketRatio;

            log.info("[{}] KRW 잔고: {}, 주문 금액: {}",
                    market,
                    String.format("%.0f", krwBalance),
                    String.format("%.0f", orderAmount));

            if (orderAmount < minOrderAmount) {
                log.warn("[{}] 주문 금액이 최소 주문 금액({})보다 작습니다.", market, minOrderAmount);
                orderAmount = minOrderAmount;
            }

            OrderResponse order = upbitApiService.buyMarketOrder(market, orderAmount);
            log.info("[{}] 매수 주문 완료! UUID: {}", market, order.getUuid());

            // 거래 내역 저장 (목표가 포함)
            saveTradeHistory(market, TradeType.BUY, orderAmount, currentPrice, order.getUuid(), strategyName, targetPrice);

        } catch (Exception e) {
            log.error("[{}] 매수 실행 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 매도 실행 (특정 마켓)
     */
    private void executeSellForMarket(String market, double currentPrice, String strategyName) {
        if (!isMarketAllowed(market)) {
            log.warn("[{}] 제외된 마켓입니다. 매도 취소.", market);
            return;
        }

        try {
            String currency = market.split("-")[1]; // KRW-BTC -> BTC
            double coinBalance = upbitApiService.getCoinBalance(currency);

            log.info("[{}] {} 보유량: {}", market, currency, coinBalance);

            if (coinBalance <= 0) {
                log.warn("[{}] 매도할 코인이 없습니다.", market);
                return;
            }

            OrderResponse order = upbitApiService.sellMarketOrder(market, coinBalance);
            log.info("[{}] 매도 주문 완료! UUID: {}", market, order.getUuid());

            // 매도 금액 계산 (수량 * 현재가)
            double sellAmount = coinBalance * currentPrice;

            // 거래 내역 저장 (매도 시 목표가 없음)
            saveTradeHistory(market, TradeType.SELL, sellAmount, currentPrice, order.getUuid(), strategyName, null);

            // 전략별 포지션 청산
            for (TradingStrategy strategy : strategies) {
                strategy.clearPosition(market);
            }

        } catch (Exception e) {
            log.error("[{}] 매도 실행 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 거래 내역 저장
     */
    private void saveTradeHistory(String market, TradeType tradeType, double amount,
                                   double price, String orderUuid, String strategyName, Double targetPrice) {
        try {
            double volume = amount / price;
            double fee = amount * UPBIT_FEE_RATE;

            TradeHistory history = TradeHistory.builder()
                    .market(market)
                    .tradeMethod("MARKET")  // 시장가 주문
                    .tradeDate(LocalDate.now())
                    .tradeTime(LocalTime.now())
                    .tradeType(tradeType)
                    .amount(BigDecimal.valueOf(amount))
                    .volume(BigDecimal.valueOf(volume))
                    .price(BigDecimal.valueOf(price))
                    .fee(BigDecimal.valueOf(fee))
                    .orderUuid(orderUuid)
                    .strategyName(strategyName)
                    .targetPrice(targetPrice != null ? BigDecimal.valueOf(targetPrice) : null)
                    .highestPrice(tradeType == TradeType.BUY ? BigDecimal.valueOf(price) : null)  // 매수 시 최고가 초기화
                    .build();

            tradeHistoryRepository.save(history);
            log.info("[{}] 거래 내역 저장 완료 - {}, 금액: {}, 수수료: {}, 목표가: {}",
                    market, tradeType, String.format("%.0f", amount), String.format("%.0f", fee),
                    targetPrice != null ? String.format("%.0f", targetPrice) : "없음");

        } catch (Exception e) {
            log.error("[{}] 거래 내역 저장 실패: {}", market, e.getMessage());
        }
    }

    // 하위 호환을 위한 기존 메서드 (단일 마켓)
    private void executeBuy(double currentPrice) {
        executeBuyForMarket(targetMarket, currentPrice, "Manual", null);
    }

    private void executeSell() {
        executeSellForMarket(targetMarket, 0, "Manual");
    }

    /**
     * 현재 보유 현황 조회
     */
    public void printAccountStatus() {
        log.info("========== 보유 현황 ==========");
        try {
            List<Account> accounts = upbitApiService.getAccounts();
            for (Account account : accounts) {
                if (Double.parseDouble(account.getBalance()) > 0) {
                    log.info("{}: {} (평균 매수가: {})",
                            account.getCurrency(),
                            account.getBalance(),
                            account.getAvgBuyPrice());
                }
            }
        } catch (Exception e) {
            log.error("보유 현황 조회 실패: {}", e.getMessage());
        }
        log.info("==============================\n");
    }

    /**
     * 단일 전략으로 매매 실행
     */
    public void executeWithStrategy(TradingStrategy strategy) {
        log.info("========== {} 전략 매매 시작 ==========", strategy.getStrategyName());

        try {
            List<Candle> candles = upbitApiService.getMinuteCandles(targetMarket, minuteInterval, candleCount);
            List<Ticker> tickers = upbitApiService.getTicker(targetMarket);
            double currentPrice = tickers.get(0).getTradePrice().doubleValue();

            int signal = strategy.analyze(candles);

            if (signal == 1) {
                executeBuy(currentPrice);
            } else if (signal == -1) {
                executeSell();
            } else {
                log.info("관망");
            }

        } catch (Exception e) {
            log.error("전략 매매 실행 중 오류: {}", e.getMessage());
        }

        log.info("========== {} 전략 매매 종료 ==========\n", strategy.getStrategyName());
    }
}
