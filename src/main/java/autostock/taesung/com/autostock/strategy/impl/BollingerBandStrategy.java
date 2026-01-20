package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.backtest.dto.ExitReason;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.exchange.upbit.dto.Orderbook;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import autostock.taesung.com.autostock.service.StrategyParameterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 볼린저밴드 전략
 *
 * ===== GCP 저사양 환경 최적화 =====
 * - 1분 스케줄러 전용
 * - 호가창은 진입 시에만 체크 (API 호출 최소화)
 * - 각 서버별 마켓 분리로 동기화 불필요
 * - GCP 서버 2대, 각 100개 마켓 분리 운영
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BollingerBandStrategy implements TradingStrategy {

    /* =====================================================
     * 기본 상수 (1분봉 기준)
     * ===================================================== */
    private static final int PERIOD = 20;
    private static final double STD_DEV_MULTIPLIER = 2.0;

    // 3분봉 권장: STOP_LOSS_COOLDOWN_CANDLES = 3 (현재 1분봉: 5)
    private static final int STOP_LOSS_COOLDOWN_CANDLES = 5;
    // 3분봉 권장: MIN_HOLD_CANDLES = 2 (현재 1분봉: 3)
    private static final int MIN_HOLD_CANDLES = 3;

    // 3분봉 권장: STOP_LOSS_ATR_MULT = 2.5 (현재 1분봉: 2.0)
    private static final double STOP_LOSS_ATR_MULT = 2.0;
    // 3분봉 권장: TAKE_PROFIT_ATR_MULT = 3.0 (현재 1분봉: 2.5)
    private static final double TAKE_PROFIT_ATR_MULT = 2.5;
    private static final double TRAILING_STOP_ATR_MULT = 1.5;

    /* =====================================================
     * [1] 슬리피지 및 수수료 상수
     * ===================================================== */
    private static final double SLIPPAGE_RATE = 0.0015;    // 0.15% 슬리피지
    private static final double FEE_RATE = 0.0005;         // 0.05% 수수료
    private static final double TOTAL_COST = 0.002;        // 0.2% 총 비용
    private static final double MIN_PROFIT_RATE = 0.006;   // 0.6% 최소 수익률 (비용의 3배)

    /* =====================================================
     * 호가창 검증 상수
     * ===================================================== */
    private static final double MAX_SPREAD_RATE = 0.003;       // 최대 스프레드 0.3%
    private static final double MIN_BID_IMBALANCE = 0.55;      // 최소 매수세 55%
    private static final double MAX_PRICE_DIFF_RATE = 0.005;   // 최대 가격 괴리 0.5%

    /* =====================================================
     * ATR 손절 최대값 제한
     * ===================================================== */
    private static final double MAX_STOP_LOSS_RATE = 0.03;     // 최대 손절 -3%

    /* =====================================================
     * [2] 의존성 주입 (@RequiredArgsConstructor)
     * ===================================================== */
    private final TechnicalIndicator indicator;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyParameterService strategyParameterService;
    private final UpbitApiService upbitApiService;

    private final ThreadLocal<Double> targetPrice = new ThreadLocal<>();

    @Override
    public Double getTargetPrice() {
        return targetPrice.get();
    }

    @Override
    public void clearPosition(String market) {
        targetPrice.remove();
    }

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {
        TradeHistory latest = tradeHistoryRepository.findLatestByMarket(market)
                .stream().findFirst().orElse(null);

        boolean holding = latest != null && latest.getTradeType() == TradeHistory.TradeType.BUY;
        double buyPrice = holding ? latest.getPrice().doubleValue() : 0;
        double currentPrice = candles.isEmpty() ? 0 : candles.get(0).getTradePrice().doubleValue();
        
        double highestPrice = buyPrice;
        LocalDateTime buyCreatedAt = LocalDateTime.now();
        boolean isSell = latest != null && latest.getTradeType() == TradeHistory.TradeType.SELL;
        LocalDateTime lastTradeAt = latest != null ? latest.getCreatedAt() : LocalDateTime.now();

        if (holding) {
            highestPrice = latest.getHighestPrice() == null ? currentPrice : latest.getHighestPrice().doubleValue();
            if (currentPrice > highestPrice) {
                latest.setHighestPrice(BigDecimal.valueOf(currentPrice));
                tradeHistoryRepository.save(latest);
                highestPrice = currentPrice;
            }
            buyCreatedAt = latest.getCreatedAt();
        }

        return analyzeLogic(market, candles, holding, buyPrice, highestPrice, buyCreatedAt, isSell, lastTradeAt, false);
    }

    @Override
    public int analyzeForBacktest(String market, List<Candle> candles, BacktestPosition position) {
        boolean holding = position != null && position.isHolding();
        double buyPrice = holding ? position.getBuyPrice() : 0;
        double highestPrice = holding ? position.getHighestPrice() : 0;
        LocalDateTime buyCreatedAt = (holding && position.getBuyTime() != null) ? position.getBuyTime() : LocalDateTime.now();

        // 백테스트 모드로 호출 (호가창 검증 스킵)
        return analyzeLogic(market, candles, holding, buyPrice, highestPrice, buyCreatedAt, false, LocalDateTime.now(), true);
    }

    private int analyzeLogic(String market, List<Candle> candles, boolean holding, double buyPrice,
                             double highestPrice, LocalDateTime buyCreatedAt, boolean isSell, LocalDateTime lastTradeAt,
                             boolean isBacktest) {
        
        if (candles.size() < 30) return 0;

        int period = strategyParameterService.getIntParam(getStrategyName(), null, "bollinger.period", PERIOD);
        double multiplier = strategyParameterService.getDoubleParam(getStrategyName(), null, "bollinger.multiplier", STD_DEV_MULTIPLIER);
        double stopLossRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "stopLoss.rate", -2.5);
        double takeProfitRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "takeProfit.rate", 2.0);
        // 3분봉 권장: volumeThreshold = 100.0 (현재 1분봉: 120.0)
        double volumeThreshold = strategyParameterService.getDoubleParam(getStrategyName(), null, "volume.threshold", 120.0);
        double rsiOversold = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.oversold", 30.0);
        double rsiOverbought = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.overbought", 70.0);
        int rsiPeriod = strategyParameterService.getIntParam(getStrategyName(), null, "rsi.period", 14);

        double[] bands = indicator.calculateBollingerBands(candles, period, multiplier);
        double middleBand = bands[0];
        double upperBand = bands[1];
        double lowerBand = bands[2];

        double bandWidthPercent = ((upperBand - lowerBand) / middleBand) * 100;
        double rsi = calculateRSI(candles, rsiPeriod);
        double atr = calculateATR(candles, rsiPeriod);
        double[] stoch = calculateStochRSI(candles, rsiPeriod, rsiPeriod);
        double stochK = stoch[0];
        double stochD = stoch[1];

        double currentPrice = candles.get(0).getTradePrice().doubleValue();
        double currentVolume = candles.get(0).getCandleAccTradePrice().doubleValue();
        double avgVolume = candles.subList(1, 6).stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average().orElse(1.0);

        /* =====================================================
         * 1️⃣ 매도 로직 (보유 중)
         * [6] 손익 계산 시 비용 반영
         * ===================================================== */
        if (holding) {
            // [6] 실제 매수가 반영 (슬리피지 + 수수료)
            double realBuyPrice = buyPrice * (1 + TOTAL_COST);
            // [6] 실제 매도가 반영
            double realSellPrice = currentPrice * (1 - TOTAL_COST);
            // 실제 수익률 계산
            double realProfitRate = (realSellPrice - realBuyPrice) / realBuyPrice;

            // [7] ATR 손절 최대값 제한 (-3%)
            double maxStopLoss = buyPrice * (1 - MAX_STOP_LOSS_RATE);
            double fixedStopLoss = buyPrice * (1 + stopLossRate / 100);
            double atrStopLoss = buyPrice - atr * STOP_LOSS_ATR_MULT;
            double stopLoss = Math.max(maxStopLoss, Math.max(fixedStopLoss, atrStopLoss));

            // 익절 조건
            double fixedTakeProfit = buyPrice * (1 + takeProfitRate / 100);
            double atrTakeProfit = buyPrice + atr * TAKE_PROFIT_ATR_MULT;
            double takeProfit = Math.min(fixedTakeProfit, atrTakeProfit);

            double trailingStop = highestPrice - atr * TRAILING_STOP_ATR_MULT;

            long holdingMinutes = java.time.Duration.between(buyCreatedAt, LocalDateTime.now()).toMinutes();

            // [8] 손절 로깅 개선
            if (holdingMinutes >= MIN_HOLD_CANDLES && currentPrice <= stopLoss) {
                log.info("[{}] 손절 - BuyPrice: {}, CurrentPrice: {}, Loss: {}%",
                        market, String.format("%.0f", buyPrice), String.format("%.0f", currentPrice),
                        String.format("%.2f", realProfitRate * 100));
                return -1;
            }

            // [6] 익절 시 최소 수익률 체크 (0.6% 이상만 익절)
            if (currentPrice >= takeProfit && rsi > rsiOverbought) {
                if (realProfitRate >= MIN_PROFIT_RATE) {
                    log.info("[{}] 익절 - BuyPrice: {}, CurrentPrice: {}, RealProfit: {}%",
                            market, String.format("%.0f", buyPrice), String.format("%.0f", currentPrice),
                            String.format("%.2f", realProfitRate * 100));
                    return -1;
                } else {
                    log.debug("[{}] 익절 조건 충족했으나 최소수익률(0.6%) 미달: {}%",
                            market, String.format("%.2f", realProfitRate * 100));
                }
            }

            // [8] 트레일링 로깅 개선
            if (holdingMinutes >= MIN_HOLD_CANDLES && currentPrice <= trailingStop && highestPrice > buyPrice * 1.01) {
                log.info("[{}] 트레일링 종료 - BuyPrice: {}, Highest: {}, CurrentPrice: {}, Profit: {}%",
                        market, String.format("%.0f", buyPrice), String.format("%.0f", highestPrice),
                        String.format("%.0f", currentPrice), String.format("%.2f", realProfitRate * 100));
                return -1;
            }

            return 0;
        }

        /* =====================================================
         * 2️⃣ 손절 쿨다운
         * ===================================================== */
        if (isSell) {
            long diff = java.time.Duration.between(lastTradeAt, LocalDateTime.now()).toMinutes();
            if (diff < STOP_LOSS_COOLDOWN_CANDLES) return 0;
        }

        /* =====================================================
         * 3️⃣ 1분봉 생존 필터
         * ===================================================== */
        if (bandWidthPercent < 0.8) return 0;
        if (!isHigherLowStructure(candles)) return 0;
        if (isFakeRebound(candles)) return 0;

        double candleMove = Math.abs(candles.get(0).getTradePrice().doubleValue() - candles.get(1).getTradePrice().doubleValue());
        if (candleMove > atr * 0.8) return 0;
        if (currentVolume < avgVolume * 0.9) return 0;
        if (rsi > rsiOverbought) return 0;

        /* =====================================================
         * 4️⃣ 진입 조건 강화 - 추세 확인
         * ===================================================== */
        double avgPrice10 = candles.subList(0, 10).stream().mapToDouble(c -> c.getTradePrice().doubleValue()).average().orElse(currentPrice);
        if (currentPrice <= avgPrice10 * 0.998) {
            log.debug("[{}] 하락 추세 감지 - 진입 차단", market);
            return 0;
        }

        /* =====================================================
         * 4️⃣ 진입 조건 강화 - 거래량 지속성 체크
         * ===================================================== */
        double minSustainedVolume = avgVolume * 0.8;
        for (int i = 0; i < 3; i++) {
            if (candles.get(i).getCandleAccTradePrice().doubleValue() < minSustainedVolume) {
                log.debug("[{}] 거래량 지속성 부족 - 진입 차단", market);
                return 0;
            }
        }

        /* =====================================================
         * 5️⃣ 진입 시그널
         * ===================================================== */
        boolean stochEntry = stochK > stochD && stochK < 0.8 && rsi > rsiOversold && currentPrice > middleBand * 0.98;
        boolean volumeBreakout = rsi > 45 && (currentVolume / avgVolume) * 100 >= volumeThreshold && currentPrice > middleBand;

        /* =====================================================
         * 6️⃣ 거래대금 필터
         * ===================================================== */
        double minTradeAmount = getMinTradeAmountByTime();
        double avgTradeAmount = candles.subList(1, 4).stream().mapToDouble(c -> c.getCandleAccTradePrice().doubleValue()).average().orElse(0);
        if (avgTradeAmount < minTradeAmount * 0.7) return 0;

        /* =====================================================
         * 7️⃣ [4] 매수 신호 + 호가창 최종 검증
         * ===================================================== */
        if (stochEntry || volumeBreakout) {
            // [3] 호가창 최종 검증 (백테스트에서는 스킵)
            if (!isBacktest && !validateOrderbookForEntry(market, currentPrice)) {
                targetPrice.remove();
                return 0;  // 검증 실패 시 진입 포기
            }
            this.targetPrice.set(currentPrice + atr * 1.5);
            // [8] 매수 진입 로깅 개선
            log.info("[{}]{} 매수 진입 - Price: {}, RSI: {}, ATR: {}", market, isBacktest ? "[백테스트]" : "",
                    String.format("%.0f", currentPrice), String.format("%.1f", rsi), String.format("%.2f", atr));
            return 1;
        }

        targetPrice.remove();
        return 0;
    }

    private boolean validateOrderbookForEntry(String market, double currentPrice) {
        try {
            Orderbook ob = upbitApiService.getOrderbook(market);
            if (ob == null) return false;
            double askPrice = ob.getAskPrice(0);
            double bidPrice = ob.getBidPrice(0);
            double spread = (askPrice - bidPrice) / bidPrice;
            if (spread > MAX_SPREAD_RATE) return false;
            double totalBid = ob.getBidSize(0) + ob.getBidSize(1) + ob.getBidSize(2);
            double totalAsk = ob.getAskSize(0) + ob.getAskSize(1) + ob.getAskSize(2);
            double imbalance = totalBid / (totalBid + totalAsk);
            if (imbalance < MIN_BID_IMBALANCE) return false;
            double priceDiff = Math.abs(currentPrice - bidPrice) / currentPrice;
            return priceDiff <= MAX_PRICE_DIFF_RATE;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHigherLowStructure(List<Candle> candles) {
        double l0 = candles.get(0).getLowPrice().doubleValue();
        double l1 = candles.get(1).getLowPrice().doubleValue();
        double l2 = candles.get(2).getLowPrice().doubleValue();
        return l0 > l1 && l1 >= l2;
    }

    private boolean isFakeRebound(List<Candle> candles) {
        Candle c0 = candles.get(0);
        Candle c1 = candles.get(1);
        double body0 = Math.abs(c0.getTradePrice().doubleValue() - c0.getOpeningPrice().doubleValue());
        double range0 = c0.getHighPrice().doubleValue() - c0.getLowPrice().doubleValue();
        double body1 = Math.abs(c1.getTradePrice().doubleValue() - c1.getOpeningPrice().doubleValue());
        double range1 = c1.getHighPrice().doubleValue() - c1.getLowPrice().doubleValue();
        return body0 / range0 < 0.35 && body1 / range1 < 0.35;
    }

    private double calculateRSI(List<Candle> candles, int period) {
        double gain = 0, loss = 0;
        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getTradePrice().doubleValue() - candles.get(i + 1).getTradePrice().doubleValue();
            if (diff > 0) gain += diff; else loss -= diff;
        }
        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - (100 / (1 + rs));
    }

    private double calculateATR(List<Candle> candles, int period) {
        double sum = 0;
        for (int i = 0; i < period; i++) {
            double h = candles.get(i).getHighPrice().doubleValue();
            double l = candles.get(i).getLowPrice().doubleValue();
            double pc = candles.get(i + 1).getTradePrice().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / period;
    }

    private double[] calculateStochRSI(List<Candle> candles, int rsiPeriod, int stochPeriod) {
        List<Double> rsiList = new ArrayList<>();
        for (int i = 0; i <= candles.size() - rsiPeriod - 1; i++) {
            rsiList.add(calculateRSI(candles.subList(i, candles.size()), rsiPeriod));
        }
        List<Double> recent = rsiList.subList(rsiList.size() - stochPeriod, rsiList.size());
        double min = recent.stream().min(Double::compare).orElse(0.0);
        double max = recent.stream().max(Double::compare).orElse(1.0);
        double k = (recent.get(recent.size() - 1) - min) / (max - min + 1e-9);
        double d = recent.stream().skip(Math.max(0, recent.size() - 3)).mapToDouble(Double::doubleValue).average().orElse(k);
        return new double[]{k, d};
    }

    private double getMinTradeAmountByTime() {
        int hour = LocalTime.now(ZoneId.of("Asia/Seoul")).getHour();
        if (hour >= 2 && hour < 9) return 20_000_000;
        if (hour >= 9 && hour < 18) return 50_000_000;
        if (hour >= 18 && hour < 22) return 80_000_000;
        return 100_000_000;
    }

    @Override
    public String getStrategyName() {
        return "BollingerBandStrategy";
    }
}