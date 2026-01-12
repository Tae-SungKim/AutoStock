package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.backtest.dto.ExitReason;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 급등 단타 전략 (Momentum Scalping)
 *
 * 진입 조건:
 * 1. 최근 N개 캔들 대비 현재 캔들 거래량 급증 (2배 이상)
 * 2. 최근 캔들 가격 상승률 일정% 이상
 * 3. 연속 양봉 확인
 *
 * 청산 조건:
 * - 빠른 익절 (2~3%)
 * - 빠른 손절 (-1.5%)
 * - 음봉 출현 시 청산
 */
@Slf4j
@Component
public class MomentumScalpingStrategy implements TradingStrategy {

    private static final int VOLUME_LOOKBACK = 20;      // 거래량 비교 기간
    private static final double VOLUME_MULTIPLIER = 2.0; // 거래량 배수 조건
    private static final double PRICE_SURGE_RATE = 0.015; // 가격 급등 기준 (1.5%)
    private static final int CONSECUTIVE_BULLISH = 2;    // 연속 양봉 수

    private boolean inPosition = false;
    private double entryPrice = 0;

    @Override
    public int analyze(List<Candle> candles) {
        try {
            if (candles.size() < VOLUME_LOOKBACK + 5) {
                return 0;
            }

            Candle current = candles.get(0);
            Candle prev = candles.get(1);
            double currentPrice = current.getTradePrice().doubleValue();

            // 포지션 보유 중일 때 청산 조건 체크
            if (inPosition) {
                double profitRate = (currentPrice - entryPrice) / entryPrice;

                // 음봉 출현 시 청산
                boolean isBearish = current.getTradePrice().compareTo(current.getOpeningPrice()) < 0;

                // 익절 또는 손절 또는 음봉
                if (profitRate >= 0.025 || profitRate <= -0.015 || isBearish) {
                    log.info("[급등단타] 청산 - 수익률: {}%, 음봉: {}",
                            String.format("%.2f", profitRate * 100), isBearish);
                    inPosition = false;
                    entryPrice = 0;
                    return -1;
                }
                return 0;
            }

            // 평균 거래량 계산
            double avgVolume = 0;
            for (int i = 1; i <= VOLUME_LOOKBACK; i++) {
                avgVolume += candles.get(i).getCandleAccTradeVolume().doubleValue();
            }
            avgVolume /= VOLUME_LOOKBACK;

            // 현재 거래량
            double currentVolume = current.getCandleAccTradeVolume().doubleValue();

            // 거래량 급증 여부
            boolean volumeSurge = currentVolume > avgVolume * VOLUME_MULTIPLIER;

            // 가격 급등 여부 (이전 캔들 대비)
            double priceChange = (currentPrice - prev.getTradePrice().doubleValue()) / prev.getTradePrice().doubleValue();
            boolean priceSurge = priceChange >= PRICE_SURGE_RATE;

            // 연속 양봉 확인
            int bullishCount = 0;
            for (int i = 0; i < CONSECUTIVE_BULLISH + 1; i++) {
                Candle c = candles.get(i);
                if (c.getTradePrice().compareTo(c.getOpeningPrice()) > 0) {
                    bullishCount++;
                }
            }
            boolean consecutiveBullish = bullishCount >= CONSECUTIVE_BULLISH;

            // 고가 대비 현재가 위치 (고점 부근에서 매수 방지)
            double highLowRange = current.getHighPrice().doubleValue() - current.getLowPrice().doubleValue();
            double pricePosition = highLowRange > 0 ?
                    (currentPrice - current.getLowPrice().doubleValue()) / highLowRange : 0.5;

            log.debug("[급등단타] 거래량비: {}, 가격변화: {}%, 연속양봉: {}, 가격위치: {}",
                    String.format("%.1f", currentVolume / avgVolume),
                    String.format("%.2f", priceChange * 100),
                    bullishCount,
                    String.format("%.2f", pricePosition));

            // 매수 조건: 거래량 급증 + 가격 급등 + 연속 양봉 + 고점 부근 아님
            if (volumeSurge && priceSurge && consecutiveBullish && pricePosition < 0.85) {
                log.info("[급등단타] 매수 신호 - 거래량 {}배, 상승률 {}%",
                        String.format("%.1f", currentVolume / avgVolume),
                        String.format("%.2f", priceChange * 100));
                inPosition = true;
                entryPrice = currentPrice;
                return 1;
            }

            return 0;
        } catch (Exception e) {
            log.error("[급등단타] 분석 실패: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int analyzeForBacktest(String market, List<Candle> candles, BacktestPosition position) {
        if (candles.size() < VOLUME_LOOKBACK + 5) return 0;

        Candle current = candles.get(0);
        double currentPrice = current.getTradePrice().doubleValue();

        if (position != null && position.isHolding()) {
            double buyPrice = position.getBuyPrice();
            double profitRate = (currentPrice - buyPrice) / buyPrice;

            boolean isBearish = current.getTradePrice().compareTo(current.getOpeningPrice()) < 0;

            if (profitRate >= 0.025) return exit(ExitReason.TAKE_PROFIT);
            if (profitRate <= -0.015) return exit(ExitReason.STOP_LOSS_FIXED);
            if (isBearish) return exit(ExitReason.SIGNAL_INVALID); // 상승 추세 꺾임

            return 0;
        }

        return analyze(market, candles);
    }

    @Override
    public String getStrategyName() {
        return "MomentumScalpingStrategy";
    }
}
