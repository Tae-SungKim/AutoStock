package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.entity.ImpulsePosition;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.service.ImpulsePositionService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import autostock.taesung.com.autostock.strategy.replay.ReplayResult;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class VolumeImpulseStrategy implements TradingStrategy {

    private final ImpulsePositionService positionService;

    // =====================
    // CONFIG
    // =====================
    private static final int WINDOW = 15;

    private static final double IMPULSE_Z = 1.5;
    private static final double CONFIRM_Z = 1.2;
    private static final double REBREAK_Z = 1.0;

    private static final double DENSITY_MIN = 0.3;

    private static final double VOL_MULT_IMPULSE = 1.6;
    private static final double VOL_MULT_REBREAK = 1.3;

    private static final double MAX_PULLBACK = 0.5;

    // EXIT
    private static final double EXIT_Z_COLLAPSE = 0.3;
    private static final double EXIT_VOL_RATIO = 0.7;
    private static final double STOP_LOSS_RATIO = 0.95;
    private static final double TRAILING_STOP_RATIO = 0.93;
    private static final int MAX_HOLD_MIN = 5;

    // =====================
    // PHASE STATE
    // =====================
    private final Map<String, ImpulseState> stateMap = new HashMap<>();

    enum Phase {
        IDLE,
        IMPULSE,
        CONFIRMED,
        PULLBACK
    }

    @Data
    private static class ImpulseState {
        Phase phase = Phase.IDLE;
        double peakPrice;

        void toImpulse(double price) {
            this.phase = Phase.IMPULSE;
            this.peakPrice = price;
        }

        void toConfirmed() {
            this.phase = Phase.CONFIRMED;
        }

        void toPullback() {
            this.phase = Phase.PULLBACK;
        }

        void reset() {
            this.phase = Phase.IDLE;
            this.peakPrice = 0;
        }
    }

    @Override
    public String getStrategyName() {
        return "VolumeImpulseStrategy";
    }

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {
        if (!market.startsWith("KRW-")) return 0;
        if (candles.size() < WINDOW + 3) return 0;

        Candle cur = candles.get(0); // 🔥 중요
        double price = cur.getTradePrice().doubleValue();
        LocalDateTime now = getCandleTime(cur);

        Z zNow = calcZ(candles, now);

        // =====================
        // EXIT 우선
        // =====================
        Optional<ImpulsePosition> posOpt =
                positionService.getOpenPosition(market);

        if (posOpt.isPresent()) {
            return checkExit(market, price, now, zNow, posOpt.get());
        }

        // =====================
        // ENTRY
        // =====================
        return checkEntry(market, candles, price, now, zNow);
    }

    // =====================
    // ENTRY
    // =====================
    private int checkEntry(String market,
                           List<Candle> candles,
                           double price,
                           LocalDateTime now,
                           Z zNow) {

        ImpulseState state =
                stateMap.computeIfAbsent(market, k -> new ImpulseState());

        Z zPrev = calcZ(candles, now.minusMinutes(1));

        double z = zNow.z;
        double prevZ = zPrev.z;
        double curVol = zNow.cur;
        double avgVol = zNow.avg;
        double density = zNow.density;

        switch (state.phase) {

            case IDLE:
                if (z >= IMPULSE_Z &&
                        curVol > avgVol * VOL_MULT_IMPULSE &&
                        density >= DENSITY_MIN) {

                    state.toImpulse(price);
                }
                break;

            case IMPULSE:
                if (z >= CONFIRM_Z) {
                    state.toConfirmed();
                    return enter(market, price, z, z - prevZ, curVol, density);
                }
                if (z < 0.7) state.reset();
                break;

            case CONFIRMED:
                double pullback =
                        (state.peakPrice - price) / state.peakPrice;

                if (pullback >= 0.05 && pullback <= MAX_PULLBACK) {
                    state.toPullback();
                }
                break;

            case PULLBACK:
                if (z >= REBREAK_Z &&
                        z > prevZ &&
                        curVol > avgVol * VOL_MULT_REBREAK) {

                    state.reset();
                    return enter(market, price, z, z - prevZ, curVol, density);
                }
                if (z < 0.5) state.reset();
                break;
        }

        return 0;
    }

    private int enter(String market, double price, double z, double dz,
                      double vol, double density) {

        positionService.openPosition(
                market,
                BigDecimal.valueOf(price),
                BigDecimal.ONE,
                z,
                dz,
                0.0,
                vol
        );

        log.info("[ENTRY] {} price={} Z={} dZ={}",
                market, price, z, dz);

        return 1;
    }

    // =====================
    // EXIT + TRAILING STOP
    // =====================
    private int checkExit(String market,
                          double price,
                          LocalDateTime now,
                          Z zNow,
                          ImpulsePosition pos) {

        long heldMin =
                ChronoUnit.MINUTES.between(pos.getEntryTime(), now);

        BigDecimal currentPrice = BigDecimal.valueOf(price);

        // 🔥 고점 갱신 (내부에서 highestPrice + 시간 처리)
        pos.updateHighest(currentPrice);

        // =====================
        // Trailing Stop
        // =====================
        BigDecimal trailingStopPrice =
                pos.getHighestPrice()
                        .multiply(BigDecimal.valueOf(TRAILING_STOP_RATIO));

        if (currentPrice.compareTo(trailingStopPrice) <= 0) {
            close(market, price, "TRAILING_STOP");
            return -1;
        }

        if (zNow.z < EXIT_Z_COLLAPSE) {
            close(market, price, "Z_COLLAPSE");
            return -1;
        }

        if (zNow.cur < zNow.avg * EXIT_VOL_RATIO) {
            close(market, price, "VOLUME_DRY");
            return -1;
        }

        if (heldMin >= MAX_HOLD_MIN) {
            close(market, price, "TIMEOUT");
            return -1;
        }

        if (price < pos.getEntryPrice().doubleValue() * STOP_LOSS_RATIO) {
            close(market, price, "STOP_LOSS");
            return -1;
        }

        return 0;
    }

    private void close(String market, double price, String reason) {
        positionService.closePosition(
                market,
                BigDecimal.valueOf(price),
                reason
        );

        log.info("[EXIT] {} price={} reason={}", market, price, reason);
        stateMap.remove(market);
    }

    // =====================
    // Z SCORE
    // =====================
    @Data
    private static class Z {
        double z;
        double avg;
        double cur;
        double density;
    }

    private Z calcZ(List<Candle> candles, LocalDateTime base) {

        LocalDateTime start = base.minusMinutes(WINDOW);

        Map<LocalDateTime, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < WINDOW; i++) {
            map.put(start.plusMinutes(i), 0.0);
        }

        int actual = 0;
        for (int i = 1; i < candles.size(); i++) {
            LocalDateTime t = getCandleTime(candles.get(i))
                    .truncatedTo(ChronoUnit.MINUTES);

            if (!t.isBefore(start) && t.isBefore(base)) {
                double v = getMinuteVolume(candles, i);
                map.put(t, v);
                if (v > 0) actual++;
            }
        }

        double curVol = map.getOrDefault(base.minusMinutes(1), 0.0);
        double avg = map.values().stream().mapToDouble(v -> v).sum() / WINDOW;

        double std = Math.sqrt(
                map.values().stream()
                        .mapToDouble(v -> Math.pow(v - avg, 2))
                        .sum() / WINDOW
        );

        Z r = new Z();
        r.z = std > 0 ? (curVol - avg) / std : 0;
        r.avg = avg;
        r.cur = curVol;
        r.density = (double) actual / WINDOW;
        return r;
    }

    // =====================
    // UTIL
    // =====================
    private double getMinuteVolume(List<Candle> candles, int idx) {
        if (idx <= 0) return 0;
        Candle cur = candles.get(idx);
        Candle prev = candles.get(idx - 1);

        return Math.max(0,
                cur.getCandleAccTradeVolume().doubleValue()
                        - prev.getCandleAccTradeVolume().doubleValue());
    }
    public List<ReplayResult> runReplay(String market, List<Candle> candles) {

        List<ReplayResult> results = new ArrayList<>();

        // 리플레이 시작 시 상태 초기화
        stateMap.remove(market);
        ImpulseState state = new ImpulseState();

        // 가상 포지션 (리플레이 전용)
        boolean hasPosition = false;
        double entryPrice = 0;
        double peakPrice = 0;
        LocalDateTime entryTime = null;

        for (int i = WINDOW + 2; i < candles.size(); i++) {

            List<Candle> slice = candles.subList(0, i + 1);
            Candle cur = slice.get(slice.size() - 1);

            double price = cur.getTradePrice().doubleValue();
            LocalDateTime now = getCandleTime(cur);

            Z zNow = calcZ(slice, now);
            Z zPrev = calcZ(slice, now.minusMinutes(1));

            double z = zNow.z;
            double prevZ = zPrev.z;
            double curVol = zNow.cur;
            double avgVol = zNow.avg;
            double density = zNow.density;

            // =========================
            // 1️⃣ EXIT LOGIC (우선)
            // =========================
            if (hasPosition) {

                // 고점 갱신
                peakPrice = Math.max(peakPrice, price);

                // 🔻 Trailing Stop (예: 고점 대비 -10%)
                if (price < peakPrice * 0.90) {

                    results.add(
                            ReplayResult.builder()
                                    .time(now)
                                    .market(market)
                                    .price(price)
                                    .z(z)
                                    .prevZ(prevZ)
                                    .volume(curVol)
                                    .avgVolume(avgVol)
                                    .density(density)
                                    .action("EXIT")
                                    .reason("TRAILING_STOP")
                                    .build()
                    );

                    hasPosition = false;
                    state.reset();
                    continue;
                }

                // ⏱ Impulse 종료 (5분)
                if (java.time.Duration.between(entryTime, now).toMinutes() >= 5) {

                    results.add(
                            ReplayResult.builder()
                                    .time(now)
                                    .market(market)
                                    .price(price)
                                    .z(z)
                                    .prevZ(prevZ)
                                    .volume(curVol)
                                    .avgVolume(avgVol)
                                    .density(density)
                                    .action("EXIT")
                                    .reason("IMPULSE_END")
                                    .build()
                    );

                    hasPosition = false;
                    state.reset();
                    continue;
                }
            }

            // =========================
            // 2️⃣ PHASE MACHINE
            // =========================
            String action = null;
            String reason = null;

            switch (state.phase) {

                case IDLE:
                    if (z >= IMPULSE_Z &&
                            curVol > avgVol * VOL_MULT_IMPULSE &&
                            density >= DENSITY_MIN) {

                        state.toImpulse(price);
                        action = "PHASE";
                        reason = "IDLE→IMPULSE";
                    }
                    break;

                case IMPULSE:
                    if (z >= CONFIRM_Z && z > prevZ) {

                        state.toConfirmed();

                        // ENTRY
                        hasPosition = true;
                        entryPrice = price;
                        peakPrice = price;
                        entryTime = now;

                        action = "ENTRY";
                        reason = "CONFIRM_ENTRY";
                    }

                    if (z < 0.7) {
                        state.reset();
                    }
                    break;

                case CONFIRMED:
                    double pullback =
                            (state.peakPrice - price) / state.peakPrice;

                    if (pullback >= 0.05 && pullback <= MAX_PULLBACK) {
                        state.toPullback();
                        action = "PHASE";
                        reason = "CONFIRMED→PULLBACK";
                    }
                    break;

                case PULLBACK:
                    if (z >= REBREAK_Z &&
                            z > prevZ &&
                            curVol > avgVol * VOL_MULT_REBREAK) {

                        // ENTRY (재진입)
                        hasPosition = true;
                        entryPrice = price;
                        peakPrice = price;
                        entryTime = now;

                        state.reset();
                        action = "ENTRY";
                        reason = "REBREAK_ENTRY";
                    }

                    if (z < 0.5) {
                        state.reset();
                    }
                    break;
            }

            // =========================
            // 3️⃣ LOG RECORD
            // =========================
            if (action != null) {
                results.add(
                        ReplayResult.builder()
                                .time(now)
                                .market(market)
                                .price(price)
                                .z(z)
                                .prevZ(prevZ)
                                .volume(curVol)
                                .avgVolume(avgVol)
                                .density(density)
                                .action(action)
                                .reason(reason)
                                .build()
                );
            }
        }

        return results;
    }

    public void printReplayLog(String market, List<ReplayResult> results) {

        log.info("========== REPLAY RESULT [{}] ==========", market);

        for (ReplayResult r : results) {
            log.info(
                    "[{}] {} | price={} Z={}/{} vol={} dens={} action={} ({})",
                    r.getTime(),
                    r.getMarket(),
                    String.format("%.2f", r.getPrice()),
                    String.format("%.2f", r.getZ()),
                    String.format("%.2f", r.getPrevZ()),
                    String.format("%.0f", r.getVolume()),
                    String.format("%.2f", r.getDensity()),
                    r.getAction(),
                    r.getReason()
            );
        }

        log.info("========== END ==========");
    }

    private LocalDateTime getCandleTime(Candle c) {
        Object t = c.getCandleDateTimeKst();
        if (t instanceof LocalDateTime) return (LocalDateTime) t;
        if (t instanceof OffsetDateTime) return ((OffsetDateTime) t).toLocalDateTime();
        return LocalDateTime.parse(t.toString());
    }
}