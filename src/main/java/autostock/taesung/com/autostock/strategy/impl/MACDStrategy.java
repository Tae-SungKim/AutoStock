package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MACD 전략
 * - MACD선이 시그널선을 상향 돌파 + 히스토그램 양수 전환 = 매수
 * - MACD선이 시그널선을 하향 돌파 + 히스토그램 음수 전환 = 매도
 * - 0선 위/아래 위치도 고려
 */
@Slf4j
@Component
public class MACDStrategy implements TradingStrategy {

    private static final int FAST_PERIOD = 12;
    private static final int SLOW_PERIOD = 26;
    private static final int SIGNAL_PERIOD = 9;

    @Override
    public int analyze(List<Candle> candles) {
        try {
            if (candles.size() < SLOW_PERIOD + SIGNAL_PERIOD) {
                return 0;
            }

            // 현재 MACD 계산
            double[] currentMACD = calculateMACD(candles, 0);
            double macd = currentMACD[0];
            double signal = currentMACD[1];
            double histogram = currentMACD[2];

            // 이전 MACD 계산
            List<Candle> prevCandles = candles.subList(1, candles.size());
            double[] prevMACD = calculateMACD(prevCandles, 0);
            double prevMacd = prevMACD[0];
            double prevSignal = prevMACD[1];
            double prevHistogram = prevMACD[2];

            log.debug("[MACD] MACD: {}, Signal: {}, Histogram: {}",
                    String.format("%.2f", macd),
                    String.format("%.2f", signal),
                    String.format("%.2f", histogram));

            // 골든크로스: MACD가 시그널선을 상향 돌파
            boolean goldenCross = prevMacd <= prevSignal && macd > signal;
            // 데드크로스: MACD가 시그널선을 하향 돌파
            boolean deadCross = prevMacd >= prevSignal && macd < signal;

            // 히스토그램 방향 전환
            boolean histogramTurningUp = prevHistogram < 0 && histogram > prevHistogram;
            boolean histogramTurningDown = prevHistogram > 0 && histogram < prevHistogram;

            // 매수 신호: 골든크로스 또는 (MACD가 시그널 위에 있고 히스토그램 상승 전환)
            if (goldenCross || (macd > signal && histogramTurningUp && histogram > -50)) {
                log.info("[MACD] 매수 신호 - 골든크로스: {}, 히스토그램 상승: {}", goldenCross, histogramTurningUp);
                return 1;
            }

            // 매도 신호: 데드크로스 또는 (MACD가 시그널 아래에 있고 히스토그램 하락 전환)
            if (deadCross || (macd < signal && histogramTurningDown && histogram < 50)) {
                log.info("[MACD] 매도 신호 - 데드크로스: {}, 히스토그램 하락: {}", deadCross, histogramTurningDown);
                return -1;
            }

            return 0;
        } catch (Exception e) {
            log.error("[MACD] 분석 실패: {}", e.getMessage());
            return 0;
        }
    }

    private double[] calculateMACD(List<Candle> candles, int offset) {
        double ema12 = calculateEMA(candles, FAST_PERIOD, offset);
        double ema26 = calculateEMA(candles, SLOW_PERIOD, offset);
        double macd = ema12 - ema26;

        // 시그널선: MACD의 EMA
        double signal = calculateMACDSignal(candles, offset);
        double histogram = macd - signal;

        return new double[]{macd, signal, histogram};
    }

    private double calculateEMA(List<Candle> candles, int period, int offset) {
        if (candles.size() < period + offset) {
            return 0;
        }

        double multiplier = 2.0 / (period + 1);

        // 초기 SMA
        double sum = 0;
        for (int i = offset; i < period + offset; i++) {
            sum += candles.get(i).getTradePrice().doubleValue();
        }
        double ema = sum / period;

        // EMA 계산 (최신 방향으로)
        for (int i = period + offset - 1; i >= offset; i--) {
            ema = (candles.get(i).getTradePrice().doubleValue() - ema) * multiplier + ema;
        }

        return ema;
    }

    private double calculateMACDSignal(List<Candle> candles, int offset) {
        // 간소화된 시그널 계산
        double sum = 0;
        for (int i = 0; i < SIGNAL_PERIOD && i + offset < candles.size() - SLOW_PERIOD; i++) {
            double ema12 = calculateEMA(candles.subList(i + offset, candles.size()), FAST_PERIOD, 0);
            double ema26 = calculateEMA(candles.subList(i + offset, candles.size()), SLOW_PERIOD, 0);
            sum += (ema12 - ema26);
        }
        return sum / SIGNAL_PERIOD;
    }

    @Override
    public String getStrategyName() {
        return "MACDStrategy";
    }
}
