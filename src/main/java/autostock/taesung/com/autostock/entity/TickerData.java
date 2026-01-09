package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ticker_data", indexes = {
    @Index(name = "idx_ticker_market_time", columnList = "market, timestamp")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TickerData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String market;

    private String tradeDate;
    private String tradeTime;
    private String tradeDateKst;
    private String tradeTimeKst;
    private long tradeTimestamp;

    @Column(precision = 20, scale = 8)
    private BigDecimal openingPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal highPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal lowPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal tradePrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal prevClosingPrice;

    @Column(name = "`change`")
    private String change;

    @Column(precision = 20, scale = 8)
    private BigDecimal changePrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal changeRate;

    private long timestamp;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
