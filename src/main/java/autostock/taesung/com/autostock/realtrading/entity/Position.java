package autostock.taesung.com.autostock.realtrading.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 실거래 포지션 엔티티
 * - 분할 진입/청산 상태 추적
 * - 평균 매수가 자동 계산
 * - 트레일링 스탑 고점 추적
 */
@Entity
@Table(name = "real_position", indexes = {
    @Index(name = "idx_position_market", columnList = "market"),
    @Index(name = "idx_position_status", columnList = "status"),
    @Index(name = "idx_position_user", columnList = "userId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용자 ID */
    @Column(nullable = false)
    private Long userId;

    /** 마켓 코드 (예: KRW-BTC) */
    @Column(nullable = false, length = 20)
    private String market;

    /** 포지션 상태 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PositionStatus status = PositionStatus.PENDING;

    // ==================== 진입 정보 ====================

    /** 현재 진입 단계 (1, 2, 3) */
    @Column
    @Builder.Default
    private Integer entryPhase = 0;

    /** 총 보유 수량 */
    @Column(precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal totalQuantity = BigDecimal.ZERO;

    /** 총 투자 금액 (수수료 포함) */
    @Column(precision = 20, scale = 2)
    @Builder.Default
    private BigDecimal totalInvested = BigDecimal.ZERO;

    /** 평균 매수가 */
    @Column(precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal avgEntryPrice = BigDecimal.ZERO;

    /** 1차 진입가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal entry1Price;

    /** 1차 진입 수량 */
    @Column(precision = 20, scale = 8)
    private BigDecimal entry1Quantity;

    /** 1차 진입 시간 */
    @Column
    private LocalDateTime entry1Time;

    /** 2차 진입가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal entry2Price;

    /** 2차 진입 수량 */
    @Column(precision = 20, scale = 8)
    private BigDecimal entry2Quantity;

    /** 2차 진입 시간 */
    @Column
    private LocalDateTime entry2Time;

    /** 3차 진입가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal entry3Price;

    /** 3차 진입 수량 */
    @Column(precision = 20, scale = 8)
    private BigDecimal entry3Quantity;

    /** 3차 진입 시간 */
    @Column
    private LocalDateTime entry3Time;

    // ==================== 청산 정보 ====================

    /** 청산 단계 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private ExitPhase exitPhase = ExitPhase.NONE;

    /** 1차 익절 청산 수량 */
    @Column(precision = 20, scale = 8)
    private BigDecimal partialExitQuantity;

    /** 1차 익절 청산가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal partialExitPrice;

    /** 1차 익절 시간 */
    @Column
    private LocalDateTime partialExitTime;

    /** 최종 청산 수량 */
    @Column(precision = 20, scale = 8)
    private BigDecimal finalExitQuantity;

    /** 최종 청산가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal finalExitPrice;

    /** 최종 청산 시간 */
    @Column
    private LocalDateTime finalExitTime;

    /** 청산 사유 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ExitReason exitReason;

    // ==================== 트레일링/손절 정보 ====================

    /** 손절가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal stopLossPrice;

    /** 목표가 (1차 익절) */
    @Column(precision = 20, scale = 8)
    private BigDecimal targetPrice;

    /** 트레일링 스탑 활성화 여부 */
    @Column
    @Builder.Default
    private Boolean trailingActive = false;

    /** 트레일링 기준 고점 */
    @Column(precision = 20, scale = 8)
    private BigDecimal trailingHighPrice;

    /** 트레일링 스탑가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal trailingStopPrice;

    /** ATR 값 (진입 시점) */
    @Column(precision = 20, scale = 8)
    private BigDecimal atrAtEntry;

    // ==================== 손익 정보 ====================

    /** 실현 손익 (청산 완료된 부분) */
    @Column(precision = 20, scale = 2)
    @Builder.Default
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    /** 총 수수료 */
    @Column(precision = 20, scale = 2)
    @Builder.Default
    private BigDecimal totalFees = BigDecimal.ZERO;

    /** 총 슬리피지 손실 */
    @Column(precision = 20, scale = 2)
    @Builder.Default
    private BigDecimal totalSlippage = BigDecimal.ZERO;

    // ==================== 메타 정보 ====================

    /** 전략 이름 */
    @Column(length = 50)
    private String strategyName;

    /** 진입 신호 강도 (0~100) */
    @Column
    private Integer signalStrength;

    /** 메모/이유 */
    @Column(length = 500)
    private String note;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // ==================== Enums ====================

    public enum PositionStatus {
        PENDING,      // 진입 대기
        ENTERING,     // 분할 진입 중
        ACTIVE,       // 보유 중 (진입 완료)
        EXITING,      // 분할 청산 중
        CLOSED        // 청산 완료
    }

    public enum ExitPhase {
        NONE,         // 미청산
        PARTIAL,      // 1차 익절 완료
        TRAILING,     // 트레일링 스탑 활성
        FULL          // 전체 청산 완료
    }

    public enum ExitReason {
        TAKE_PROFIT,      // 익절
        STOP_LOSS,        // 손절
        TRAILING_STOP,    // 트레일링 스탑
        SIGNAL_EXIT,      // 전략 신호
        MANUAL,           // 수동 청산
        RISK_LIMIT,       // 리스크 한도 초과
        TIMEOUT           // 시간 초과
    }

    // ==================== 비즈니스 메서드 ====================

    /**
     * 진입 추가
     * @param price 체결가
     * @param quantity 수량
     * @param fee 수수료
     * @param slippage 슬리피지
     */
    public void addEntry(BigDecimal price, BigDecimal quantity, BigDecimal fee, BigDecimal slippage) {
        this.entryPhase++;

        BigDecimal investment = price.multiply(quantity).add(fee);
        this.totalQuantity = this.totalQuantity.add(quantity);
        this.totalInvested = this.totalInvested.add(investment);
        this.totalFees = this.totalFees.add(fee);
        this.totalSlippage = this.totalSlippage.add(slippage);

        // 평균 매수가 계산 (총 투자금 / 총 수량)
        if (this.totalQuantity.compareTo(BigDecimal.ZERO) > 0) {
            this.avgEntryPrice = this.totalInvested.divide(this.totalQuantity, 8, RoundingMode.HALF_UP);
        }

        // 단계별 정보 저장
        switch (this.entryPhase) {
            case 1 -> {
                this.entry1Price = price;
                this.entry1Quantity = quantity;
                this.entry1Time = LocalDateTime.now();
                this.status = PositionStatus.ENTERING;
            }
            case 2 -> {
                this.entry2Price = price;
                this.entry2Quantity = quantity;
                this.entry2Time = LocalDateTime.now();
            }
            case 3 -> {
                this.entry3Price = price;
                this.entry3Quantity = quantity;
                this.entry3Time = LocalDateTime.now();
                this.status = PositionStatus.ACTIVE;
            }
        }
    }

    /**
     * 부분 청산 (1차 익절)
     */
    public void partialExit(BigDecimal price, BigDecimal quantity, BigDecimal fee, BigDecimal slippage) {
        this.partialExitPrice = price;
        this.partialExitQuantity = quantity;
        this.partialExitTime = LocalDateTime.now();
        this.exitPhase = ExitPhase.PARTIAL;
        this.status = PositionStatus.EXITING;

        // 부분 실현 손익 계산
        BigDecimal proceeds = price.multiply(quantity).subtract(fee);
        BigDecimal cost = this.avgEntryPrice.multiply(quantity);
        BigDecimal pnl = proceeds.subtract(cost);

        this.realizedPnl = this.realizedPnl.add(pnl);
        this.totalQuantity = this.totalQuantity.subtract(quantity);
        this.totalFees = this.totalFees.add(fee);
        this.totalSlippage = this.totalSlippage.add(slippage);
    }

    /**
     * 전체 청산
     */
    public void fullExit(BigDecimal price, BigDecimal quantity, BigDecimal fee,
                         BigDecimal slippage, ExitReason reason) {
        this.finalExitPrice = price;
        this.finalExitQuantity = quantity;
        this.finalExitTime = LocalDateTime.now();
        this.exitPhase = ExitPhase.FULL;
        this.exitReason = reason;
        this.status = PositionStatus.CLOSED;

        // 최종 실현 손익 계산
        BigDecimal proceeds = price.multiply(quantity).subtract(fee);
        BigDecimal cost = this.avgEntryPrice.multiply(quantity);
        BigDecimal pnl = proceeds.subtract(cost);

        this.realizedPnl = this.realizedPnl.add(pnl);
        this.totalQuantity = BigDecimal.ZERO;
        this.totalFees = this.totalFees.add(fee);
        this.totalSlippage = this.totalSlippage.add(slippage);
    }

    /**
     * 트레일링 스탑 활성화
     */
    public void activateTrailingStop(BigDecimal highPrice, BigDecimal stopPrice) {
        this.trailingActive = true;
        this.trailingHighPrice = highPrice;
        this.trailingStopPrice = stopPrice;
        this.exitPhase = ExitPhase.TRAILING;
    }

    /**
     * 트레일링 고점 업데이트
     */
    public void updateTrailingHigh(BigDecimal newHigh, BigDecimal newStopPrice) {
        if (newHigh.compareTo(this.trailingHighPrice) > 0) {
            this.trailingHighPrice = newHigh;
            this.trailingStopPrice = newStopPrice;
        }
    }

    /**
     * 현재 미실현 손익 계산
     */
    public BigDecimal getUnrealizedPnl(BigDecimal currentPrice) {
        if (this.totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal currentValue = currentPrice.multiply(this.totalQuantity);
        BigDecimal costBasis = this.avgEntryPrice.multiply(this.totalQuantity);
        return currentValue.subtract(costBasis);
    }

    /**
     * 현재 수익률 계산
     */
    public BigDecimal getCurrentReturnRate(BigDecimal currentPrice) {
        if (this.avgEntryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(this.avgEntryPrice)
                .divide(this.avgEntryPrice, 6, RoundingMode.HALF_UP);
    }

    /**
     * 다음 진입 가능 여부
     */
    public boolean canAddEntry() {
        return this.entryPhase < 3 && this.status != PositionStatus.CLOSED;
    }

    /**
     * 잔여 수량 조회
     */
    public BigDecimal getRemainingQuantity() {
        return this.totalQuantity;
    }

    /**
     * 청산 가능 여부
     */
    public boolean canExit() {
        return this.totalQuantity.compareTo(BigDecimal.ZERO) > 0
            && this.status != PositionStatus.CLOSED;
    }
}