package autostock.taesung.com.autostock.scheduler;

import autostock.taesung.com.autostock.entity.ImpulseHourParam;
import autostock.taesung.com.autostock.repository.ImpulseHourParamRepository;
import autostock.taesung.com.autostock.repository.ImpulseTradeStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Impulse 전략 파라미터 자동 튜닝 스케줄러
 *
 * [목적]
 * - 전날 거래 통계를 분석하여 시간대별 파라미터 자동 조정
 * - 승률이 낮은 시간대는 조건 강화, 높은 시간대는 완화
 * - 데이터 기반 자동 진화 시스템의 핵심 컴포넌트
 *
 * [실행 주기]
 * - 매일 새벽 4시 30분 (cron: "0 30 4 * * *")
 * - 전날 자정~당일 자정 데이터 분석
 *
 * [튜닝 규칙]
 * - 승률 < 45%: 조건 강화 (진입 어렵게)
 *   → minExecutionStrength: 70%, minZScore: 2.0, volumeMultiplier: 5.0
 * - 승률 > 60%: 조건 완화 (진입 쉽게)
 *   → minExecutionStrength: 60%, minZScore: 1.2, volumeMultiplier: 3.5
 * - 그 외: 기본값 유지
 *   → minExecutionStrength: 65%, minZScore: 1.5, volumeMultiplier: 4.0
 *
 * [안전장치]
 * - 샘플 수 < 20개인 시간대는 튜닝 스킵 (통계적 유의성 부족)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImpulseParamTuningScheduler {

    private final ImpulseTradeStatRepository statRepository;
    private final ImpulseHourParamRepository hourParamRepository;

    /** 튜닝에 필요한 최소 샘플 수 */
    private static final int MIN_SAMPLES = 20;

    // ============ 기본 파라미터 (승률 45~60%) ============
    private static final double DEFAULT_EXEC_STRENGTH = 65.0;
    private static final double DEFAULT_Z_SCORE = 1.5;
    private static final double DEFAULT_VOLUME_MULT = 4.0;

    // ============ 강화 파라미터 (승률 < 45%) ============
    // 진입 조건을 까다롭게 → 가짜 신호 필터링
    private static final double STRONG_EXEC_STRENGTH = 70.0;
    private static final double STRONG_Z_SCORE = 2.0;
    private static final double STRONG_VOLUME_MULT = 5.0;

    // ============ 완화 파라미터 (승률 > 60%) ============
    // 진입 조건을 느슨하게 → 더 많은 기회 포착
    private static final double LOOSE_EXEC_STRENGTH = 60.0;
    private static final double LOOSE_Z_SCORE = 1.2;
    private static final double LOOSE_VOLUME_MULT = 3.5;

    /**
     * 파라미터 자동 튜닝 실행
     *
     * [실행 시점]
     * - 매일 새벽 4시 30분
     *
     * [처리 흐름]
     * 1. 전날 데이터에서 시간대별 통계 조회
     * 2. 각 시간대의 승률 계산
     * 3. 승률에 따라 파라미터 조정
     * 4. DB 저장
     */
    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void tuneParameters() {
        log.info("=== Impulse Parameter Tuning Start ===");

        // 전날 자정 ~ 오늘 자정
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);

        // 시간대별 상세 통계 조회 (최소 샘플 수 충족 시간대만)
        List<Object[]> hourlyStats = statRepository.findHourlyStatsForTuning(
                yesterday, today, MIN_SAMPLES);

        if (hourlyStats.isEmpty()) {
            log.info("No sufficient data for tuning");
            return;
        }

        for (Object[] row : hourlyStats) {
            // row[0]: 시간대 (0~23)
            // row[1]: 총 거래 수
            // row[2]: 성공 거래 수
            // row[3]: 평균 수익률
            // row[4]: 평균 Z-score
            // row[5]: 평균 체결강도
            int hour = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            long winCount = ((Number) row[2]).longValue();
            double avgProfitRate = row[3] != null ? ((Number) row[3]).doubleValue() : 0;
            double avgZScore = row[4] != null ? ((Number) row[4]).doubleValue() : DEFAULT_Z_SCORE;
            double avgExecStrength = row[5] != null ? ((Number) row[5]).doubleValue() : DEFAULT_EXEC_STRENGTH;

            // 승률 계산
            double winRate = (double) winCount / count;

            // 해당 시간대의 파라미터 조회 (없으면 생성)
            ImpulseHourParam param = hourParamRepository.findByHour(hour)
                    .orElse(createDefaultParam(hour));

            // 승률에 따라 파라미터 조정
            adjustParam(param, winRate, avgZScore, avgExecStrength);

            // 통계 정보 업데이트
            param.setSampleCount((int) count);
            param.setWinRate(BigDecimal.valueOf(winRate));
            param.setAvgProfitRate(BigDecimal.valueOf(avgProfitRate));

            hourParamRepository.save(param);

            log.info("TUNING,hour={},samples={},winRate={}%,newExec={},newZ={},newVol={}",
                    hour,
                    count,
                    String.format("%.1f", winRate * 100),
                    param.getMinExecutionStrength(),
                    param.getMinZScore(),
                    param.getVolumeMultiplier());
        }

        log.info("=== Impulse Parameter Tuning Complete ===");
    }

    /**
     * 승률에 따른 파라미터 조정
     *
     * @param param 조정할 파라미터 엔티티
     * @param winRate 승률 (0.0 ~ 1.0)
     * @param avgZScore 평균 진입 Z-score (참고용)
     * @param avgExecStrength 평균 진입 체결강도 (참고용)
     */
    private void adjustParam(ImpulseHourParam param, double winRate,
                             double avgZScore, double avgExecStrength) {

        if (winRate < 0.45) {
            // 승률 45% 미만: 조건 강화 (진입 어렵게)
            // → 가짜 신호를 더 많이 필터링
            param.setMinExecutionStrength(BigDecimal.valueOf(STRONG_EXEC_STRENGTH));
            param.setMinZScore(BigDecimal.valueOf(STRONG_Z_SCORE));
            param.setVolumeMultiplier(BigDecimal.valueOf(STRONG_VOLUME_MULT));
        } else if (winRate > 0.60) {
            // 승률 60% 초과: 조건 완화 (진입 쉽게)
            // → 더 많은 기회 포착
            param.setMinExecutionStrength(BigDecimal.valueOf(LOOSE_EXEC_STRENGTH));
            param.setMinZScore(BigDecimal.valueOf(LOOSE_Z_SCORE));
            param.setVolumeMultiplier(BigDecimal.valueOf(LOOSE_VOLUME_MULT));
        } else {
            // 승률 45~60%: 기본값 유지
            param.setMinExecutionStrength(BigDecimal.valueOf(DEFAULT_EXEC_STRENGTH));
            param.setMinZScore(BigDecimal.valueOf(DEFAULT_Z_SCORE));
            param.setVolumeMultiplier(BigDecimal.valueOf(DEFAULT_VOLUME_MULT));
        }
    }

    /**
     * 기본 파라미터 엔티티 생성
     *
     * @param hour 시간대
     * @return 기본값이 설정된 ImpulseHourParam
     */
    private ImpulseHourParam createDefaultParam(int hour) {
        return ImpulseHourParam.builder()
                .hour(hour)
                .minExecutionStrength(BigDecimal.valueOf(DEFAULT_EXEC_STRENGTH))
                .minZScore(BigDecimal.valueOf(DEFAULT_Z_SCORE))
                .volumeMultiplier(BigDecimal.valueOf(DEFAULT_VOLUME_MULT))
                .enabled(true)
                .build();
    }
}