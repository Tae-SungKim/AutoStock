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

@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeConfirmedBreakoutStrategy implements TradingStrategy {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyReplayLogService replayLogService;
    private final UpbitOrderbookService orderbookService;

    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, Double> prevExecMap = new HashMap<>();

    /** DB 저장 활성화 */
    @Setter
    private boolean dbLoggingEnabled = true;

    /** 리플레이 로그 DTO */
    @Getter
    @Builder
    public static class ReplayLog {
        LocalDateTime time;
        String market;
        String action;
        String reason;
        double price;
        double rsi;
        double atr;
        double volumeRatio;
        double executionStrength;
        Double profitRate;
    }

    private final List<ReplayLog> replayLogs = new ArrayList<>();

    /** 현재 세션 ID 조회 */
    public String getSessionId() {
        return sessionId;
    }

    /** 메모리 로그 가져오기 */
    public List<ReplayLog> getReplayLogs() {
        return new ArrayList<>(replayLogs);
    }

    /** 메모리 로그 클리어 */
    public void clearReplayLogs() {
        replayLogs.clear();
    }

    /* ==============================
     * 장 상태
     * ============================== */
    private enum MarketRegime {
        BULL, SIDEWAYS, BEAR
    }

    /* ==============================
     * 파라미터
     * ============================== */
    private static final int RSI_MINUTES = 14;
    private static final int ATR_MINUTES = 14;
    private static final int REGIME_MINUTES = 20;

    private static final double MIN_EXEC_STRENGTH = 0.58;
    private static final double EXIT_EXEC_STRENGTH = 0.45;
    private static final double EXIT_VOL_DROP = 0.7;

    /* ==============================
     * 시간
     * ============================== */
    private LocalDateTime time(Candle c) {
        Object t = c.getCandleDateTimeKst();
        if (t instanceof LocalDateTime) return (LocalDateTime) t;
        if (t instanceof OffsetDateTime) return ((OffsetDateTime) t).toLocalDateTime();
        return LocalDateTime.parse(t.toString());
    }

    private List<Candle> slice(List<Candle> candles, LocalDateTime now, int min) {
        LocalDateTime from = now.minusMinutes(min);
        return candles.stream()
                .filter(c -> {
                    LocalDateTime t = time(c);
                    return !t.isBefore(from) && !t.isAfter(now);
                }).toList();
    }

    /* ==============================
     * 지표
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
     * 체결 강도
     * ============================== */
    private double execStrength(String market) {
        try {
            var ob = orderbookService.getOrderbook(market);
            if (ob == null || ob.getOrderbookUnits() == null) return 0.5;

            double bid = 0, ask = 0;
            for (int i = 0; i < Math.min(3, ob.getOrderbookUnits().size()); i++) {
                bid += ob.getBidSize(i);
                ask += ob.getAskSize(i);
            }
            return bid / (bid + ask + 1e-9);
        } catch (Exception e) {
            return 0.5;
        }
    }

    /* ==============================
     * 🔥 자동 장 분류
     * ============================== */
    private MarketRegime detectRegime(List<Candle> candles, LocalDateTime now) {
        List<Candle> w = slice(candles, now, REGIME_MINUTES);
        if (w.size() < 5) return MarketRegime.SIDEWAYS;

        double first = w.get(w.size() - 1).getTradePrice().doubleValue();
        double last = w.get(0).getTradePrice().doubleValue();
        double change = (last - first) / first;

        if (change > 0.004) return MarketRegime.BULL;
        if (change < -0.004) return MarketRegime.BEAR;
        return MarketRegime.SIDEWAYS;
    }

    /* ==============================
     * 메인
     * ============================== */

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {

        Candle cur = candles.get(0);
        LocalDateTime now = time(cur);
        double price = cur.getTradePrice().doubleValue();

        MarketRegime regime = detectRegime(candles, now);

        TradeHistory last =
                tradeHistoryRepository.findLatestByMarket(market)
                        .stream().findFirst().orElse(null);

        boolean holding = last != null && last.getTradeType() == TradeHistory.TradeType.BUY;

        double rsi = rsi(candles, now, RSI_MINUTES);
        double atr = atr(candles, now, ATR_MINUTES);

        List<Candle> last5 = slice(candles, now, 5);
        double avgVol = last5.stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average().orElse(1);

        double curVol = cur.getCandleAccTradePrice().doubleValue();
        double volRatio = curVol / avgVol;

        double exec = execStrength(market);
        double prevExec = prevExecMap.getOrDefault(market, exec);
        prevExecMap.put(market, exec);

        /* ======================
         * EXIT
         * ====================== */
        if (holding) {
            double buy = last.getPrice().doubleValue();
            double profit = (price - buy) / buy;

            if (regime == MarketRegime.BEAR) {
                logExit(now, market, price, rsi, atr, volRatio, exec, profit, "EXIT_BEAR");
                return -1;
            }

            if (exec < EXIT_EXEC_STRENGTH &&
                    curVol < avgVol * EXIT_VOL_DROP &&
                    profit < 0.01) {
                logExit(now, market, price, rsi, atr, volRatio, exec, profit, "EXIT_EARLY");
                return -1;
            }

            if (profit > 0 &&
                    exec < prevExec * 0.85 &&
                    exec < 0.5) {
                logExit(now, market, price, rsi, atr, volRatio, exec, profit, "EXIT_DIVERGENCE");
                return -1;
            }

            if (price < buy - atr * 2.0) {
                logExit(now, market, price, rsi, atr, volRatio, exec, profit, "EXIT_STOP");
                return -1;
            }

            return 0;
        }

        /* ======================
         * BUY (장 상태별)
         * ====================== */
        if (regime == MarketRegime.BEAR) return 0;

        if (regime == MarketRegime.SIDEWAYS) {
            if (rsi < 60 || volRatio < 2.5 || exec < 0.6) return 0;
        }

        if (rsi > 55 && volRatio >= 2.0 && exec >= MIN_EXEC_STRENGTH) {
            logBuy(now, market, price, rsi, atr, volRatio, exec, regime.name());
            return 1;
        }

        return 0;
    }

    /* ==============================
     * 로그
     * ============================== */
    private void logBuy(LocalDateTime t, String m, double p, double r,
                        double a, double v, double e, String reason) {

        replayLogs.add(ReplayLog.builder()
                .time(t).market(m).action("BUY").reason(reason)
                .price(p).rsi(r).atr(a)
                .volumeRatio(v).executionStrength(e)
                .build());

        if (dbLoggingEnabled) {
            replayLogService.saveBreakoutLog(
                    m, t, "BUY", p, r, a, v, 0, e, null, sessionId
            );
        }
    }

    private void logExit(LocalDateTime t, String m, double p, double r,
                         double a, double v, double e,
                         double pr, String reason) {

        replayLogs.add(ReplayLog.builder()
                .time(t).market(m).action("EXIT").reason(reason)
                .price(p).rsi(r).atr(a)
                .volumeRatio(v).executionStrength(e)
                .profitRate(pr)
                .build());

        if (dbLoggingEnabled) {
            replayLogService.saveBreakoutLog(
                    m, t, "EXIT", p, r, a, v, 0, e, pr, sessionId
            );
        }
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