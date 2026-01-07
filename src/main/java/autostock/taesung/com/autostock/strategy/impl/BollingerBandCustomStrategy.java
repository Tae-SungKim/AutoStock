package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 볼린저 밴드 급등 전략
 * - 스퀴즈(수축) → 확장 돌파 시 매수
 * - 손절/익절/트레일링 스탑 관리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BollingerBandCustomStrategy implements TradingStrategy {

    private final TechnicalIndicator indicator;

    // 볼린저 밴드 설정
    private static final int PERIOD = 15;
    private static final double STD_DEV_MULTIPLIER = 1.2;

    // 손절/익절 설정
    private static final double STOP_LOSS_RATE = 0.008;      // -0.8%
    private static final double TAKE_PROFIT_RATE = 0.015;    // +1.5% (1차 목표)
    private static final double TRAILING_RATE = 0.012;       // 1.2% 트레일링

    // 최소 거래량 설정 (KRW 기준)
    private static final double MIN_TRADE_VOLUME_KRW = 500_000_000;  // 5억원 이상

    // 마켓별 포지션 상태 관리
    private final Map<String, PositionState> positionByMarket = new ConcurrentHashMap<>();

    /**
     * 포지션 상태
     */
    @Data
    private static class PositionState {
        Double entryPrice;      // 진입가
        Double stopLossPrice;   // 손절가
        Double targetPrice;     // 목표가
        Double highestPrice;    // 최고가 (트레일링용)
        Double trailingStop;    // 트레일링 스탑가

        void clear() {
            entryPrice = null;
            stopLossPrice = null;
            targetPrice = null;
            highestPrice = null;
            trailingStop = null;
        }

        boolean hasPosition() {
            return entryPrice != null;
        }
    }

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {
        try {
            if (candles.size() < PERIOD + 1) {
                return 0;
            }

            Candle curr = candles.get(0);
            double currentPrice = curr.getTradePrice().doubleValue();
            String priceFormat = currentPrice < 100 ? "%.4f" : "%.0f";

            // 포지션 상태 가져오기
            PositionState position = positionByMarket.computeIfAbsent(market, k -> new PositionState());

            // === 포지션이 있으면 청산 조건 체크 ===
            if (position.hasPosition()) {
                return checkExitConditions(market, position, currentPrice, priceFormat);
            }

            // === 포지션이 없으면 진입 조건 체크 ===
            return checkEntryConditions(market, candles, position, currentPrice, priceFormat);

        } catch (Exception e) {
            log.error("[{}][볼린저밴드] 분석 실패: {}", market, e.getMessage());
            return 0;
        }
    }

    /**
     * 청산 조건 체크 (손절/익절/트레일링)
     */
    private int checkExitConditions(String market, PositionState position, double currentPrice, String priceFormat) {
        // 최고가 갱신
        if (currentPrice > position.highestPrice) {
            position.highestPrice = currentPrice;
            // 트레일링 스탑 갱신: 최고가에서 TRAILING_RATE만큼 아래
            position.trailingStop = position.highestPrice * (1 - TRAILING_RATE);

            log.info("[{}][볼린저밴드] 최고가 갱신: {}, 트레일링스탑: {}",
                    market,
                    String.format(priceFormat, position.highestPrice),
                    String.format(priceFormat, position.trailingStop));
        }

        double profitRate = (currentPrice - position.entryPrice) / position.entryPrice * 100;

        // 1. 손절 체크
        if (currentPrice <= position.stopLossPrice) {
            log.info("[{}][볼린저밴드][손절] 현재가: {} <= 손절가: {} (수익률: {}%)",
                    market,
                    String.format(priceFormat, currentPrice),
                    String.format(priceFormat, position.stopLossPrice),
                    String.format("%.2f", profitRate));
            return -1;
        }

        // 2. 1차 목표가 도달 후 트레일링 스탑 체크
        if (currentPrice >= position.targetPrice && position.trailingStop != null) {
            if (currentPrice <= position.trailingStop) {
                log.info("[{}][볼린저밴드][트레일링스탑] 현재가: {} <= 트레일링: {} (수익률: {}%)",
                        market,
                        String.format(priceFormat, currentPrice),
                        String.format(priceFormat, position.trailingStop),
                        String.format("%.2f", profitRate));
                return -1;
            }
        }

        // 3. 급락 시 익절 (1차 목표 달성 후 진입가 근처까지 하락)
        if (profitRate >= TAKE_PROFIT_RATE * 100 * 0.5) { // 목표의 50% 이상 수익 중
            if (currentPrice < position.highestPrice * 0.98) { // 최고점 대비 2% 하락
                log.info("[{}][볼린저밴드][익절] 최고점 대비 하락 - 현재가: {}, 최고가: {} (수익률: {}%)",
                        market,
                        String.format(priceFormat, currentPrice),
                        String.format(priceFormat, position.highestPrice),
                        String.format("%.2f", profitRate));
                return -1;
            }
        }

        log.info("[{}][볼린저밴드][홀딩] 진입: {}, 현재: {}, 손절: {}, 목표: {}, 수익률: {}%",
                market,
                String.format(priceFormat, position.entryPrice),
                String.format(priceFormat, currentPrice),
                String.format(priceFormat, position.stopLossPrice),
                String.format(priceFormat, position.targetPrice),
                String.format("%.2f", profitRate));

        return 0;
    }

    /**
     * 진입 조건 체크 (스퀴즈 돌파)
     */
    private int checkEntryConditions(String market, List<Candle> candles, PositionState position,
                                     double currentPrice, String priceFormat) {
        // === 거래량 하위 코인 필터링 ===
        double totalTradeVolume = candles.subList(0, PERIOD).stream()
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .sum();

        if (totalTradeVolume < MIN_TRADE_VOLUME_KRW) {
            log.debug("[{}][볼린저밴드] 거래량 부족 - 스킵 ({}원 < {}원)",
                    market,
                    String.format("%.0f", totalTradeVolume),
                    String.format("%.0f", MIN_TRADE_VOLUME_KRW));
            return 0;
        }

        // === 볼린저 밴드 계산 ===
        double[] currBands = indicator.calculateBollingerBands(candles.subList(0, PERIOD), PERIOD, STD_DEV_MULTIPLIER);
        double[] prevBands = indicator.calculateBollingerBands(candles.subList(1, PERIOD + 1), PERIOD, STD_DEV_MULTIPLIER);

        double currUpper = currBands[1];
        double prevWidthPct = calculateBandWidthPercent(prevBands);
        double currWidthPct = calculateBandWidthPercent(currBands);

        Candle curr = candles.get(0);

        log.debug("[{}][볼린저밴드] 현재가: {}, 상단: {}, 이전폭: {}%, 현재폭: {}%",
                market,
                String.format(priceFormat, currentPrice),
                String.format(priceFormat, currUpper),
                String.format("%.2f", prevWidthPct),
                String.format("%.2f", currWidthPct));

        // === 거래량 스파이크 체크 ===
        double avgVolume = candles.subList(0, PERIOD).stream()
                .mapToDouble(c -> c.getCandleAccTradeVolume().doubleValue())
                .average()
                .orElse(0);
        boolean isVolumeSpike = curr.getCandleAccTradeVolume().doubleValue() >= avgVolume * 1.8;

        // === 강한 양봉 체크 ===
        double body = Math.abs(curr.getTradePrice().doubleValue() - curr.getOpeningPrice().doubleValue());
        double range = curr.getHighPrice().doubleValue() - curr.getLowPrice().doubleValue();
        boolean isStrongCandle = range > 0 && (body / range) >= 0.65;

        // === 스퀴즈 → 확장 돌파 조건 ===
        boolean isSqueezeBreakout = prevWidthPct <= 1.2
                && currWidthPct >= 1.5
                && currWidthPct > prevWidthPct * 1.3;

        boolean isUpperBreak = currentPrice > currUpper;

        // === 매수 조건 ===
        if (isSqueezeBreakout && isUpperBreak && isVolumeSpike && isStrongCandle) {
            // 포지션 설정
            position.entryPrice = currentPrice;
            position.highestPrice = currentPrice;
            position.stopLossPrice = currentPrice * (1 - STOP_LOSS_RATE);
            position.targetPrice = currentPrice * (1 + TAKE_PROFIT_RATE);
            position.trailingStop = null; // 목표가 도달 전에는 트레일링 없음

            log.info("[{}][볼린저밴드][매수] 스퀴즈 돌파! 진입가: {}, 손절가: {}, 목표가: {}",
                    market,
                    String.format(priceFormat, position.entryPrice),
                    String.format(priceFormat, position.stopLossPrice),
                    String.format(priceFormat, position.targetPrice));

            return 1;
        }

        return 0;
    }

    /**
     * 볼린저 밴드 폭 (%) 계산
     */
    private double calculateBandWidthPercent(double[] bands) {
        double middle = bands[0];
        double upper = bands[1];
        double lower = bands[2];
        return (upper - lower) / middle * 100;
    }

    @Override
    public String getStrategyName() {
        return "BollingerBandCustomStrategy";
    }

    @Override
    public Double getTargetPrice() {
        return null;
    }

    @Override
    public Double getTargetPrice(String market) {
        PositionState position = positionByMarket.get(market);
        return position != null ? position.targetPrice : null;
    }

    @Override
    public Double getStopLossPrice(String market) {
        PositionState position = positionByMarket.get(market);
        return position != null ? position.stopLossPrice : null;
    }

    @Override
    public Double getEntryPrice(String market) {
        PositionState position = positionByMarket.get(market);
        return position != null ? position.entryPrice : null;
    }

    @Override
    public void clearPosition(String market) {
        PositionState position = positionByMarket.get(market);
        if (position != null) {
            log.info("[{}][볼린저밴드] 포지션 청산", market);
            position.clear();
        }
    }
}