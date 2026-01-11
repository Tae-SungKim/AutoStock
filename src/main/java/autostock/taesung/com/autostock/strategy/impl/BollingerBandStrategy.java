package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import autostock.taesung.com.autostock.service.StrategyParameterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BollingerBandStrategy implements TradingStrategy {
    private static final int STOP_LOSS_COOLDOWN_CANDLES = 5;  // 손절 후 재진입 대기 캔들
    private static final int MIN_HOLD_CANDLES = 3;            // 최소 보유 캔들 (손절 체크 전)
    private final TechnicalIndicator indicator;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyParameterService strategyParameterService;

    private static final int PERIOD = 20;
    private static final double STD_DEV_MULTIPLIER = 2;

    // ATR 배수 (완화: 손절 여유 확보)
    private static final double STOP_LOSS_ATR_MULT = 2.0;     // 1.4 → 2.0
    private static final double TAKE_PROFIT_ATR_MULT = 2.5;   // 1.4 → 2.5
    private static final double TRAILING_STOP_ATR_MULT = 1.5; // 1.0 → 1.5

    private Double targetPrice = null;

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }
    /* =====================================================
     *  메인 분석 로직
     * ===================================================== */
    @Override
    public int analyze(String market, List<Candle> candles) {
        // 동적 파라미터 로드 (글로벌 우선)
        int period = strategyParameterService.getIntParam(getStrategyName(), null, "bollinger.period", PERIOD);
        double multiplier = strategyParameterService.getDoubleParam(getStrategyName(), null, "bollinger.multiplier", STD_DEV_MULTIPLIER);
        double stopLossRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "stopLoss.rate", -2.5);
        double takeProfitRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "takeProfit.rate", 2.0);
        double volumeThreshold = strategyParameterService.getDoubleParam(getStrategyName(), null, "volume.threshold", 100.0);
        double rsiOversold = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.oversold", 30.0);
        double rsiOverbought = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.overbought", 70.0);
        int rsiPeriod = strategyParameterService.getIntParam(getStrategyName(), null, "rsi.period", 14);

        try {
            TradeHistory latest = tradeHistoryRepository.findLatestByMarket(market)
                    .stream().findFirst().orElse(null);

            boolean holding = latest != null
                    && latest.getTradeType() == TradeHistory.TradeType.BUY;

            /* =======================
             *  지표 계산
             * ======================= */
            double[] bands = indicator.calculateBollingerBands(candles, period, multiplier);
            double middleBand = bands[0];
            double upperBand = bands[1];
            double lowerBand = bands[2];

            List<Candle> prevCandles = candles.subList(1, candles.size());
            double[] prevBands = indicator.calculateBollingerBands(prevCandles, period, multiplier);

            double bandWidthPercent =
                    ((upperBand - lowerBand) / middleBand) * 100;
            double prevBandWidthPercent =
                    ((prevBands[1] - prevBands[2]) / prevBands[0]) * 100;

            double rsi = calculateRSI(candles, rsiPeriod);
            double atr = calculateATR(candles, rsiPeriod);

            double[] stoch = calculateStochRSI(candles, rsiPeriod, rsiPeriod);
            double stochK = stoch[0];
            double stochD = stoch[1];

            double currentPrice = candles.get(0).getTradePrice().doubleValue();
            double high = candles.get(0).getHighPrice().doubleValue();
            double low = candles.get(0).getLowPrice().doubleValue();

            /* =======================
             *  거래량
             * ======================= */
            double currentVolume = candles.get(0).getCandleAccTradePrice().doubleValue();
            double prevVolume = candles.get(1).getCandleAccTradePrice().doubleValue();

            double avgPrevVolume = candles.subList(1, 6).stream()
                    .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                    .average().orElse(1.0);

            double volumeIncreaseRate = (currentVolume / avgPrevVolume) * 100;

            boolean risingTrend =
                    isMiddleBandRising(candles, period)
                            || currentPrice > middleBand * 1.001;

            /* =====================================================
             *  1️⃣ 매도 로직 (보유 중)
             * ===================================================== */
            if (holding) {
                double buyPrice = latest.getPrice().doubleValue();
                double highest = latest.getHighestPrice() == null
                        ? currentPrice
                        : latest.getHighestPrice().doubleValue();

                if (currentPrice > highest) {
                    latest.setHighestPrice(BigDecimal.valueOf(currentPrice));
                    tradeHistoryRepository.save(latest);
                    highest = currentPrice;
                }

                // 최적화된 고정 비율 손절과 ATR 기반 동적 손절 중 더 타이트한 것 적용
                double fixedStopLoss = buyPrice * (1 + stopLossRate / 100);
                double atrStopLoss = buyPrice - atr * STOP_LOSS_ATR_MULT;
                double stopLoss = Math.max(fixedStopLoss, atrStopLoss);

                // 최적화된 고정 비율 익절과 ATR 기반 동적 익절 중 더 타이트한 것 적용
                double fixedTakeProfit = buyPrice * (1 + takeProfitRate / 100);
                double atrTakeProfit = buyPrice + atr * TAKE_PROFIT_ATR_MULT;
                double takeProfit = Math.min(fixedTakeProfit, atrTakeProfit);

                double trailingStop = highest - atr * TRAILING_STOP_ATR_MULT;

                // 최소 보유 시간 체크 (MIN_HOLD_CANDLES 캔들 이후부터 손절 체크)
                long holdingMinutes = java.time.Duration.between(
                        latest.getCreatedAt(), java.time.LocalDateTime.now()).toMinutes();
                boolean canCheckStopLoss = holdingMinutes >= MIN_HOLD_CANDLES;  // 캔들 단위 = 분 가정

                if (canCheckStopLoss && currentPrice <= stopLoss) {
                    log.info("[{}] 손절 (보유 {}분, 매수가: {}, 현재가: {}, 손절선: {})",
                            market, holdingMinutes, buyPrice, currentPrice, stopLoss);
                    return -1;
                }

                if (currentPrice >= takeProfit && rsi > rsiOverbought) {
                    log.info("[{}] 익절 (매수가: {}, 현재가: {}, 익절선: {})",
                            market, buyPrice, currentPrice, takeProfit);
                    return -1;
                }

                if (canCheckStopLoss && currentPrice <= trailingStop && highest > buyPrice * 1.01) {
                    // 최소 1% 이상 올랐을 때만 트레일링 적용
                    log.info("[{}] 트레일링 종료 (최고가: {}, 현재가: {}, 트레일링선: {})",
                            market, highest, currentPrice, trailingStop);
                    return -1;
                }

                return 0;
            }

            /* =====================================================
             *  2️⃣ 손절 후 쿨다운 체크
             * ===================================================== */
            if (latest != null && latest.getTradeType() == TradeHistory.TradeType.SELL) {
                long minutesSinceLastSell = java.time.Duration.between(
                        latest.getCreatedAt(), java.time.LocalDateTime.now()).toMinutes();
                if (minutesSinceLastSell < STOP_LOSS_COOLDOWN_CANDLES) {
                    log.debug("[{}] 손절 쿨다운 중 ({}분 경과, {}분 필요)",
                            market, minutesSinceLastSell, STOP_LOSS_COOLDOWN_CANDLES);
                    return 0;
                }
            }

            /* =====================================================
             *  3️⃣ 공통 진입 필터 (완화)
             * ===================================================== */
            // 밴드폭 필터 (최적화 결과 반영: 최소 0.8% 이상 벌어졌을 때)
            if (bandWidthPercent < 0.8) {
                log.debug("[{}] 밴드폭 협소 : {:.2f}%", market, bandWidthPercent);
                return 0;
            }

            /* =====================================================
             *  3️⃣ 손절 잘 나는 패턴 차단 (2차 튜닝 핵심)
             * ===================================================== */

            // ❌ 윗꼬리 과다
            double upperWickRatio =
                    (high - currentPrice) / (high - low + 1e-9);
            if (upperWickRatio > 0.45) {
                log.info("[{}] 윗꼬리 과다 : {} ", market, upperWickRatio);
                return 0;
            }

            // ❌ 거래량 식는 구간
            if (currentVolume < prevVolume * 0.9) {
                log.info("[{}] 거래량 식는 구간", market);
                return 0;
            }

            // ❌ 중단선 이격 과다 (추격매수 방지)
            double distanceFromMiddle = (currentPrice - middleBand) / atr;
            if (distanceFromMiddle > 1.3) {
                log.info("[{}] 중단선 이격 과다", market);
                return 0;
            }

            // RSI 피로 구간 (최적화 파라미터 적용)
            if (rsi > rsiOverbought) {
                log.debug("[{}] RSI 피로 구간 : {}", market, rsi);
                return 0;
            }

            /* =====================================================
             *  4️⃣ 진입 시그널
             * ===================================================== */

            // StochRSI 초입 (주력)
            boolean stochEntry =
                    stochK > stochD
                            && stochK < 0.8
                            && rsi > rsiOversold
                            && currentPrice > middleBand * 0.98;

            // RSI + 거래량 동반 돌파 (최적화 로직 반영)
            boolean volumeBreakout =
                    rsi > 45
                            && volumeIncreaseRate >= volumeThreshold
                            && currentPrice > middleBand;

            /* =====================================================
             *  5️⃣ 거래대금 필터
             * ===================================================== */
            double minTradeAmount = getMinTradeAmountByTime();
            double avgTradeAmount =
                    (candles.get(1).getCandleAccTradePrice().doubleValue()
                            + candles.get(2).getCandleAccTradePrice().doubleValue()
                            + candles.get(3).getCandleAccTradePrice().doubleValue()) / 3;

            if (avgTradeAmount < minTradeAmount * 0.7) {
                log.info("[{}] 거래대금 필터 3분간 평균 금액 : {}, 시간대별 거래대금 필터", market, avgTradeAmount, minTradeAmount * 0.7);
                return 0;
            }

            /* =====================================================
            *  6️⃣ 매수
            * ===================================================== */
            if (stochEntry || volumeBreakout) {
                this.targetPrice = currentPrice + atr * 1.5;
                log.info("[{}] 매수 (stoch={}, volumeBreakout={})", market, stochEntry, volumeBreakout);
                return 1;
            }

            this.targetPrice = null;
            return 0;

        } catch (Exception e) {
            log.error("[전략 오류] {}", e.getMessage());
            return 0;
        }
    }

    /* =====================================================
     *  지표 계산
     * ===================================================== */
    private double calculateRSI(List<Candle> candles, int period) {
        double gain = 0, loss = 0;

        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getTradePrice().doubleValue()
                    - candles.get(i + 1).getTradePrice().doubleValue();
            if (diff > 0) gain += diff;
            else loss -= diff;
        }

        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - (100 / (1 + rs));
    }

    private double calculateATR(List<Candle> candles, int period) {
        double sumTR = 0;
        for (int i = 0; i < period; i++) {
            double high = candles.get(i).getHighPrice().doubleValue();
            double low = candles.get(i).getLowPrice().doubleValue();
            double prevClose = candles.get(i + 1).getTradePrice().doubleValue();

            double tr = Math.max(
                    high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose))
            );
            sumTR += tr;
        }
        return sumTR / period;
    }

    private double[] calculateStochRSI(List<Candle> candles, int rsiPeriod, int stochPeriod) {
        List<Double> rsiList = new ArrayList<>();

        for (int i = 0; i <= candles.size() - rsiPeriod - 1; i++) {
            rsiList.add(calculateRSI(candles.subList(i, candles.size()), rsiPeriod));
        }

        if (rsiList.size() < stochPeriod) return new double[]{0, 0};

        List<Double> recent = rsiList.subList(rsiList.size() - stochPeriod, rsiList.size());
        double min = recent.stream().min(Double::compare).orElse(0.0);
        double max = recent.stream().max(Double::compare).orElse(1.0);

        double k = (recent.get(recent.size() - 1) - min) / (max - min + 1e-9);
        double d = recent.stream()
                .skip(Math.max(0, recent.size() - 3))
                .mapToDouble(Double::doubleValue)
                .average().orElse(k);

        return new double[]{k, d};
    }

    private boolean isMiddleBandRising(List<Candle> candles, int period) {
        double prev = indicator.calculateSMA(candles.subList(1, candles.size()), period);
        double current = indicator.calculateSMA(candles, period);
        return current > prev;
    }

    private double getMinTradeAmountByTime() {
        // 한국 시간대 명시적 지정 (서버 시간대 무관)
        int hour = LocalTime.now(ZoneId.of("Asia/Seoul")).getHour();
        // 완화: 기존 대비 50% 수준으로 낮춤
        if (hour >= 2 && hour < 9) return 20_000_000;   // 새벽 02~09시: 2천만
        if (hour >= 9 && hour < 18) return 50_000_000;  // 낮 09~18시: 5천만
        if (hour >= 18 && hour < 22) return 80_000_000; // 저녁 18~22시: 8천만
        return 100_000_000;                              // 밤 22~02시: 1억
    }

    @Override
    public Double getTargetPrice() {
        return targetPrice;
    }


    @Override
    public String getStrategyName() {
        return "BollingerBandStrategy";
    }
}