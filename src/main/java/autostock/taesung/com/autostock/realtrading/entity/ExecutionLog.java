package autostock.taesung.com.autostock.realtrading.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 체결 로그 엔티티
 * - 슬리피지 추적
 * - 체결 통계 수집
 * - 백테스트/실거래 공용
 */
@Entity
@Table(name = "execution_log", indexes = {
    @Index(name = "idx_exec_position", columnList = "positionId"),
    @Index(name = "idx_exec_market", columnList = "market"),
    @Index(name = "idx_exec_time", columnList = "executedAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 연결된 포지션 ID */
    @Column
    private Long positionId;

    /** 마켓 코드 */
    @Column(nullable = false, length = 20)
    private String market;

    /** 주문 유형 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSide side;

    /** 실행 유형 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionType executionType;

    // ==================== 주문 정보 ====================

    /** 주문 UUID (거래소) */
    @Column(length = 50)
    private String orderId;

    /** 요청 가격 (지정가) */
    @Column(precision = 20, scale = 8)
    private BigDecimal requestedPrice;

    /** 요청 수량 */
    @Column(precision = 20, scale = 8)
    private BigDecimal requestedQuantity;

    /** 주문 시점 시장가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal marketPriceAtOrder;

    // ==================== 체결 정보 ====================

    /** 체결가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal executedPrice;

    /** 체결 수량 */
    @Column(precision = 20, scale = 8)
    private BigDecimal executedQuantity;

    /** 체결 금액 */
    @Column(precision = 20, scale = 2)
    private BigDecimal executedAmount;

    /** 수수료 */
    @Column(precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal fee = BigDecimal.ZERO;

    /** 체결 상태 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExecutionStatus status;

    // ==================== 슬리피지 분석 ====================

    /** 슬리피지 (요청가 대비 체결가 차이) */
    @Column(precision = 20, scale = 8)
    private BigDecimal slippage;

    /** 슬리피지율 (%) */
    @Column(precision = 10, scale = 6)
    private BigDecimal slippagePercent;

    /** 시장가 대비 슬리피지 */
    @Column(precision = 20, scale = 8)
    private BigDecimal marketSlippage;

    /** 시장가 대비 슬리피지율 (%) */
    @Column(precision = 10, scale = 6)
    private BigDecimal marketSlippagePercent;

    // ==================== 시간 정보 ====================

    /** 주문 생성 시간 */
    @Column
    private LocalDateTime orderedAt;

    /** 체결 시간 */
    @Column
    private LocalDateTime executedAt;

    /** 체결 소요 시간 (ms) */
    @Column
    private Long executionTimeMs;

    /** 재시도 횟수 */
    @Column
    @Builder.Default
    private Integer retryCount = 0;

    // ==================== 유동성 정보 ====================

    /** 주문 시점 거래대금 (24h) */
    @Column(precision = 20, scale = 2)
    private BigDecimal tradingVolume;

    /** 주문 시점 매수 호가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal bidPrice;

    /** 주문 시점 매도 호가 */
    @Column(precision = 20, scale = 8)
    private BigDecimal askPrice;

    /** 스프레드 (%) */
    @Column(precision = 10, scale = 6)
    private BigDecimal spread;

    // ==================== 메타 정보 ====================

    /** 백테스트 여부 */
    @Column
    @Builder.Default
    private Boolean isBacktest = false;

    /** 실패 사유 */
    @Column(length = 200)
    private String failureReason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ==================== Enums ====================

    public enum OrderSide {
        BUY, SELL
    }

    public enum ExecutionType {
        ENTRY_1,      // 1차 진입
        ENTRY_2,      // 2차 진입
        ENTRY_3,      // 3차 진입
        EXIT_PARTIAL, // 부분 청산
        EXIT_FULL,    // 전체 청산
        STOP_LOSS,    // 손절
        TRAILING      // 트레일링 스탑
    }

    public enum ExecutionStatus {
        PENDING,      // 대기
        PARTIAL,      // 부분 체결
        FILLED,       // 전체 체결
        CANCELLED,    // 취소됨
        FAILED,       // 실패
        TIMEOUT       // 시간 초과
    }

    // ==================== 비즈니스 메서드 ====================

    /**
     * 체결 완료 처리
     */
    public void markFilled(BigDecimal price, BigDecimal quantity, BigDecimal fee, LocalDateTime time) {
        this.executedPrice = price;
        this.executedQuantity = quantity;
        this.executedAmount = price.multiply(quantity);
        this.fee = fee;
        this.executedAt = time;
        this.status = ExecutionStatus.FILLED;

        // 체결 소요 시간 계산
        if (this.orderedAt != null && time != null) {
            this.executionTimeMs = java.time.Duration.between(this.orderedAt, time).toMillis();
        }

        // 슬리피지 계산
        calculateSlippage();
    }

    /**
     * 부분 체결 처리
     */
    public void markPartialFill(BigDecimal price, BigDecimal quantity, BigDecimal fee) {
        this.executedPrice = price;
        this.executedQuantity = quantity;
        this.executedAmount = price.multiply(quantity);
        this.fee = fee;
        this.status = ExecutionStatus.PARTIAL;

        calculateSlippage();
    }

    /**
     * 실패 처리
     */
    public void markFailed(String reason) {
        this.status = ExecutionStatus.FAILED;
        this.failureReason = reason;
    }

    /**
     * 슬리피지 계산
     */
    private void calculateSlippage() {
        if (this.requestedPrice != null && this.executedPrice != null
            && this.requestedPrice.compareTo(BigDecimal.ZERO) > 0) {

            // 요청가 대비 슬리피지
            this.slippage = this.executedPrice.subtract(this.requestedPrice);
            this.slippagePercent = this.slippage
                    .divide(this.requestedPrice, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        if (this.marketPriceAtOrder != null && this.executedPrice != null
            && this.marketPriceAtOrder.compareTo(BigDecimal.ZERO) > 0) {

            // 시장가 대비 슬리피지
            this.marketSlippage = this.executedPrice.subtract(this.marketPriceAtOrder);
            this.marketSlippagePercent = this.marketSlippage
                    .divide(this.marketPriceAtOrder, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }
    }

    /**
     * 체결 품질 평가 (0~100)
     * 슬리피지가 적을수록, 체결 시간이 짧을수록 높은 점수
     */
    public int getExecutionQualityScore() {
        if (this.status != ExecutionStatus.FILLED) {
            return 0;
        }

        int score = 100;

        // 슬리피지 감점 (0.1%당 10점)
        if (this.slippagePercent != null) {
            double slipPct = Math.abs(this.slippagePercent.doubleValue());
            score -= (int) (slipPct * 100);
        }

        // 체결 시간 감점 (10초당 5점)
        if (this.executionTimeMs != null) {
            score -= (int) (this.executionTimeMs / 10000 * 5);
        }

        return Math.max(0, Math.min(100, score));
    }

    /**
     * 슬리피지 비용 (금액)
     */
    public BigDecimal getSlippageCost() {
        if (this.slippage == null || this.executedQuantity == null) {
            return BigDecimal.ZERO;
        }
        return this.slippage.abs().multiply(this.executedQuantity);
    }
}