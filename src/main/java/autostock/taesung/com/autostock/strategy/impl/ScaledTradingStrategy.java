package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.realtrading.entity.Position;
import autostock.taesung.com.autostock.realtrading.entity.Position.ExitPhase;
import autostock.taesung.com.autostock.realtrading.entity.Position.PositionStatus;
import autostock.taesung.com.autostock.realtrading.repository.PositionRepository;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.service.StrategyParameterService;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 분할 매매 전략 (ScaledTradingStrategy)
 *
 * 특징:
 * - 3단계 분할 진입 (30%/30%/40%)
 * - 하락 시 물타기 (평균단가 낮추기)
 * - 50% 1차 익절 + 트레일링 스탑
 * - ATR 기반 동적 손절/익절
 *
 * 매매기법명: SCALED_TRADING
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScaledTradingStrategy implements TradingStrategy {

    private final TechnicalIndicator indicator;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyParameterService strategyParameterService;
    private final RealTradingConfig config;
    private final PositionRepository positionRepository;

    // 마켓별 상태 관리
    private final Map<String, MarketState> marketStates = new ConcurrentHashMap<>();

    // 기본 파라미터
    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;
    private static final int BOLLINGER_PERIOD = 20;
    private static final double BOLLINGER_STD = 2.0;

    @Override
    public String getStrategyName() {
        return "ScaledTradingStrategy";
    }

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {
        if (candles == null || candles.size() < 50) {
            return 0;
        }

        // 마켓 상태 초기화
        MarketState state = marketStates.computeIfAbsent(market, k -> new MarketState());

        // 현재 보유 상태 확인
        TradeHistory latestTrade = tradeHistoryRepository.findLatestByMarket(market)
                .stream().findFirst().orElse(null);
        boolean holding = latestTrade != null && latestTrade.getTradeType() == TradeHistory.TradeType.BUY;

        // 지표 계산
        double currentPrice = candles.get(0).getTradePrice().doubleValue();
        double atr = calculateATR(candles, ATR_PERIOD);
        double rsi = calculateRSI(candles, RSI_PERIOD);
        double[] bands = indicator.calculateBollingerBands(candles, BOLLINGER_PERIOD, BOLLINGER_STD);
        double middleBand = bands[0];
        double lowerBand = bands[2];

        // 거래대금 체크
        double currentVolume = candles.get(0).getCandleAccTradePrice().doubleValue();
        LocalDateTime now = getCandleTime(candles.get(0));

        double avgVolume =
                calcAvgTradePriceByMinutes(candles, now, 6);

        // ========== 보유 중일 때: 청산 로직 ==========
        if (holding) {
            return analyzeExitSignal(market, latestTrade, candles, currentPrice, atr, rsi, state);
        }

        // ========== 미보유 시: 진입 로직 ==========
        return analyzeEntrySignal(market, candles, currentPrice, atr, rsi,
                middleBand, lowerBand, currentVolume, avgVolume, state);
    }

    /**
     * 청산 신호 분석
     */
    private int analyzeExitSignal(String market, TradeHistory latestTrade, List<Candle> candles,
                                   double currentPrice, double atr, double rsi, MarketState state) {

        double buyPrice = latestTrade.getPrice().doubleValue();
        double highestPrice = latestTrade.getHighestPrice() != null
                ? latestTrade.getHighestPrice().doubleValue()
                : currentPrice;

        // 최고가 갱신
        if (currentPrice > highestPrice) {
            latestTrade.setHighestPrice(BigDecimal.valueOf(currentPrice));
            tradeHistoryRepository.save(latestTrade);
            highestPrice = currentPrice;
            state.highestPrice = highestPrice;
        }

        // 보유 시간 계산
        long holdingMinutes = Duration.between(latestTrade.getCreatedAt(), LocalDateTime.now()).toMinutes();
        double profitRate = (currentPrice - buyPrice) / buyPrice;

        // 파라미터 조회
        double stopLossRate = strategyParameterService.getDoubleParam(
                getStrategyName(), null, "stopLoss.rate", config.getMaxStopLossRate());
        double takeProfitRate = strategyParameterService.getDoubleParam(
                getStrategyName(), null, "takeProfit.rate", config.getPartialTakeProfitRate());
        double trailingStopRate = strategyParameterService.getDoubleParam(
                getStrategyName(), null, "trailing.rate", config.getTrailingStopRate());

        // 1. 손절 체크 (ATR 기반 + 고정률)
        double atrStopLoss = buyPrice - (atr * config.getStopLossAtrMultiplier());
        double fixedStopLoss = buyPrice * (1 + stopLossRate);
        double stopLossPrice = Math.max(atrStopLoss, fixedStopLoss);

        if (holdingMinutes >= 3 && currentPrice <= stopLossPrice) {
            log.warn("[{}] 🔴 손절 신호! 현재가: {}, 손절가: {}, 손익률: {}%",
                    market, currentPrice, stopLossPrice, String.format("%.2f", profitRate * 100));
            state.exitReason = "STOP_LOSS";
            return -1;
        }

        // 2. 1차 익절 체크 (50% 청산) - 아직 1차 익절 안 했으면
        if (!state.partialExitDone && profitRate >= takeProfitRate && rsi > 65) {
            log.info("[{}] 🟢 1차 익절 신호! 수익률: {}%, RSI: {}",
                    market, String.format("%.2f", profitRate * 100), String.format("%.1f", rsi));
            state.partialExitDone = true;
            state.exitReason = "PARTIAL_TAKE_PROFIT";
            state.partialExitRatio = config.getPartialExitRatio();
            return -1;  // 부분 매도 신호
        }

        // 3. 트레일링 스탑 체크 (1차 익절 후 활성화)
        if (state.partialExitDone) {
            double trailingStopPrice = highestPrice * (1 - trailingStopRate);

            // ATR 기반 트레일링
            double atrTrailingStop = highestPrice - (atr * config.getTrailingAtrMultiplier());
            trailingStopPrice = Math.max(trailingStopPrice, atrTrailingStop);

            if (currentPrice <= trailingStopPrice && holdingMinutes >= 5) {
                log.info("[{}] 🟡 트레일링 스탑! 고점: {}, 현재: {}, 스탑가: {}",
                        market, highestPrice, currentPrice, trailingStopPrice);
                state.exitReason = "TRAILING_STOP";
                return -1;
            }
        }

        // 4. RSI 과매수 청산
        if (profitRate > 0 && rsi > 80) {
            log.info("[{}] 과매수 청산 신호 - RSI: {}", market, String.format("%.1f", rsi));
            state.exitReason = "RSI_OVERBOUGHT";
            return -1;
        }

        return 0;  // 홀드
    }

    /**
     * 진입 신호 분석
     */
    private int analyzeEntrySignal(String market, List<Candle> candles, double currentPrice,
                                    double atr, double rsi, double middleBand, double lowerBand,
                                    double currentVolume, double avgVolume, MarketState state) {

        // 쿨다운 체크 (손절 후 일정 시간 대기)
        if (state.lastExitTime != null) {
            long minutesSinceExit = Duration.between(state.lastExitTime, LocalDateTime.now()).toMinutes();
            int cooldownMinutes = strategyParameterService.getIntParam(
                    getStrategyName(), null, "cooldown.minutes", 5);
            if (minutesSinceExit < cooldownMinutes) {
                return 0;
            }
        }

        // 파라미터 조회
        double rsiOversold = strategyParameterService.getDoubleParam(
                getStrategyName(), null, "rsi.oversold", 35.0);
        double volumeThreshold = strategyParameterService.getDoubleParam(
                getStrategyName(), null, "volume.threshold", 100.0);
        double prevVolume = candles.get(1).getCandleAccTradePrice().doubleValue();
        double volumeSpikeRatio = currentVolume / prevVolume;

        // 거래대금 필터
        double minTradeAmount = getMinTradeAmountByTime();
        if (avgVolume < minTradeAmount * 0.7) {
            return 0;
        }

        // 급등 추격 차단
        double candleMove = Math.abs(candles.get(0).getTradePrice().doubleValue()
                - candles.get(1).getTradePrice().doubleValue());
        if (candleMove > atr * 0.6 && volumeSpikeRatio < 1.8) {
            return 0;
        }

        // ========== 진입 조건 ==========
        boolean condition1 = currentPrice <= lowerBand * 1.02 && rsi < rsiOversold + 10;
        boolean condition2 = isHigherLowStructure(candles) && rsi < 45 && rsi > rsiOversold;
        boolean condition3 = currentVolume > avgVolume * (volumeThreshold / 100) &&
                             currentPrice > middleBand * 0.98 && rsi < 55;

        // 거래량 급증 + 가격 구조
        boolean condition4 =
                volumeSpikeRatio >= 2.0 &&          // 직전 대비 거래량 2배 이상
                        currentPrice > candles.get(1).getTradePrice().doubleValue() && // 양봉
                        rsi >= 50 && rsi <= 65 &&            // 초입 RSI
                        currentPrice < middleBand * 1.03;   // 아직 멀리 안 감

        // 추가 진입 조건 (기존 포지션 확인)
        Optional<Position> existingPosition = positionRepository.findActivePosition(1L, market);
        if (existingPosition.isPresent()) {
            Position pos = existingPosition.get();
            if (pos.canAddEntry()) {
                // 추가 진입 조건: 하락률 체크
                double avgEntryPrice = pos.getAvgEntryPrice().doubleValue();
                double dropRate = (avgEntryPrice - currentPrice) / avgEntryPrice;

                double requiredDrop = pos.getEntryPhase() == 1
                        ? config.getEntry2DropThreshold()
                        : config.getEntry3DropThreshold();

                if (dropRate >= requiredDrop) {
                    log.info("[{}] 📈 {}차 추가 진입 신호! 하락률: {}%, 필요: {}%",
                            market, pos.getEntryPhase() + 1,
                            String.format("%.2f", dropRate * 100),
                            String.format("%.2f", requiredDrop * 100));
                    state.entryPhase = pos.getEntryPhase() + 1;
                    state.entryReason = "SCALED_ENTRY_" + state.entryPhase;
                    return 1;
                }
            }
            return 0;  // 이미 포지션이 있으면 추가 진입 조건 미달 시 대기
        }

        // 신규 진입
        if (condition1 || condition2 || condition3 || condition4) {
            String reason = condition1 ? "볼린저하단+RSI과매도" :
                           condition2 ? "저점상승구조" : "거래량돌파";
            log.info("[{}] 📊 1차 진입 신호! 사유: {}, RSI: {}",
                    market, reason, String.format("%.1f", rsi));

            state.entryPhase = 1;
            state.entryReason = "SCALED_ENTRY_1";
            state.targetPrice = currentPrice + (atr * config.getTakeProfitAtrMultiplier());
            state.stopLossPrice = currentPrice - (atr * config.getStopLossAtrMultiplier());
            state.atr = atr;
            return 1;
        }

        return 0;
    }

    @Override
    public Double getTargetPrice(String market) {
        MarketState state = marketStates.get(market);
        return state != null ? state.targetPrice : null;
    }

    @Override
    public Double getStopLossPrice(String market) {
        MarketState state = marketStates.get(market);
        return state != null ? state.stopLossPrice : null;
    }

    @Override
    public void clearPosition(String market) {
        MarketState state = marketStates.get(market);
        if (state != null) {
            state.lastExitTime = LocalDateTime.now();
            state.partialExitDone = false;
            state.entryPhase = 0;
            state.targetPrice = null;
            state.stopLossPrice = null;
            state.highestPrice = 0;
        }
    }

    /**
     * 현재 진입 단계 조회
     */
    public int getEntryPhase(String market) {
        MarketState state = marketStates.get(market);
        return state != null ? state.entryPhase : 0;
    }

    /**
     * 부분 청산 비율 조회 (1차 익절 시)
     */
    public double getPartialExitRatio(String market) {
        MarketState state = marketStates.get(market);
        return state != null && state.partialExitRatio > 0 ? state.partialExitRatio : 1.0;
    }

    /**
     * 청산 사유 조회
     */
    public String getExitReason(String market) {
        MarketState state = marketStates.get(market);
        return state != null ? state.exitReason : null;
    }

    /**
     * 1차 익절 완료 여부
     */
    public boolean isPartialExitDone(String market) {
        MarketState state = marketStates.get(market);
        return state != null && state.partialExitDone;
    }

    // ========== 보조 메서드 ==========

    private boolean isHigherLowStructure(List<Candle> candles) {
        double l0 = candles.get(0).getLowPrice().doubleValue();
        double l1 = candles.get(1).getLowPrice().doubleValue();
        double l2 = candles.get(2).getLowPrice().doubleValue();
        return l0 > l1 && l1 >= l2;
    }

    private double calculateATR(List<Candle> candles, int period) {
        double sum = 0;
        for (int i = 0; i < period && i < candles.size() - 1; i++) {
            double h = candles.get(i).getHighPrice().doubleValue();
            double l = candles.get(i).getLowPrice().doubleValue();
            double pc = candles.get(i + 1).getTradePrice().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / period;
    }

    private double calculateRSI(List<Candle> candles, int period) {
        double gain = 0, loss = 0;
        for (int i = 0; i < period && i < candles.size() - 1; i++) {
            double diff = candles.get(i).getTradePrice().doubleValue()
                    - candles.get(i + 1).getTradePrice().doubleValue();
            if (diff > 0) gain += diff;
            else loss -= diff;
        }
        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - (100 / (1 + rs));
    }

    private double getMinTradeAmountByTime() {
        int hour = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul")).getHour();
        if (hour >= 2 && hour < 9) return 20_000_000;
        if (hour >= 9 && hour < 18) return 50_000_000;
        if (hour >= 18 && hour < 22) return 80_000_000;
        return 100_000_000;
    }

    private double calcAvgTradePriceByMinutes(
            List<Candle> candles,
            LocalDateTime baseTime,
            int minutes
    ) {
        LocalDateTime from = baseTime.minusMinutes(minutes);

        return candles.stream()
                .filter(c -> {
                    LocalDateTime t = getCandleTime(c);
                    return !t.isBefore(from) && t.isBefore(baseTime);
                })
                .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                .average()
                .orElse(1.0);
    }

    private LocalDateTime getCandleTime(Candle c) {
        Object t = c.getCandleDateTimeKst();
        if (t instanceof LocalDateTime) return (LocalDateTime) t;
        if (t instanceof OffsetDateTime) return ((OffsetDateTime) t).toLocalDateTime();
        return LocalDateTime.parse(t.toString());
    }

    /**
     * 마켓별 상태 관리 클래스
     */
    private static class MarketState {
        int entryPhase = 0;
        String entryReason;
        String exitReason;
        Double targetPrice;
        Double stopLossPrice;
        double highestPrice = 0;
        double atr = 0;
        boolean partialExitDone = false;
        double partialExitRatio = 0;
        LocalDateTime lastExitTime;
    }
}