package autostock.taesung.com.autostock.strategy.config;


/**
 * 시간대별 유동성 / 하드리미트 설정
 */
public class TimeWindowConfig {

    private final double minVolume;          // 🔥 최소 거래량
    private final double volumeFactor;       // 평균 대비 배수
    private final double tradeAmountFactor;  // 거래대금 보조

    public TimeWindowConfig(double minVolume,
                            double volumeFactor,
                            double tradeAmountFactor) {
        this.minVolume = minVolume;
        this.volumeFactor = volumeFactor;
        this.tradeAmountFactor = tradeAmountFactor;
    }

    public double getMinVolume() {
        return minVolume;
    }

    public double getVolumeFactor() {
        return volumeFactor;
    }

    public double getTradeAmountFactor() {
        return tradeAmountFactor;
    }
}
