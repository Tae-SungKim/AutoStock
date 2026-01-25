package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.entity.ImpulsePosition;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.service.ImpulsePositionService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Volume Impulse 전략 v2.0 (실거래 운영용)
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [핵심 설계]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * - 캔들 개수가 아닌 "현재 시간 기준 최근 N분" 윈도우
 * - 시간 정규화 Z-score로 급등 판단
 * - 급등 '초입'이 아닌 "급등이 확정된 직후" 진입
 * - 트레일링 스탑 기반 청산 (고정 익절/손절 금지)
 * - 가짜 급등 필터로 허위 신호 제거
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VolumeImpulseStrategy implements TradingStrategy {

    private final ImpulsePositionService positionService;

    // ═══════════════════════════════════════════════════════════════
    // 기본 파라미터
    // ═══════════════════════════════════════════════════════════════
    private static final int Z_WINDOW = 15;
    private static final double Z_THRESHOLD = 1.75;
    private static final double VOLUME_MULTIPLIER = 1.55;
    private static final double DENSITY_THRESHOLD = 0.50;

    // ═══════════════════════════════════════════════════════════════
    // 트레일링 스탑 파라미터
    // ═══════════════════════════════════════════════════════════════
    private static final double TRAILING_START_PROFIT = 0.003;   // 0.3% 수익부터 트레일링 시작
    private static final double TRAILING_STOP_RATIO = 0.6;       // 고점 대비 60% 되돌림 시 청산
    private static final int NO_HIGH_UPDATE_TIMEOUT_SEC = 300;   // 5분간 고점 갱신 없으면 청산
    private static final double HARD_STOP_LOSS = -0.015;         // 최대 손실 제한 -1.5%

    // ═══════════════════════════════════════════════════════════════
    // 가짜 급등 필터 파라미터
    // ═══════════════════════════════════════════════════════════════
    private static final double LONG_WICK_RATIO = 0.6;           // 윗꼬리가 몸통의 60% 이상
    private static final double CLOSE_HIGH_RATIO = 0.7;          // 종가가 (고가-저가)의 70% 이하
    private static final double PREV_CLOSE_DROP = -0.003;        // 직전 대비 -0.3% 급락
    private static final double MIN_PRICE_RISE_WITH_VOL = 0.002; // 거래량 급등 시 최소 0.2% 상승

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
        if (candles.size() < Z_WINDOW + 3) return 0;

        Candle lastCompleted = candles.get(candles.size() - 1);
        double currentPrice = lastCompleted.getTradePrice().doubleValue();
        LocalDateTime now = getCandleTime(lastCompleted);

        // 포지션 보유 중이면 청산 평가
        Optional<ImpulsePosition> posOpt = positionService.getOpenPosition(market);
        if (posOpt.isPresent()) {
            return evaluateExit(market, candles, currentPrice, now, posOpt.get());
        }

        // 신규 진입 평가
        return evaluateEntry(market, candles, lastCompleted, currentPrice, now);
    }

    // ═══════════════════════════════════════════════════════════════
    // 진입 판단 로직
    // ═══════════════════════════════════════════════════════════════
    private int evaluateEntry(String market, List<Candle> candles,
                               Candle current, double price, LocalDateTime now) {

        // 1️⃣ 시간 정규화 Z-score 계산
        ZScoreResult zResult = calculateTimeNormalizedZScore(candles, now, Z_WINDOW);
        double z = zResult.zScore;
        double density = zResult.density;
        double avgVolume = zResult.avgVolume;
        double currentVolume = zResult.currentVolume;

        // 2️⃣ 직전 분 Z-score 계산 (상승 가속 확인용)
        LocalDateTime prevMinute = now.minusMinutes(1);
        ZScoreResult prevZResult = calculateTimeNormalizedZScore(candles, prevMinute, Z_WINDOW);
        double prevZ = prevZResult.zScore;

        // 3️⃣ 진입 조건 검증
        EntryCheckResult check = checkEntryConditions(
                z, prevZ, currentVolume, avgVolume, density, current, candles, now
        );

        // 리플레이 로그 출력
        logReplayData(market, now, currentVolume, avgVolume, z, prevZ, density, check);

        if (!check.passed) {
            return 0;
        }

        // 4️⃣ 가짜 급등 필터
        FakeImpulseResult fakeResult = checkFakeImpulse(current, candles, currentVolume, avgVolume);
        if (fakeResult.isFake) {
            log.info("[FAKE_FILTER] {} | reasons={}", market, fakeResult.reasons);
            return 0;
        }

        // 5️⃣ 진입 실행
        positionService.openPosition(
                market,
                BigDecimal.valueOf(price),
                BigDecimal.ONE,
                z,
                z - prevZ,
                0.0,  // 체결강도 (별도 서비스에서 조회 시 사용)
                currentVolume
        );

        log.info("[ENTRY] {} | price={} | Z={} | dZ={} | vol={} | avgVol={} | density={}",
                market,
                String.format("%.4f", price),
                String.format("%.2f", z),
                String.format("%.2f", z - prevZ),
                String.format("%.0f", currentVolume),
                String.format("%.0f", avgVolume),
                String.format("%.2f", density)
        );

        return 1;
    }

    // ═══════════════════════════════════════════════════════════════
    // 청산 판단 로직 (트레일링 스탑 기반)
    // ═══════════════════════════════════════════════════════════════
    private int evaluateExit(String market, List<Candle> candles,
                              double price, LocalDateTime now, ImpulsePosition pos) {

        BigDecimal curPrice = BigDecimal.valueOf(price);
        double profitRate = pos.getCurrentProfitRate(curPrice).doubleValue();
        long holdingSec = pos.getHoldingSeconds();

        // 고점 갱신
        boolean highUpdated = pos.updateHighest(curPrice);
        if (highUpdated) {
            positionService.updateHighest(market, curPrice);
        }

        // 1️⃣ 하드 손절 (급등 실패 판단)
        if (profitRate <= HARD_STOP_LOSS) {
            positionService.closePosition(market, curPrice, "HARD_STOP_LOSS");
            log.info("[EXIT] {} | reason=HARD_STOP_LOSS | pnl={}",
                    market, String.format("%.2f%%", profitRate * 100));
            return -1;
        }

        // 2️⃣ 트레일링 스탑 체크
        if (profitRate >= TRAILING_START_PROFIT && pos.getHighestPrice() != null) {
            double highestProfit = pos.getHighestPrice().subtract(pos.getEntryPrice())
                    .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP)
                    .doubleValue();

            double drawdown = highestProfit - profitRate;
            double allowedDrawdown = highestProfit * (1 - TRAILING_STOP_RATIO);

            if (drawdown > allowedDrawdown && drawdown > 0.002) {
                positionService.closePosition(market, curPrice, "TRAILING_STOP");
                log.info("[EXIT] {} | reason=TRAILING_STOP | pnl={} | highPnl={}",
                        market,
                        String.format("%.2f%%", profitRate * 100),
                        String.format("%.2f%%", highestProfit * 100));
                return -1;
            }
        }

        // 3️⃣ 고점 갱신 없음 타임아웃 (모멘텀 소멸)
        long secSinceLastHigh = pos.getSecondsSinceLastHighUpdate();
        if (secSinceLastHigh > NO_HIGH_UPDATE_TIMEOUT_SEC) {
            String reason = profitRate > 0 ? "TIMEOUT_PROFIT" : "TIMEOUT_LOSS";
            positionService.closePosition(market, curPrice, reason);
            log.info("[EXIT] {} | reason={} | pnl={} | secSinceHigh={}",
                    market, reason,
                    String.format("%.2f%%", profitRate * 100), secSinceLastHigh);
            return -1;
        }

        // 4️⃣ Z-score 급락 체크 (급등 실패)
        ZScoreResult zResult = calculateTimeNormalizedZScore(candles, now, Z_WINDOW);
        double entryZ = pos.getEntryZScore().doubleValue();

        if (zResult.zScore < entryZ * 0.4 && holdingSec > 60) {
            positionService.closePosition(market, curPrice, "Z_COLLAPSE");
            log.info("[EXIT] {} | reason=Z_COLLAPSE | pnl={} | Z={} | entryZ={}",
                    market,
                    String.format("%.2f%%", profitRate * 100),
                    String.format("%.2f", zResult.zScore),
                    String.format("%.2f", entryZ));
            return -1;
        }

        return 0;
    }

    // ═══════════════════════════════════════════════════════════════
    // 시간 정규화 Z-score 계산
    // ═══════════════════════════════════════════════════════════════
    private ZScoreResult calculateTimeNormalizedZScore(List<Candle> candles,
                                                        LocalDateTime baseTime, int window) {
        LocalDateTime windowStart = baseTime.minusMinutes(window);

        // 1️⃣ 모든 분 슬롯을 0으로 초기화
        Map<LocalDateTime, Double> volumeMap = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            volumeMap.put(windowStart.plusMinutes(i), 0.0);
        }

        // 2️⃣ 실제 캔들 거래량으로 덮어쓰기
        int actualCandleCount = 0;
        for (int i = 1; i < candles.size(); i++) {
            LocalDateTime candleTime = getCandleTime(candles.get(i)).withSecond(0).withNano(0);

            if (!candleTime.isBefore(windowStart) && candleTime.isBefore(baseTime)) {
                double vol = getMinuteVolume(candles, i);
                volumeMap.put(candleTime, vol);
                if (vol > 0) actualCandleCount++;
            }
        }

        // 3️⃣ 현재 거래량 (마지막 완성 캔들)
        double currentVolume = 0;
        for (int i = candles.size() - 1; i >= 1; i--) {
            LocalDateTime candleTime = getCandleTime(candles.get(i)).withSecond(0).withNano(0);
            if (candleTime.equals(baseTime.minusMinutes(1)) || candleTime.equals(baseTime)) {
                currentVolume = getMinuteVolume(candles, i);
                break;
            }
        }

        // 4️⃣ 평균, 표준편차 계산 (window 기준)
        double sum = volumeMap.values().stream().mapToDouble(v -> v).sum();
        double mean = sum / window;

        double varianceSum = volumeMap.values().stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum();
        double std = Math.sqrt(varianceSum / window);

        // 5️⃣ Z-score 계산
        double zScore = (std > 0) ? (currentVolume - mean) / std : 0;
        double density = (double) actualCandleCount / window;

        return ZScoreResult.builder()
                .zScore(zScore)
                .density(density)
                .avgVolume(mean)
                .currentVolume(currentVolume)
                .std(std)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 진입 조건 검증
    // ═══════════════════════════════════════════════════════════════
    private EntryCheckResult checkEntryConditions(double z, double prevZ,
                                                   double currentVol, double avgVol,
                                                   double density, Candle current,
                                                   List<Candle> candles, LocalDateTime now) {
        List<String> failReasons = new ArrayList<>();

        // 조건 1: Z-score > threshold
        if (z <= Z_THRESHOLD) {
            failReasons.add(String.format("Z=%.2f<=%.2f", z, Z_THRESHOLD));
        }

        // 조건 2: Z-score 상승 가속
        if (z <= prevZ) {
            failReasons.add(String.format("Z_NOT_RISING(%.2f<=%.2f)", z, prevZ));
        }

        // 조건 3: 현재 거래량 > 평균 * multiplier
        double volumeThreshold = avgVol * VOLUME_MULTIPLIER;
        if (currentVol <= volumeThreshold) {
            failReasons.add(String.format("VOL=%.0f<=%.0f", currentVol, volumeThreshold));
        }

        // 조건 4: 캔들 밀도 >= threshold
        if (density < DENSITY_THRESHOLD) {
            failReasons.add(String.format("DENSITY=%.2f<%.2f", density, DENSITY_THRESHOLD));
        }

        return EntryCheckResult.builder()
                .passed(failReasons.isEmpty())
                .failReasons(failReasons)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 가짜 급등 필터 (2개 이상 만족 시 진입 금지)
    // ═══════════════════════════════════════════════════════════════
    private FakeImpulseResult checkFakeImpulse(Candle current, List<Candle> candles,
                                                double currentVol, double avgVol) {
        List<String> reasons = new ArrayList<>();

        double open = current.getOpeningPrice().doubleValue();
        double high = current.getHighPrice().doubleValue();
        double low = current.getLowPrice().doubleValue();
        double close = current.getTradePrice().doubleValue();
        double body = Math.abs(close - open);
        double upperWick = high - Math.max(open, close);
        double range = high - low;

        // 1️⃣ 긴 윗꼬리
        if (body > 0 && upperWick / body >= LONG_WICK_RATIO) {
            reasons.add("LONG_UPPER_WICK");
        }

        // 2️⃣ 종가가 고가의 일정 비율 이하
        if (range > 0 && (close - low) / range < CLOSE_HIGH_RATIO) {
            reasons.add("CLOSE_NEAR_LOW");
        }

        // 3️⃣ 직전 캔들 대비 종가 급락
        if (candles.size() >= 2) {
            Candle prev = candles.get(candles.size() - 2);
            double prevClose = prev.getTradePrice().doubleValue();
            double changeRate = (close - prevClose) / prevClose;
            if (changeRate <= PREV_CLOSE_DROP) {
                reasons.add("PREV_CLOSE_DROP");
            }
        }

        // 4️⃣ 거래량은 크지만 가격 상승폭 미미
        if (currentVol > avgVol * 2) {
            double priceRise = (close - open) / open;
            if (priceRise < MIN_PRICE_RISE_WITH_VOL) {
                reasons.add("VOL_BUT_NO_RISE");
            }
        }

        return FakeImpulseResult.builder()
                .isFake(reasons.size() >= 2)
                .reasons(reasons)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 리플레이 분석 로그
    // ═══════════════════════════════════════════════════════════════
    private void logReplayData(String market, LocalDateTime time,
                                double currentVol, double avgVol,
                                double z, double prevZ, double density,
                                EntryCheckResult check) {
        String entryStatus = check.passed ? "ENTRY" : "NO_ENTRY";
        String reason = check.passed ? "-" : String.join(",", check.failReasons);

        log.debug("[REPLAY] {} | {} | vol={} | avgVol={} | Z={} | prevZ={} | density={} | {} | {}",
                market,
                time.toString(),
                String.format("%.0f", currentVol),
                String.format("%.0f", avgVol),
                String.format("%.2f", z),
                String.format("%.2f", prevZ),
                String.format("%.2f", density),
                entryStatus,
                reason
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // 급등 리플레이 시뮬레이션 (외부 호출용)
    // ═══════════════════════════════════════════════════════════════
    public List<ReplayResult> runReplay(String market, List<Candle> candles) {
        List<ReplayResult> results = new ArrayList<>();

        if (candles.size() < Z_WINDOW + 3) {
            return results;
        }

        for (int i = Z_WINDOW + 2; i < candles.size(); i++) {
            List<Candle> subCandles = candles.subList(0, i + 1);
            Candle current = subCandles.get(subCandles.size() - 1);
            LocalDateTime now = getCandleTime(current);
            double price = current.getTradePrice().doubleValue();

            ZScoreResult zResult = calculateTimeNormalizedZScore(subCandles, now, Z_WINDOW);
            ZScoreResult prevZResult = calculateTimeNormalizedZScore(subCandles, now.minusMinutes(1), Z_WINDOW);

            EntryCheckResult check = checkEntryConditions(
                    zResult.zScore, prevZResult.zScore,
                    zResult.currentVolume, zResult.avgVolume,
                    zResult.density, current, subCandles, now
            );

            FakeImpulseResult fakeResult = checkFakeImpulse(
                    current, subCandles, zResult.currentVolume, zResult.avgVolume
            );

            String decision;
            String reason;
            if (!check.passed) {
                decision = "NO_ENTRY";
                reason = String.join(",", check.failReasons);
            } else if (fakeResult.isFake) {
                decision = "FAKE_FILTERED";
                reason = String.join(",", fakeResult.reasons);
            } else {
                decision = "ENTRY";
                reason = "-";
            }

            results.add(ReplayResult.builder()
                    .time(now)
                    .price(price)
                    .currentVolume(zResult.currentVolume)
                    .avgVolume(zResult.avgVolume)
                    .zScore(zResult.zScore)
                    .prevZScore(prevZResult.zScore)
                    .density(zResult.density)
                    .decision(decision)
                    .reason(reason)
                    .build());
        }

        return results;
    }

    /**
     * 리플레이 결과 로그 출력
     */
    public void printReplayLog(String market, List<ReplayResult> results) {
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("[REPLAY START] {}", market);
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("{}", String.format("%-20s | %10s | %10s | %10s | %6s | %6s | %6s | %-15s | %s",
                "TIME", "PRICE", "VOL", "AVG_VOL", "Z", "PREV_Z", "DENS", "DECISION", "REASON"));
        log.info("───────────────────────────────────────────────────────────────");

        for (ReplayResult r : results) {
            log.info("{}", String.format("%-20s | %10.4f | %10.0f | %10.0f | %6.2f | %6.2f | %6.2f | %-15s | %s",
                    r.time.toString(),
                    r.price,
                    r.currentVolume,
                    r.avgVolume,
                    r.zScore,
                    r.prevZScore,
                    r.density,
                    r.decision,
                    r.reason));
        }

        long entryCount = results.stream().filter(r -> "ENTRY".equals(r.decision)).count();
        log.info("───────────────────────────────────────────────────────────────");
        log.info("[REPLAY END] Total={}, Entries={}", results.size(), entryCount);
        log.info("═══════════════════════════════════════════════════════════════");
    }

    // ═══════════════════════════════════════════════════════════════
    // 유틸리티 메서드
    // ═══════════════════════════════════════════════════════════════
    private double getMinuteVolume(List<Candle> candles, int idx) {
        if (idx <= 0) return 0;

        Candle cur = candles.get(idx);
        Candle prev = candles.get(idx - 1);

        LocalDateTime curTime = getCandleTime(cur).truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime prevTime = getCandleTime(prev).truncatedTo(ChronoUnit.MINUTES);

        // 연속된 캔들인 경우에만 차이 계산
        if (ChronoUnit.MINUTES.between(prevTime, curTime) == 1) {
            double curAcc = cur.getCandleAccTradeVolume().doubleValue();
            double prevAcc = prev.getCandleAccTradeVolume().doubleValue();
            return Math.max(0, curAcc - prevAcc);
        }

        // 비연속 캔들 → 해당 캔들 전체 거래량 사용
        return cur.getCandleAccTradeVolume().doubleValue();
    }

    private LocalDateTime getCandleTime(Candle c) {
        Object t = c.getCandleDateTimeKst();
        if (t instanceof LocalDateTime) return (LocalDateTime) t;
        if (t instanceof OffsetDateTime) return ((OffsetDateTime) t).toLocalDateTime();
        return LocalDateTime.parse(t.toString());
    }

    // ═══════════════════════════════════════════════════════════════
    // 내부 DTO
    // ═══════════════════════════════════════════════════════════════
    @Data
    @Builder
    private static class ZScoreResult {
        private double zScore;
        private double density;
        private double avgVolume;
        private double currentVolume;
        private double std;
    }

    @Data
    @Builder
    private static class EntryCheckResult {
        private boolean passed;
        private List<String> failReasons;
    }

    @Data
    @Builder
    private static class FakeImpulseResult {
        private boolean isFake;
        private List<String> reasons;
    }

    @Data
    @Builder
    public static class ReplayResult {
        private LocalDateTime time;
        private double price;
        private double currentVolume;
        private double avgVolume;
        private double zScore;
        private double prevZScore;
        private double density;
        private String decision;
        private String reason;
    }
}