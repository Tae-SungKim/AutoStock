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
    private static final double VOL_MULT_CONFIRM = 1.2;
    private static final double VOL_MULT_REBREAK = 1.3;

    private static final double MAX_PULLBACK = 0.5;

    // =====================
    // PHASE STATE
    // =====================
    private final Map<String, ImpulseState> stateMap = new HashMap<>();
    private final List<ReplayResult> replayLog = new ArrayList<>();

    enum Phase {
        IDLE,
        IMPULSE,
        CONFIRMED,
        PULLBACK
    }

    @Data
    private static class ImpulseState {
        Phase phase = Phase.IDLE;
        double peakZ;
        double peakPrice;
        LocalDateTime impulseTime;

        void toImpulse(double z, double price, LocalDateTime now) {
            this.phase = Phase.IMPULSE;
            this.peakZ = z;
            this.peakPrice = price;
            this.impulseTime = now;
        }

        void toConfirmed() {
            this.phase = Phase.CONFIRMED;
        }

        void toPullback() {
            this.phase = Phase.PULLBACK;
        }

        void reset() {
            this.phase = Phase.IDLE;
            this.peakZ = 0;
            this.peakPrice = 0;
            this.impulseTime = null;
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

        Candle cur = candles.get(0);
        double price = cur.getTradePrice().doubleValue();
        LocalDateTime now = getCandleTime(cur);

        if (positionService.getOpenPosition(market).isPresent()) {
            return 0;
        }

        ImpulseState state =
                stateMap.computeIfAbsent(market, k -> new ImpulseState());

        Z zNow = calcZ(candles, now);
        Z zPrev = calcZ(candles, now.minusMinutes(1));

        double z = zNow.z;
        double prevZ = zPrev.z;
        double curVol = zNow.cur;
        double avgVol = zNow.avg;
        double density = zNow.density;

        // =====================
        // PHASE MACHINE (🔥 핵심)
        // =====================
        switch (state.phase) {

            case IDLE:
                if (z >= IMPULSE_Z &&
                        curVol > avgVol * VOL_MULT_IMPULSE &&
                        density >= DENSITY_MIN) {

                    state.toImpulse(z, price, now);
                    log.debug("[PHASE] {} IDLE → IMPULSE | Z={}", market, z);
                }
                break;

            case IMPULSE:
                if (z >= CONFIRM_Z) {

                    state.toConfirmed();

                    // ✅ 급등 확정 직후 진입
                    return enter(market, price, z, z - prevZ, curVol, density);
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
                }
                break;

            case PULLBACK:
                if (z >= REBREAK_Z &&
                        z > prevZ &&
                        curVol > avgVol * VOL_MULT_REBREAK) {

                    // ✅ 눌림 후 재출발
                    state.reset();
                    return enter(market, price, z, z - prevZ, curVol, density);
                }

                if (z < 0.5) {
                    state.reset();
                }
                break;
        }

        return 0;
    }

    // =====================
    // ENTRY
    // =====================
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

        log.info("[ENTRY] {} price={} Z={} dZ={} vol={} density={}",
                market,
                price,
                String.format("%.2f", z),
                String.format("%.2f", dz),
                String.format("%.0f", vol),
                String.format("%.2f", density)
        );

        return 1;
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
        double sum = map.values().stream().mapToDouble(v -> v).sum();
        double avg = sum / WINDOW;

        double var = map.values().stream()
                .mapToDouble(v -> Math.pow(v - avg, 2))
                .sum() / WINDOW;

        double std = Math.sqrt(var);
        double z = std > 0 ? (curVol - avg) / std : 0;

        Z r = new Z();
        r.z = z;
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

        LocalDateTime ct = getCandleTime(cur).truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime pt = getCandleTime(prev).truncatedTo(ChronoUnit.MINUTES);

        if (ChronoUnit.MINUTES.between(pt, ct) == 1) {
            return Math.max(0,
                    cur.getCandleAccTradeVolume().doubleValue()
                            - prev.getCandleAccTradeVolume().doubleValue());
        }
        return cur.getCandleAccTradeVolume().doubleValue();
    }
    public List<ReplayResult> runReplay(String market, List<Candle> candles) {

        List<ReplayResult> results = new ArrayList<>();
        stateMap.remove(market);

        for (int i = WINDOW + 2; i < candles.size(); i++) {
            List<Candle> slice = candles.subList(0, i + 1);

            Candle cur = slice.get(slice.size() - 1);
            double price = cur.getTradePrice().doubleValue();
            LocalDateTime now = getCandleTime(cur);

            ImpulseState state =
                    stateMap.computeIfAbsent(market, k -> new ImpulseState());

            Z zNow = calcZ(slice, now);
            Z zPrev = calcZ(slice, now.minusMinutes(1));

            double z = zNow.z;
            double prevZ = zPrev.z;
            double curVol = zNow.cur;
            double avgVol = zNow.avg;
            double density = zNow.density;

            String action = null;

            switch (state.phase) {

                case IDLE:
                    if (z >= IMPULSE_Z &&
                            curVol > avgVol * VOL_MULT_IMPULSE &&
                            density >= DENSITY_MIN) {

                        state.toImpulse(z, price, now);
                        action = "IMPULSE";
                    }
                    break;

                case IMPULSE:
                    if (z >= CONFIRM_Z) {
                        state.toConfirmed();
                        action = "CONFIRM_ENTRY";
                    } else if (z < 0.7) {
                        state.reset();
                        action = "RESET";
                    }
                    break;

                case CONFIRMED:
                    double pullback =
                            (state.peakPrice - price) / state.peakPrice;

                    if (pullback >= 0.05 && pullback <= MAX_PULLBACK) {
                        state.toPullback();
                        action = "PULLBACK";
                    }
                    break;

                case PULLBACK:
                    if (z >= REBREAK_Z &&
                            z > prevZ &&
                            curVol > avgVol * VOL_MULT_REBREAK) {

                        state.reset();
                        action = "REBREAK_ENTRY";
                    } else if (z < 0.5) {
                        state.reset();
                        action = "RESET";
                    }
                    break;
            }

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
                                .reason(action == null ? "NO_SIGNAL" : action)
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
                    "[{}] {} | price={} Z={}/{} vol={} action={}",
                    r.getTime(),
                    r.getMarket(),
                    String.format("%.2f", r.getPrice()),
                    String.format("%.2f", r.getZ()),
                    String.format("%.2f", r.getPrevZ()),
                    String.format("%.0f", r.getVolume()),
                    r.getAction()
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