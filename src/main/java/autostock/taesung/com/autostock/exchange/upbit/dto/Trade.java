package autostock.taesung.com.autostock.exchange.upbit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class Trade {

    private String market;

    @JsonProperty("trade_date_utc")
    private String tradeDateUtc;

    @JsonProperty("trade_time_utc")
    private String tradeTimeUtc;

    private Long timestamp;

    @JsonProperty("trade_price")
    private BigDecimal tradePrice;

    @JsonProperty("trade_volume")
    private BigDecimal tradeVolume;

    @JsonProperty("prev_closing_price")
    private BigDecimal prevClosingPrice;

    @JsonProperty("change_price")
    private BigDecimal changePrice;

    @JsonProperty("ask_bid")
    private String askBid;

    @JsonProperty("sequential_id")
    private Long sequentialId;

    public boolean isBid() {
        return "BID".equalsIgnoreCase(askBid);
    }

    public boolean isAsk() {
        return "ASK".equalsIgnoreCase(askBid);
    }
}