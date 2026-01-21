package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
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
 *
 * ===== Fast Breakout Entry 추가 =====
 * - 급등 초입 종목 포착을 위한 별도 진입 로직
 * - 기존 진입 조건보다 먼저 평가
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BollingerBandStrategy implements TradingStrategy {

    /* =====================================================
     * 기본 상수 (기본값 - StrategyParameterService에서 동적 로드)
     * ===================================================== */
    private static final int DEFAULT_PERIOD = 20;
    private static final double DEFAULT_STD_DEV_MULTIPLIER = 2.0;
    private static final int DEFAULT_STOP_LOSS_COOLDOWN_CANDLES = 5;
    private static final int DEFAULT_MIN_HOLD_CANDLES = 3;
    private static final double DEFAULT_STOP_LOSS_ATR_MULT = 2.0;
    private static final double DEFAULT_TAKE_PROFIT_ATR_MULT = 2.5;
    private static final double DEFAULT_TRAILING_STOP_ATR_MULT = 1.5;
    private static final double DEFAULT_TOTAL_COST = 0.002;
    private static final double DEFAULT_MIN_PROFIT_RATE = 0.006;
    private static final double DEFAULT_MAX_SPREAD_RATE = 0.003;
    private static final double DEFAULT_MIN_BID_IMBALANCE = 0.55;
    private static final double DEFAULT_MAX_PRICE_DIFF_RATE = 0.005;
    private static final double DEFAULT_MAX_STOP_LOSS_RATE = 0.03;
    private static final double DEFAULT_FAST_BREAKOUT_UPPER_MULT = 1.002;
    private static final double DEFAULT_FAST_BREAKOUT_VOLUME_MULT = 2.5;
    private static final double DEFAULT_FAST_BREAKOUT_RSI_MIN = 55.0;
    private static final double DEFAULT_HIGH_VOLUME_THRESHOLD = 2.0;
    private static final double DEFAULT_CHASE_PREVENTION_RATE = 0.035;
    private static final double DEFAULT_BAND_WIDTH_MIN_PERCENT = 0.8;
    private static final double DEFAULT_ATR_CANDLE_MOVE_MULT = 0.8;

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

        /* =====================================================
         * 파라미터 서비스에서 동적 로드
         * ===================================================== */
        // 기본 볼린저밴드 설정
        int period = strategyParameterService.getIntParam(getStrategyName(), null, "bollinger.period", DEFAULT_PERIOD);
        double multiplier = strategyParameterService.getDoubleParam(getStrategyName(), null, "bollinger.multiplier", DEFAULT_STD_DEV_MULTIPLIER);

        // RSI 설정
        int rsiPeriod = strategyParameterService.getIntParam(getStrategyName(), null, "rsi.period", 14);
        double rsiOversold = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.oversold", 30.0);
        double rsiOverbought = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.overbought", 70.0);

        // 손절/익절 설정
        double stopLossRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "stopLoss.rate", -2.5);
        double takeProfitRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "takeProfit.rate", 2.0);
        double volumeThreshold = strategyParameterService.getDoubleParam(getStrategyName(), null, "volume.threshold", 120.0);

        // 캔들 기반 설정
        int stopLossCooldownCandles = strategyParameterService.getIntParam(getStrategyName(), null, "stopLoss.cooldownCandles", DEFAULT_STOP_LOSS_COOLDOWN_CANDLES);
        int minHoldCandles = strategyParameterService.getIntParam(getStrategyName(), null, "minHold.candles", DEFAULT_MIN_HOLD_CANDLES);

        // ATR 기반 손익 설정
        double stopLossAtrMult = strategyParameterService.getDoubleParam(getStrategyName(), null, "stopLoss.atrMult", DEFAULT_STOP_LOSS_ATR_MULT);
        double takeProfitAtrMult = strategyParameterService.getDoubleParam(getStrategyName(), null, "takeProfit.atrMult", DEFAULT_TAKE_PROFIT_ATR_MULT);
        double trailingStopAtrMult = strategyParameterService.getDoubleParam(getStrategyName(), null, "trailingStop.atrMult", DEFAULT_TRAILING_STOP_ATR_MULT);
        double maxStopLossRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "maxStopLoss.rate", DEFAULT_MAX_STOP_LOSS_RATE);

        // 슬리피지 및 수수료
        double totalCost = strategyParameterService.getDoubleParam(getStrategyName(), null, "total.cost", DEFAULT_TOTAL_COST);
        double minProfitRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "minProfit.rate", DEFAULT_MIN_PROFIT_RATE);

        // Fast Breakout 설정
        double fastBreakoutUpperMult = strategyParameterService.getDoubleParam(getStrategyName(), null, "fastBreakout.upperMult", DEFAULT_FAST_BREAKOUT_UPPER_MULT);
        double fastBreakoutVolumeMult = strategyParameterService.getDoubleParam(getStrategyName(), null, "fastBreakout.volumeMult", DEFAULT_FAST_BREAKOUT_VOLUME_MULT);
        double fastBreakoutRsiMin = strategyParameterService.getDoubleParam(getStrategyName(), null, "fastBreakout.rsiMin", DEFAULT_FAST_BREAKOUT_RSI_MIN);

        // 급등 차단 및 추격 매수 방지
        double highVolumeThreshold = strategyParameterService.getDoubleParam(getStrategyName(), null, "highVolume.threshold", DEFAULT_HIGH_VOLUME_THRESHOLD);
        double chasePreventionRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "chasePrevention.rate", DEFAULT_CHASE_PREVENTION_RATE);

        // 밴드폭 및 ATR 필터
        double bandWidthMinPercent = strategyParameterService.getDoubleParam(getStrategyName(), null, "bandWidth.minPercent", DEFAULT_BAND_WIDTH_MIN_PERCENT);
        double atrCandleMoveMult = strategyParameterService.getDoubleParam(getStrategyName(), null, "atr.candleMoveMult", DEFAULT_ATR_CANDLE_MOVE_MULT);

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
        double openPrice = candles.get(0).getOpeningPrice().doubleValue();
        double currentVolume = candles.get(0).getCandleAccTradePrice().doubleValue();
        double avgVolume = candles.subList(1, 6).stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average().orElse(1.0);
        double volumeRatio = currentVolume / avgVolume;

        /* =====================================================
         * 1️⃣ 매도 로직 (보유 중)
         * 손익 계산 시 비용 반영
         * ===================================================== */
        if (holding) {
            // 실제 매수가 반영 (슬리피지 + 수수료)
            double realBuyPrice = buyPrice * (1 + totalCost);
            // 실제 매도가 반영
            double realSellPrice = currentPrice * (1 - totalCost);
            // 실제 수익률 계산
            double realProfitRate = (realSellPrice - realBuyPrice) / realBuyPrice;

            // ATR 손절 최대값 제한
            double maxStopLoss = buyPrice * (1 - maxStopLossRate);
            double fixedStopLoss = buyPrice * (1 + stopLossRate / 100);
            double atrStopLoss = buyPrice - atr * stopLossAtrMult;
            double stopLoss = Math.max(maxStopLoss, Math.max(fixedStopLoss, atrStopLoss));

            // 익절 조건
            double fixedTakeProfit = buyPrice * (1 + takeProfitRate / 100);
            double atrTakeProfit = buyPrice + atr * takeProfitAtrMult;
            double takeProfit = Math.min(fixedTakeProfit, atrTakeProfit);

            double trailingStop = highestPrice - atr * trailingStopAtrMult;

            long holdingMinutes = java.time.Duration.between(buyCreatedAt, LocalDateTime.now()).toMinutes();

            // 손절 로깅
            if (holdingMinutes >= minHoldCandles && currentPrice <= stopLoss) {
                log.info("[{}] 손절 - BuyPrice: {}, CurrentPrice: {}, Loss: {}%",
                        market, String.format("%.0f", buyPrice), String.format("%.0f", currentPrice),
                        String.format("%.2f", realProfitRate * 100));
                return -1;
            }

            // 익절 시 최소 수익률 체크
            if (currentPrice >= takeProfit && rsi > rsiOverbought) {
                if (realProfitRate >= minProfitRate) {
                    log.info("[{}] 익절 - BuyPrice: {}, CurrentPrice: {}, RealProfit: {}%",
                            market, String.format("%.0f", buyPrice), String.format("%.0f", currentPrice),
                            String.format("%.2f", realProfitRate * 100));
                    return -1;
                } else {
                    log.debug("[{}] 익절 조건 충족했으나 최소수익률({}) 미달: {}%",
                            market, String.format("%.2f", minProfitRate * 100), String.format("%.2f", realProfitRate * 100));
                }
            }

            // 트레일링 로깅
            if (holdingMinutes >= minHoldCandles && currentPrice <= trailingStop && highestPrice > buyPrice * 1.01) {
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
            if (diff < stopLossCooldownCandles) return 0;
        }

        /* =====================================================
         * 🚀 Fast Breakout 진입 (기존 진입보다 먼저 평가)
         * - 급등 초입 포착을 위한 별도 로직
         * ===================================================== */
        boolean isBullishCandle = currentPrice > openPrice;
        boolean isAboveUpperBand = currentPrice > upperBand * fastBreakoutUpperMult;
        boolean isHighVolume = volumeRatio >= fastBreakoutVolumeMult;
        boolean isRsiAboveThreshold = rsi > fastBreakoutRsiMin;

        boolean isFastBreakout = isAboveUpperBand && isHighVolume && isRsiAboveThreshold && isBullishCandle;

        if (isFastBreakout) {
            // Fast Breakout은 호가창 검증만 통과하면 즉시 진입
            if (!isBacktest && !validateOrderbookForEntry(market, currentPrice)) {
                targetPrice.remove();
                log.debug("[{}] Fast Breakout - 호가창 검증 실패", market);
                return 0;
            }
            // 추격 매수 방지: targetPrice는 현재가로 설정
            this.targetPrice.set(currentPrice);
            log.info("[{}]{} 🚀 Fast Breakout 진입 - Price: {}, RSI: {}, VolumeRatio: {}x, UpperBand: {}",
                    market, isBacktest ? "[백테스트]" : "",
                    String.format("%.0f", currentPrice),
                    String.format("%.1f", rsi),
                    String.format("%.1f", volumeRatio),
                    String.format("%.0f", upperBand));
            return 1;
        }

        /* =====================================================
         * 3️⃣ 1분봉 생존 필터 (기존 로직)
         * 급등 차단 로직 완화 - 고거래량 시 예외
         * ===================================================== */
        if (bandWidthPercent < bandWidthMinPercent) return 0;
        if (!isHigherLowStructure(candles)) return 0;
        if (isFakeRebound(candles)) return 0;

        double candleMove = Math.abs(candles.get(0).getTradePrice().doubleValue() - candles.get(1).getTradePrice().doubleValue());
        // ATR 대비 큰 캔들이더라도 거래대금이 평균의 2배 이상이면 차단하지 않음
        boolean isLargeCandleMove = candleMove > atr * atrCandleMoveMult;
        boolean isHighVolumeException = volumeRatio >= highVolumeThreshold;
        if (isLargeCandleMove && !isHighVolumeException) {
            log.debug("[{}] 급등 차단 - CandleMove: {}, ATR: {}, VolumeRatio: {}",
                    market, String.format("%.2f", candleMove), String.format("%.2f", atr), String.format("%.1f", volumeRatio));
            return 0;
        }

        if (currentVolume < avgVolume * 0.9) return 0;

        // RSI 상단 차단 - Fast Breakout이 아닌 경우에만 적용
        if (rsi > rsiOverbought) {
            log.debug("[{}] RSI 과매수 차단 - RSI: {}", market, String.format("%.1f", rsi));
            return 0;
        }

        /* =====================================================
         * 추격 매수 방지 로직
         * - 하단 밴드 대비 이탈 비율 이상 시 진입 차단
         * ===================================================== */
        double distanceFromLower = (currentPrice - lowerBand) / lowerBand;
        if (distanceFromLower > chasePreventionRate) {
            log.debug("[{}] 추격 매수 방지 - 하단밴드 대비 {}% 이탈",
                    market, String.format("%.2f", distanceFromLower * 100));
            return 0;
        }

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
         * 5️⃣ 기존 진입 시그널
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
         * 7️⃣ [4] 기존 매수 신호 + 호가창 최종 검증
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
            // 호가창 검증 파라미터 로드
            double maxSpreadRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "orderbook.maxSpreadRate", DEFAULT_MAX_SPREAD_RATE);
            double minBidImbalance = strategyParameterService.getDoubleParam(getStrategyName(), null, "orderbook.minBidImbalance", DEFAULT_MIN_BID_IMBALANCE);
            double maxPriceDiffRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "orderbook.maxPriceDiffRate", DEFAULT_MAX_PRICE_DIFF_RATE);

            Orderbook ob = upbitApiService.getOrderbook(market);
            if (ob == null) return false;
            double askPrice = ob.getAskPrice(0);
            double bidPrice = ob.getBidPrice(0);
            double spread = (askPrice - bidPrice) / bidPrice;
            if (spread > maxSpreadRate) return false;
            double totalBid = ob.getBidSize(0) + ob.getBidSize(1) + ob.getBidSize(2);
            double totalAsk = ob.getAskSize(0) + ob.getAskSize(1) + ob.getAskSize(2);
            double imbalance = totalBid / (totalBid + totalAsk);
            if (imbalance < minBidImbalance) return false;
            double priceDiff = Math.abs(currentPrice - bidPrice) / currentPrice;
            return priceDiff <= maxPriceDiffRate;
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