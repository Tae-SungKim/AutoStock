package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.service.ImpulseStatService;
import autostock.taesung.com.autostock.service.MarketVolumeService;
import autostock.taesung.com.autostock.service.StrategyParameterService;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import autostock.taesung.com.autostock.strategy.config.TimeWindowConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volume Breakout 전략 (거래량 돌파 기반)
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [핵심 설계: 업비트 분봉 데이터 특성 대응]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * ★ 업비트는 거래가 없는 분에 캔들 데이터 자체를 반환하지 않음
 * ★ 단순 candle.size() 기반 평균 계산 시 유동성 착시 발생
 *
 * 해결책:
 * 1. 시간 정규화 평균: sumVolume / window (캔들 개수 아님)
 * 2. 캔들 밀도 필터: density < 0.85 → 진입 금지
 * 3. Z-score도 시간 정규화 기준 계산
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [Impulse 연계 필터 - 최우선]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * - 단독 Breakout 진입 금지
 * - 최근 15분 이내 Impulse 성공 이력 필수
 * - impulseStatService.hasRecentSuccess() 체크 필수
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [진입 조건]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 0. Impulse 연계: 15분 이내 성공 이력 필수
 * 1. 캔들 밀도 >= 85%
 * 2. 시간 정규화 평균 거래량 >= MIN_AVG_VOLUME
 * 3. 현재 거래량 >= 평균 × 0.8
 * 4. Z-score 증가: dZ >= 0.35
 * 5. Early/Strong Breakout 조건 충족
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [청산 조건]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * - STOP_LOSS: 손절가 도달
 * - Z_WEAK_EXIT: Z < 1.0 && RSI < 65 (2분 이상)
 * - Z_DROP_EXIT: Z < 0.3 (1분 이상)
 * - TRAIL_EXIT: 트레일링 스탑 (3분 이상)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeBreakoutStrategy implements TradingStrategy {

    private final TechnicalIndicator indicator;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final StrategyParameterService paramService;
    private final RealTradingConfig config;
    private final MarketVolumeService marketVolumeService;
    private final ImpulseStatService impulseStatService;

    // ==================== 지표 파라미터 ====================

    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;

    // ==================== 청산 파라미터 ====================

    private static final double TRAIL_ATR_MULTIPLIER = 1.0;
    private static final double MIN_PROFIT_ATR = 1.0;

    // ==================== 거래량 파라미터 ====================

    private static final int Z_WINDOW = 20;
    private static final int VOLUME_LOOKBACK = 30;

    /** 최소 시간 정규화 평균 거래량 */
    private static final double MIN_TIME_NORMALIZED_AVG = 5_000;

    /** Z-score 증가 임계값 */
    private static final double DZ_THRESHOLD = 0.35;

    // ==================== 캔들 밀도 필터 (핵심!) ====================

    /**
     * 최소 캔들 밀도
     * - 0.85 = 20분 중 최소 17개 캔들 필요
     */
    private static final double MIN_CANDLE_DENSITY = 0.85;

    /** 마켓별 상태 관리 */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Override
    public String getStrategyName() {
        return "VolumeBreakoutStrategy";
    }

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    @Override
    public int analyze(String market, List<Candle> candles) {

        if (!market.startsWith("KRW-")) return 0;
        if (market.equals("KRW-BTC") || market.equals("KRW-ETH")) return 0;
        if (candles.size() < 40) return 0;

        int last = candles.size() - 1;

        Candle cur = candles.get(last - 1);
        Candle prev = candles.get(last - 2);

        State state = states.computeIfAbsent(market, k -> new State());

        TradeHistory latest =
                tradeHistoryRepository.findLatestByMarket(market)
                        .stream().findFirst().orElse(null);

        boolean holding =
                latest != null && latest.getTradeType() == TradeHistory.TradeType.BUY;

        double price = cur.getTradePrice().doubleValue();
        double atr = atr(candles);
        double rsi = rsi(candles);

        // ★ 시간 정규화 Z-score ★
        double zScore = calculateTimeNormalizedZScore(candles, Z_WINDOW);
        double prevZ = calculateTimeNormalizedZScore(candles.subList(0, candles.size() - 1), Z_WINDOW);
        double dz = zScore - prevZ;

        if (holding) {
            return exit(market, latest, price, atr, rsi, zScore, state);
        }

        return entry(market, candles, cur, prev, price, atr, rsi, zScore, dz, state);
    }

    /**
     * 진입 신호 평가
     */
    private int entry(String market, List<Candle> candles,
                      Candle cur, Candle prev,
                      double price, double atr, double rsi,
                      double zScore, double dz,
                      State state) {

        int last = candles.size() - 1;

        // ============ 0. Impulse 연계 필터 (최우선) ============
        if (!impulseStatService.hasRecentSuccess(market)) {
            return 0;
        }

        // ============ 1. 캔들 밀도 검증 (유동성 착시 방지) ============
        double density = calculateCandleDensity(candles, last, VOLUME_LOOKBACK);

        if (density < MIN_CANDLE_DENSITY) {
            log.debug("REJECT,{},density={}<{}", market,
                    String.format("%.2f", density), MIN_CANDLE_DENSITY);
            return 0;
        }

        // ============ 2. 시간 정규화 평균 거래량 (핵심!) ============
        // ★ sumVolume / window (캔들 개수 아님!) ★
        double timeNormalizedAvg = calculateTimeNormalizedAvgVolume(candles, last, VOLUME_LOOKBACK);

        if (timeNormalizedAvg < MIN_TIME_NORMALIZED_AVG) {
            log.debug("REJECT,{},time_avg={}<{}", market,
                    (long) timeNormalizedAvg, MIN_TIME_NORMALIZED_AVG);
            return 0;
        }

        // ============ 3. 현재 거래량 필터 ============
        double curVolume = cur.getCandleAccTradeVolume().doubleValue();

        if (curVolume < timeNormalizedAvg * 0.8) {
            log.debug("REJECT,{},vol={}<avg*0.8", market, (long) curVolume);
            return 0;
        }

        // ============ 4. Z-score 증가 필터 ============
        if (dz < DZ_THRESHOLD) {
            log.debug("REJECT,{},dZ={}<{}", market,
                    String.format("%.2f", dz), DZ_THRESHOLD);
            return 0;
        }

        // ============ 5. Breakout 조건 ============
        double priceChange =
                (price - prev.getTradePrice().doubleValue())
                        / prev.getTradePrice().doubleValue();

        boolean earlyBreakout =
                zScore >= 1.6 &&
                        priceChange >= 0.0015 &&
                        rsi >= 35 && rsi <= 60;

        boolean strongBreakout =
                zScore >= 2.1 &&
                        price > prev.getHighPrice().doubleValue() &&
                        rsi <= 78;

        boolean entrySignal = earlyBreakout || strongBreakout;

        log.debug("BREAKOUT_CHK,{},Z={},dZ={},vol={},avg={},rsi={},density={}",
                market,
                String.format("%.2f", zScore),
                String.format("%.2f", dz),
                (long) curVolume,
                (long) timeNormalizedAvg,
                String.format("%.1f", rsi),
                String.format("%.2f", density));

        if (!entrySignal) return 0;

        state.entryPrice = price;
        state.highest = price;
        state.stop = Math.min(
                prev.getLowPrice().doubleValue(),
                price - atr * 0.7
        );
        state.entryZ = zScore;

        log.info("BREAKOUT_ENTRY,{},price={},Z={},dZ={},rsi={},density={}",
                market,
                String.format("%.4f", price),
                String.format("%.2f", zScore),
                String.format("%.2f", dz),
                String.format("%.1f", rsi),
                String.format("%.2f", density));

        return 1;
    }

    /**
     * 청산 신호 평가
     */
    private int exit(String market, TradeHistory trade,
                     double price, double atr, double rsi,
                     double zScore, State state) {

        state.highest = Math.max(state.highest, price);

        long minutes =
                Duration.between(trade.getCreatedAt(), LocalDateTime.now()).toMinutes();

        if (price <= state.stop && minutes >= 1) {
            log.warn("EXIT,{},STOP_LOSS", market);
            return -1;
        }

        double profitAtr = (price - state.entryPrice) / atr;
        if (profitAtr < MIN_PROFIT_ATR) return 0;

        if (zScore < 1.0 && rsi < 65 && minutes >= 2) {
            log.info("EXIT,{},Z_WEAK,Z={},rsi={}", market,
                    String.format("%.2f", zScore), String.format("%.1f", rsi));
            return -1;
        }

        if (zScore < 0.3 && minutes >= 1) {
            log.info("EXIT,{},Z_DROP,Z={}", market, String.format("%.2f", zScore));
            return -1;
        }

        double trail = state.highest - atr * TRAIL_ATR_MULTIPLIER;
        if (price <= trail && minutes >= 3) {
            log.info("EXIT,{},TRAIL,price={},trail={}", market,
                    String.format("%.4f", price), String.format("%.4f", trail));
            return -1;
        }

        return 0;
    }

    // ==================== 지표 계산 ====================

    private double atr(List<Candle> c) {
        double sum = 0;
        int last = c.size() - 1;
        for (int i = last - 2; i >= last - ATR_PERIOD - 1; i--) {
            double h = c.get(i).getHighPrice().doubleValue();
            double l = c.get(i).getLowPrice().doubleValue();
            double pc = c.get(i + 1).getTradePrice().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / ATR_PERIOD;
    }

    private double rsi(List<Candle> c) {
        double g = 0, l = 0;
        int last = c.size() - 1;
        for (int i = last - 2; i >= last - RSI_PERIOD - 1; i--) {
            double d = c.get(i).getTradePrice().doubleValue()
                    - c.get(i + 1).getTradePrice().doubleValue();
            if (d > 0) g += d;
            else l -= d;
        }
        return l == 0 ? 100 : 100 - (100 / (1 + g / l));
    }

    // ==================== 핵심 유틸리티: 시간 정규화 계산 ====================

    /**
     * 누적 거래량 계산
     */
    private double calculateSumVolume(List<Candle> candles, int last, int window) {
        int start = Math.max(0, last - window + 1);
        double sum = 0;

        for (int i = start; i <= last; i++) {
            if (i < candles.size()) {
                Candle c = candles.get(i);
                if (c.getCandleAccTradeVolume() != null) {
                    sum += c.getCandleAccTradeVolume().doubleValue();
                }
            }
        }

        return sum;
    }

    /**
     * 시간 정규화 평균 거래량 (핵심!)
     *
     * ★ 캔들 개수가 아닌 시간(window)으로 나눔 ★
     */
    private double calculateTimeNormalizedAvgVolume(List<Candle> candles, int last, int window) {
        double sum = calculateSumVolume(candles, last, window);
        return sum / window;  // ★ window로 나눔 ★
    }

    /**
     * 캔들 밀도 계산
     */
    private double calculateCandleDensity(List<Candle> candles, int last, int window) {
        int start = Math.max(0, last - window + 1);
        int actualCandleCount = 0;

        for (int i = start; i <= last; i++) {
            if (i < candles.size()) {
                actualCandleCount++;
            }
        }

        return (double) actualCandleCount / window;
    }

    /**
     * 시간 정규화 Z-score 계산
     *
     * ★ 평균/분산을 시간 기준으로 계산 ★
     */
    private double calculateTimeNormalizedZScore(List<Candle> candles, int window) {
        if (candles.size() < 2) return 0;

        int last = candles.size() - 1;
        int start = Math.max(0, last - window);

        // 시간 정규화 평균
        double sum = 0;
        for (int i = start; i < last; i++) {
            if (i >= 0 && i < candles.size()) {
                sum += candles.get(i).getCandleAccTradeVolume().doubleValue();
            }
        }
        double mean = sum / window;

        // 시간 정규화 분산
        double sumSquaredDiff = 0;
        int actualCandleCount = 0;

        for (int i = start; i < last; i++) {
            if (i >= 0 && i < candles.size()) {
                double vol = candles.get(i).getCandleAccTradeVolume().doubleValue();
                sumSquaredDiff += Math.pow(vol - mean, 2);
                actualCandleCount++;
            }
        }

        // 없는 캔들들의 편차 (volume=0으로 간주)
        int missingCandles = window - actualCandleCount;
        sumSquaredDiff += missingCandles * Math.pow(0 - mean, 2);

        double variance = sumSquaredDiff / window;
        double std = Math.sqrt(variance);

        if (std == 0) return 0;

        double curVolume = candles.get(last).getCandleAccTradeVolume().doubleValue();

        return (curVolume - mean) / std;
    }

    private TimeWindowConfig getTimeWindowConfig() {
        int hour = LocalTime.now(ZoneId.of("Asia/Seoul")).getHour();
        if (hour >= 2 && hour < 8) return new TimeWindowConfig(8_000, 1.1, 0.6);
        if (hour >= 9 && hour < 12) return new TimeWindowConfig(15_000, 1.2, 0.7);
        if (hour >= 12 && hour < 18) return new TimeWindowConfig(20_000, 1.3, 0.8);
        if (hour >= 18 && hour < 22) return new TimeWindowConfig(25_000, 1.35, 0.9);
        return new TimeWindowConfig(12_000, 1.15, 0.7);
    }

    private static class State {
        double entryPrice;
        double highest;
        double stop;
        double entryZ;
    }
}