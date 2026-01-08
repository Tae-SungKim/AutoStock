package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BollingerBandStrategy implements TradingStrategy {
    private static final int STOP_LOSS_COOLDOWN_CANDLES = 3;
    private final TechnicalIndicator indicator;
    private final TradeHistoryRepository tradeHistoryRepository;

    private static final int PERIOD = 20;
    private static final double STD_DEV_MULTIPLIER = 2;

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
        try {
            TradeHistory latest = tradeHistoryRepository.findLatestByMarket(market)
                    .stream().findFirst().orElse(null);

            boolean holding = latest != null
                    && latest.getTradeType() == TradeHistory.TradeType.BUY;

            /* =======================
             *  지표 계산
             * ======================= */
            double[] bands = indicator.calculateBollingerBands(candles, PERIOD, STD_DEV_MULTIPLIER);
            double middleBand = bands[0];
            double upperBand = bands[1];
            double lowerBand = bands[2];

            List<Candle> prevCandles = candles.subList(1, candles.size());
            double[] prevBands = indicator.calculateBollingerBands(prevCandles, PERIOD, STD_DEV_MULTIPLIER);

            double bandWidthPercent =
                    ((upperBand - lowerBand) / middleBand) * 100;
            double prevBandWidthPercent =
                    ((prevBands[1] - prevBands[2]) / prevBands[0]) * 100;

            double rsi = calculateRSI(candles, 14);
            double atr = calculateATR(candles, 14);

            double[] stoch = calculateStochRSI(candles, 14, 14);
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
                    isMiddleBandRising(candles)
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

                double stopLoss = buyPrice - atr * 0.8;
                double takeProfit = buyPrice + atr * 1.4;
                double trailingStop = highest - atr;

                if (currentPrice <= stopLoss) {
                    log.info("[{}] 손절", market);
                    return -1;
                }

                if (currentPrice >= takeProfit && rsi > 70) {
                    log.info("[{}] 익절", market);
                    return -1;
                }

                if (currentPrice <= trailingStop) {
                    log.info("[{}] 트레일링 종료", market);
                    return -1;
                }

                return 0;
            }

            /* =====================================================
             *  2️⃣ 공통 진입 필터 (너무 강하지 않게)
             * ===================================================== */
            boolean commonEntryFilter =
                    volumeIncreaseRate >= 100//120
                            && bandWidthPercent >= 1.0//1.2
                            //&& bandWidthPercent > prevBandWidthPercent * 1.05
                            && risingTrend;

            if (!commonEntryFilter) {
                log.info("[{}] 진입 필터", market);
                return 0;
            }

            /* =====================================================
             *  3️⃣ 손절 잘 나는 패턴 차단 (2차 튜닝 핵심)
             * ===================================================== */

            // ❌ 윗꼬리 과다
            double upperWickRatio =
                    (high - currentPrice) / (high - low + 1e-9);
            if (upperWickRatio > 0.45) {
                log.info("[{}] 윗꼬리 과다 {} ", market, upperWickRatio);
                return 0;
            }

            // ❌ 거래량 식는 구간
            if (currentVolume < prevVolume * 0.5) {
                log.info("[{}] 거래량 식는구간 {} ", market, currentVolume);
                return 0;
            }

            // ❌ 중단선 이격 과다 (추격매수 방지)
            double distanceFromMiddle = (currentPrice - middleBand) / atr;
            if (distanceFromMiddle > 2.5) {
                log.info("[{}] 중단선 이격 과다(추격매수 방지) {} ", market, distanceFromMiddle);
                return 0;
            }

            // ❌ RSI 피로 구간
            if (rsi > /*62*/80) {
                log.info("[{}] rsi 피로구간 {} ", market, rsi);
                return 0;
            }

            /* =====================================================
             *  4️⃣ 진입 시그널
             * ===================================================== */

            // StochRSI 초입 (주력)
            boolean stochEntry =
                    stochK > stochD
                            && stochK > 0.15
                            //&& stochK < 0.8//0.6
                            && rsi > 45
                            && currentPrice > middleBand;

            // RSI 보조
            boolean rsiEntry =
                    rsi >= 50 //&& rsi <= 65
                            && currentPrice > middleBand;

            /* =====================================================
             *  5️⃣ 거래대금 필터
             * ===================================================== */
            double minTradeAmount = getMinTradeAmountByTime();

            // [수정] candles.get(1~3) 대신 0번(현재)을 포함하거나 0번 위주로 변경
            // 초단타는 '지금' 돈이 들어오는지가 훨씬 중요합니다.
            double currentCandleAmount = candles.get(0).getCandleAccTradePrice().doubleValue();

            // 과거 평균보다 '현재' 터지고 있는 거래대금이 기준치(minTradeAmount)를 넘었는지 확인
            if (currentCandleAmount < minTradeAmount) {
                log.info("[{}] 거래대금 필터 {} ", market, currentCandleAmount);
                return 0;
            }


            /*double minTradeAmount = getMinTradeAmountByTime();
            double avgTradeAmount =
                    (candles.get(1).getCandleAccTradePrice().doubleValue()
                            + candles.get(2).getCandleAccTradePrice().doubleValue()
                            + candles.get(3).getCandleAccTradePrice().doubleValue()) / 3;

            if (avgTradeAmount < minTradeAmount * 0.7) {
                log.info("[{}] 거래대금 필터 {} ", market, avgTradeAmount);
                return 0;
            }*/

            /* =====================================================
             *  6️⃣ 매수
             * ===================================================== */
            if (stochEntry || rsiEntry) {
                this.targetPrice = currentPrice + atr * 1.5;
                log.info("[{}] 매수 (stoch={}, rsi={})", market, stochEntry, rsiEntry);
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

    private boolean isMiddleBandRising(List<Candle> candles) {
        double prev = indicator.calculateSMA(candles.subList(1, candles.size()), PERIOD);
        double current = indicator.calculateSMA(candles, PERIOD);
        return current > prev;
    }

    private double getMinTradeAmountByTime_old() {
        int hour = LocalTime.now().getHour();
        if (hour >= 2 && hour < 9) return 50_000_000;
        if (hour >= 9 && hour < 18) return 120_000_000;
        if (hour >= 18 && hour < 22) return 180_000_000;
        return 220_000_000;
    }

    private double getMinTradeAmountByTime() {
        int hour = LocalTime.now().getHour();
        // 분봉 기준이므로 단위를 확 낮춥니다. (현재 코드는 3천만~1억 원 사이)
        if (hour >= 2 && hour < 9) return 10_000_000;   // 1천만
        if (hour >= 9 && hour < 18) return 30_000_000;  // 3천만
        if (hour >= 18 && hour < 22) return 40_000_000; // 4천만
        return 50_000_000; // 심야
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