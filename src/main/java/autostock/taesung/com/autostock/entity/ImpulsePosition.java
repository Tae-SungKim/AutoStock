package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Volume Impulse 전략 포지션 엔티티
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [핵심 목적]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * - 서버 재기동 시에도 포지션 복구 보장
 * - 실계좌 운영 환경의 안정성 확보
 * - 모든 포지션 상태를 DB에 영속화
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [상태 머신]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * OPEN: 진입 완료, 보유 중
 *   → FAKE_IMPULSE로 청산
 *   → STOP_LOSS로 청산
 *   → SUCCESS (익절)로 청산
 *   → IMPULSE_END (5분 타임아웃)로 청산
 * CLOSED: 청산 완료
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [서버 재기동 시 복구 프로세스]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 1. status=OPEN인 레코드 전체 조회
 * 2. 메모리 캐시(ImpulseState)에 복원
 * 3. exit 로직 정상 동작 보장
 *
 * @see autostock.taesung.com.autostock.service.ImpulsePositionService
 * @see autostock.taesung.com.autostock.strategy.impl.VolumeImpulseStrategy
 */
@Entity
@Table(name = "impulse_position", indexes = {
        @Index(name = "idx_impulse_position_market", columnList = "market"),
        @Index(name = "idx_impulse_position_status", columnList = "status"),
        @Index(name = "idx_impulse_position_entry_time", columnList = "entryTime")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImpulsePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==================== 마켓 정보 ====================

    /**
     * 마켓 코드 (예: KRW-XRP)
     * - UNIQUE 제약: 동일 마켓에 OPEN 포지션은 1개만 허용
     */
    @Column(nullable = false, length = 20)
    private String market;

    /**
     * 포지션 상태
     * - OPEN: 보유 중 (서버 재기동 시 복구 대상)
     * - CLOSED: 청산 완료
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PositionStatus status = PositionStatus.OPEN;

    // ==================== 진입 정보 ====================

    /** 진입 시각 (서울 시간) */
    @Column(nullable = false)
    private LocalDateTime entryTime;

    /** 진입 가격 */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    /** 보유 수량 */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;

    /** 총 투자 금액 (수수료 포함) */
    @Column(precision = 20, scale = 2)
    private BigDecimal totalInvested;

    // ==================== 진입 시점 지표 ====================

    /**
     * 진입 시 거래량 Z-score
     * - FAKE IMPULSE 판단에 사용
     * - 현재 Z-score가 entryZScore × 0.7 미만이면 가짜 판정
     */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal entryZScore;

    /**
     * 진입 시 Z-score 변화량 (dZ)
     * - 모멘텀 상승 여부 확인용
     */
    @Column(precision = 10, scale = 4)
    private BigDecimal entryDeltaZ;

    /**
     * 진입 시 체결강도 (%)
     * - BID 비율 / 전체 체결 × 100
     */
    @Column(precision = 10, scale = 2)
    private BigDecimal entryExecutionStrength;

    /**
     * 진입 시 거래량
     */
    @Column(precision = 20, scale = 8)
    private BigDecimal entryVolume;

    /**
     * 진입 시간대 (0~23)
     * - 시간대별 파라미터 튜닝에 활용
     */
    @Column
    private Integer entryHour;

    // ==================== 트레일링 정보 ====================

    /**
     * 손절가
     * - 진입가 × (1 - STOP_LOSS_RATE)
     */
    @Column(precision = 20, scale = 8)
    private BigDecimal stopLossPrice;

    /**
     * 트레일링 기준 고점
     * - 보유 중 도달한 최고가
     */
    @Column(precision = 20, scale = 8)
    private BigDecimal highestPrice;

    // ==================== 청산 정보 ====================

    /** 청산 시각 */
    @Column
    private LocalDateTime exitTime;

    /** 청산 가격 */
    @Column(precision = 20, scale = 8)
    private BigDecimal exitPrice;

    /**
     * 청산 사유
     * - SUCCESS: 익절 (+수익)
     * - STOP_LOSS: 손절 (-1% 이하)
     * - FAKE_IMPULSE: 가짜 급등 (90초 이내 조건 충족)
     * - IMPULSE_END: 5분 타임아웃
     * - TRAILING_STOP: 트레일링 스탑
     */
    @Column(length = 30)
    private String exitReason;

    /**
     * 수익률 (청산가 - 진입가) / 진입가
     * - 양수: 수익, 음수: 손실
     */
    @Column(precision = 10, scale = 6)
    private BigDecimal profitRate;

    /**
     * 실현 손익 (원화)
     */
    @Column(precision = 20, scale = 2)
    private BigDecimal realizedPnl;

    // ==================== 메타 정보 ====================

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // ==================== Enum ====================

    public enum PositionStatus {
        /** 보유 중 (서버 재기동 시 복구 대상) */
        OPEN,
        /** 청산 완료 */
        CLOSED
    }

    // ==================== 비즈니스 메서드 ====================

    /**
     * 고점 업데이트
     *
     * @param currentPrice 현재가
     * @return 고점 갱신 여부
     */
    public boolean updateHighest(BigDecimal currentPrice) {
        if (this.highestPrice == null || currentPrice.compareTo(this.highestPrice) > 0) {
            this.highestPrice = currentPrice;
            return true;
        }
        return false;
    }

    /**
     * 현재 수익률 계산
     *
     * @param currentPrice 현재가
     * @return 수익률 (소수점, 예: 0.015 = +1.5%)
     */
    public BigDecimal getCurrentProfitRate(BigDecimal currentPrice) {
        if (this.entryPrice == null || this.entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(this.entryPrice)
                .divide(this.entryPrice, 6, RoundingMode.HALF_UP);
    }

    /**
     * 손절가 도달 여부
     *
     * @param currentPrice 현재가
     * @return true: 손절 필요
     */
    public boolean isStopLossTriggered(BigDecimal currentPrice) {
        if (this.stopLossPrice == null) {
            return false;
        }
        return currentPrice.compareTo(this.stopLossPrice) <= 0;
    }

    /**
     * 보유 시간 (초) 계산
     *
     * @return 진입 후 경과 시간 (초)
     */
    public long getHoldingSeconds() {
        if (this.entryTime == null) {
            return 0;
        }
        return java.time.Duration.between(this.entryTime, LocalDateTime.now()).getSeconds();
    }

    /**
     * 보유 시간 (분) 계산
     *
     * @return 진입 후 경과 시간 (분)
     */
    public long getHoldingMinutes() {
        return getHoldingSeconds() / 60;
    }

    /**
     * 청산 처리
     *
     * @param exitPrice 청산가
     * @param reason 청산 사유
     */
    public void close(BigDecimal exitPrice, String reason) {
        this.status = PositionStatus.CLOSED;
        this.exitTime = LocalDateTime.now();
        this.exitPrice = exitPrice;
        this.exitReason = reason;

        // 수익률 계산
        if (this.entryPrice != null && this.entryPrice.compareTo(BigDecimal.ZERO) > 0) {
            this.profitRate = exitPrice.subtract(this.entryPrice)
                    .divide(this.entryPrice, 6, RoundingMode.HALF_UP);

            // 실현 손익 계산 (수량 × 수익률 × 진입가)
            if (this.quantity != null) {
                this.realizedPnl = this.profitRate.multiply(this.entryPrice).multiply(this.quantity);
            }
        }
    }

    /**
     * 미청산 포지션 여부
     */
    public boolean isOpen() {
        return this.status == PositionStatus.OPEN;
    }

    /**
     * 진입 후 90초 이내 여부 (FAKE IMPULSE 판정 윈도우)
     */
    public boolean isWithinFakeCheckWindow() {
        return getHoldingSeconds() <= 90;
    }

    /**
     * 진입 후 5분 경과 여부 (IMPULSE_END 판정)
     */
    public boolean isImpulseExpired() {
        return getHoldingMinutes() >= 5;
    }

    @Override
    public String toString() {
        return String.format("ImpulsePosition[%s, %s, entry=%.4f, Z=%.2f, status=%s]",
                market,
                entryTime,
                entryPrice != null ? entryPrice.doubleValue() : 0,
                entryZScore != null ? entryZScore.doubleValue() : 0,
                status);
    }
}