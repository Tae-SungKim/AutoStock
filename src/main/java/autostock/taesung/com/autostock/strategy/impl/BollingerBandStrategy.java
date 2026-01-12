package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.backtest.dto.ExitReason;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import autostock.taesung.com.autostock.service.StrategyParameterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BollingerBandStrategy implements TradingStrategy {

    private static final int PERIOD = 20;
    private static final double STD_DEV_MULTIPLIER = 2.0;

    private static final int STOP_LOSS_COOLDOWN_CANDLES = 5;
    private static final int MIN_HOLD_CANDLES = 3;

    private static final double STOP_LOSS_ATR_MULT = 2.0;
    private static final double TAKE_PROFIT_ATR_MULT = 2.5;
    private static final double TRAILING_STOP_ATR_MULT = 1.5;

    private final TechnicalIndicator indicator;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyParameterService strategyParameterService;

    private Double targetPrice = null;

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {

        if (candles.size() < 30) return 0;

        int period = strategyParameterService.getIntParam(getStrategyName(), null, "bollinger.period", PERIOD);
        double multiplier = strategyParameterService.getDoubleParam(getStrategyName(), null, "bollinger.multiplier", STD_DEV_MULTIPLIER);
        double stopLossRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "stopLoss.rate", -2.5);
        double takeProfitRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "takeProfit.rate", 2.0);
        double volumeThreshold = strategyParameterService.getDoubleParam(getStrategyName(), null, "volume.threshold", 120.0);
        double rsiOversold = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.oversold", 30.0);
        double rsiOverbought = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.overbought", 70.0);
        int rsiPeriod = strategyParameterService.getIntParam(getStrategyName(), null, "rsi.period", 14);

        TradeHistory latest = tradeHistoryRepository.findLatestByMarket(market)
                .stream().findFirst().orElse(null);

        boolean holding = latest != null && latest.getTradeType() == TradeHistory.TradeType.BUY;

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

            double fixedStopLoss = buyPrice * (1 + stopLossRate / 100);
            double atrStopLoss = buyPrice - atr * STOP_LOSS_ATR_MULT;
            double stopLoss = Math.max(fixedStopLoss, atrStopLoss);

            double fixedTakeProfit = buyPrice * (1 + takeProfitRate / 100);
            double atrTakeProfit = buyPrice + atr * TAKE_PROFIT_ATR_MULT;
            double takeProfit = Math.min(fixedTakeProfit, atrTakeProfit);

            double trailingStop = highest - atr * TRAILING_STOP_ATR_MULT;

            long holdingMinutes = java.time.Duration
                    .between(latest.getCreatedAt(), java.time.LocalDateTime.now())
                    .toMinutes();

            if (holdingMinutes >= MIN_HOLD_CANDLES && currentPrice <= stopLoss) {
                log.info("[{}] 손절", market);
                return -1;
            }

            if (currentPrice >= takeProfit && rsi > rsiOverbought) {
                log.info("[{}] 익절", market);
                return -1;
            }

            if (holdingMinutes >= MIN_HOLD_CANDLES &&
                    currentPrice <= trailingStop &&
                    highest > buyPrice * 1.01) {
                log.info("[{}] 트레일링 종료", market);
                return -1;
            }

            return 0;
        }

        /* =====================================================
         * 2️⃣ 손절 쿨다운
         * ===================================================== */
        if (latest != null && latest.getTradeType() == TradeHistory.TradeType.SELL) {
            long diff = java.time.Duration
                    .between(latest.getCreatedAt(), java.time.LocalDateTime.now())
                    .toMinutes();
            if (diff < STOP_LOSS_COOLDOWN_CANDLES) return 0;
        }

        /* =====================================================
         * 3️⃣ 1분봉 생존 필터
         * ===================================================== */

        if (bandWidthPercent < 0.8) return 0;

        if (!isHigherLowStructure(candles)) return 0;

        if (isFakeRebound(candles)) return 0;

        double candleMove = Math.abs(
                candles.get(0).getTradePrice().doubleValue()
                        - candles.get(1).getTradePrice().doubleValue()
        );
        if (candleMove > atr * 0.8) return 0;

        if (currentVolume < avgVolume * 0.9) return 0;

        if (rsi > rsiOverbought) return 0;

        /* =====================================================
         * 4️⃣ 진입 시그널
         * ===================================================== */
        boolean stochEntry =
                stochK > stochD &&
                        stochK < 0.8 &&
                        rsi > rsiOversold &&
                        currentPrice > middleBand * 0.98;

        boolean volumeBreakout =
                rsi > 45 &&
                        (currentVolume / avgVolume) * 100 >= volumeThreshold &&
                        currentPrice > middleBand;

        /* =====================================================
         * 5️⃣ 거래대금 필터
         * ===================================================== */
        double minTradeAmount = getMinTradeAmountByTime();
        double avgTradeAmount = candles.subList(1, 4).stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average().orElse(0);

        if (avgTradeAmount < minTradeAmount * 0.7) return 0;

        /* =====================================================
         * 6️⃣ 매수
         * ===================================================== */
        if (stochEntry || volumeBreakout) {
            this.targetPrice = currentPrice + atr * 1.5;
            log.info("[{}] 매수 진입", market);
            return 1;
        }

        targetPrice = null;
        return 0;
    }

    /* ================== 보조 메서드 ================== */

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
        double d = recent.stream().skip(Math.max(0, recent.size() - 3))
                .mapToDouble(Double::doubleValue).average().orElse(k);
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
    public int analyzeForBacktest(String market, List<Candle> candles, BacktestPosition position) {
        if (candles.size() < 30) return 0;

        int period = strategyParameterService.getIntParam(getStrategyName(), null, "bollinger.period", PERIOD);
        double multiplier = strategyParameterService.getDoubleParam(getStrategyName(), null, "bollinger.multiplier", STD_DEV_MULTIPLIER);
        double stopLossRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "stopLoss.rate", -2.5);
        double takeProfitRate = strategyParameterService.getDoubleParam(getStrategyName(), null, "takeProfit.rate", 2.0);
        double volumeThreshold = strategyParameterService.getDoubleParam(getStrategyName(), null, "volume.threshold", 120.0);
        double rsiOversold = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.oversold", 30.0);
        double rsiOverbought = strategyParameterService.getDoubleParam(getStrategyName(), null, "rsi.overbought", 70.0);
        int rsiPeriod = strategyParameterService.getIntParam(getStrategyName(), null, "rsi.period", 14);

        boolean holding = position != null && position.isHolding();

        double[] bands = indicator.calculateBollingerBands(candles, period, multiplier);
        double middleBand = bands[0];

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

        if (holding) {
            double buyPrice = position.getBuyPrice();
            double highest = position.getHighestPrice();

            double fixedStopLoss = buyPrice * (1 + stopLossRate / 100);
            double atrStopLoss = buyPrice - atr * STOP_LOSS_ATR_MULT;
            
            // 손절 사유 세분화
            if (currentPrice <= atrStopLoss) {
                return exit(ExitReason.STOP_LOSS_ATR);
            }
            if (currentPrice <= fixedStopLoss) {
                return exit(ExitReason.STOP_LOSS_FIXED);
            }

            double fixedTakeProfit = buyPrice * (1 + takeProfitRate / 100);
            double atrTakeProfit = buyPrice + atr * TAKE_PROFIT_ATR_MULT;
            double takeProfit = Math.min(fixedTakeProfit, atrTakeProfit);

            if (currentPrice >= takeProfit && rsi > rsiOverbought) {
                return exit(ExitReason.TAKE_PROFIT);
            }

            double trailingStop = highest - atr * TRAILING_STOP_ATR_MULT;
            if (currentPrice <= trailingStop && highest > buyPrice * 1.01) {
                return exit(ExitReason.TRAILING_STOP);
            }

            // 추가 사유 예시
            if (currentVolume < avgVolume * 0.5) {
                return exit(ExitReason.VOLUME_DROP);
            }
            
            if (rsi > 85) {
                return exit(ExitReason.OVERHEATED);
            }

            return 0;
        }

        // 진입 로직은 analyze와 동일하게 유지하되 리턴값만 반환
        return analyze(market, candles);
    }

    @Override
    public String getStrategyName() {
        return "BollingerBandStrategy";
    }
}