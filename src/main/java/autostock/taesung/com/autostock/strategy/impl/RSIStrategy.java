package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RSI 기반 매매 전략 (개선판)
 * - RSI 25 이하에서 상승 반전: 매수 신호
 * - RSI 75 이상에서 하락 반전: 매도 신호
 * - RSI 방향성 + 가격 모멘텀 확인
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RSIStrategy implements TradingStrategy {

    private final TechnicalIndicator indicator;

    private static final int RSI_PERIOD = 14;
    private static final double OVERSOLD_THRESHOLD = 25.0;   // 과매도 기준 (더 엄격하게)
    private static final double OVERBOUGHT_THRESHOLD = 75.0; // 과매수 기준 (더 엄격하게)

    @Override
    public int analyze(List<Candle> candles) {
        try {
            if (candles.size() < RSI_PERIOD + 2) {
                return 0;
            }

            // 현재 RSI
            double currentRsi = indicator.calculateRSI(candles, RSI_PERIOD);

            // 이전 RSI (1캔들 전)
            List<Candle> prevCandles = candles.subList(1, candles.size());
            double prevRsi = indicator.calculateRSI(prevCandles, RSI_PERIOD);

            // RSI 변화량
            double rsiChange = currentRsi - prevRsi;

            // 현재 캔들이 양봉인지 (가격 상승)
            Candle current = candles.get(0);
            boolean isBullish = current.getTradePrice().compareTo(current.getOpeningPrice()) > 0;
            boolean isBearish = current.getTradePrice().compareTo(current.getOpeningPrice()) < 0;

            log.debug("[RSI 전략] RSI: {} (변화: {}), 양봉: {}",
                    String.format("%.2f", currentRsi),
                    String.format("%.2f", rsiChange),
                    isBullish);

            // 매수: 과매도 구간에서 RSI가 상승 반전 + 양봉
            if (currentRsi <= OVERSOLD_THRESHOLD + 10 && prevRsi <= OVERSOLD_THRESHOLD && rsiChange > 0 && isBullish) {
                log.info("[RSI 전략] 과매도 반전 - 매수 신호 (RSI: {} -> {})",
                        String.format("%.1f", prevRsi), String.format("%.1f", currentRsi));
                return 1;
            }

            // 매도: 과매수 구간에서 RSI가 하락 반전 + 음봉
            if (currentRsi >= OVERBOUGHT_THRESHOLD - 10 && prevRsi >= OVERBOUGHT_THRESHOLD && rsiChange < 0 && isBearish) {
                log.info("[RSI 전략] 과매수 반전 - 매도 신호 (RSI: {} -> {})",
                        String.format("%.1f", prevRsi), String.format("%.1f", currentRsi));
                return -1;
            }

            return 0;
        } catch (Exception e) {
            log.error("[RSI 전략] 분석 실패: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public String getStrategyName() {
        return "RSIStrategy";
    }
}
