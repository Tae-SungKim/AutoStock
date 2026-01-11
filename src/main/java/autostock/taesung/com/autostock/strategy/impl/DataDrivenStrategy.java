package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.service.StrategyOptimizerService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 데이터 기반 최적화 전략
 * DB에 저장된 과거 데이터를 분석하여 도출된 최적의 파라미터를 사용
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataDrivenStrategy implements TradingStrategy {

    private final StrategyOptimizerService optimizerService;
    private final TradeHistoryRepository tradeHistoryRepository;

    // 마켓별 최적화된 파라미터 캐시
    private final Map<String, StrategyOptimizerService.OptimizedParams> marketParams = new ConcurrentHashMap<>();

    // 글로벌 최적 파라미터
    private StrategyOptimizerService.OptimizedParams globalParams;

    // 손절 쿨다운
    private static final int STOP_LOSS_COOLDOWN_MINUTES = 5;
    private static final int MIN_HOLD_MINUTES = 3;

    private Double targetPrice = null;

    @PostConstruct
    public void init() {
        // 초기화 시 기본 파라미터 설정 (최적화는 별도 트리거)
        globalParams = StrategyOptimizerService.OptimizedParams.builder()
                .bollingerPeriod(20)
                .bollingerMultiplier(2.0)
                .rsiPeriod(14)
                .rsiBuyThreshold(30)
                .rsiSellThreshold(70)
                .volumeIncreaseRate(100)
                .stopLossRate(-2.5)
                .takeProfitRate(3.0)
                .trailingStopRate(1.5)
                .bandWidthMinPercent(0.8)
                .upperWickMaxRatio(0.45)
                .minTradeAmount(50_000_000)
                .build();
        log.info("[DataDrivenStrategy] 초기화 완료 (기본 파라미터)");
    }

    /**
     * 파라미터 최적화 실행 (API 트리거)
     */
    public void runOptimization() {
        try {
            log.info("[DataDrivenStrategy] 전략 최적화 시작...");
            globalParams = optimizerService.optimizeStrategy();
            log.info("[DataDrivenStrategy] 최적화 완료 - 예상 승률: {}%, 예상 수익률: {}%",
                    globalParams.getExpectedWinRate(), globalParams.getExpectedProfitRate());

            // DB에 저장
            optimizerService.saveOptimizedParams(getStrategyName(), null, "GLOBAL", globalParams);
        } catch (Exception e) {
            log.error("[DataDrivenStrategy] 최적화 실패: {}", e.getMessage());
        }
    }

    /**
     * 마켓별 파라미터 최적화
     */
    public void optimizeForMarket(String market) {
        try {
            StrategyOptimizerService.OptimizedParams params = optimizerService.optimizeForMarket(market);
            marketParams.put(market, params);
            log.info("[DataDrivenStrategy] {} 마켓 최적화 완료", market);

            // DB에 저장 (사용자 ID는 현재 알 수 없으므로 null로 처리하거나 세션 정보 필요)
            optimizerService.saveOptimizedParams(getStrategyName(), null, market, params);
        } catch (Exception e) {
            log.error("[DataDrivenStrategy] {} 마켓 최적화 실패: {}", market, e.getMessage());
        }
    }

    /**
     * 마켓별 파라미터 조회 (없으면 글로벌 사용)
     */
    private StrategyOptimizerService.OptimizedParams getParams(String market) {
        return marketParams.getOrDefault(market, globalParams);
    }

    /**
     * 파라미터 수동 설정
     */
    public void setParams(Map<String, Object> params) {
        StrategyOptimizerService.OptimizedParams.OptimizedParamsBuilder builder =
                StrategyOptimizerService.OptimizedParams.builder();

        // 기존 값 복사 후 새 값으로 덮어쓰기
        builder.bollingerPeriod(getIntParam(params, "bollingerPeriod", globalParams.getBollingerPeriod()));
        builder.bollingerMultiplier(getDoubleParam(params, "bollingerMultiplier", globalParams.getBollingerMultiplier()));
        builder.rsiPeriod(getIntParam(params, "rsiPeriod", globalParams.getRsiPeriod()));
        builder.rsiBuyThreshold(getDoubleParam(params, "rsiBuyThreshold", globalParams.getRsiBuyThreshold()));
        builder.rsiSellThreshold(getDoubleParam(params, "rsiSellThreshold", globalParams.getRsiSellThreshold()));
        builder.volumeIncreaseRate(getDoubleParam(params, "volumeIncreaseRate", globalParams.getVolumeIncreaseRate()));
        builder.stopLossRate(getDoubleParam(params, "stopLossRate", globalParams.getStopLossRate()));
        builder.takeProfitRate(getDoubleParam(params, "takeProfitRate", globalParams.getTakeProfitRate()));
        builder.trailingStopRate(getDoubleParam(params, "trailingStopRate", globalParams.getTrailingStopRate()));
        builder.bandWidthMinPercent(getDoubleParam(params, "bandWidthMinPercent", globalParams.getBandWidthMinPercent()));
        builder.upperWickMaxRatio(getDoubleParam(params, "upperWickMaxRatio", globalParams.getUpperWickMaxRatio()));
        builder.minTradeAmount(getDoubleParam(params, "minTradeAmount", globalParams.getMinTradeAmount()));

        globalParams = builder.build();
        log.info("[DataDrivenStrategy] 파라미터 수동 설정 완료: {}", getCurrentParams("GLOBAL"));

        // DB에 저장
        try {
            optimizerService.saveOptimizedParams(getStrategyName(), null, "GLOBAL", globalParams);
        } catch (Exception e) {
            log.error("[DataDrivenStrategy] 파라미터 DB 저장 실패: {}", e.getMessage());
        }
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private double getDoubleParam(Map<String, Object> params, String key, double defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {
        try {
            StrategyOptimizerService.OptimizedParams params = getParams(market);

            // 최근 거래 내역 조회
            TradeHistory latest = tradeHistoryRepository.findLatestByMarket(market)
                    .stream().findFirst().orElse(null);

            boolean holding = latest != null &&
                    latest.getTradeType() == TradeHistory.TradeType.BUY;

            // 지표 계산
            int bp = params.getBollingerPeriod();
            double bm = params.getBollingerMultiplier();
            int rp = params.getRsiPeriod();

            if (candles.size() < Math.max(bp, rp) + 5) {
                return 0;
            }

            double[] bands = calculateBollingerBands(candles, bp, bm);
            double middleBand = bands[0];
            double upperBand = bands[1];
            double lowerBand = bands[2];

            double rsi = calculateRSI(candles, rp);
            double atr = calculateATR(candles, 14);

            double currentPrice = candles.get(0).getTradePrice().doubleValue();
            double high = candles.get(0).getHighPrice().doubleValue();
            double low = candles.get(0).getLowPrice().doubleValue();

            // 거래량
            double currentVolume = candles.get(0).getCandleAccTradePrice().doubleValue();
            double avgVolume = candles.subList(1, 6).stream()
                    .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                    .average().orElse(1.0);
            double volumeRate = (currentVolume / avgVolume) * 100;

            // ===== 매도 로직 (보유 중) =====
            if (holding) {
                double buyPrice = latest.getPrice().doubleValue();
                double highest = latest.getHighestPrice() == null
                        ? currentPrice
                        : latest.getHighestPrice().doubleValue();

                if (currentPrice > highest) {
                    latest.setHighestPrice(BigDecimal.valueOf(currentPrice));
                    tradeHistoryRepository.save(latest);
                    highest = currentPrice;
                }

                double profitRate = (currentPrice - buyPrice) / buyPrice * 100;
                double stopLossPrice = buyPrice * (1 + params.getStopLossRate() / 100);
                double takeProfitPrice = buyPrice * (1 + params.getTakeProfitRate() / 100);
                double trailingStopPrice = highest * (1 - params.getTrailingStopRate() / 100);

                long holdingMinutes = Duration.between(
                        latest.getCreatedAt(), LocalDateTime.now()).toMinutes();
                boolean canCheckStopLoss = holdingMinutes >= MIN_HOLD_MINUTES;

                // 손절
                if (canCheckStopLoss && currentPrice <= stopLossPrice) {
                    log.info("[{}] 손절 - 매수가: {}, 현재가: {}, 손익: {:.2f}%",
                            market, buyPrice, currentPrice, profitRate);
                    return -1;
                }

                // 익절
                if (currentPrice >= takeProfitPrice && rsi > params.getRsiSellThreshold()) {
                    log.info("[{}] 익절 - 매수가: {}, 현재가: {}, 손익: {:.2f}%",
                            market, buyPrice, currentPrice, profitRate);
                    return -1;
                }

                // 트레일링 스탑
                if (canCheckStopLoss && currentPrice <= trailingStopPrice &&
                        highest > buyPrice * 1.01) {
                    log.info("[{}] 트레일링 종료 - 최고가: {}, 현재가: {}, 손익: {:.2f}%",
                            market, highest, currentPrice, profitRate);
                    return -1;
                }

                // RSI 과매수 매도
                if (rsi >= params.getRsiSellThreshold() && profitRate > 0) {
                    log.info("[{}] RSI 매도 신호 - RSI: {:.1f}, 손익: {:.2f}%",
                            market, rsi, profitRate);
                    return -1;
                }

                return 0;
            }

            // ===== 손절 쿨다운 체크 =====
            if (latest != null && latest.getTradeType() == TradeHistory.TradeType.SELL) {
                long minutesSinceLastSell = Duration.between(
                        latest.getCreatedAt(), LocalDateTime.now()).toMinutes();
                if (minutesSinceLastSell < STOP_LOSS_COOLDOWN_MINUTES) {
                    return 0;
                }
            }

            // ===== 진입 필터 =====

            // 거래량 필터
            if (volumeRate < params.getVolumeIncreaseRate()) {
                return 0;
            }

            // 밴드폭 필터
            double bandWidthPercent = ((upperBand - lowerBand) / middleBand) * 100;
            if (bandWidthPercent < params.getBandWidthMinPercent()) {
                return 0;
            }

            // 윗꼬리 필터
            double upperWickRatio = (high - currentPrice) / (high - low + 1e-9);
            if (upperWickRatio > params.getUpperWickMaxRatio()) {
                return 0;
            }

            // 거래대금 필터
            double minTradeAmount = getMinTradeAmountByTime();
            double avgTradeAmount = (candles.get(1).getCandleAccTradePrice().doubleValue()
                    + candles.get(2).getCandleAccTradePrice().doubleValue()
                    + candles.get(3).getCandleAccTradePrice().doubleValue()) / 3;
            if (avgTradeAmount < minTradeAmount * 0.7) {
                return 0;
            }

            // ===== 매수 신호 =====

            // 조건 1: RSI 과매도 + 하단 밴드 근접
            boolean oversoldNearLower = rsi <= params.getRsiBuyThreshold() &&
                    currentPrice <= lowerBand * 1.02;

            // 조건 2: RSI 상승 + 중단선 위 + 거래량 증가
            boolean risingMomentum = rsi >= 45 && rsi <= 60 &&
                    currentPrice > middleBand &&
                    volumeRate >= params.getVolumeIncreaseRate() * 1.2;

            // 조건 3: 골든크로스 (하단밴드 돌파 후 상승)
            double prevClose = candles.get(1).getTradePrice().doubleValue();
            boolean goldenBreak = prevClose <= lowerBand &&
                    currentPrice > lowerBand &&
                    rsi > 30;

            if (oversoldNearLower || risingMomentum || goldenBreak) {
                this.targetPrice = currentPrice + atr * 2;
                log.info("[{}] 매수 신호 - RSI: {:.1f}, 가격: {}, 조건: {}",
                        market, rsi, currentPrice,
                        oversoldNearLower ? "과매도" : risingMomentum ? "모멘텀" : "돌파");
                return 1;
            }

            this.targetPrice = null;
            return 0;

        } catch (Exception e) {
            log.error("[{}] 전략 오류: {}", market, e.getMessage());
            return 0;
        }
    }

    private double[] calculateBollingerBands(List<Candle> candles, int period, double mult) {
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getTradePrice().doubleValue();
        }
        double sma = sum / period;

        double variance = 0;
        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getTradePrice().doubleValue() - sma;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / period);

        return new double[]{sma, sma + mult * stdDev, sma - mult * stdDev};
    }

    private double calculateRSI(List<Candle> candles, int period) {
        double gain = 0, loss = 0;
        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getTradePrice().doubleValue() -
                    candles.get(i + 1).getTradePrice().doubleValue();
            if (diff > 0) gain += diff;
            else loss -= diff;
        }
        if (loss == 0) return 100;
        return 100 - (100 / (1 + gain / loss));
    }

    private double calculateATR(List<Candle> candles, int period) {
        double sumTR = 0;
        for (int i = 0; i < period; i++) {
            double high = candles.get(i).getHighPrice().doubleValue();
            double low = candles.get(i).getLowPrice().doubleValue();
            double prevClose = candles.get(i + 1).getTradePrice().doubleValue();
            double tr = Math.max(high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sumTR += tr;
        }
        return sumTR / period;
    }

    private double getMinTradeAmountByTime() {
        int hour = LocalTime.now(ZoneId.of("Asia/Seoul")).getHour();
        if (hour >= 2 && hour < 9) return 20_000_000;
        if (hour >= 9 && hour < 18) return 50_000_000;
        if (hour >= 18 && hour < 22) return 80_000_000;
        return 100_000_000;
    }

    @Override
    public Double getTargetPrice() {
        return targetPrice;
    }

    @Override
    public String getStrategyName() {
        return "BollingerBandStrategy";
    }

    /**
     * 현재 사용 중인 파라미터 조회
     */
    public Map<String, Object> getCurrentParams(String market) {
        StrategyOptimizerService.OptimizedParams params = getParams(market);
        Map<String, Object> result = new HashMap<>();
        result.put("bollingerPeriod", params.getBollingerPeriod());
        result.put("bollingerMultiplier", params.getBollingerMultiplier());
        result.put("rsiPeriod", params.getRsiPeriod());
        result.put("rsiBuyThreshold", params.getRsiBuyThreshold());
        result.put("rsiSellThreshold", params.getRsiSellThreshold());
        result.put("volumeIncreaseRate", params.getVolumeIncreaseRate());
        result.put("stopLossRate", params.getStopLossRate());
        result.put("takeProfitRate", params.getTakeProfitRate());
        result.put("expectedWinRate", params.getExpectedWinRate());
        result.put("expectedProfitRate", params.getExpectedProfitRate());
        return result;
    }
}