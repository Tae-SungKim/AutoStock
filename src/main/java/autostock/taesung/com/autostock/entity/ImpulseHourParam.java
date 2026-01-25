package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 시간대별 Impulse 전략 파라미터 엔티티
 *
 * [목적]
 * - 시간대(0~23시)마다 최적화된 진입 조건을 저장
 * - 자동 튜닝 스케줄러가 매일 전날 통계 기반으로 업데이트
 * - VolumeImpulseStrategy에서 현재 시간에 맞는 파라미터 자동 적용
 *
 * [튜닝 규칙]
 * - 승률 < 45% → 조건 강화 (minExecutionStrength↑, minZScore↑, volumeMultiplier↑)
 * - 승률 > 60% → 조건 완화 (minExecutionStrength↓, minZScore↓, volumeMultiplier↓)
 * - 그 외 → 기본값 유지
 *
 * [사용 예시]
 * - 새벽 2~8시: 거래량 적음 → 조건 강화 필요
 * - 오전 9~12시: 거래 활발 → 조건 완화 가능
 */
@Entity
@Table(name = "impulse_hour_param", indexes = {
        @Index(name = "idx_impulse_hour_param_hour", columnList = "hour")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImpulseHourParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 시간대 (0~23)
     * - 0: 자정~1시
     * - 9: 오전 9시~10시
     * - 23: 밤 11시~자정
     */
    @Column(name = "hour", nullable = false, unique = true)
    private Integer hour;

    /**
     * 최소 체결강도 (%)
     * - 기본값: 65.0
     * - 강화: 70.0 (승률 < 45%)
     * - 완화: 60.0 (승률 > 60%)
     * - 매수체결량/전체체결량 × 100 이 이 값 이상이어야 진입
     */
    @Column(name = "min_execution_strength", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal minExecutionStrength = BigDecimal.valueOf(65.0);

    /**
     * 최소 Z-score
     * - 기본값: 1.5
     * - 강화: 2.0 (승률 < 45%)
     * - 완화: 1.2 (승률 > 60%)
     * - 거래량 Z-score가 이 값 이상이어야 진입
     */
    @Column(name = "min_z_score", nullable = false, precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal minZScore = BigDecimal.valueOf(1.5);

    /**
     * 거래량 급증 배수
     * - 기본값: 4.0
     * - 강화: 5.0 (승률 < 45%)
     * - 완화: 3.5 (승률 > 60%)
     * - 현재 거래량 >= 평균 거래량 × 이 값 이어야 진입
     */
    @Column(name = "volume_multiplier", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal volumeMultiplier = BigDecimal.valueOf(4.0);

    /**
     * 샘플 수
     * - 이 시간대의 총 거래 횟수
     * - 튜닝 시 최소 20개 이상이어야 유효
     */
    @Column(name = "sample_count")
    private Integer sampleCount;

    /**
     * 승률
     * - 성공 거래 수 / 전체 거래 수
     * - 예: 0.55 = 55% 승률
     */
    @Column(name = "win_rate", precision = 10, scale = 4)
    private BigDecimal winRate;

    /**
     * 평균 수익률
     * - 이 시간대 거래들의 평균 profitRate
     * - 양수: 평균적으로 수익, 음수: 평균적으로 손실
     */
    @Column(name = "avg_profit_rate", precision = 10, scale = 6)
    private BigDecimal avgProfitRate;

    /**
     * 활성화 여부
     * - false면 기본 파라미터 사용
     * - 특정 시간대 비활성화 용도
     */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 마지막 튜닝 시각
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 레코드 생성 시각
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 체결강도 파라미터 조회 (double 변환)
     * - null 방어 처리 포함
     */
    public double getMinExecutionStrengthValue() {
        return minExecutionStrength != null ? minExecutionStrength.doubleValue() : 65.0;
    }

    /**
     * Z-score 파라미터 조회 (double 변환)
     * - null 방어 처리 포함
     */
    public double getMinZScoreValue() {
        return minZScore != null ? minZScore.doubleValue() : 0.7;
    }

    /**
     * 거래량 배수 파라미터 조회 (double 변환)
     * - null 방어 처리 포함
     */
    public double getVolumeMultiplierValue() {
        return volumeMultiplier != null ? volumeMultiplier.doubleValue() : 4.0;
    }
}