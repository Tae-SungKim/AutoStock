package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 변동성 돌파 전략 (Larry Williams)
 * - 전일 고가-저가 범위의 K% 만큼 당일 시가에서 상승하면 매수
 * - 다음 캔들 시가에 매도 (단타)
 *
 * 코인 시장에서 검증된 전략 중 하나
 */
@Slf4j
@Component
public class VolatilityBreakoutStrategy implements TradingStrategy {

    private static final double K = 0.5;  // 변동성 계수 (0.4 ~ 0.6 권장)
    private boolean positionOpen = false;

    @Override
    public int analyze(List<Candle> candles) {
        try {
            if (candles.size() < 3) {
                return 0;
            }

            // 최신 캔들 (현재)
            Candle current = candles.get(0);
            // 이전 캔들
            Candle prev = candles.get(1);
            // 전전 캔들 (범위 계산용)
            Candle prevPrev = candles.get(2);

            // 이전 캔들의 변동폭
            double range = prev.getHighPrice().doubleValue() - prev.getLowPrice().doubleValue();

            // 목표가: 현재 캔들 시가 + (이전 변동폭 * K)
            double targetPrice = current.getOpeningPrice().doubleValue() + (range * K);

            // 현재가
            double currentPrice = current.getTradePrice().doubleValue();

            log.debug("[변동성돌파] 목표가: {}, 현재가: {}, 범위: {}",
                    String.format("%.0f", targetPrice),
                    String.format("%.0f", currentPrice),
                    String.format("%.0f", range));

            // 포지션이 없고, 현재가가 목표가를 돌파하면 매수
            if (!positionOpen && currentPrice > targetPrice && range > 0) {
                // 거래량 확인 (평균 대비 증가 시에만)
                double avgVolume = calculateAvgVolume(candles, 20);
                if (current.getCandleAccTradeVolume().doubleValue() > avgVolume * 0.8) {
                    log.info("[변동성돌파] 돌파 발생! 목표가: {}, 현재가: {}",
                            String.format("%.0f", targetPrice),
                            String.format("%.0f", currentPrice));
                    positionOpen = true;
                    return 1;  // 매수
                }
            }

            // 포지션이 있으면 다음 캔들에서 매도 (또는 일정 시간 후)
            if (positionOpen) {
                positionOpen = false;
                return -1;  // 매도
            }

            return 0;
        } catch (Exception e) {
            log.error("[변동성돌파] 분석 실패: {}", e.getMessage());
            return 0;
        }
    }

    private double calculateAvgVolume(List<Candle> candles, int period) {
        double sum = 0;
        int count = Math.min(period, candles.size());
        for (int i = 0; i < count; i++) {
            sum += candles.get(i).getCandleAccTradeVolume().doubleValue();
        }
        return sum / count;
    }

    @Override
    public String getStrategyName() {
        return "VolatilityBreakoutStrategy";
    }
}
