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
    private static final double MIN_PROFIT_ATR = 1.0;

    private static final int Z_WINDOW = 20;
    private static final int VOLUME_LOOKBACK = 30;

    // 🔒 거래량 안전장치
    private static final double ABS_MIN_AVG_VOLUME = 5_000;   // 완전 유령 코인 차단
    private static final double DZ_THRESHOLD = 0.35;          // Z-score 상승 속도

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
        if (candles.size() < 40) return 0;

        int last = candles.size() - 1;

        Candle cur = candles.get(last - 1);
        Candle prev = candles.get(last - 2);

        State state = states.computeIfAbsent(market, k -> new State());

        TradeHistory latest =
                tradeHistoryRepository.findLatestByMarket(market)
                        .stream().findFirst().orElse(null);

        boolean holding =
                latest != null && latest.getTradeType() == TradeHistory.TradeType.BUY;

        double price = cur.getTradePrice().doubleValue();
        double atr = atr(candles);
        double rsi = rsi(candles);

        double zScore = volumeZScore(candles, Z_WINDOW);
        double prevZ = volumeZScore(candles.subList(0, candles.size() - 1), Z_WINDOW);
        double dz = zScore - prevZ;

        if (holding) {
            return exit(market, latest, price, atr, rsi, zScore, state);
        }

        return entry(market, candles, cur, prev, price, atr, rsi, zScore, dz, state);
    }

    /* ================= 진입 ================= */

    private int entry(String market, List<Candle> candles,
                      Candle cur, Candle prev,
                      double price, double atr, double rsi,
                      double zScore, double dz,
                      State state) {

        int last = candles.size() - 1;
        TimeWindowConfig cfg = getTimeWindowConfig();

        double curVolume = cur.getCandleAccTradeVolume().doubleValue();

        double avgVolume30 =
                candles.subList(last - VOLUME_LOOKBACK, last).stream()
                        .mapToDouble(c -> c.getCandleAccTradeVolume().doubleValue())
                        .average().orElse(0);

        // ❌ 거래량 거의 없는 코인 제거
        if (avgVolume30 < ABS_MIN_AVG_VOLUME) return 0;
        if (curVolume < avgVolume30 * 0.8) return 0;

        // ❌ Z-score 증가 중이 아니면 진입 금지
        if (dz < DZ_THRESHOLD) return 0;

        double priceChange =
                (price - prev.getTradePrice().doubleValue())
                        / prev.getTradePrice().doubleValue();

        boolean earlyBreakout =
                zScore >= 1.6 &&
                        priceChange >= 0.0015 &&
                        rsi >= 35 && rsi <= 60;

        boolean strongBreakout =
                zScore >= 2.1 &&
                        price > prev.getHighPrice().doubleValue() &&
                        rsi <= 78;

        boolean entrySignal = earlyBreakout || strongBreakout;

        log.debug(
                "[{}] ENTRY chk | Z={} dZ={} vol={} avg30={} rsi={}",
                market,
                String.format("%.2f", zScore),
                String.format("%.2f", dz),
                (long) curVolume,
                (long) avgVolume30,
                String.format("%.1f", rsi)
        );

        if (!entrySignal) return 0;

        state.entryPrice = price;
        state.highest = price;
        state.stop = Math.min(
                prev.getLowPrice().doubleValue(),
                price - atr * 0.7
        );
        state.entryZ = zScore;

        log.info("[{}] 🚀 ENTRY | Z={} dZ={} rsi={}",
                market,
                String.format("%.2f", zScore),
                String.format("%.2f", dz),
                String.format("%.1f", rsi)
        );

        return 1;
    }

    /* ================= 청산 ================= */

    private int exit(String market, TradeHistory trade,
                     double price, double atr, double rsi,
                     double zScore, State state) {

        state.highest = Math.max(state.highest, price);

        long minutes =
                Duration.between(trade.getCreatedAt(), LocalDateTime.now()).toMinutes();

        if (price <= state.stop && minutes >= 1) {
            log.warn("[{}] 🔴 STOP LOSS", market);
            return -1;
        }

        double profitAtr = (price - state.entryPrice) / atr;
        if (profitAtr < MIN_PROFIT_ATR) return 0;

        if (zScore < 1.0 && rsi < 65 && minutes >= 2) {
            log.info("[{}] 🟡 Z WEAK EXIT", market);
            return -1;
        }

        if (zScore < 0.3 && minutes >= 1) {
            log.info("[{}] 🟢 Z DROP EXIT", market);
            return -1;
        }

        double trail = state.highest - atr * TRAIL_ATR_MULTIPLIER;
        if (price <= trail && minutes >= 3) {
            log.info("[{}] 🔵 TRAIL EXIT", market);
            return -1;
        }

        return 0;
    }

    /* ================= 지표 ================= */

    private double atr(List<Candle> c) {
        double sum = 0;
        int last = c.size() - 1;
        for (int i = last - 2; i >= last - ATR_PERIOD - 1; i--) {
            double h = c.get(i).getHighPrice().doubleValue();
            double l = c.get(i).getLowPrice().doubleValue();
            double pc = c.get(i + 1).getTradePrice().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / ATR_PERIOD;
    }

    private double rsi(List<Candle> c) {
        double g = 0, l = 0;
        int last = c.size() - 1;
        for (int i = last - 2; i >= last - RSI_PERIOD - 1; i--) {
            double d = c.get(i).getTradePrice().doubleValue()
                    - c.get(i + 1).getTradePrice().doubleValue();
            if (d > 0) g += d;
            else l -= d;
        }
        return l == 0 ? 100 : 100 - (100 / (1 + g / l));
    }

    private double volumeZScore(List<Candle> candles, int window) {
        if (candles.size() < window + 1) return 0;

        int last = candles.size() - 1;

        double mean =
                candles.subList(last - window, last).stream()
                        .mapToDouble(c -> c.getCandleAccTradeVolume().doubleValue())
                        .average().orElse(0);

        double variance =
                candles.subList(last - window, last).stream()
                        .mapToDouble(c -> Math.pow(
                                c.getCandleAccTradeVolume().doubleValue() - mean, 2))
                        .average().orElse(0);

        double std = Math.sqrt(variance);
        if (std == 0) return 0;

        double curVolume =
                candles.get(last).getCandleAccTradeVolume().doubleValue();

        return (curVolume - mean) / std;
    }

    private TimeWindowConfig getTimeWindowConfig() {
        int hour = LocalTime.now(ZoneId.of("Asia/Seoul")).getHour();
        if (hour >= 2 && hour < 8) return new TimeWindowConfig(8_000, 1.1, 0.6);
        if (hour >= 9 && hour < 12) return new TimeWindowConfig(15_000, 1.2, 0.7);
        if (hour >= 12 && hour < 18) return new TimeWindowConfig(20_000, 1.3, 0.8);
        if (hour >= 18 && hour < 22) return new TimeWindowConfig(25_000, 1.35, 0.9);
        return new TimeWindowConfig(12_000, 1.15, 0.7);
    }

    private static class State {
        double entryPrice;
        double highest;
        double stop;
        double entryZ;
    }
}