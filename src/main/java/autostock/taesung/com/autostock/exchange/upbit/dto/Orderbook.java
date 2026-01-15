package autostock.taesung.com.autostock.exchange.upbit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 업비트 호가창 DTO
 * API: GET /orderbook?markets={market}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Orderbook {
    private String market;
    private long timestamp;
    private BigDecimal totalAskSize;  // 매도 총량
    private BigDecimal totalBidSize;  // 매수 총량
    private List<OrderbookUnit> orderbookUnits;

    /**
     * 호가 단위 (매수/매도 각 가격대)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderbookUnit {
        private BigDecimal askPrice;   // 매도 호가
        private BigDecimal bidPrice;   // 매수 호가
        private BigDecimal askSize;    // 매도 잔량
        private BigDecimal bidSize;    // 매수 잔량
    }

    /**
     * 최우선 매도호가 (가장 낮은 매도가)
     */
    public BigDecimal getBestAskPrice() {
        if (orderbookUnits != null && !orderbookUnits.isEmpty()) {
            return orderbookUnits.get(0).getAskPrice();
        }
        return BigDecimal.ZERO;
    }

    /**
     * 최우선 매수호가 (가장 높은 매수가)
     */
    public BigDecimal getBestBidPrice() {
        if (orderbookUnits != null && !orderbookUnits.isEmpty()) {
            return orderbookUnits.get(0).getBidPrice();
        }
        return BigDecimal.ZERO;
    }

    /**
     * 스프레드 계산 (매도호가 - 매수호가) / 매수호가
     */
    public double getSpreadRate() {
        BigDecimal ask = getBestAskPrice();
        BigDecimal bid = getBestBidPrice();
        if (bid.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return ask.subtract(bid).divide(bid, 6, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 상위 N개 호가의 매수 잔량 합계
     */
    public BigDecimal getTopBidSize(int count) {
        if (orderbookUnits == null) return BigDecimal.ZERO;
        return orderbookUnits.stream()
                .limit(count)
                .map(OrderbookUnit::getBidSize)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 상위 N개 호가의 매도 잔량 합계
     */
    public BigDecimal getTopAskSize(int count) {
        if (orderbookUnits == null) return BigDecimal.ZERO;
        return orderbookUnits.stream()
                .limit(count)
                .map(OrderbookUnit::getAskSize)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 호가 불균형 비율 (매수잔량 / 매도잔량)
     */
    public double getVolumeRatio(int count) {
        BigDecimal bidSize = getTopBidSize(count);
        BigDecimal askSize = getTopAskSize(count);
        if (askSize.compareTo(BigDecimal.ZERO) == 0) {
            return 1.0;
        }
        return bidSize.divide(askSize, 4, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 특정 인덱스의 매도호가
     */
    public double getAskPrice(int index) {
        if (orderbookUnits != null && orderbookUnits.size() > index) {
            return orderbookUnits.get(index).getAskPrice().doubleValue();
        }
        return 0.0;
    }

    /**
     * 특정 인덱스의 매수호가
     */
    public double getBidPrice(int index) {
        if (orderbookUnits != null && orderbookUnits.size() > index) {
            return orderbookUnits.get(index).getBidPrice().doubleValue();
        }
        return 0.0;
    }

    /**
     * 특정 인덱스의 매도 잔량
     */
    public double getAskSize(int index) {
        if (orderbookUnits != null && orderbookUnits.size() > index) {
            return orderbookUnits.get(index).getAskSize().doubleValue();
        }
        return 0.0;
    }

    /**
     * 특정 인덱스의 매수 잔량
     */
    public double getBidSize(int index) {
        if (orderbookUnits != null && orderbookUnits.size() > index) {
            return orderbookUnits.get(index).getBidSize().doubleValue();
        }
        return 0.0;
    }
}