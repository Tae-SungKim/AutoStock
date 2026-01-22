package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.service.MarketVolumeService;
import autostock.taesung.com.autostock.service.StrategyParameterService;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import autostock.taesung.com.autostock.strategy.config.TimeWindowConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeBreakoutStrategy implements TradingStrategy {

    private final TechnicalIndicator indicator;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyParameterService paramService;
    private final RealTradingConfig config;
    private final MarketVolumeService marketVolumeService;

    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;

    private static final double TRAIL_ATR_MULTIPLIER = 1.0;
    private static final double MIN_PROFIT_ATR = 1.2;

    /* ================= 성과 기반 자동 튜닝 ================= */

    private static final int PERFORMANCE_WINDOW = 20;
    private int winCount = 0;
    private int lossCount = 0;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Override
    public String getStrategyName() {
        return "VolumeBreakoutStrategy";
    }

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {

        if (!market.startsWith("KRW-")) return 0;
        if (market.equals("KRW-BTC") || market.equals("KRW-ETH")) return 0;
        if (candles.size() < 30) return 0;

        State state = states.computeIfAbsent(market, k -> new State());

        TradeHistory latest = tradeHistoryRepository.findLatestByMarket(market)
                .stream().findFirst().orElse(null);

        boolean holding = latest != null &&
                latest.getTradeType() == TradeHistory.TradeType.BUY;

        double price = candles.get(0).getTradePrice().doubleValue();
        double atr = atr(candles);
        double rsi = rsi(candles);

        if (holding) {
            return exit(market, latest, price, atr, rsi, state);
        }
        return entry(market, candles, price, atr, rsi, state);
    }

    /* ================= 진입 ================= */

    private int entry(String market, List<Candle> candles,
                      double price, double atr, double rsi, State state) {

        Candle cur = candles.get(0);
        Candle prev = candles.get(1);

        double curTradeAmount = cur.getCandleAccTradePrice().doubleValue();

        /* === 시장 평균 거래대금 === */
        double avgKrwAltTradeAmount30m =
                marketVolumeService.getKrwAltAvgTradeAmount(30);

        /* === 시간대 설정 === */
        TimeWindowConfig cfg = getTimeWindowConfig();

        double dynamicMin =
                avgKrwAltTradeAmount30m * cfg.getLiquidityFactor();

        /* === 성과 기반 자동 보정 === */
        double performanceFactor = getPerformanceFactor();

        double minTradeAmount =
                Math.max(
                        cfg.getHardMinTradeAmount(),
                        dynamicMin * performanceFactor
                );

        if (curTradeAmount < minTradeAmount) {
            return 0;
        }

        /* === 거래량 스파이크 === */
        double avgVol5 = candles.subList(1, 6).stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average().orElse(1);

        boolean strongVolume =
                curTradeAmount >= avgVol5 * 3.0 &&
                        curTradeAmount >= prev.getCandleAccTradePrice().doubleValue() * 2.5;

        /* === 구조 돌파 === */
        boolean breakout =
                price > prev.getHighPrice().doubleValue() &&
                        price > cur.getOpeningPrice().doubleValue();

        boolean rsiEarly = rsi >= 50 && rsi <= 68;
        boolean notFakeVolume = !isFakeVolume(candles);

        if (strongVolume && breakout && rsiEarly && notFakeVolume) {

            state.entryPrice = price;
            state.highest = price;
            state.stop = Math.min(
                    prev.getLowPrice().doubleValue(),
                    price - atr * 0.8
            );
            state.entryTime = LocalDateTime.now();

            log.info("[{}] 🚀 VOLUME BREAKOUT | 거래대금:{} / 기준:{} / 보정:{}",
                    market,
                    String.format("%.0f", curTradeAmount),
                    String.format("%.0f", minTradeAmount),
                    String.format("%.2f", performanceFactor));

            return 1;
        }

        return 0;
    }

    /* ================= 청산 ================= */

    private int exit(String market, TradeHistory trade,
                     double price, double atr, double rsi, State state) {

        state.highest = Math.max(state.highest, price);

        long minutes =
                Duration.between(trade.getCreatedAt(), LocalDateTime.now()).toMinutes();

        if (price <= state.stop && minutes >= 1) {
            recordLoss();
            log.warn("[{}] 🔴 구조 손절", market);
            return -1;
        }

        double profitAtr = (price - state.entryPrice) / atr;

        if (profitAtr >= MIN_PROFIT_ATR) {

            double trail = state.highest - atr * TRAIL_ATR_MULTIPLIER;

            if (rsi >= 75) {
                trail = state.highest - atr * 0.7;
            }

            if (price <= trail && minutes >= 2) {
                recordWin();
                log.info("[{}] 🟢 런 트레일링 익절", market);
                return -1;
            }
        }

        return 0;
    }

    /* ================= 성과 학습 ================= */

    private void recordWin() {
        winCount++;
        trimPerformance();
    }

    private void recordLoss() {
        lossCount++;
        trimPerformance();
    }

    private void trimPerformance() {
        if (winCount + lossCount > PERFORMANCE_WINDOW) {
            if (lossCount > winCount) lossCount--;
            else winCount--;
        }
    }

    private double getPerformanceFactor() {
        int total = winCount + lossCount;
        if (total < 5) return 1.0;

        double winRate = (double) winCount / total;

        if (winRate < 0.35) return 1.25;
        if (winRate > 0.60) return 0.85;

        return 1.0;
    }

    /* ================= 보조 ================= */

    private double atr(List<Candle> c) {
        double sum = 0;
        for (int i = 0; i < ATR_PERIOD; i++) {
            double h = c.get(i).getHighPrice().doubleValue();
            double l = c.get(i).getLowPrice().doubleValue();
            double pc = c.get(i + 1).getTradePrice().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / ATR_PERIOD;
    }

    private double rsi(List<Candle> c) {
        double g = 0, l = 0;
        for (int i = 0; i < RSI_PERIOD; i++) {
            double d = c.get(i).getTradePrice().doubleValue()
                    - c.get(i + 1).getTradePrice().doubleValue();
            if (d > 0) g += d;
            else l -= d;
        }
        return l == 0 ? 100 : 100 - (100 / (1 + g / l));
    }

    private boolean isFakeVolume(List<Candle> candles) {
        Candle c = candles.get(0);
        double body =
                Math.abs(c.getTradePrice().doubleValue() - c.getOpeningPrice().doubleValue());
        double range =
                c.getHighPrice().doubleValue() - c.getLowPrice().doubleValue();

        return range > 0 &&
                body / range < 0.3 &&
                c.getTradePrice().doubleValue() < c.getHighPrice().doubleValue() * 0.97;
    }

    private TimeWindowConfig getTimeWindowConfig() {
        int hour = LocalTime.now(ZoneId.of("Asia/Seoul")).getHour();

        if (hour >= 2 && hour < 8) return new TimeWindowConfig(3_000_000_000.0, 0.6);
        if (hour >= 9 && hour < 12) return new TimeWindowConfig(2_500_000_000.0, 0.5);
        if (hour >= 12 && hour < 18) return new TimeWindowConfig(2_000_000_000.0, 0.45);
        if (hour >= 18 && hour < 22) return new TimeWindowConfig(2_500_000_000.0, 0.5);

        return new TimeWindowConfig(3_500_000_000.0, 0.6);
    }

    private static class State {
        double entryPrice;
        double highest;
        double stop;
        LocalDateTime entryTime;
    }
}