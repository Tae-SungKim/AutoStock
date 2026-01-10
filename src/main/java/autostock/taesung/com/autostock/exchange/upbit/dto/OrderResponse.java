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
    private String state;           // wait, watch, done, cancel
    private String market;
    private String createdAt;
    private String volume;
    private String remainingVolume;
    private String reservedFee;
    private String remainingFee;
    private String paidFee;
    private String locked;
    private String executedVolume;  // 체결된 수량
    private String executedFunds;   // 체결된 금액 (KRW)
    private String avgPrice;        // 평균 체결 단가
    private int tradesCount;

    /**
     * 체결 완료 여부
     */
    public boolean isDone() {
        return "done".equals(state);
    }

    /**
     * 대기 중 여부
     */
    public boolean isWaiting() {
        return "wait".equals(state);
    }

    /**
     * 취소 여부
     */
    public boolean isCancelled() {
        return "cancel".equals(state);
    }
}