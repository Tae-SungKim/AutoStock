package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.exchange.upbit.UpbitOrderbookService;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.service.StrategyReplayLogService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeConfirmedBreakoutStrategy implements TradingStrategy {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyReplayLogService replayLogService;
    private final UpbitOrderbookService orderbookService;

    /** 현재 세션 ID */
    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);

    /** DB 저장 활성화 */
    @Setter
    private boolean dbLoggingEnabled = true;

    /* ==============================
     * 🔁 Replay Log
     * ============================== */
    @Getter
    @Builder
    public static class ReplayLog {
        LocalDateTime time;
        String market;
        String action;
        double price;
        double rsi;
        double atr;
        double volumeRatio;
        double density;
        double executionStrength;
        Double profitRate;
    }

    private final List<ReplayLog> replayLogs = new ArrayList<>();

    /* ==============================
     * ⚙️ 전략 파라미터
     * ============================== */
    private static final int RSI_MINUTES = 14;
    private static final int ATR_MINUTES = 14;

    private static final int MARKET_CHECK_MINUTES = 10;
    private static final double MIN_MARKET_DENSITY = 0.45;
    private static final double MIN_MARKET_AVG_VOLUME = 5_000_000;

    private static final int FAKE_PUMP_MINUTES = 3;
    private static final double FAKE_PRICE_CHANGE = 0.006;
    private static final double FAKE_MIN_DENSITY = 0.30;

    private static final double MIN_EXECUTION_STRENGTH = 0.58;

    /* ==============================
     * 🕒 시간 처리
     * ============================== */
    private LocalDateTime getTime(Candle c) {
        Object t = c.getCandleDateTimeKst();
        if (t instanceof LocalDateTime) return (LocalDateTime) t;
        if (t instanceof OffsetDateTime) return ((OffsetDateTime) t).toLocalDateTime();
        return LocalDateTime.parse(t.toString());
    }

    private List<Candle> sliceByMinutes(List<Candle> candles, LocalDateTime now, int minutes) {
        LocalDateTime from = now.minusMinutes(minutes);
        return candles.stream()
                .filter(c -> {
                    LocalDateTime t = getTime(c);
                    return !t.isBefore(from) && !t.isAfter(now);
                })
                .toList();
    }

    /* ==============================
     * 📊 지표 계산
     * ============================== */
    private double calculateRSI(List<Candle> candles, LocalDateTime now, int minutes) {
        List<Candle> w = sliceByMinutes(candles, now, minutes);
        if (w.size() < 3) return 50;

        double gain = 0, loss = 0;
        for (int i = 0; i < w.size() - 1; i++) {
            double diff = w.get(i).getTradePrice().doubleValue()
                    - w.get(i + 1).getTradePrice().doubleValue();
            if (diff > 0) gain += diff;
            else loss -= diff;
        }
        return loss == 0 ? 100 : 100 - (100 / (1 + gain / loss));
    }

    private double calculateATR(List<Candle> candles, LocalDateTime now, int minutes) {
        List<Candle> w = sliceByMinutes(candles, now, minutes);
        if (w.size() < 2) return 0;

        double sum = 0;
        for (int i = 0; i < w.size() - 1; i++) {
            Candle c = w.get(i);
            Candle p = w.get(i + 1);
            double h = c.getHighPrice().doubleValue();
            double l = c.getLowPrice().doubleValue();
            double pc = p.getTradePrice().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / w.size();
    }

    /* ==============================
     * ⏱ 밀도 / 필터
     * ============================== */
    private double minuteDensity(List<Candle> candles, LocalDateTime now, int minutes) {
        Set<LocalDateTime> set = sliceByMinutes(candles, now, minutes).stream()
                .map(c -> getTime(c).truncatedTo(ChronoUnit.MINUTES))
                .collect(Collectors.toSet());
        return set.size() / (double) minutes;
    }

    private boolean isBadMarket(List<Candle> candles, LocalDateTime now) {
        List<Candle> w = sliceByMinutes(candles, now, MARKET_CHECK_MINUTES);
        if (w.isEmpty()) return true;

        double density = minuteDensity(candles, now, MARKET_CHECK_MINUTES);
        double avgVolume = w.stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average().orElse(0);

        return density < MIN_MARKET_DENSITY || avgVolume < MIN_MARKET_AVG_VOLUME;
    }

    private boolean isFakePump(List<Candle> candles, LocalDateTime now) {
        List<Candle> w = sliceByMinutes(candles, now, FAKE_PUMP_MINUTES);
        if (w.size() < 2) return false;

        double first = w.get(w.size() - 1).getTradePrice().doubleValue();
        double last = w.get(0).getTradePrice().doubleValue();
        double change = (last - first) / first;

        double density = minuteDensity(candles, now, FAKE_PUMP_MINUTES);
        return change >= FAKE_PRICE_CHANGE && density < FAKE_MIN_DENSITY;
    }

    /* ==============================
     * 🔥 체결 강도 (호가창 기반)
     * ============================== */
    private double executionStrength(String market) {
        try {
            var ob = orderbookService.getOrderbook(market);
            if (ob == null || ob.getOrderbookUnits() == null || ob.getOrderbookUnits().isEmpty()) {
                return 0.5;
            }

            double bid = 0, ask = 0;
            int count = Math.min(3, ob.getOrderbookUnits().size());
            for (int i = 0; i < count; i++) {
                bid += ob.getBidSize(i);
                ask += ob.getAskSize(i);
            }
            return bid / (bid + ask + 1e-9);
        } catch (Exception e) {
            return 0.5;
        }
    }

    /* ==============================
     * 🚀 메인 로직
     * ============================== */
    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {

        if (candles.size() < 10) return 0;

        Candle cur = candles.get(0);
        LocalDateTime now = getTime(cur);
        double price = cur.getTradePrice().doubleValue();

        TradeHistory last =
                tradeHistoryRepository.findLatestByMarket(market)
                        .stream().findFirst().orElse(null);

        boolean holding = last != null && last.getTradeType() == TradeHistory.TradeType.BUY;

        double rsi = calculateRSI(candles, now, RSI_MINUTES);
        double atr = calculateATR(candles, now, ATR_MINUTES);
        double density = minuteDensity(candles, now, MARKET_CHECK_MINUTES);

        List<Candle> last5m = sliceByMinutes(candles, now, 5);
        double avgVol = last5m.stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average().orElse(1);
        double volRatio = cur.getCandleAccTradePrice().doubleValue() / avgVol;

        double execStrength = executionStrength(market);

        /* ======================
         * 💣 매도
         * ====================== */
        if (holding) {
            double buy = last.getPrice().doubleValue();
            double high = Optional.ofNullable(last.getHighestPrice())
                    .map(BigDecimal::doubleValue)
                    .orElse(buy);

            if (price > high) {
                last.setHighestPrice(BigDecimal.valueOf(price));
                tradeHistoryRepository.save(last);
                high = price;
            }

            double profitRate = (price - buy) / buy;

            if (price <= buy - atr * 2.0 || price <= high - atr * 1.5) {
                replayLogs.add(exitLog(now, market, price, rsi, atr, volRatio, density, execStrength, profitRate));
                return -1;
            }

            replayLogs.add(holdLog(now, market, price, rsi, atr, volRatio, density, execStrength));
            return 0;
        }

        /* ======================
         * 🚫 필터
         * ====================== */
        if (isBadMarket(candles, now)) return 0;
        if (isFakePump(candles, now)) return 0;
        if (execStrength < MIN_EXECUTION_STRENGTH) return 0;

        /* ======================
         * ✅ 매수
         * ====================== */
        if (rsi > 55 && volRatio >= 2.0) {
            replayLogs.add(buyLog(now, market, price, rsi, atr, volRatio, density, execStrength));
            return 1;
        }

        return 0;
    }

    /* ==============================
     * 🔁 Replay Helpers
     * ============================== */
    private ReplayLog buyLog(LocalDateTime t, String m, double p, double r, double a,
                             double v, double d, double e) {
        saveToDb(m, t, "BUY", p, r, a, v, d, e, null);
        return ReplayLog.builder()
                .time(t).market(m).action("BUY")
                .price(p).rsi(r).atr(a)
                .volumeRatio(v).density(d)
                .executionStrength(e)
                .build();
    }

    private ReplayLog holdLog(LocalDateTime t, String m, double p, double r, double a,
                              double v, double d, double e) {
        return ReplayLog.builder()
                .time(t).market(m).action("HOLD")
                .price(p).rsi(r).atr(a)
                .volumeRatio(v).density(d)
                .executionStrength(e)
                .build();
    }

    private ReplayLog exitLog(LocalDateTime t, String m, double p, double r, double a,
                              double v, double d, double e, double pr) {
        saveToDb(m, t, "EXIT", p, r, a, v, d, e, pr);
        return ReplayLog.builder()
                .time(t).market(m).action("EXIT")
                .price(p).rsi(r).atr(a)
                .volumeRatio(v).density(d)
                .executionStrength(e)
                .profitRate(pr)
                .build();
    }

    /** DB 저장 */
    private void saveToDb(String market, LocalDateTime time, String action,
                          double price, double rsi, double atr,
                          double volumeRatio, double density,
                          double execStrength, Double profitRate) {
        if (!dbLoggingEnabled) return;

        try {
            replayLogService.saveBreakoutLog(
                    market, time, action, price, rsi, atr,
                    volumeRatio, density, profitRate, sessionId
            );
        } catch (Exception e) {
            log.debug("[REPLAY_DB] Save failed: {}", e.getMessage());
        }
    }

    /** 현재 세션 ID 조회 */
    public String getSessionId() {
        return sessionId;
    }

    /** 메모리 로그 가져오기 (API용) */
    public List<ReplayLog> getReplayLogs() {
        return new ArrayList<>(replayLogs);
    }

    /** 메모리 로그 클리어 */
    public void clearReplayLogs() {
        replayLogs.clear();
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