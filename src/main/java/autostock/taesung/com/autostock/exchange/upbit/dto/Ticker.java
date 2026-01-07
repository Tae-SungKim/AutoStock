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
public class Ticker {
    private String market;
    private String tradeDate;
    private String tradeTime;
    private String tradeDateKst;
    private String tradeTimeKst;
    private long tradeTimestamp;
    private BigDecimal openingPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal tradePrice;
    private BigDecimal prevClosingPrice;
    private String change;
    private BigDecimal changePrice;
    private BigDecimal changeRate;
    private BigDecimal signedChangePrice;
    private BigDecimal signedChangeRate;
    private BigDecimal tradeVolume;
    private BigDecimal accTradePrice;
    private BigDecimal accTradePrice24h;
    private BigDecimal accTradeVolume;
    private BigDecimal accTradeVolume24h;
    private BigDecimal highest52WeekPrice;
    private String highest52WeekDate;
    private BigDecimal lowest52WeekPrice;
    private String lowest52WeekDate;
    private long timestamp;
}