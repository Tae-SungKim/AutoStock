package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 추세 추종 전략 (Trend Following)
 * - 상승 추세에서만 매수, 하락 추세에서는 매도
 * - EMA 정배열 + RSI 조합
 *
 * 조건:
 * - 매수: EMA7 > EMA25 > EMA99 (정배열) + RSI 40~60 구간 (과열 아님)
 * - 매도: EMA7 < EMA25 (단기 하락 전환) 또는 RSI > 75
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendFollowingStrategy implements TradingStrategy {

    private final TechnicalIndicator indicator;

    @Override
    public int analyze(List<Candle> candles) {
        try {
            if (candles.size() < 100) {
                return 0;
            }

            // EMA 계산
            double ema7 = calculateEMA(candles, 7);
            double ema25 = calculateEMA(candles, 25);
            double ema99 = calculateEMA(candles, 99);

            // RSI 계산
            double rsi = indicator.calculateRSI(candles, 14);

            // 현재가
            double currentPrice = candles.get(0).getTradePrice().doubleValue();

            // 추세 판단
            boolean uptrend = ema7 > ema25 && ema25 > ema99;  // 정배열 (상승 추세)
            boolean downtrend = ema7 < ema25 && ema25 < ema99;  // 역배열 (하락 추세)

            // 가격이 EMA 위에 있는지
            boolean priceAboveEMA = currentPrice > ema25;

            log.debug("[추세추종] EMA7: {}, EMA25: {}, EMA99: {}, RSI: {}, 추세: {}",
                    String.format("%.0f", ema7),
                    String.format("%.0f", ema25),
                    String.format("%.0f", ema99),
                    String.format("%.1f", rsi),
                    uptrend ? "상승" : downtrend ? "하락" : "횡보");

            // 매수 조건: 상승 추세 + RSI 과열 아님 + 가격이 EMA25 위
            if (uptrend && rsi > 35 && rsi < 65 && priceAboveEMA) {
                // 추가 확인: 최근 캔들이 양봉인지
                boolean recentBullish = candles.get(0).getTradePrice().compareTo(candles.get(0).getOpeningPrice()) > 0;
                if (recentBullish) {
                    log.info("[추세추종] 매수 신호 - 상승추세 + RSI: {}", String.format("%.1f", rsi));
                    return 1;
                }
            }

            // 매도 조건: 하락 추세 전환 또는 RSI 과열
            if (downtrend || rsi > 75 || (ema7 < ema25 && !priceAboveEMA)) {
                log.info("[추세추종] 매도 신호 - 추세: {}, RSI: {}",
                        downtrend ? "하락" : "상승약화", String.format("%.1f", rsi));
                return -1;
            }

            return 0;
        } catch (Exception e) {
            log.error("[추세추종] 분석 실패: {}", e.getMessage());
            return 0;
        }
    }

    private double calculateEMA(List<Candle> candles, int period) {
        if (candles.size() < period) {
            return candles.get(0).getTradePrice().doubleValue();
        }

        double multiplier = 2.0 / (period + 1);

        // 초기 SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(candles.size() - 1 - i).getTradePrice().doubleValue();
        }
        double ema = sum / period;

        // EMA 계산
        for (int i = candles.size() - period - 1; i >= 0; i--) {
            ema = (candles.get(i).getTradePrice().doubleValue() - ema) * multiplier + ema;
        }

        return ema;
    }

    @Override
    public String getStrategyName() {
        return "TrendFollowingStrategy";
    }
}
