package autostock.taesung.com.autostock.exchange.upbit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle {
    private String market;
    private String candleDateTimeUtc;
    private String candleDateTimeKst;
    private BigDecimal openingPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal tradePrice;
    private long timestamp;
    private BigDecimal candleAccTradePrice;
    private BigDecimal candleAccTradeVolume;
    private int unit;
}