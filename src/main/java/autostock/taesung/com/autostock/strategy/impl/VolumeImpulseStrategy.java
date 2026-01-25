package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.entity.ImpulseHourParam;
import autostock.taesung.com.autostock.entity.ImpulsePosition;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.service.ImpulsePositionService;
import autostock.taesung.com.autostock.service.ImpulseStatService;
import autostock.taesung.com.autostock.service.MarketVolumeService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Volume Impulse 전략 (실거래 운영용)
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [핵심 설계: 업비트 분봉 데이터 특성 대응]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * ★ 업비트는 거래가 없는 분에 캔들 데이터 자체를 반환하지 않음
 * ★ 단순 candle.size() 기반 평균 계산 시 유동성 착시 발생
 *
 * 예: 20분 중 5개 캔들만 존재, 총 거래량 100,000
 * - 잘못된 계산: 100,000 / 5 = 20,000 (높아 보임!)
 * - 올바른 계산: 100,000 / 20 = 5,000 (실제 유동성)
 *
 * 해결책:
 * 1. 시간 정규화 평균: sumVolume / window (캔들 개수 아님)
 * 2. 캔들 밀도 필터: density < 0.85 → 진입 금지
 * 3. 캔들 개수 검증: 최소 90% 캔들 필수
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [진입 조건] (ALL 충족)
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 0. 캔들 밀도 >= 85% (유동성 검증 - 최우선)
 * 1. 절대 유동성: 현재 분 거래량 >= MIN_1M_VOLUME
 * 2. 누적 유동성: 20분 누적 거래량 >= MIN_SUM_VOLUME_20M
 * 3. 시간 정규화 평균: >= MIN_AVG_VOLUME_20M
 * 4. 상대 거래량: >= 시간 정규화 평균 × volumeMultiplier
 * 5. 가격 필터: 5분 상승률 < 2%
 * 6. 체결강도: >= minExecutionStrength
 * 7. Z-score: 증가 중 && >= minZScore
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [청산 조건]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 1. STOP_LOSS: 수익률 <= -1%
 * 2. FAKE_IMPULSE: 90초 이내 + Z-score 급감 + 상승률 부진
 * 3. Z_DROP: Z-score < entryZ × 50%
 * 4. TIMEOUT: 5분 경과
 * 5. WEAK_IMPULSE: 체결강도 급감
 *
 * @see ImpulsePositionService 포지션 영속화 서비스
 * @see ImpulseStatService 통계/캐시 서비스
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VolumeImpulseStrategy implements TradingStrategy {

    private final ImpulsePositionService positionService;
    private final ImpulseStatService statService;
    private final MarketVolumeService marketVolumeService;

    private static final int Z_WINDOW = 20;

    private static final double MIN_CANDLE_DENSITY = 0.6;
    private static final double MIN_CANDLE_RATIO = 0.75;

    private static final int MIN_1M_VOLUME = 50_000;
    private static final int MIN_SUM_VOLUME_20M = 300_000;
    private static final int MIN_AVG_VOLUME_20M = 10_000;

    private static final double MAX_PRICE_RISE_5M = 0.02;

    private static final int EXEC_STRENGTH_SECONDS = 30;

    private static final double STOP_LOSS_RATE = -0.01;
    private static final int FAKE_CHECK_SECONDS = 90;
    private static final double FAKE_Z_RATIO = 0.7;
    private static final int MAX_HOLDING_MINUTES = 5;
    private static final double Z_DROP_RATIO = 0.5;
    private static final double WEAK_EXEC_STRENGTH = 50.0;

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
        if (candles.size() < Z_WINDOW + 5) return 0;

        int last = candles.size() - 1;

        Candle completed = candles.get(last - 1);
        double price = completed.getTradePrice().doubleValue();

        Optional<ImpulsePosition> posOpt = positionService.getOpenPosition(market);
        if (posOpt.isPresent()) {
            return evaluateExit(market, candles, price, posOpt.get());
        }

        return evaluateEntry(market, candles, completed, price);
    }

    // =========================
    // ENTRY
    // =========================
    private int evaluateEntry(String market, List<Candle> candles, Candle cur, double price) {

        int last = candles.size() - 1;

        // 0️⃣ 캔들 밀도
        double density = calculateCandleDensity(candles, last, Z_WINDOW);
        if (density < MIN_CANDLE_DENSITY) return 0;

        if (!isCandleCountValid(candles, last, Z_WINDOW)) return 0;

        ImpulseHourParam hourParam = statService.getHourParam(statService.getCurrentHour());

        double curVolume = cur.getCandleAccTradeVolume().doubleValue();
        if (curVolume < MIN_1M_VOLUME) return 0;

        double sumVolume = calculateSumVolume(candles, last - 1, Z_WINDOW);
        if (sumVolume < MIN_SUM_VOLUME_20M) return 0;

        double avgVolume = sumVolume / Z_WINDOW;
        if (avgVolume < MIN_AVG_VOLUME_20M) return 0;

        if (curVolume < avgVolume * hourParam.getVolumeMultiplierValue()) return 0;

        double price5mAgo = candles.get(last - 6).getTradePrice().doubleValue();
        if ((price - price5mAgo) / price5mAgo >= MAX_PRICE_RISE_5M) return 0;

        double execStrength = marketVolumeService.getExecutionStrength(market, EXEC_STRENGTH_SECONDS);
        if (execStrength < hourParam.getMinExecutionStrengthValue()) return 0;

        // ⭐ Z-score 핵심 수정 ⭐
        double z = calculateTimeNormalizedZScore(candles.subList(0, last), Z_WINDOW);
        double prevZ = calculateTimeNormalizedZScore(candles.subList(0, last - 1), Z_WINDOW);

        if (z <= prevZ) return 0;
        if (z < hourParam.getMinZScoreValue()) return 0;

        positionService.openPosition(
                market,
                BigDecimal.valueOf(price),
                BigDecimal.ONE,
                z,
                z - prevZ,
                execStrength,
                curVolume
        );

        log.info("ENTRY,{},price={},Z={},dZ={},vol={},density={}",
                market,
                price,
                String.format("%.2f", z),
                String.format("%.2f", z - prevZ),
                curVolume,
                String.format("%.2f", density)
        );

        return 1;
    }

    // =========================
    // EXIT
    // =========================
    private int evaluateExit(String market, List<Candle> candles, double price, ImpulsePosition pos) {

        BigDecimal curPrice = BigDecimal.valueOf(price);
        double pnl = pos.getCurrentProfitRate(curPrice).doubleValue();

        long sec = pos.getHoldingSeconds();
        long min = pos.getHoldingMinutes();

        if (pnl <= STOP_LOSS_RATE) {
            positionService.closeWithStopLoss(market, curPrice);
            return -1;
        }

        double z = calculateTimeNormalizedZScore(candles, Z_WINDOW);
        double entryZ = pos.getEntryZScore().doubleValue();

        if (sec <= FAKE_CHECK_SECONDS && z < entryZ * FAKE_Z_RATIO) {
            positionService.closeWithFakeImpulse(market, curPrice);
            return -1;
        }

        if (z < entryZ * Z_DROP_RATIO) {
            positionService.closePosition(market, curPrice, "Z_DROP");
            return -1;
        }

        if (min >= MAX_HOLDING_MINUTES) {
            positionService.closePosition(market, curPrice, "TIMEOUT");
            return -1;
        }

        double exec = marketVolumeService.getExecutionStrength(market, EXEC_STRENGTH_SECONDS);
        if (sec > 60 && exec < WEAK_EXEC_STRENGTH) {
            positionService.closePosition(market, curPrice, "WEAK_IMPULSE");
            return -1;
        }

        return 0;
    }

    // =========================
    // UTIL
    // =========================
    private double calculateSumVolume(List<Candle> candles, int last, int window) {
        int start = Math.max(0, last - window + 1);
        double sum = 0;
        for (int i = start; i <= last; i++) {
            sum += candles.get(i).getCandleAccTradeVolume().doubleValue();
        }
        return sum;
    }

    private double calculateTimeNormalizedZScore(List<Candle> candles, int window) {
        if (candles.size() < window + 1) return 0;

        int last = candles.size() - 1;
        LocalDateTime end = getCandleTime(candles.get(last)).withSecond(0).withNano(0);
        LocalDateTime start = end.minusMinutes(window);

        Map<LocalDateTime, Double> slots = new HashMap<>();
        for (int i = 0; i < window; i++) {
            slots.put(start.plusMinutes(i), 0.0);
        }

        for (int i = 0; i < last; i++) {
            LocalDateTime t = getCandleTime(candles.get(i)).withSecond(0).withNano(0);
            if (!t.isBefore(start) && t.isBefore(end)) {
                slots.put(t, candles.get(i).getCandleAccTradeVolume().doubleValue());
            }
        }

        double mean = slots.values().stream().mapToDouble(v -> v).sum() / window;
        double var = slots.values().stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / window;
        double std = Math.sqrt(var);
        if (std == 0) return 0;

        double curVol = candles.get(last).getCandleAccTradeVolume().doubleValue();
        return (curVol - mean) / std;
    }

    private double calculateCandleDensity(List<Candle> candles, int last, int window) {
        LocalDateTime end = getCandleTime(candles.get(last)).withSecond(0).withNano(0);
        LocalDateTime start = end.minusMinutes(window);

        return candles.stream()
                .map(this::getCandleTime)
                .filter(t -> !t.isBefore(start) && t.isBefore(end))
                .map(t -> t.withSecond(0).withNano(0))
                .distinct()
                .count() / (double) window;
    }

    private boolean isCandleCountValid(List<Candle> candles, int last, int window) {
        LocalDateTime end = getCandleTime(candles.get(last));
        LocalDateTime start = end.minusMinutes(window);

        long count = candles.stream()
                .map(this::getCandleTime)
                .filter(t -> !t.isBefore(start))
                .count();

        return count >= window * MIN_CANDLE_RATIO;
    }

    private LocalDateTime getCandleTime(Candle c) {
        Object t = c.getCandleDateTimeKst();
        if (t instanceof LocalDateTime) return (LocalDateTime) t;
        if (t instanceof OffsetDateTime) return ((OffsetDateTime) t).toLocalDateTime();
        return LocalDateTime.parse(t.toString());
    }
}