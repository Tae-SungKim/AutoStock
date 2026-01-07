package autostock.taesung.com.autostock.strategy;

import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TechnicalIndicator {

    /**
     * 단순 이동평균선 (SMA) 계산
     * @param candles 캔들 데이터 (최신순)
     * @param period 기간
     * @return SMA 값
     */
    public double calculateSMA(List<Candle> candles, int period) {
        if (candles.size() < period) {
            throw new IllegalArgumentException("캔들 데이터가 부족합니다.");
        }

        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getTradePrice().doubleValue();
        }
        return sum / period;
    }

    /**
     * 지수 이동평균선 (EMA) 계산
     * @param candles 캔들 데이터 (최신순)
     * @param period 기간
     * @return EMA 값
     */
    public double calculateEMA(List<Candle> candles, int period) {
        if (candles.size() < period) {
            throw new IllegalArgumentException("캔들 데이터가 부족합니다.");
        }

        double multiplier = 2.0 / (period + 1);

        // 초기 EMA는 SMA로 계산
        double ema = 0;
        for (int i = period - 1; i >= 0; i--) {
            if (i == period - 1) {
                // 가장 오래된 데이터부터 시작
                double sum = 0;
                for (int j = i; j < candles.size() && j < i + period; j++) {
                    sum += candles.get(j).getTradePrice().doubleValue();
                }
                ema = sum / period;
            } else {
                ema = (candles.get(i).getTradePrice().doubleValue() - ema) * multiplier + ema;
            }
        }
        return ema;
    }

    /**
     * RSI (Relative Strength Index) 계산
     * @param candles 캔들 데이터 (최신순)
     * @param period 기간 (기본 14)
     * @return RSI 값 (0-100)
     */
    public double calculateRSI(List<Candle> candles, int period) {
        if (candles.size() < period + 1) {
            throw new IllegalArgumentException("캔들 데이터가 부족합니다.");
        }

        double gains = 0;
        double losses = 0;

        // 가격 변화 계산 (역순으로 - 최신 데이터가 앞에 있으므로)
        for (int i = 0; i < period; i++) {
            double change = candles.get(i).getTradePrice().doubleValue() - candles.get(i + 1).getTradePrice().doubleValue();
            if (change > 0) {
                gains += change;
            } else {
                losses += Math.abs(change);
            }
        }

        double avgGain = gains / period;
        double avgLoss = losses / period;

        if (avgLoss == 0) {
            return 100;
        }

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * 볼린저 밴드 계산
     * @param candles 캔들 데이터 (최신순)
     * @param period 기간 (기본 20)
     * @param stdDevMultiplier 표준편차 배수 (기본 2)
     * @return [중간밴드, 상단밴드, 하단밴드]
     */
    public double[] calculateBollingerBands(List<Candle> candles, int period, double stdDevMultiplier) {
        if (candles.size() < period) {
            throw new IllegalArgumentException("캔들 데이터가 부족합니다.");
        }

        // 중간밴드 (SMA)
        double sma = calculateSMA(candles, period);

        // 표준편차 계산
        double sumSquaredDiff = 0;
        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getTradePrice().doubleValue() - sma;
            sumSquaredDiff += diff * diff;
        }
        double stdDev = Math.sqrt(sumSquaredDiff / period);

        double upperBand = sma + (stdDevMultiplier * stdDev);
        double lowerBand = sma - (stdDevMultiplier * stdDev);

        return new double[]{sma, upperBand, lowerBand};
    }

    /**
     * MACD 계산
     * @param candles 캔들 데이터 (최신순)
     * @return [MACD선, 시그널선, 히스토그램]
     */
    public double[] calculateMACD(List<Candle> candles) {
        if (candles.size() < 26) {
            throw new IllegalArgumentException("캔들 데이터가 부족합니다.");
        }

        double ema12 = calculateEMA(candles, 12);
        double ema26 = calculateEMA(candles, 26);
        double macd = ema12 - ema26;

        // 시그널선 (MACD의 9일 EMA) - 간소화된 계산
        double signal = macd * 0.2; // 근사값
        double histogram = macd - signal;

        return new double[]{macd, signal, histogram};
    }

    /**
     * 거래량 이동평균 계산
     */
    public double calculateVolumeMA(List<Candle> candles, int period) {
        if (candles.size() < period) {
            throw new IllegalArgumentException("캔들 데이터가 부족합니다.");
        }

        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getCandleAccTradeVolume().doubleValue();
        }
        return sum / period;
    }
}
