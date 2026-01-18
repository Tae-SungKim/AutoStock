package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 거래 히스토리 엔티티
 * 매수/매도 거래 내역을 저장
 */
@Entity
@Table(name = "trade_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 사용자 ID (외래키)
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 마켓 코드 (예: KRW-BTC, KRW-ETH)
     */
    @Column(name = "market", nullable = false, length = 20)
    private String market;

    /**
     * 매입 방법 (예: MARKET - 시장가, LIMIT - 지정가)
     */
    @Column(name = "trade_method", nullable = false, length = 20)
    private String tradeMethod;

    /**
     * 거래일
     */
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /**
     * 거래 시간
     */
    @Column(name = "trade_time", nullable = false)
    private LocalTime tradeTime;

    /**
     * 거래 유형 (BUY: 매수, SELL: 매도)
     */
    @Column(name = "trade_type", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private TradeType tradeType;

    /**
     * 거래 금액 (KRW)
     */
    @Column(name = "amount", nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    /**
     * 거래 수량
     */
    @Column(name = "volume", nullable = false, precision = 20, scale = 8)
    private BigDecimal volume;

    /**
     * 단가 (1개당 가격)
     */
    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    private BigDecimal price;

    /**
     * 수수료
     */
    @Column(name = "fee", nullable = false, precision = 20, scale = 8)
    private BigDecimal fee;

    /**
     * 업비트 주문 UUID
     */
    @Column(name = "order_uuid", length = 100)
    private String orderUuid;

    /**
     * 사용된 전략 이름
     */
    @Column(name = "strategy_name", length = 100)
    private String strategyName;

    /**
     * 목표 판매가 (볼린저밴드 상단 등)
     */
    @Column(name = "target_price", precision = 20, scale = 8)
    private BigDecimal targetPrice;

    /**
     * 보유 중 최고가 (트레일링 스탑용)
     */
    @Column(name = "highest_price", precision = 20, scale = 8)
    private BigDecimal highestPrice;

    /**
     * 부분 익절 여부
     */
    private Boolean halfSold;   // 부분 익절 여부

    private Boolean isStopLoss;

    /**
     * 진입 단계 (1차, 2차, 3차 분할매수)
     */
    @Column(name = "entry_phase")
    private Integer entryPhase;

    /**
     * 청산 단계 (1차 부분익절, 2차 전량청산)
     */
    @Column(name = "exit_phase")
    private Integer exitPhase;

    /**
     * 트레일링 스탑 활성화 여부
     */
    @Column(name = "trailing_active")
    private Boolean trailingActive;

    /**
     * 트레일링 스탑가
     */
    @Column(name = "trailing_stop_price", precision = 20, scale = 8)
    private BigDecimal trailingStopPrice;

    /**
     * 평균 매수가 (분할매수 시 가중평균)
     */
    @Column(name = "avg_entry_price", precision = 20, scale = 8)
    private BigDecimal avgEntryPrice;

    /**
     * 총 투자금액
     */
    @Column(name = "total_invested", precision = 20, scale = 8)
    private BigDecimal totalInvested;
    /**
     * 생성 일시
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.tradeDate == null) {
            this.tradeDate = LocalDate.now();
        }
        if (this.tradeTime == null) {
            this.tradeTime = LocalTime.now();
        }
    }

    /**
     * 거래 유형 enum
     */
    public enum TradeType {
        BUY,    // 매수
        SELL    // 매도
    }
}
