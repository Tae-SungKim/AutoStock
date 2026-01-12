package autostock.taesung.com.autostock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 백테스트용 포지션 정보
 * - 실제 매매에서 DB(TradeHistory)에서 조회하는 정보를 대체
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestPosition {

    /**
     * 포지션 보유 여부
     */
    private boolean holding;

    /**
     * 매수 가격
     */
    private Double buyPrice;

    /**
     * 매수 후 최고가 (트레일링 스탑용)
     */
    private Double highestPrice;

    /**
     * 목표가 (상단밴드 등)
     */
    private Double targetPrice;

    /**
     * 코인 보유 수량
     */
    private Double coinBalance;

    /**
     * 종료 사유
     */
    private ExitReason exitReason;

    /**
     * 빈 포지션 (미보유 상태)
     */
    public static BacktestPosition empty() {
        return BacktestPosition.builder()
                .holding(false)
                .buyPrice(null)
                .highestPrice(null)
                .targetPrice(null)
                .coinBalance(0.0)
                .exitReason(null)
                .build();
    }

    /**
     * 매수 포지션 생성
     */
    public static BacktestPosition buy(double buyPrice, double coinBalance, Double targetPrice) {
        return BacktestPosition.builder()
                .holding(true)
                .buyPrice(buyPrice)
                .highestPrice(buyPrice)
                .targetPrice(targetPrice)
                .coinBalance(coinBalance)
                .build();
    }

    /**
     * 최고가 갱신
     */
    public void updateHighestPrice(double currentPrice) {
        if (holding && currentPrice > (highestPrice != null ? highestPrice : 0)) {
            this.highestPrice = currentPrice;
        }
    }
}