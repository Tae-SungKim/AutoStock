package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "candle_data", indexes = {
    @Index(name = "idx_candle_market_time", columnList = "market, candle_date_time_kst")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandleData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String market;

    @Column(name = "candle_date_time_utc", length = 30)
    private String candleDateTimeUtc;

    @Column(name = "candle_date_time_kst", length = 30)
    private String candleDateTimeKst;

    @Column(precision = 20, scale = 8)
    private BigDecimal openingPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal highPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal lowPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal tradePrice;

    private long timestamp;

    @Column(precision = 20, scale = 8)
    private BigDecimal candleAccTradePrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal candleAccTradeVolume;

    private int unit;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
