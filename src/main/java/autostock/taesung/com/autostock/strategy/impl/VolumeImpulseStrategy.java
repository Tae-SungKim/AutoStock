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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeImpulseStrategy implements TradingStrategy {

    // ==================== 의존성 ====================

    private final ImpulsePositionService positionService;
    private final ImpulseStatService statService;
    private final MarketVolumeService marketVolumeService;

    // ==================== 시간 윈도우 ====================

    /** Z-score / 평균 거래량 계산 윈도우 (분) */
    private static final int Z_WINDOW = 20;

    // ==================== 캔들 밀도 필터 (핵심!) ====================

    /**
     * 최소 캔들 밀도
     * - 실제 캔들 수 / 기대 캔들 수
     * - 0.85 = 20분 중 최소 17개 캔들 필요
     * - 이하면 유동성 부족으로 판단
     */
    private static final double MIN_CANDLE_DENSITY = 0.85;

    /**
     * 최소 캔들 개수 비율 (개수 검증용)
     * - 0.90 = 20분 중 최소 18개 캔들 필요
     */
    private static final double MIN_CANDLE_RATIO = 0.90;

    // ==================== 절대 유동성 필터 ====================

    /** 현재 1분 최소 거래량 */
    private static final int MIN_1M_VOLUME = 50_000;

    /** 20분 시간 정규화 평균 최소값 */
    private static final int MIN_AVG_VOLUME_20M = 10_000;

    /** 20분 누적 거래량 최소값 */
    private static final int MIN_SUM_VOLUME_20M = 300_000;

    // ==================== 가격/체결강도 필터 ====================

    /** 최대 5분 가격 상승률 (추격 매수 방지) */
    private static final double MAX_PRICE_RISE_5M = 0.02;

    /** 체결강도 계산 기준 (초) */
    private static final int EXEC_STRENGTH_SECONDS = 30;

    // ==================== 청산 파라미터 ====================

    /** 손절 기준 (-1%) */
    private static final double STOP_LOSS_RATE = -0.01;

    /** FAKE IMPULSE 판정 윈도우 (초) */
    private static final int FAKE_CHECK_SECONDS = 90;

    /** FAKE IMPULSE: 최소 가격 상승률 */
    private static final double FAKE_MIN_RISE = 0.002;

    /** FAKE IMPULSE: Z-score 하락 비율 */
    private static final double FAKE_Z_RATIO = 0.7;

    /** Impulse 최대 보유 시간 (분) */
    private static final int MAX_HOLDING_MINUTES = 5;

    /** Impulse 종료: Z-score 급감 비율 */
    private static final double Z_DROP_RATIO = 0.5;

    /** WEAK_IMPULSE: 체결강도 급감 기준 */
    private static final double WEAK_EXEC_STRENGTH = 50.0;

    // ==================== 인터페이스 구현 ====================

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

        // 기본 필터
        if (!market.startsWith("KRW-")) return 0;
        if (market.equals("KRW-BTC") || market.equals("KRW-ETH")) return 0;

        int last = candles.size() - 1;
        if (last < 1) return 0;

        Candle cur = candles.get(last - 1);  // 최근 완성된 캔들
        double price = cur.getTradePrice().doubleValue();

        // ★ DB 기반 포지션 확인 (서버 재기동 안전) ★
        Optional<ImpulsePosition> positionOpt = positionService.getOpenPosition(market);

        if (positionOpt.isPresent()) {
            // 보유 중: 청산 로직
            return evaluateExit(market, candles, price, positionOpt.get());
        }

        // 캔들 데이터 충분성 검증
        if (candles.size() < Z_WINDOW + 5) {
            return 0;
        }

        // 미보유: 진입 로직
        return evaluateEntry(market, candles, cur, price);
    }

    @Override
    public void clearPosition(String market) {
        positionService.getOpenPosition(market).ifPresent(pos -> {
            double exitPrice = pos.getEntryPrice().doubleValue();
            positionService.closePosition(market, BigDecimal.valueOf(exitPrice), "MANUAL_CLEAR");
        });
    }

    // ==================== 진입 로직 ====================

    private int evaluateEntry(String market, List<Candle> candles, Candle cur, double price) {

        int last = candles.size() - 1;

        // ============ 0. 캔들 밀도 검증 (최우선 - 유동성 착시 방지) ============
        double density = calculateCandleDensity(candles, last, Z_WINDOW);

        if (density < MIN_CANDLE_DENSITY) {
            log.debug("REJECT,{},density={}<{}",
                    market,
                    String.format("%.2f", density),
                    MIN_CANDLE_DENSITY);
            return 0;  // 캔들 밀도 부족 → 유동성 부족
        }

        // 캔들 개수 검증
        if (!isCandleCountValid(candles, last, Z_WINDOW)) {
            log.debug("REJECT,{},candle_count_insufficient", market);
            return 0;
        }

        // ============ 시간대별 파라미터 조회 ============
        int currentHour = statService.getCurrentHour();
        ImpulseHourParam hourParam = statService.getHourParam(currentHour);

        double volumeMultiplier = hourParam.getVolumeMultiplierValue();
        double minExecutionStrength = hourParam.getMinExecutionStrengthValue();
        double minZScore = hourParam.getMinZScoreValue();

        // ============ 1. 절대 유동성 필터 ============
        double curVolume = cur.getCandleAccTradeVolume().doubleValue();

        if (curVolume < MIN_1M_VOLUME) {
            log.debug("REJECT,{},1m_vol={}<{}", market, (long) curVolume, MIN_1M_VOLUME);
            return 0;
        }

        // ============ 2. 누적 거래량 필터 ============
        double sumVolume20 = calculateSumVolume(candles, last, Z_WINDOW);

        if (sumVolume20 < MIN_SUM_VOLUME_20M) {
            log.debug("REJECT,{},sum_vol={}<{}", market, (long) sumVolume20, MIN_SUM_VOLUME_20M);
            return 0;
        }

        // ============ 3. 시간 정규화 평균 거래량 (핵심!) ============
        // ★ sumVolume / window (캔들 개수 아님!) ★
        double timeNormalizedAvg = calculateTimeNormalizedAvgVolume(candles, last, Z_WINDOW);

        if (timeNormalizedAvg < MIN_AVG_VOLUME_20M) {
            log.debug("REJECT,{},time_avg={}<{}", market, (long) timeNormalizedAvg, MIN_AVG_VOLUME_20M);
            return 0;
        }

        // ============ 4. 상대 거래량 급증 ============
        if (curVolume < timeNormalizedAvg * volumeMultiplier) {
            log.debug("REJECT,{},vol_ratio={}<{}",
                    market,
                    String.format("%.1f", curVolume / timeNormalizedAvg),
                    volumeMultiplier);
            return 0;
        }

        // ============ 5. 가격 필터 (추격 매수 방지) ============
        if (last < 5) return 0;
        double price5mAgo = candles.get(last - 5).getTradePrice().doubleValue();
        double priceRise5m = (price - price5mAgo) / price5mAgo;

        if (priceRise5m >= MAX_PRICE_RISE_5M) {
            log.debug("REJECT,{},price_rise={}%>={}%",
                    market,
                    String.format("%.2f", priceRise5m * 100),
                    MAX_PRICE_RISE_5M * 100);
            return 0;
        }

        // ============ 6. 체결강도 ============
        double executionStrength = marketVolumeService.getExecutionStrength(market, EXEC_STRENGTH_SECONDS);

        if (executionStrength < minExecutionStrength) {
            log.debug("REJECT,{},exec_str={}<{}",
                    market,
                    String.format("%.1f", executionStrength),
                    minExecutionStrength);
            return 0;
        }

        // ============ 7. Z-score (시간 정규화 기반) ============
        double zScore = calculateTimeNormalizedZScore(candles, Z_WINDOW);
        double prevZ = calculateTimeNormalizedZScore(candles.subList(0, candles.size() - 1), Z_WINDOW);
        double dz = zScore - prevZ;

        if (zScore <= prevZ) {
            log.debug("REJECT,{},z_not_increasing,Z={},prevZ={}", market,
                    String.format("%.2f", zScore), String.format("%.2f", prevZ));
            return 0;
        }

        if (zScore < minZScore) {
            log.debug("REJECT,{},z_low={}<{}", market,
                    String.format("%.2f", zScore), minZScore);
            return 0;
        }

        // ============ 진입 확정: DB 저장 ============
        Optional<ImpulsePosition> positionOpt = positionService.openPosition(
                market,
                BigDecimal.valueOf(price),
                BigDecimal.valueOf(1.0),
                zScore,
                dz,
                executionStrength,
                curVolume
        );

        if (positionOpt.isEmpty()) {
            log.warn("ENTRY_FAILED,{},position_create_failed", market);
            return 0;
        }

        // 진입 로그 (CSV 분석 가능)
        log.info("IMPULSE_ENTRY,{},price={},Z={},dZ={},exec={},vol={},density={},hour={}",
                market,
                String.format("%.4f", price),
                String.format("%.2f", zScore),
                String.format("%.2f", dz),
                String.format("%.1f", executionStrength),
                String.format("%.0f", curVolume),
                String.format("%.2f", density),
                currentHour);

        return 1;
    }

    // ==================== 청산 로직 ====================

    private int evaluateExit(String market, List<Candle> candles, double price, ImpulsePosition position) {

        BigDecimal currentPrice = BigDecimal.valueOf(price);
        double profitRate = position.getCurrentProfitRate(currentPrice).doubleValue();
        long seconds = position.getHoldingSeconds();
        long minutes = position.getHoldingMinutes();

        // 고점 업데이트
        positionService.updateHighest(market, currentPrice);

        // ============ 1. 손절 (STOP_LOSS) ============
        if (profitRate <= STOP_LOSS_RATE) {
            positionService.closeWithStopLoss(market, currentPrice);
            log.warn("EXIT,{},STOP_LOSS,pnl={}%", market, String.format("%.4f", profitRate * 100));
            return -1;
        }

        // ============ 2. FAKE IMPULSE (90초 이내) ============
        if (seconds <= FAKE_CHECK_SECONDS) {
            double currentZ = calculateTimeNormalizedZScore(candles, Z_WINDOW);
            double entryZ = position.getEntryZScore().doubleValue();

            boolean isFake = profitRate < FAKE_MIN_RISE && currentZ < entryZ * FAKE_Z_RATIO;

            if (isFake) {
                positionService.closeWithFakeImpulse(market, currentPrice);
                log.info("EXIT,{},FAKE_IMPULSE,pnl={}%,Z={}<{}",
                        market,
                        String.format("%.4f", profitRate * 100),
                        String.format("%.2f", currentZ),
                        String.format("%.2f", entryZ * FAKE_Z_RATIO));
                return -1;
            }
        }

        // ============ 3. Z-score 급감 (Z_DROP) ============
        double currentZ = calculateTimeNormalizedZScore(candles, Z_WINDOW);
        double entryZ = position.getEntryZScore().doubleValue();

        if (currentZ < entryZ * Z_DROP_RATIO) {
            String reason = profitRate > 0 ? "SUCCESS" : "Z_DROP";
            if (profitRate > 0) {
                positionService.closeWithSuccess(market, currentPrice);
            } else {
                positionService.closePosition(market, currentPrice, "Z_DROP");
            }
            log.info("EXIT,{},{},pnl={}%,Z={},entryZ={}",
                    market, reason,
                    String.format("%.4f", profitRate * 100),
                    String.format("%.2f", currentZ),
                    String.format("%.2f", entryZ));
            return -1;
        }

        // ============ 4. 타임아웃 (TIMEOUT) ============
        if (minutes >= MAX_HOLDING_MINUTES) {
            String reason = profitRate > 0 ? "SUCCESS" : "TIMEOUT";
            if (profitRate > 0) {
                positionService.closeWithSuccess(market, currentPrice);
            } else {
                positionService.closePosition(market, currentPrice, "TIMEOUT");
            }
            log.info("EXIT,{},{},pnl={}%,{}min",
                    market, reason,
                    String.format("%.4f", profitRate * 100), minutes);
            return -1;
        }

        // ============ 5. 체결강도 약화 (WEAK_IMPULSE) ============
        double currentExec = marketVolumeService.getExecutionStrength(market, EXEC_STRENGTH_SECONDS);
        double entryExec = position.getEntryExecutionStrength().doubleValue();

        // 1분 이상 보유 + 체결강도 50% 미만 + 진입 시 대비 30% 이상 하락
        if (seconds >= 60 && currentExec < WEAK_EXEC_STRENGTH && currentExec < entryExec * 0.7) {
            String reason = profitRate > 0 ? "SUCCESS" : "WEAK_IMPULSE";
            if (profitRate > 0) {
                positionService.closeWithSuccess(market, currentPrice);
            } else {
                positionService.closePosition(market, currentPrice, "WEAK_IMPULSE");
            }
            log.info("EXIT,{},{},pnl={}%,exec={}<{}",
                    market, reason,
                    String.format("%.4f", profitRate * 100),
                    String.format("%.1f", currentExec),
                    String.format("%.1f", entryExec));
            return -1;
        }

        return 0;  // 계속 보유
    }

    // ==================== 외부 청산 API ====================

    public void closeWithSuccess(String market, double exitPrice) {
        positionService.closeWithSuccess(market, BigDecimal.valueOf(exitPrice));
    }

    public void closeWithStopLoss(String market, double exitPrice) {
        positionService.closeWithStopLoss(market, BigDecimal.valueOf(exitPrice));
    }

    public void closeWithTrailingStop(String market, double exitPrice) {
        positionService.closeWithTrailingStop(market, BigDecimal.valueOf(exitPrice));
    }

    public boolean hasRecentSuccess(String market) {
        return statService.hasRecentSuccess(market);
    }

    public long countOpenPositions() {
        return positionService.countOpenPositions();
    }

    // ==================== 핵심 유틸리티: 시간 정규화 계산 ====================

    /**
     * 누적 거래량 계산
     *
     * @param candles 캔들 리스트
     * @param last 마지막 인덱스
     * @param window 윈도우 크기 (분)
     * @return 누적 거래량
     */
    private double calculateSumVolume(List<Candle> candles, int last, int window) {
        int start = Math.max(0, last - window + 1);
        double sum = 0;

        for (int i = start; i <= last; i++) {
            Candle c = candles.get(i);
            if (c.getCandleAccTradeVolume() != null) {
                sum += c.getCandleAccTradeVolume().doubleValue();
            }
        }

        return sum;
    }

    /**
     * 시간 정규화 평균 거래량 (핵심!)
     *
     * ★ 캔들 개수가 아닌 시간(window)으로 나눔 ★
     * - 거래 없는 분은 volume=0으로 간주한 효과
     * - 유동성 착시 방지
     *
     * @param candles 캔들 리스트
     * @param last 마지막 인덱스
     * @param window 윈도우 크기 (분)
     * @return 시간 정규화 평균 (sumVolume / window)
     */
    private double calculateTimeNormalizedAvgVolume(List<Candle> candles, int last, int window) {
        double sum = calculateSumVolume(candles, last, window);
        // ★ 핵심: window로 나눔 (candleCount 아님!) ★
        return sum / window;
    }

    /**
     * 캔들 밀도 계산
     *
     * - 실제 존재하는 캔들 수 / 기대 캔들 수
     * - 1.0에 가까울수록 유동성 좋음
     * - 0.5면 20분 중 10분만 거래 발생
     *
     * @param candles 캔들 리스트
     * @param last 마지막 인덱스
     * @param window 윈도우 크기 (분)
     * @return 밀도 (0.0 ~ 1.0)
     */
    private double calculateCandleDensity(List<Candle> candles, int last, int window) {
        int start = Math.max(0, last - window + 1);
        int actualCandleCount = 0;

        for (int i = start; i <= last; i++) {
            if (i < candles.size()) {
                actualCandleCount++;
            }
        }

        // 기대 캔들 수 = window (분)
        return (double) actualCandleCount / window;
    }

    /**
     * 캔들 개수 유효성 검증
     *
     * - 최소 90% 이상 캔들이 존재해야 함
     * - 20분 기준 최소 18개 필요
     *
     * @param candles 캔들 리스트
     * @param last 마지막 인덱스
     * @param window 윈도우 크기 (분)
     * @return true: 유효, false: 부족
     */
    private boolean isCandleCountValid(List<Candle> candles, int last, int window) {
        if (candles.isEmpty()) return false;
        if (!isLastCandleFresh(candles, last)) {
            return false; // 거래 중단
        }

        LocalDateTime endTime = getCandleTime(candles.get(last));
        LocalDateTime startTime = endTime.minusMinutes(window);

        int actualCount = 0;

        for (int i = last; i >= 0; i--) {
            LocalDateTime t = getCandleTime(candles.get(i));
            if (t.isBefore(startTime)) break;
            actualCount++;
        }

        int requiredCount = (int) Math.ceil(window * MIN_CANDLE_RATIO);
        return actualCount >= requiredCount;
    }

    /**
     * 시간 정규화 Z-score 계산
     *
     * ★ 평균/분산을 시간 기준으로 계산 ★
     * - 캔들 개수 기반이 아닌 시간 기준
     * - 유동성 착시 방지
     *
     * Z-score = (현재 거래량 - 시간 정규화 평균) / 시간 정규화 표준편차
     *
     * @param candles 캔들 리스트
     * @param window 윈도우 크기 (분)
     * @return Z-score
     */
    private double calculateTimeNormalizedZScore(List<Candle> candles, int window) {
        if (candles.size() < 2) return 0;

        int last = candles.size() - 1;
        LocalDateTime endTime = getCandleTime(candles.get(last));
        LocalDateTime startTime = endTime.minusMinutes(window);

        // 1️⃣ 시간 슬롯 초기화 (모든 분 = 0)
        Map<LocalDateTime, Double> volumeByMinute = new HashMap<>();
        for (int i = 0; i < window; i++) {
            volumeByMinute.put(startTime.plusMinutes(i), 0.0);
        }

        // 2️⃣ 실제 캔들 덮어쓰기
        for (int i = 0; i < last; i++) {
            LocalDateTime t = getCandleTime(candles.get(i))
                    .withSecond(0).withNano(0);
            if (!t.isBefore(startTime) && t.isBefore(endTime)) {
                volumeByMinute.put(
                        t,
                        candles.get(i).getCandleAccTradeVolume().doubleValue()
                );
            }
        }

        // 3️⃣ 평균
        double sum = volumeByMinute.values().stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / window;

        // 4️⃣ 분산
        double variance = volumeByMinute.values().stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum() / window;

        double std = Math.sqrt(variance);
        if (std == 0) return 0;

        // 5️⃣ 현재 거래량
        double curVolume = candles.get(last).getCandleAccTradeVolume().doubleValue();

        return (curVolume - mean) / std;
    }

    private LocalDateTime getCandleTime(Candle c) {
        Object t = c.getCandleDateTimeKst();

        if (t instanceof LocalDateTime) {
            return (LocalDateTime) t;
        }
        if (t instanceof OffsetDateTime) {
            return ((OffsetDateTime) t).toLocalDateTime();
        }
        if (t instanceof String) {
            return LocalDateTime.parse((String) t);
        }
        throw new IllegalStateException("Unknown candle time type: " + t);
    }

    private boolean isLastCandleFresh(List<Candle> candles, int last) {
        if (last < 1) return false;

        LocalDateTime lastTime = getCandleTime(candles.get(last));
        LocalDateTime prevTime = getCandleTime(candles.get(last - 1));

        long diffSeconds = java.time.Duration.between(prevTime, lastTime).getSeconds();

        // 업비트 1분봉은 정상일 때 60초 간격
        return diffSeconds <= 90;
    }
}