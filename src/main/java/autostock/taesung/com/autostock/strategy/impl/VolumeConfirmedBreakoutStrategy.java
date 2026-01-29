package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.UpbitOrderbookService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.service.StrategyReplayLogService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeConfirmedBreakoutStrategy implements TradingStrategy {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyReplayLogService replayLogService;
    private final UpbitOrderbookService orderbookService;

    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);

    /* ==============================
     * Market State
     * ============================== */
    private enum MarketState {
        BULL, SIDEWAYS, BEAR
    }

    /* ==============================
     * Parameters
     * ============================== */
    private static final int RSI_MINUTES = 14;
    private static final int ATR_MINUTES = 14;

    private static final double BULL_MIN_EXEC = 0.58;
    private static final double SIDE_MIN_EXEC = 0.65;

    private static final double BULL_VOL_RATIO = 1.8;
    private static final double SIDE_VOL_RATIO = 2.8;

    private static final double STOP_ATR = 0.9;

    /* ==============================
     * Time
     * ============================== */
    private LocalDateTime getTime(Candle c) {
        Object t = c.getCandleDateTimeKst();
        if (t instanceof LocalDateTime) return (LocalDateTime) t;
        if (t instanceof OffsetDateTime) return ((OffsetDateTime) t).toLocalDateTime();
        return LocalDateTime.parse(t.toString());
    }

    private List<Candle> slice(List<Candle> candles, LocalDateTime now, int minutes) {
        LocalDateTime from = now.minusMinutes(minutes);
        return candles.stream()
                .filter(c -> {
                    LocalDateTime t = getTime(c);
                    return !t.isBefore(from) && !t.isAfter(now);
                })
                .toList();
    }

    /* ==============================
     * Indicators
     * ============================== */
    private double rsi(List<Candle> c, LocalDateTime n, int m) {
        List<Candle> w = slice(c, n, m);
        if (w.size() < 3) return 50;

        double g = 0, l = 0;
        for (int i = 0; i < w.size() - 1; i++) {
            double d = w.get(i).getTradePrice().doubleValue()
                    - w.get(i + 1).getTradePrice().doubleValue();
            if (d > 0) g += d;
            else l -= d;
        }
        return l == 0 ? 100 : 100 - (100 / (1 + g / l));
    }

    private double atr(List<Candle> c, LocalDateTime n, int m) {
        List<Candle> w = slice(c, n, m);
        if (w.size() < 2) return 0;

        double sum = 0;
        for (int i = 0; i < w.size() - 1; i++) {
            Candle cur = w.get(i);
            Candle prev = w.get(i + 1);
            double h = cur.getHighPrice().doubleValue();
            double l = cur.getLowPrice().doubleValue();
            double pc = prev.getTradePrice().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / w.size();
    }

    /* ==============================
     * Execution Strength
     * ============================== */
    private double execStrength(String market) {
        try {
            var ob = orderbookService.getOrderbook(market);
            if (ob == null || ob.getOrderbookUnits().isEmpty()) return 0.5;

            double bid = 0, ask = 0;
            int n = Math.min(3, ob.getOrderbookUnits().size());
            for (int i = 0; i < n; i++) {
                bid += ob.getBidSize(i);
                ask += ob.getAskSize(i);
            }
            return bid / (bid + ask + 1e-9);
        } catch (Exception e) {
            return 0.5;
        }
    }

    /* ==============================
     * Market State Detection
     * ============================== */
    private MarketState detectMarketState(List<Candle> candles) {
        if (candles.size() < 20) return MarketState.SIDEWAYS;

        List<Double> prices = candles.stream()
                .limit(20)
                .map(c -> c.getTradePrice().doubleValue())
                .toList();

        double first = prices.get(prices.size() - 1);
        double last = prices.get(0);
        double change = (last - first) / first;

        long higherLows = 0;
        for (int i = 0; i < prices.size() - 1; i++) {
            if (prices.get(i) > prices.get(i + 1)) higherLows++;
        }

        if (change > 0.006 && higherLows >= 12) return MarketState.BULL;
        if (change < -0.006) return MarketState.BEAR;
        return MarketState.SIDEWAYS;
    }

    /* ==============================
     * MAIN
     * ============================== */

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {

        Candle cur = candles.get(0);
        LocalDateTime now = getTime(cur);
        double price = cur.getTradePrice().doubleValue();

        MarketState state = detectMarketState(candles);

        TradeHistory last =
                tradeHistoryRepository.findLatestByMarket(market)
                        .stream().findFirst().orElse(null);

        boolean holding = last != null && last.getTradeType() == TradeHistory.TradeType.BUY;

        double rsi = rsi(candles, now, RSI_MINUTES);
        double atr = atr(candles, now, ATR_MINUTES);

        List<Candle> last5m = slice(candles, now, 5);
        double avgVol = last5m.stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average().orElse(1);

        double curVol = cur.getCandleAccTradePrice().doubleValue();
        double volRatio = curVol / avgVol;

        double exec = execStrength(market);

        /* ======================
         * EXIT
         * ====================== */
        if (holding) {
            double buy = last.getPrice().doubleValue();

            if (price < buy - atr * STOP_ATR) {
                return -1;
            }
            return 0;
        }

        /* ======================
         * ENTRY FILTER
         * ====================== */
        if (state == MarketState.BEAR) return 0;

        if (state == MarketState.SIDEWAYS) {
            if (volRatio < SIDE_VOL_RATIO || exec < SIDE_MIN_EXEC || rsi < 60) {
                return 0;
            }
        }

        if (state == MarketState.BULL) {
            if (volRatio < BULL_VOL_RATIO || exec < BULL_MIN_EXEC || rsi < 55) {
                return 0;
            }
        }

        return 1;
    }

    @Override
    public int analyzeForBacktest(String market, List<Candle> candles, BacktestPosition position) {
        return analyze(market, candles);
    }

    @Override
    public String getStrategyName() {
        return "VolumeConfirmedBreakoutStrategy";
    }
}