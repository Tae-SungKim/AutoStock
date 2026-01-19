package autostock.taesung.com.autostock.trading;

import autostock.taesung.com.autostock.entity.CandleData;
import autostock.taesung.com.autostock.entity.TickerData;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.entity.TradeHistory.TradeType;
import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Account;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.exchange.upbit.dto.Market;
import autostock.taesung.com.autostock.exchange.upbit.dto.OrderResponse;
import autostock.taesung.com.autostock.exchange.upbit.dto.Ticker;
import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.repository.CandleDataRepository;
import autostock.taesung.com.autostock.repository.TickerDataRepository;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import autostock.taesung.com.autostock.strategy.impl.ScaledTradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoTradingService {

    private final UpbitApiService upbitApiService;
    private final List<TradingStrategy> strategies;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final CandleDataRepository candleDataRepository;
    private final TickerDataRepository tickerDataRepository;

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

    // 마켓 범위 설정 (분산 서버용)
    @Value("${trading.market-range-start:0}")
    private int marketRangeStart;

    @Value("${trading.market-range-count:100}")
    private int marketRangeCount;

    @Value("${trading.investment-ratio:0.2}")
    private double investmentRatio;  // 투자 비율 (예: 0.1 = 10%)

    @Value("${trading.min-order-amount:6000}")
    private double minOrderAmount;   // 최소 주문 금액 (업비트 최소 6000원)

    // 손절 설정
    @Value("${trading.stop-loss-rate:-0.02}")
    private double stopLossRate;     // 손절률 (기본 -2%)

    @Value("${trading.stop-loss-enabled:true}")
    private boolean stopLossEnabled; // 손절 활성화 여부

    // 전략 모드 설정 (DEFAULT: 다수결, SCALED_TRADING: 분할매매)
    @Value("${trading.strategy-mode:DEFAULT}")
    private String strategyMode;

    // ===== 주문 타입 설정 =====
    // 주문 타입 (MARKET: 시장가, LIMIT: 지정가)
    @Value("${trading.order-type:MARKET}")
    private String orderType;

    // 지정가 매수 시 가격 오프셋 (현재가 대비 %, 양수면 높게)
    @Value("${trading.limit-order.buy-offset:0.001}")
    private double limitBuyOffset;

    // 지정가 매도 시 가격 오프셋 (현재가 대비 %, 양수면 낮게)
    @Value("${trading.limit-order.sell-offset:0.001}")
    private double limitSellOffset;

    // 지정가 주문 체결 대기 시간 (초)
    @Value("${trading.limit-order.timeout-seconds:30}")
    private int limitOrderTimeout;

    // 지정가 주문 체결 확인 간격 (초)
    @Value("${trading.limit-order.poll-interval-seconds:2}")
    private int limitOrderPollInterval;

    // 미체결 시 재시도 횟수
    @Value("${trading.limit-order.retry-count:2}")
    private int limitOrderRetryCount;

    // 재시도 시 가격 조정률 (더 유리한 가격으로)
    @Value("${trading.limit-order.retry-price-adjust:0.002}")
    private double limitOrderRetryPriceAdjust;

    private final int minuteInterval = 1;
    private final int candleCount = 200;

    // 분할매매 전략 (주입)
    private final RealTradingConfig realTradingConfig;

    private List<String> targetMarkets = new ArrayList<>();
    private List<String> excludedMarkets = new ArrayList<>();

    // ===== 리스크 관리용 변수 =====
    // 일일 손실 추적 (userId -> 손실금액)
    private final Map<Long, BigDecimal> dailyLossMap = new ConcurrentHashMap<>();
    // 일일 거래 횟수 추적
    private final Map<Long, Integer> dailyTradeCountMap = new ConcurrentHashMap<>();
    // 연속 손실 횟수
    private final Map<Long, Integer> consecutiveLossMap = new ConcurrentHashMap<>();
    // 쿨다운 상태 (userId -> 쿨다운 종료 시간)
    private final Map<Long, LocalDateTime> cooldownMap = new ConcurrentHashMap<>();

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

        // 상위 N개 자동 선택 (분산 서버용 범위 적용)
        if (autoSelectTop > 0 || marketRangeCount > 0) {
            try {
                List<Market> markets = upbitApiService.getMarkets();
                return markets.stream()
                        .filter(m -> m.getMarket().startsWith("KRW-"))
                        .filter(m -> !"CAUTION".equals(m.getMarketWarning()))
                        .map(Market::getMarket)
                        .filter(this::isMarketAllowed)
                        .skip(marketRangeStart)
                        .limit(marketRangeCount)
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
    public void executeAutoTrading(User user) {
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
            checkAndExecuteStopLoss(user);
        }

        // 2. 마켓별 전략 분석 및 매매
        for (String market : markets) {
            try {
                executeAutoTradingForMarket(user, market);
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
    private void checkAndExecuteStopLoss(User user) {
        log.info("----- 손절 체크 시작 -----");

        try {
            List<Account> accounts = upbitApiService.getAccounts(user);

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

                        executeStopLoss(user, market, currentPrice, balance, profitRate);
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
    private void executeStopLoss(User user, String market, double currentPrice, double coinBalance, double profitRate) {
        try {
            if(currentPrice * coinBalance < 5000){
                log.warn("5000원 미만 매도는 불가.");
                return;
            }
            OrderResponse order = upbitApiService.sellMarketOrder(user, market, coinBalance);
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
    private void executeAutoTradingForMarket(User user, String market) {
        if (!isMarketAllowed(market)) {
            log.info("[{}] 제외된 마켓입니다. 스킵.", market);
            return;
        }

        log.info("----- [{}] 분석 시작 (모드: {}) -----", market, strategyMode);

        try {
            // DB에 데이터가 충분한지 확인하여 API 호출 갯수 조절
            int fetchCount = candleCount;
            Optional<CandleData> lastCandle = candleDataRepository.findFirstByMarketAndUnitOrderByCandleDateTimeKstDesc(market, minuteInterval);

            if (lastCandle.isEmpty()) {
                fetchCount = candleCount;
            } else {
                fetchCount = candleCount;
            }

            // 1. 캔들 데이터 조회
            List<Candle> candles = upbitApiService.getMinuteCandles(market, minuteInterval, fetchCount);
            if (candles == null || candles.size() < 50) {
                log.warn("[{}] 캔들 데이터 부족", market);
                return;
            }

            // 캔들 데이터 DB 저장 (최신 데이터 위주로 저장, 중복 제외)
            saveCandlesToDb(candles);

            // 2. 현재가 조회
            List<Ticker> tickers = upbitApiService.getTicker(market);
            if (tickers == null || tickers.isEmpty()) {
                log.warn("[{}] 현재가 조회 실패", market);
                return;
            }
            Ticker ticker = tickers.get(0);
            double currentPrice = ticker.getTradePrice().doubleValue();
            log.info("[{}] 현재가: {}", market, String.format("%.0f", currentPrice));

            // 현재가 DB 저장
            saveTickerToDb(ticker);

            // 전략 모드에 따라 분기
            if ("SCALED_TRADING".equalsIgnoreCase(strategyMode)) {
                executeScaledTradingForMarket(user, market, candles, currentPrice);
            } else {
                executeDefaultTradingForMarket(user, market, candles, currentPrice);
            }

        } catch (Exception e) {
            log.error("[{}] 분석 중 오류: {}", market, e.getMessage());
        }
    }

    /**
     * 기본 매매 모드 (다수결 전략 + 분할매도 + 리스크 관리)
     */
    private void executeDefaultTradingForMarket(User user, String market, List<Candle> candles, double currentPrice) {
        int buySignals = 0;
        int sellSignals = 0;
        List<String> buyStrategies = new ArrayList<>();
        List<String> sellStrategies = new ArrayList<>();
        Double targetPrice = null;

        for (TradingStrategy strategy : strategies) {
            try {
                int signal = strategy.analyze(market, candles);
                if (signal == 1) {
                    buySignals++;
                    buyStrategies.add(strategy.getStrategyName());
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

        int threshold = (strategies.size() / 2) + 1;

        // 보유 포지션 체크 (분할매도/트레일링 스탑 적용)
        TradeHistory latestBuy = tradeHistoryRepository.findLatestByMarket(market)
                .stream()
                .filter(h -> h.getTradeType() == TradeType.BUY)
                .findFirst().orElse(null);

        boolean isHolding = latestBuy != null && !isPositionFullyClosed(market, latestBuy);

        if (isHolding) {
            // 보유 중인 경우: 분할매도 로직 적용
            executeScaledExitLogic(user, market, currentPrice, latestBuy, sellSignals >= threshold,
                    String.join(", ", sellStrategies), candles);
        } else if (buySignals >= threshold) {
            // 리스크 체크 후 매수
            if (checkRiskBeforeEntry(user, market)) {
                log.info("[{}] 매수 신호! 동의 전략: {}, 목표가: {}", market, buyStrategies,
                        targetPrice != null ? String.format("%.0f", targetPrice) : "없음");
                executeBuyWithScaledEntry(user, market, currentPrice, String.join(", ", buyStrategies), targetPrice, candles);
            } else {
                log.warn("[{}] 리스크 한도 초과로 매수 보류", market);
            }
        } else {
            log.info("[{}] 관망 - 매매 조건 미충족", market);
        }
    }

    /**
     * 포지션이 완전히 청산되었는지 확인
     */
    private boolean isPositionFullyClosed(String market, TradeHistory buyHistory) {
        // 매수 이후 전량 매도가 있었는지 확인
        List<TradeHistory> sellsAfterBuy = tradeHistoryRepository.findLatestByMarket(market)
                .stream()
                .filter(h -> h.getTradeType() == TradeType.SELL)
                .filter(h -> h.getCreatedAt().isAfter(buyHistory.getCreatedAt()))
                .toList();

        if (sellsAfterBuy.isEmpty()) {
            return false;
        }

        // exitPhase가 2 (전량청산)인 매도가 있으면 포지션 종료
        return sellsAfterBuy.stream()
                .anyMatch(h -> h.getExitPhase() != null && h.getExitPhase() >= 2);
    }

    /**
     * 분할매도 로직 실행
     */
    private void executeScaledExitLogic(User user, String market, double currentPrice,
                                         TradeHistory buyHistory, boolean hasSellSignal,
                                         String sellStrategies, List<Candle> candles) {
        double buyPrice = buyHistory.getAvgEntryPrice() != null
                ? buyHistory.getAvgEntryPrice().doubleValue()
                : buyHistory.getPrice().doubleValue();
        double highestPrice = buyHistory.getHighestPrice() != null
                ? buyHistory.getHighestPrice().doubleValue()
                : currentPrice;

        // 최고가 갱신
        if (currentPrice > highestPrice) {
            buyHistory.setHighestPrice(BigDecimal.valueOf(currentPrice));
            tradeHistoryRepository.save(buyHistory);
            highestPrice = currentPrice;
        }

        double profitRate = (currentPrice - buyPrice) / buyPrice;
        boolean halfSold = buyHistory.getHalfSold() != null && buyHistory.getHalfSold();
        boolean trailingActive = buyHistory.getTrailingActive() != null && buyHistory.getTrailingActive();

        double atr = calculateATR(candles, 14);

        log.info("[{}] 분할매도 체크 - 매수가: {}, 현재가: {}, 수익률: {}%, 1차익절완료: {}, 트레일링: {}",
                market, String.format("%.0f", buyPrice), String.format("%.0f", currentPrice),
                String.format("%.2f", profitRate * 100), halfSold, trailingActive);

        // 1. 손절 체크 (최우선)
        double stopLossPrice = buyPrice * (1 + realTradingConfig.getMaxStopLossRate());
        if (currentPrice <= stopLossPrice) {
            log.warn("[{}] 손절 실행! 현재가 {} <= 손절가 {}", market,
                    String.format("%.0f", currentPrice), String.format("%.0f", stopLossPrice));
            executeFullExit(user, market, currentPrice, "손절", 2);
            recordLoss(user.getId(), buyHistory, currentPrice);
            return;
        }

        // 2. 1차 익절 (50%) - 아직 안 했으면
        if (!halfSold && profitRate >= realTradingConfig.getPartialTakeProfitRate()) {
            log.info("[{}] 1차 익절 실행! 수익률 {}% >= {}%", market,
                    String.format("%.2f", profitRate * 100),
                    String.format("%.1f", realTradingConfig.getPartialTakeProfitRate() * 100));
            executePartialExit(user, market, currentPrice, "1차익절", realTradingConfig.getPartialExitRatio());

            // 1차 익절 완료 표시
            buyHistory.setHalfSold(true);
            buyHistory.setExitPhase(1);
            tradeHistoryRepository.save(buyHistory);
            return;
        }

        // 3. 트레일링 스탑 활성화 체크
        if (halfSold && !trailingActive && profitRate >= realTradingConfig.getTrailingActivationThreshold()) {
            double trailingStopPrice = calculateTrailingStopPrice(highestPrice, atr);
            buyHistory.setTrailingActive(true);
            buyHistory.setTrailingStopPrice(BigDecimal.valueOf(trailingStopPrice));
            tradeHistoryRepository.save(buyHistory);
            log.info("[{}] 트레일링 스탑 활성화! 고점: {}, 스탑가: {}", market,
                    String.format("%.0f", highestPrice), String.format("%.0f", trailingStopPrice));
        }

        // 4. 트레일링 스탑 체크 및 실행
        if (trailingActive) {
            double trailingStopPrice = buyHistory.getTrailingStopPrice() != null
                    ? buyHistory.getTrailingStopPrice().doubleValue()
                    : calculateTrailingStopPrice(highestPrice, atr);

            // 고점 갱신 시 트레일링 스탑가도 갱신
            if (currentPrice > highestPrice) {
                trailingStopPrice = calculateTrailingStopPrice(currentPrice, atr);
                buyHistory.setTrailingStopPrice(BigDecimal.valueOf(trailingStopPrice));
                tradeHistoryRepository.save(buyHistory);
            }

            if (currentPrice <= trailingStopPrice) {
                log.info("[{}] 트레일링 스탑 실행! 현재가 {} <= 스탑가 {}", market,
                        String.format("%.0f", currentPrice), String.format("%.0f", trailingStopPrice));
                executeFullExit(user, market, currentPrice, "트레일링스탑", 2);
                recordProfit(user.getId(), buyHistory, currentPrice);
                return;
            }
        }

        // 5. 매도 신호에 의한 청산 (1차 익절 완료 후)
        if (halfSold && hasSellSignal) {
            log.info("[{}] 전략 신호에 의한 전량 청산! 전략: {}", market, sellStrategies);
            executeFullExit(user, market, currentPrice, "신호청산_" + sellStrategies, 2);
            if (profitRate > 0) {
                recordProfit(user.getId(), buyHistory, currentPrice);
            } else {
                recordLoss(user.getId(), buyHistory, currentPrice);
            }
        }
    }

    /**
     * 트레일링 스탑가 계산
     */
    private double calculateTrailingStopPrice(double highPrice, double atr) {
        double atrDistance = atr * realTradingConfig.getTrailingAtrMultiplier();
        double minDistance = highPrice * realTradingConfig.getTrailingStopRate();
        double distance = Math.max(atrDistance, minDistance);
        return highPrice - distance;
    }

    /**
     * ATR 계산
     */
    private double calculateATR(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 0;
        double sumTR = 0;
        for (int i = 0; i < period; i++) {
            double high = candles.get(i).getHighPrice().doubleValue();
            double low = candles.get(i).getLowPrice().doubleValue();
            double prevClose = candles.get(i + 1).getTradePrice().doubleValue();
            double tr = Math.max(high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sumTR += tr;
        }
        return sumTR / period;
    }

    /**
     * 부분 청산 실행 (분할매도) - 지정가/시장가 지원
     */
    private void executePartialExit(User user, String market, double currentPrice, String reason, double exitRatio) {
        try {
            String currency = market.split("-")[1];
            double coinBalance = upbitApiService.getCoinBalance(user, currency);

            if (coinBalance <= 0) {
                log.warn("[{}] 매도할 코인이 없습니다.", market);
                return;
            }

            double sellAmount = coinBalance * exitRatio;
            double sellValue = sellAmount * currentPrice;

            if (sellValue < 5000) {
                log.warn("[{}] 부분 청산 금액이 최소 주문 금액 미만. 스킵.", market);
                return;
            }

            // 주문 실행 (시장가 또는 지정가)
            OrderResult orderResult = executeSellOrder(user, market, sellAmount, currentPrice);

            if (!orderResult.isSuccess()) {
                log.error("[{}] 부분 매도 실패: {}", market, orderResult.getErrorMessage());
                return;
            }

            log.info("[{}] 부분 매도 완료! UUID: {}, 청산비율: {}%, 수량: {}, 체결가: {}, 주문타입: {}",
                    market, orderResult.getUuid(),
                    String.format("%.0f", exitRatio * 100),
                    String.format("%.8f", orderResult.getExecutedVolume()),
                    String.format("%.0f", orderResult.getExecutedPrice()),
                    orderResult.getOrderType());

            double actualSellValue = orderResult.getExecutedPrice() * orderResult.getExecutedVolume();
            saveTradeHistoryWithPhase(market, TradeType.SELL, actualSellValue, orderResult.getExecutedPrice(),
                    orderResult.getUuid(), reason + "_" + orderResult.getOrderType(), null, 1);

        } catch (Exception e) {
            log.error("[{}] 부분 매도 실행 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 전량 청산 실행 - 지정가/시장가 지원
     */
    private void executeFullExit(User user, String market, double currentPrice, String reason, int exitPhase) {
        try {
            String currency = market.split("-")[1];
            double coinBalance = upbitApiService.getCoinBalance(user, currency);

            if (coinBalance <= 0) {
                log.warn("[{}] 매도할 코인이 없습니다.", market);
                return;
            }

            double sellValue = coinBalance * currentPrice;
            if (sellValue < 5000) {
                log.warn("[{}] 청산 금액이 최소 주문 금액 미만.", market);
                return;
            }

            // 손절인 경우 시장가로 빠르게 처리
            boolean isUrgent = reason.contains("손절") || reason.contains("StopLoss");
            OrderResult orderResult;

            if (isUrgent) {
                log.info("[{}] 긴급 청산 - 시장가로 처리", market);
                orderResult = executeMarketSellOrder(user, market, coinBalance, currentPrice);
            } else {
                orderResult = executeSellOrder(user, market, coinBalance, currentPrice);
            }

            if (!orderResult.isSuccess()) {
                log.error("[{}] 전량 매도 실패: {}", market, orderResult.getErrorMessage());
                return;
            }

            log.info("[{}] 전량 매도 완료! UUID: {}, 수량: {}, 체결가: {}, 사유: {}, 주문타입: {}",
                    market, orderResult.getUuid(),
                    String.format("%.8f", orderResult.getExecutedVolume()),
                    String.format("%.0f", orderResult.getExecutedPrice()),
                    reason, orderResult.getOrderType());

            double actualSellValue = orderResult.getExecutedPrice() * orderResult.getExecutedVolume();
            saveTradeHistoryWithPhase(market, TradeType.SELL, actualSellValue, orderResult.getExecutedPrice(),
                    orderResult.getUuid(), reason + "_" + orderResult.getOrderType(), null, exitPhase);

            // 전략별 포지션 청산
            for (TradingStrategy strategy : strategies) {
                strategy.clearPosition(market);
            }

        } catch (Exception e) {
            log.error("[{}] 전량 매도 실행 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 리스크 체크 (매수 전)
     */
    private boolean checkRiskBeforeEntry(User user, String market) {
        Long userId = user.getId();

        // 1. 쿨다운 체크
        if (isInCooldown(userId)) {
            log.warn("[{}] 쿨다운 중 - 매수 불가", market);
            return false;
        }

        // 2. 연속 손실 체크
        int consecutiveLosses = consecutiveLossMap.getOrDefault(userId, 0);
        if (consecutiveLosses >= realTradingConfig.getMaxConsecutiveLosses()) {
            activateCooldown(userId);
            log.warn("[{}] 연속 손실 {}회 - 쿨다운 활성화", market, consecutiveLosses);
            return false;
        }

        // 3. 동시 포지션 수 체크
        int activePositions = countActivePositions(user);
        if (activePositions >= realTradingConfig.getMaxConcurrentPositions()) {
            log.warn("[{}] 동시 포지션 수 초과 ({}/{})", market,
                    activePositions, realTradingConfig.getMaxConcurrentPositions());
            return false;
        }

        // 4. 일일 손실 한도 체크
        try {
            double krwBalance = upbitApiService.getKrwBalance(user);
            BigDecimal todayLoss = dailyLossMap.getOrDefault(userId, BigDecimal.ZERO);
            BigDecimal maxDailyLoss = BigDecimal.valueOf(krwBalance * realTradingConfig.getMaxDailyLossRate());

            if (todayLoss.abs().compareTo(maxDailyLoss) >= 0) {
                log.warn("[{}] 일일 손실 한도 초과 (현재: {}, 한도: {})", market,
                        todayLoss.setScale(0, RoundingMode.HALF_UP),
                        maxDailyLoss.setScale(0, RoundingMode.HALF_UP));
                return false;
            }
        } catch (Exception e) {
            log.error("일일 손실 체크 실패: {}", e.getMessage());
        }

        return true;
    }

    /**
     * 활성 포지션 수 계산
     */
    private int countActivePositions(User user) {
        try {
            List<Account> accounts = upbitApiService.getAccounts(user);
            return (int) accounts.stream()
                    .filter(a -> !"KRW".equals(a.getCurrency()))
                    .filter(a -> Double.parseDouble(a.getBalance()) > 0)
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 쿨다운 활성화
     */
    private void activateCooldown(Long userId) {
        LocalDateTime until = LocalDateTime.now().plusMinutes(realTradingConfig.getCooldownMinutes());
        cooldownMap.put(userId, until);
        log.warn("쿨다운 활성화: userId={}, until={}", userId, until);
    }

    /**
     * 쿨다운 상태 확인
     */
    private boolean isInCooldown(Long userId) {
        LocalDateTime until = cooldownMap.get(userId);
        if (until == null) return false;
        if (LocalDateTime.now().isAfter(until)) {
            cooldownMap.remove(userId);
            consecutiveLossMap.remove(userId);
            return false;
        }
        return true;
    }

    /**
     * 손실 기록 (리스크 관리용)
     */
    private void recordLoss(Long userId, TradeHistory buyHistory, double exitPrice) {
        double buyPrice = buyHistory.getPrice().doubleValue();
        double volume = buyHistory.getVolume().doubleValue();
        BigDecimal loss = BigDecimal.valueOf((exitPrice - buyPrice) * volume);

        dailyLossMap.merge(userId, loss, BigDecimal::add);
        consecutiveLossMap.merge(userId, 1, Integer::sum);

        log.info("손실 기록: userId={}, loss={}, 연속손실={}", userId,
                loss.setScale(0, RoundingMode.HALF_UP), consecutiveLossMap.get(userId));
    }

    /**
     * 수익 기록 (연속 손실 초기화)
     */
    private void recordProfit(Long userId, TradeHistory buyHistory, double exitPrice) {
        consecutiveLossMap.put(userId, 0);
        log.info("수익 거래: userId={}, 연속손실 초기화", userId);
    }

    /**
     * 분할매수 진입
     */
    private void executeBuyWithScaledEntry(User user, String market, double currentPrice,
                                            String strategyName, Double targetPrice, List<Candle> candles) {
        if (!isMarketAllowed(market)) {
            log.warn("[{}] 제외된 마켓입니다. 매수 취소.", market);
            return;
        }

        try {
            double krwBalance = upbitApiService.getKrwBalance(user);
            double atr = calculateATR(candles, 14);

            // 분할매수 1차 진입 (30%)
            double entryRatio = realTradingConfig.getEntryRatio(1);
            double positionRatio = realTradingConfig.getMaxPositionSizeRate();
            double orderAmount = krwBalance * positionRatio * entryRatio;

            log.info("[{}] 분할매수 1차 진입 - KRW 잔고: {}, 진입비율: {}%, 주문금액: {}, 주문타입: {}",
                    market, String.format("%.0f", krwBalance),
                    String.format("%.0f", entryRatio * 100),
                    String.format("%.0f", orderAmount), orderType);

            if (orderAmount < minOrderAmount) {
                orderAmount = minOrderAmount;
            }

            // 주문 실행 (시장가 또는 지정가)
            OrderResult orderResult = executeBuyOrder(user, market, orderAmount, currentPrice);

            if (!orderResult.isSuccess()) {
                log.error("[{}] 분할매수 실패: {}", market, orderResult.getErrorMessage());
                return;
            }

            log.info("[{}] 분할매수 1차 완료! UUID: {}, 체결가: {}",
                    market, orderResult.getUuid(), String.format("%.0f", orderResult.getExecutedPrice()));

            // 손절가 계산 (ATR 기반)
            double stopLossDistance = atr * realTradingConfig.getStopLossAtrMultiplier();
            double maxStopDistance = currentPrice * Math.abs(realTradingConfig.getMaxStopLossRate());
            double minStopDistance = currentPrice * Math.abs(realTradingConfig.getMinStopLossRate());
            stopLossDistance = Math.max(minStopDistance, Math.min(stopLossDistance, maxStopDistance));

            // 거래 내역 저장 (분할매수 정보 포함)
            double executedPrice = orderResult.getExecutedPrice();
            double volume = orderResult.getExecutedVolume();
            double executedAmount = executedPrice * volume;

            TradeHistory history = TradeHistory.builder()
                    .market(market)
                    .tradeMethod(orderType)
                    .tradeDate(LocalDate.now())
                    .tradeTime(LocalTime.now())
                    .tradeType(TradeType.BUY)
                    .amount(BigDecimal.valueOf(executedAmount))
                    .volume(BigDecimal.valueOf(volume))
                    .price(BigDecimal.valueOf(executedPrice))
                    .fee(BigDecimal.valueOf(executedAmount * UPBIT_FEE_RATE))
                    .orderUuid(orderResult.getUuid())
                    .strategyName(strategyName)
                    .targetPrice(targetPrice != null ? BigDecimal.valueOf(targetPrice) : null)
                    .highestPrice(BigDecimal.valueOf(executedPrice))
                    .avgEntryPrice(BigDecimal.valueOf(executedPrice))
                    .totalInvested(BigDecimal.valueOf(executedAmount))
                    .entryPhase(1)
                    .exitPhase(0)
                    .halfSold(false)
                    .trailingActive(false)
                    .isStopLoss(false)
                    .build();

            tradeHistoryRepository.save(history);

        } catch (Exception e) {
            log.error("[{}] 분할매수 실행 실패: {}", market, e.getMessage());
        }
    }

    // ==================== 지정가/시장가 주문 통합 메서드 ====================

    /**
     * 매수 주문 실행 (시장가 또는 지정가)
     */
    private OrderResult executeBuyOrder(User user, String market, double orderAmount, double currentPrice) {
        if ("LIMIT".equalsIgnoreCase(orderType)) {
            return executeLimitBuyOrder(user, market, orderAmount, currentPrice);
        } else {
            return executeMarketBuyOrder(user, market, orderAmount, currentPrice);
        }
    }

    /**
     * 매도 주문 실행 (시장가 또는 지정가)
     */
    private OrderResult executeSellOrder(User user, String market, double volume, double currentPrice) {
        if ("LIMIT".equalsIgnoreCase(orderType)) {
            return executeLimitSellOrder(user, market, volume, currentPrice);
        } else {
            return executeMarketSellOrder(user, market, volume, currentPrice);
        }
    }

    /**
     * 시장가 매수 실행
     */
    private OrderResult executeMarketBuyOrder(User user, String market, double orderAmount, double currentPrice) {
        try {
            OrderResponse order = upbitApiService.buyMarketOrder(user, market, orderAmount);
            double volume = orderAmount / currentPrice;
            return OrderResult.success(order.getUuid(), currentPrice, volume, "MARKET");
        } catch (Exception e) {
            return OrderResult.failed(e.getMessage());
        }
    }

    /**
     * 시장가 매도 실행
     */
    private OrderResult executeMarketSellOrder(User user, String market, double volume, double currentPrice) {
        try {
            OrderResponse order = upbitApiService.sellMarketOrder(user, market, volume);
            return OrderResult.success(order.getUuid(), currentPrice, volume, "MARKET");
        } catch (Exception e) {
            return OrderResult.failed(e.getMessage());
        }
    }

    /**
     * 지정가 매수 실행 (체결 확인 + 재시도 로직)
     */
    private OrderResult executeLimitBuyOrder(User user, String market, double orderAmount, double currentPrice) {
        int retryCount = 0;
        double adjustedPrice = currentPrice;

        while (retryCount <= limitOrderRetryCount) {
            try {
                // 호가창에서 최적 매수가 계산
                double limitPrice = calculateLimitBuyPrice(market, adjustedPrice);
                double volume = orderAmount / limitPrice;

                log.info("[{}] 지정가 매수 시도 ({}/{}) - 가격: {}, 수량: {}",
                        market, retryCount + 1, limitOrderRetryCount + 1,
                        String.format("%.0f", limitPrice), String.format("%.8f", volume));

                OrderResponse order = upbitApiService.buyLimitOrder(user, market, volume, limitPrice);
                String uuid = order.getUuid();

                // 체결 대기 및 확인
                OrderResult result = waitForOrderExecution(user, uuid, limitPrice, volume, "BUY");

                if (result.isSuccess()) {
                    log.info("[{}] 지정가 매수 체결 완료! 가격: {}, 수량: {}",
                            market, String.format("%.0f", result.getExecutedPrice()),
                            String.format("%.8f", result.getExecutedVolume()));
                    return result;
                } else if (result.isPartialFill()) {
                    log.info("[{}] 지정가 매수 부분 체결 - 체결수량: {}",
                            market, String.format("%.8f", result.getExecutedVolume()));
                    // 부분 체결도 성공으로 처리
                    return result;
                } else {
                    // 미체결 - 주문 취소 후 재시도
                    log.warn("[{}] 지정가 매수 미체결 - 주문 취소 후 재시도", market);
                    try {
                        upbitApiService.cancelOrder(user, uuid);
                    } catch (Exception e) {
                        log.warn("[{}] 주문 취소 실패 (이미 체결되었을 수 있음): {}", market, e.getMessage());
                    }

                    retryCount++;
                    // 재시도 시 가격 상향 조정 (체결률 향상)
                    adjustedPrice = limitPrice * (1 + limitOrderRetryPriceAdjust);
                    Thread.sleep(1000);
                }

            } catch (Exception e) {
                log.error("[{}] 지정가 매수 오류: {}", market, e.getMessage());
                retryCount++;
                adjustedPrice = adjustedPrice * (1 + limitOrderRetryPriceAdjust);
            }
        }

        // 모든 재시도 실패 시 시장가로 폴백
        log.warn("[{}] 지정가 매수 재시도 초과 - 시장가로 전환", market);
        return executeMarketBuyOrder(user, market, orderAmount, currentPrice);
    }

    /**
     * 지정가 매도 실행 (체결 확인 + 재시도 로직)
     */
    private OrderResult executeLimitSellOrder(User user, String market, double volume, double currentPrice) {
        int retryCount = 0;
        double adjustedPrice = currentPrice;

        while (retryCount <= limitOrderRetryCount) {
            try {
                // 호가창에서 최적 매도가 계산
                double limitPrice = calculateLimitSellPrice(market, adjustedPrice);

                log.info("[{}] 지정가 매도 시도 ({}/{}) - 가격: {}, 수량: {}",
                        market, retryCount + 1, limitOrderRetryCount + 1,
                        String.format("%.0f", limitPrice), String.format("%.8f", volume));

                OrderResponse order = upbitApiService.sellLimitOrder(user, market, volume, limitPrice);
                String uuid = order.getUuid();

                // 체결 대기 및 확인
                OrderResult result = waitForOrderExecution(user, uuid, limitPrice, volume, "SELL");

                if (result.isSuccess()) {
                    log.info("[{}] 지정가 매도 체결 완료! 가격: {}, 수량: {}",
                            market, String.format("%.0f", result.getExecutedPrice()),
                            String.format("%.8f", result.getExecutedVolume()));
                    return result;
                } else if (result.isPartialFill()) {
                    log.info("[{}] 지정가 매도 부분 체결 - 체결수량: {}",
                            market, String.format("%.8f", result.getExecutedVolume()));
                    return result;
                } else {
                    // 미체결 - 주문 취소 후 재시도
                    log.warn("[{}] 지정가 매도 미체결 - 주문 취소 후 재시도", market);
                    try {
                        upbitApiService.cancelOrder(user, uuid);
                    } catch (Exception e) {
                        log.warn("[{}] 주문 취소 실패: {}", market, e.getMessage());
                    }

                    retryCount++;
                    // 재시도 시 가격 하향 조정 (체결률 향상)
                    adjustedPrice = limitPrice * (1 - limitOrderRetryPriceAdjust);
                    Thread.sleep(1000);
                }

            } catch (Exception e) {
                log.error("[{}] 지정가 매도 오류: {}", market, e.getMessage());
                retryCount++;
                adjustedPrice = adjustedPrice * (1 - limitOrderRetryPriceAdjust);
            }
        }

        // 모든 재시도 실패 시 시장가로 폴백
        log.warn("[{}] 지정가 매도 재시도 초과 - 시장가로 전환", market);
        return executeMarketSellOrder(user, market, volume, currentPrice);
    }

    /**
     * 지정가 매수 가격 계산 (호가창 기반)
     */
    private double calculateLimitBuyPrice(String market, double currentPrice) {
        try {
            var orderbook = upbitApiService.getOrderbook(market);
            if (orderbook != null) {
                // 매도 1호가 (ask) 사용 - 빠른 체결을 위해
                double askPrice = orderbook.getAskPrice(0);
                // 매도 1호가에 약간의 프리미엄 추가
                return askPrice * (1 + limitBuyOffset);
            }
        } catch (Exception e) {
            log.warn("[{}] 호가창 조회 실패, 현재가 기준 계산", market);
        }
        // 호가창 실패 시 현재가 기준
        return currentPrice * (1 + limitBuyOffset);
    }

    /**
     * 지정가 매도 가격 계산 (호가창 기반)
     */
    private double calculateLimitSellPrice(String market, double currentPrice) {
        try {
            var orderbook = upbitApiService.getOrderbook(market);
            if (orderbook != null) {
                // 매수 1호가 (bid) 사용 - 빠른 체결을 위해
                double bidPrice = orderbook.getBidPrice(0);
                // 매수 1호가에서 약간 할인
                return bidPrice * (1 - limitSellOffset);
            }
        } catch (Exception e) {
            log.warn("[{}] 호가창 조회 실패, 현재가 기준 계산", market);
        }
        // 호가창 실패 시 현재가 기준
        return currentPrice * (1 - limitSellOffset);
    }

    /**
     * 주문 체결 대기 및 확인
     */
    private OrderResult waitForOrderExecution(User user, String uuid, double orderPrice,
                                               double orderVolume, String side) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = limitOrderTimeout * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                OrderResponse order = upbitApiService.getOrder(user, uuid);

                if (order == null) {
                    Thread.sleep(limitOrderPollInterval * 1000L);
                    continue;
                }

                String state = order.getState();

                // 완전 체결
                if ("done".equals(state)) {
                    double executedVolume = parseDouble(order.getExecutedVolume(), orderVolume);
                    double avgPrice = parseDouble(order.getAvgPrice(), orderPrice);
                    return OrderResult.success(uuid, avgPrice, executedVolume, "LIMIT");
                }

                // 취소됨
                if ("cancel".equals(state)) {
                    double executedVolume = parseDouble(order.getExecutedVolume(), 0);
                    if (executedVolume > 0) {
                        // 부분 체결 후 취소
                        double avgPrice = parseDouble(order.getAvgPrice(), orderPrice);
                        return OrderResult.partialFill(uuid, avgPrice, executedVolume, "LIMIT");
                    }
                    return OrderResult.failed("주문 취소됨");
                }

                // 대기 중 (wait) - 계속 폴링
                Thread.sleep(limitOrderPollInterval * 1000L);

            } catch (Exception e) {
                log.warn("주문 상태 조회 실패: {}", e.getMessage());
                try {
                    Thread.sleep(limitOrderPollInterval * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 타임아웃 - 미체결로 처리
        return OrderResult.timeout(uuid);
    }

    /**
     * String을 double로 안전하게 파싱
     */
    private double parseDouble(String value, double defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== 주문 결과 DTO ====================

    /**
     * 주문 결과 클래스
     */
    @lombok.Data
    @lombok.Builder
    private static class OrderResult {
        private boolean success;
        private boolean partialFill;
        private boolean timeout;
        private String uuid;
        private double executedPrice;
        private double executedVolume;
        private String orderType;
        private String errorMessage;

        public static OrderResult success(String uuid, double price, double volume, String type) {
            return OrderResult.builder()
                    .success(true)
                    .uuid(uuid)
                    .executedPrice(price)
                    .executedVolume(volume)
                    .orderType(type)
                    .build();
        }

        public static OrderResult partialFill(String uuid, double price, double volume, String type) {
            return OrderResult.builder()
                    .success(true)
                    .partialFill(true)
                    .uuid(uuid)
                    .executedPrice(price)
                    .executedVolume(volume)
                    .orderType(type)
                    .build();
        }

        public static OrderResult failed(String message) {
            return OrderResult.builder()
                    .success(false)
                    .errorMessage(message)
                    .build();
        }

        public static OrderResult timeout(String uuid) {
            return OrderResult.builder()
                    .success(false)
                    .timeout(true)
                    .uuid(uuid)
                    .errorMessage("주문 체결 타임아웃")
                    .build();
        }
    }

    /**
     * 거래 내역 저장 (청산 단계 포함)
     */
    private void saveTradeHistoryWithPhase(String market, TradeType tradeType, double amount,
                                            double price, String orderUuid, String strategyName,
                                            Double targetPrice, int exitPhase) {
        try {
            double volume = amount / price;
            double fee = amount * UPBIT_FEE_RATE;

            TradeHistory history = TradeHistory.builder()
                    .market(market)
                    .tradeMethod("MARKET")
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
                    .exitPhase(exitPhase)
                    .build();

            tradeHistoryRepository.save(history);
            log.info("[{}] 거래 내역 저장 - {}, 금액: {}, 청산단계: {}",
                    market, tradeType, String.format("%.0f", amount), exitPhase);

        } catch (Exception e) {
            log.error("[{}] 거래 내역 저장 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 분할매매 모드 (ScaledTradingStrategy)
     * - 3단계 분할 진입 (30%/30%/40%)
     * - 50% 부분 익절 + 트레일링 스탑
     */
    private void executeScaledTradingForMarket(User user, String market, List<Candle> candles, double currentPrice) {
        // ScaledTradingStrategy 찾기
        ScaledTradingStrategy scaledStrategy = null;
        for (TradingStrategy strategy : strategies) {
            if (strategy instanceof ScaledTradingStrategy) {
                scaledStrategy = (ScaledTradingStrategy) strategy;
                break;
            }
        }

        if (scaledStrategy == null) {
            log.warn("[{}] ScaledTradingStrategy를 찾을 수 없습니다. DEFAULT 모드로 전환", market);
            executeDefaultTradingForMarket(user, market, candles, currentPrice);
            return;
        }

        // 전략 분석
        int signal = scaledStrategy.analyze(market, candles);
        Double targetPrice = scaledStrategy.getTargetPrice(market);
        Double stopLossPrice = scaledStrategy.getStopLossPrice(market);
        int entryPhase = scaledStrategy.getEntryPhase(market);

        log.info("[{}] 분할매매 분석 - 신호: {}, 진입단계: {}, 목표가: {}, 손절가: {}",
                market, signal, entryPhase,
                targetPrice != null ? String.format("%.0f", targetPrice) : "없음",
                stopLossPrice != null ? String.format("%.0f", stopLossPrice) : "없음");

        if (signal == 1) {
            // 매수 신호 (신규 진입 또는 추가 진입)
            String reason = scaledStrategy.getEntryPhase(market) > 0
                    ? "ScaledTrading_추가진입_" + entryPhase + "차"
                    : "ScaledTrading_신규진입";
            log.info("[{}] 📈 분할매매 매수 신호! 사유: {}", market, reason);
            executeBuyForMarketWithRatio(user, market, currentPrice, reason, targetPrice, entryPhase);

        } else if (signal == -1) {
            // 매도 신호 (손절/익절/트레일링)
            String exitReason = scaledStrategy.getExitReason(market);
            double exitRatio = scaledStrategy.getPartialExitRatio(market);

            log.info("[{}] 📉 분할매매 매도 신호! 사유: {}, 청산비율: {}%",
                    market, exitReason, String.format("%.0f", exitRatio * 100));

            if (exitRatio < 1.0 && !scaledStrategy.isPartialExitDone(market)) {
                // 부분 청산 (1차 익절: 50%)
                executeSellForMarketWithRatio(user, market, currentPrice,
                        "ScaledTrading_" + exitReason, exitRatio);
            } else {
                // 전체 청산 (손절/트레일링)
                executeSellForMarket(user, market, currentPrice, "ScaledTrading_" + exitReason);
            }

            // 포지션 상태 정리
            scaledStrategy.clearPosition(market);

        } else {
            log.info("[{}] 관망 - 분할매매 조건 미충족", market);
        }
    }

    /**
     * 분할 진입 비율에 따른 매수 실행
     */
    private void executeBuyForMarketWithRatio(User user, String market, double currentPrice,
                                               String strategyName, Double targetPrice, int entryPhase) {
        if (!isMarketAllowed(market)) {
            log.warn("[{}] 제외된 마켓입니다. 매수 취소.", market);
            return;
        }

        try {
            double krwBalance = upbitApiService.getKrwBalance(user);

            // 분할 진입 비율 계산
            double entryRatio = realTradingConfig.getEntryRatio(Math.max(1, entryPhase));
            double positionRatio = realTradingConfig.getMaxPositionSizeRate();

            // 최대 포지션 크기의 N% (진입 단계별)
            double orderAmount = krwBalance * positionRatio * entryRatio;

            log.info("[{}] 분할매수 - {}차 진입, KRW 잔고: {}, 진입비율: {}%, 주문금액: {}",
                    market, entryPhase,
                    String.format("%.0f", krwBalance),
                    String.format("%.0f", entryRatio * 100),
                    String.format("%.0f", orderAmount));

            if (orderAmount < minOrderAmount) {
                log.warn("[{}] 주문 금액이 최소 주문 금액({})보다 작습니다.", market, minOrderAmount);
                orderAmount = minOrderAmount;
            }

            OrderResponse order = upbitApiService.buyMarketOrder(user, market, orderAmount);
            log.info("[{}] 분할매수 주문 완료! UUID: {}, {}차 진입", market, order.getUuid(), entryPhase);

            saveTradeHistory(market, TradeType.BUY, orderAmount, currentPrice, order.getUuid(),
                    strategyName + "_" + entryPhase + "차", targetPrice);

        } catch (Exception e) {
            log.error("[{}] 분할매수 실행 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 부분 청산 매도 실행 (비율 지정)
     */
    private void executeSellForMarketWithRatio(User user, String market, double currentPrice,
                                                String strategyName, double sellRatio) {
        if (!isMarketAllowed(market)) {
            log.warn("[{}] 제외된 마켓입니다. 매도 취소.", market);
            return;
        }

        try {
            String currency = market.split("-")[1];
            double coinBalance = upbitApiService.getCoinBalance(user, currency);

            log.info("[{}] {} 보유량: {}", market, currency, coinBalance);

            if (coinBalance <= 0) {
                log.warn("[{}] 매도할 코인이 없습니다.", market);
                return;
            }

            // 부분 청산 수량 계산
            double sellAmount = coinBalance * sellRatio;

            // 최소 주문 금액 체크
            if (sellAmount * currentPrice < 5000) {
                log.warn("[{}] 부분 청산 금액이 최소 주문 금액 미만. 전체 청산으로 전환.", market);
                sellAmount = coinBalance;
            }

            OrderResponse order = upbitApiService.sellMarketOrder(user, market, sellAmount);
            log.info("[{}] 부분 매도 주문 완료! UUID: {}, 청산비율: {}%, 수량: {}",
                    market, order.getUuid(), String.format("%.0f", sellRatio * 100), sellAmount);

            double sellValue = sellAmount * currentPrice;
            saveTradeHistory(market, TradeType.SELL, sellValue, currentPrice, order.getUuid(),
                    strategyName + "_부분청산", null);

        } catch (Exception e) {
            log.error("[{}] 부분 매도 실행 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 매수 실행 (특정 마켓)
     */
    private void executeBuyForMarket(User user, String market, double currentPrice, String strategyName, Double targetPrice) {
        if (!isMarketAllowed(market)) {
            log.warn("[{}] 제외된 마켓입니다. 매수 취소.", market);
            return;
        }

        try {
            double krwBalance = upbitApiService.getKrwBalance(user);

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

            OrderResponse order = upbitApiService.buyMarketOrder(user, market, orderAmount);
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
    private void executeSellForMarket(User user, String market, double currentPrice, String strategyName) {
        if (!isMarketAllowed(market)) {
            log.warn("[{}] 제외된 마켓입니다. 매도 취소.", market);
            return;
        }

        try {
            String currency = market.split("-")[1]; // KRW-BTC -> BTC
            double coinBalance = upbitApiService.getCoinBalance(user, currency);

            log.info("[{}] {} 보유량: {}", market, currency, coinBalance);

            if (coinBalance <= 0) {
                log.warn("[{}] 매도할 코인이 없습니다.", market);
                return;
            }

            OrderResponse order = upbitApiService.sellMarketOrder(user, market, coinBalance);
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

    /**
     * 캔들 데이터 DB 저장 (중복 제외)
     * 최신 데이터부터 확인하여 중복이 발견되면 중단을 고려할 수 있으나,
     * API 응답 순서가 최신순이므로 효율적으로 처리
     */
    private void saveCandlesToDb(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return;
        }

        try {
            // 업비트 API는 최신 캔들이 리스트의 0번에 위치함
            int savedCount = 0;
            for (int i = 0; i < candles.size(); i++) {
                Candle candle = candles.get(i);
                
                // 이미 존재하는 캔들인지 확인 (시장가와 KST 시간 기준)
                // 리스트의 0번부터 확인하므로, 이미 존재하는 데이터를 만나면 그 이후(과거 데이터)는 이미 저장되어 있을 확률이 높음
                if (candleDataRepository.findByMarketAndCandleDateTimeKst(candle.getMarket(), candle.getCandleDateTimeKst()).isPresent()) {
                    // 1분봉 데이터 적재 시, 연속된 데이터라면 중복 발견 시 중단하여 성능 최적화
                    // 단, 200개를 가져오는데 그 중 중간에 비어있을 가능성이 아주 낮으므로 break 허용
                    break;
                }

                CandleData candleData = CandleData.builder()
                        .market(candle.getMarket())
                        .candleDateTimeUtc(candle.getCandleDateTimeUtc())
                        .candleDateTimeKst(candle.getCandleDateTimeKst())
                        .openingPrice(candle.getOpeningPrice())
                        .highPrice(candle.getHighPrice())
                        .lowPrice(candle.getLowPrice())
                        .tradePrice(candle.getTradePrice())
                        .timestamp(candle.getTimestamp())
                        .candleAccTradePrice(candle.getCandleAccTradePrice())
                        .candleAccTradeVolume(candle.getCandleAccTradeVolume())
                        .unit(candle.getUnit())
                        .build();

                candleDataRepository.save(candleData);
                savedCount++;
            }
            if (savedCount > 0) {
                log.info("[{}] 신규 캔들 데이터 {}건 저장 완료", candles.get(0).getMarket(), savedCount);
            }
        } catch (Exception e) {
            log.error("캔들 데이터 DB 저장 중 오류: {}", e.getMessage());
        }
    }

    /**
     * Ticker 데이터 DB 저장
     */
    private void saveTickerToDb(Ticker ticker) {
        try {
            TickerData tickerData = TickerData.builder()
                    .market(ticker.getMarket())
                    .tradeDate(ticker.getTradeDate())
                    .tradeTime(ticker.getTradeTime())
                    .tradeDateKst(ticker.getTradeDateKst())
                    .tradeTimeKst(ticker.getTradeTimeKst())
                    .tradeTimestamp(ticker.getTradeTimestamp())
                    .openingPrice(ticker.getOpeningPrice())
                    .highPrice(ticker.getHighPrice())
                    .lowPrice(ticker.getLowPrice())
                    .tradePrice(ticker.getTradePrice())
                    .prevClosingPrice(ticker.getPrevClosingPrice())
                    .change(ticker.getChange())
                    .changePrice(ticker.getChangePrice())
                    .changeRate(ticker.getChangeRate())
                    .timestamp(ticker.getTimestamp())
                    .build();

            tickerDataRepository.save(tickerData);
        } catch (Exception e) {
            log.error("[{}] Ticker 데이터 DB 저장 중 오류: {}", ticker.getMarket(), e.getMessage());
        }
    }

    // 하위 호환을 위한 기존 메서드 (단일 마켓)
    private void executeBuy(User user, double currentPrice) {
        executeBuyForMarket(user, targetMarket, currentPrice, "Manual", null);
    }

    private void executeSell(User user) {
        executeSellForMarket(user, targetMarket, 0, "Manual");
    }

    /**
     * 현재 보유 현황 조회
     */
    public void printAccountStatus(User user) {
        log.info("========== 보유 현황 ==========");
        try {
            List<Account> accounts = upbitApiService.getAccounts(user);
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
    public void executeWithStrategy(User user, TradingStrategy strategy) {
        log.info("========== {} 전략 매매 시작 ==========", strategy.getStrategyName());

        try {
            List<Candle> candles = upbitApiService.getMinuteCandles(targetMarket, minuteInterval, candleCount);
            List<Ticker> tickers = upbitApiService.getTicker(targetMarket);
            double currentPrice = tickers.get(0).getTradePrice().doubleValue();

            int signal = strategy.analyze(candles);

            if (signal == 1) {
                executeBuy(user, currentPrice);
            } else if (signal == -1) {
                executeSell(user);
            } else {
                log.info("관망");
            }

        } catch (Exception e) {
            log.error("전략 매매 실행 중 오류: {}", e.getMessage());
        }

        log.info("========== {} 전략 매매 종료 ==========\n", strategy.getStrategyName());
    }
}
