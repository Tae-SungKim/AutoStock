package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 골든 크로스 / 데드 크로스 전략
 * - 단기 이동평균선이 장기 이동평균선을 상향 돌파: 매수 (골든 크로스)
 * - 단기 이동평균선이 장기 이동평균선을 하향 돌파: 매도 (데드 크로스)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoldenCrossStrategy implements TradingStrategy {

    private final TechnicalIndicator indicator;

    private static final int SHORT_PERIOD = 5;   // 단기 이동평균 기간
    private static final int LONG_PERIOD = 20;   // 장기 이동평균 기간

    @Override
    public int analyze(List<Candle> candles) {
        try {
            if (candles.size() < LONG_PERIOD + 1) {
                log.warn("[골든크로스 전략] 데이터 부족");
                return 0;
            }

            // 현재 이동평균
            double currentShortMA = indicator.calculateSMA(candles, SHORT_PERIOD);
            double currentLongMA = indicator.calculateSMA(candles, LONG_PERIOD);

            // 이전 이동평균 (한 캔들 이전)
            List<Candle> prevCandles = candles.subList(1, candles.size());
            double prevShortMA = indicator.calculateSMA(prevCandles, SHORT_PERIOD);
            double prevLongMA = indicator.calculateSMA(prevCandles, LONG_PERIOD);

            log.info("[골든크로스 전략] 단기MA: {}, 장기MA: {}",
                    String.format("%.0f", currentShortMA),
                    String.format("%.0f", currentLongMA));

            // 골든 크로스: 단기선이 장기선을 상향 돌파
            if (prevShortMA <= prevLongMA && currentShortMA > currentLongMA) {
                log.info("[골든크로스 전략] 골든크로스 발생 - 매수 신호");
                return 1;
            }

            // 데드 크로스: 단기선이 장기선을 하향 돌파
            if (prevShortMA >= prevLongMA && currentShortMA < currentLongMA) {
                log.info("[골든크로스 전략] 데드크로스 발생 - 매도 신호");
                return -1;
            }

            return 0;
        } catch (Exception e) {
            log.error("[골든크로스 전략] 분석 실패: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public String getStrategyName() {
        return "GoldenCrossStrategy";
    }
}
