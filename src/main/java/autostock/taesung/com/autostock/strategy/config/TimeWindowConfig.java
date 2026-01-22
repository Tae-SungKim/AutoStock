package autostock.taesung.com.autostock.strategy.config;


/**
 * 시간대별 유동성 / 하드리미트 설정
 */
public class TimeWindowConfig {

    // 🔒 하드 최소 거래대금 (절대 기준)
    private final double hardMinTradeAmount;

    // 📊 평균 대비 배율
    private final double liquidityFactor;

    public TimeWindowConfig(double hardMinTradeAmount, double liquidityFactor) {
        this.hardMinTradeAmount = hardMinTradeAmount;
        this.liquidityFactor = liquidityFactor;
    }

    public double getHardMinTradeAmount() {
        return hardMinTradeAmount;
    }

    public double getLiquidityFactor() {
        return liquidityFactor;
    }
}
