package autostock.taesung.com.autostock.backtest.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TradeRecord {
    private String timestamp;           // 거래 시간
    private String type;                // BUY / SELL
    private Double price;               // 거래 가격
    private Double volume;              // 거래 수량
    private Double amount;              // 거래 금액
    private Double balance;             // 거래 후 KRW 잔고
    private Double coinBalance;         // 거래 후 코인 잔고
    private Double totalAsset;          // 총 자산 (KRW + 코인가치)
    private Double profitRate;          // 현재 수익률 (%)
    private String strategy;            // 매매 신호를 준 전략
}
