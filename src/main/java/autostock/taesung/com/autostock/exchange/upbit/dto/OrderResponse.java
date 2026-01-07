package autostock.taesung.com.autostock.exchange.upbit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private String uuid;
    private String side;
    private String ordType;
    private String price;
    private String state;
    private String market;
    private String createdAt;
    private String volume;
    private String remainingVolume;
    private String reservedFee;
    private String remainingFee;
    private String paidFee;
    private String locked;
    private String executedVolume;
    private int tradesCount;
}