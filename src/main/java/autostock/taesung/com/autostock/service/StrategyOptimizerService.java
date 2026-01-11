package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.CandleData;
import autostock.taesung.com.autostock.entity.StrategyParameter;
import autostock.taesung.com.autostock.repository.CandleDataRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 과거 데이터 기반 전략 최적화 서비스
 * DB에 저장된 candle_data를 분석하여 최적의 매매 파라미터를 도출
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyOptimizerService {

    private final CandleDataRepository candleDataRepository;
    private final StrategyParameterService strategyParameterService;

    /**
     * 최적화된 전략 파라미터
     */
    @Data
    @Builder
    public static class OptimizedParams {
        // 볼린저 밴드
        private int bollingerPeriod;
        private double bollingerMultiplier;

        // RSI
        private int rsiPeriod;
        private double rsiBuyThreshold;
        private double rsiSellThreshold;

        // 거래량
        private double volumeIncreaseRate;
        private double minTradeAmount;

        // 손절/익절
        private double stopLossRate;
        private double takeProfitRate;
        private double trailingStopRate;

        // 기타
        private double bandWidthMinPercent;
        private double upperWickMaxRatio;

        // 성과 지표
        private double expectedWinRate;
        private double expectedProfitRate;
        private int totalSignals;
        private int winCount;
        private int lossCount;
    }

    /**
     * 시뮬레이션 결과
     */
    @Data
    @Builder
    public static class SimulationResult {
        private double totalReturn;
        private double winRate;
        private int totalTrades;
        private int wins;
        private int losses;
        private double maxDrawdown;
        private double sharpeRatio;
        private Map<String, Object> params;
    }

    /**
     * 단일 거래 결과
     */
    @Data
    @Builder
    private static class TradeResult {
        private String market;
        private double buyPrice;
        private double sellPrice;
        private double profitRate;
        private boolean isWin;
        private int holdingCandles;
    }

    // CPU 코어 수 기반 스레드 풀
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * 전체 데이터 기반 최적 파라미터 도출 (병렬 처리)
     */
    public OptimizedParams optimizeStrategy() {
        long startTime = System.currentTimeMillis();
        log.info("=== 전략 최적화 시작 ({}개 스레드) ===", THREAD_COUNT);

        List<String> markets = candleDataRepository.findDistinctMarkets();
        log.info("분석 대상 마켓 수: {}", markets.size());

        // 1️⃣ 데이터 프리로딩 (병렬)
        log.info("데이터 프리로딩 시작...");
        Map<String, List<CandleData>> marketCandles = new ConcurrentHashMap<>();

        markets.parallelStream().forEach(market -> {
            List<CandleData> candles = candleDataRepository
                    .findTop200ByMarketAndUnitOrderByCandleDateTimeKstDesc(market, 1);
            if (candles.size() >= 50) {
                List<CandleData> reversed = new ArrayList<>(candles);
                Collections.reverse(reversed);
                marketCandles.put(market, reversed);
            }
        });
        log.info("데이터 프리로딩 완료: {} 마켓 ({}ms)",
                marketCandles.size(), System.currentTimeMillis() - startTime);

        if (marketCandles.isEmpty()) {
            log.warn("유효한 데이터 없음, 기본값 반환");
            return getDefaultParams();
        }

        // 2️⃣ 파라미터 조합 생성
        int[] bollingerPeriods = {15, 20, 25};
        double[] bollingerMultipliers = {1.8, 2.0, 2.2};
        int[] rsiPeriods = {10, 14, 18};
        double[] rsiBuyThresholds = {25, 30, 35};
        double[] rsiSellThresholds = {65, 70, 75};
        double[] volumeRates = {80, 100, 120};
        double[] stopLossRates = {-2.0, -2.5, -3.0};
        double[] takeProfitRates = {2.0, 3.0, 4.0};

        List<Map<String, Object>> combinations = new ArrayList<>();
        for (int bp : bollingerPeriods) {
            for (double bm : bollingerMultipliers) {
                for (int rp : rsiPeriods) {
                    for (double rbt : rsiBuyThresholds) {
                        for (double rst : rsiSellThresholds) {
                            for (double vr : volumeRates) {
                                for (double sl : stopLossRates) {
                                    for (double tp : takeProfitRates) {
                                        Map<String, Object> params = new HashMap<>();
                                        params.put("bollingerPeriod", bp);
                                        params.put("bollingerMultiplier", bm);
                                        params.put("rsiPeriod", rp);
                                        params.put("rsiBuyThreshold", rbt);
                                        params.put("rsiSellThreshold", rst);
                                        params.put("volumeRate", vr);
                                        params.put("stopLossRate", sl);
                                        params.put("takeProfitRate", tp);
                                        combinations.add(params);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        int totalCombinations = combinations.size();
        log.info("테스트할 파라미터 조합 수: {}", totalCombinations);

        // 3️⃣ 시뮬레이션 병렬 실행 (ForkJoinPool)
        AtomicInteger progress = new AtomicInteger(0);
        AtomicInteger validResults = new AtomicInteger(0);

        ForkJoinPool customPool = new ForkJoinPool(THREAD_COUNT);
        List<SimulationResult> results;

        try {
            results = customPool.submit(() ->
                combinations.parallelStream()
                    .map(params -> {
                        SimulationResult result = runSimulation(marketCandles, params);

                        // 진행률 로깅 (10% 단위)
                        int current = progress.incrementAndGet();
                        if (current % (totalCombinations / 10 + 1) == 0) {
                            log.info("진행률: {}% ({}/{})",
                                    current * 100 / totalCombinations, current, totalCombinations);
                        }

                        if (result.getTotalTrades() >= 10) {
                            validResults.incrementAndGet();
                        }
                        return result;
                    })
                    .filter(result -> result.getTotalTrades() >= 10)
                    .toList()
            ).get();
        } catch (Exception e) {
            log.error("병렬 처리 오류: {}", e.getMessage());
            return getDefaultParams();
        } finally {
            customPool.shutdown();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("시뮬레이션 완료: {} 조합 테스트, {} 유효 결과 ({}ms)",
                totalCombinations, results.size(), elapsed);

        // 4️⃣ 최적 파라미터 선택 (수익률 * 승률 기준)
        SimulationResult best = results.stream()
                .max(Comparator.comparingDouble(r -> r.getTotalReturn() * r.getWinRate()))
                .orElse(null);

        if (best == null) {
            log.warn("유효한 시뮬레이션 결과 없음, 기본값 반환");
            return getDefaultParams();
        }

        log.info("=== 최적 파라미터 도출 완료 ({} ms) ===", System.currentTimeMillis() - startTime);
        log.info("총 수익률: {}%, 승률: {}%, 거래 수: {}",
                String.format("%.2f", best.getTotalReturn()),
                String.format("%.2f", best.getWinRate() * 100),
                best.getTotalTrades());

        Map<String, Object> p = best.getParams();
        return OptimizedParams.builder()
                .bollingerPeriod((int) p.get("bollingerPeriod"))
                .bollingerMultiplier((double) p.get("bollingerMultiplier"))
                .rsiPeriod((int) p.get("rsiPeriod"))
                .rsiBuyThreshold((double) p.get("rsiBuyThreshold"))
                .rsiSellThreshold((double) p.get("rsiSellThreshold"))
                .volumeIncreaseRate((double) p.get("volumeRate"))
                .stopLossRate((double) p.get("stopLossRate"))
                .takeProfitRate((double) p.get("takeProfitRate"))
                .trailingStopRate(1.5)
                .bandWidthMinPercent(0.8)
                .upperWickMaxRatio(0.45)
                .minTradeAmount(50_000_000)
                .expectedWinRate(best.getWinRate() * 100)
                .expectedProfitRate(best.getTotalReturn())
                .totalSignals(best.getTotalTrades())
                .winCount(best.getWins())
                .lossCount(best.getLosses())
                .build();
    }

    /**
     * 특정 마켓에 대한 최적 파라미터 도출
     */
    public OptimizedParams optimizeForMarket(String market) {
        log.info("마켓 {} 전략 최적화 시작", market);

        List<CandleData> candles = candleDataRepository
                .findTop200ByMarketAndUnitOrderByCandleDateTimeKstDesc(market, 1);

        if (candles.size() < 50) {
            log.warn("데이터 부족 ({}개), 기본값 반환", candles.size());
            return getDefaultParams();
        }

        // 역순으로 정렬 (오래된 것 먼저)
        Collections.reverse(candles);

        // 패턴 분석
        PatternAnalysis analysis = analyzePatterns(candles);

        return OptimizedParams.builder()
                .bollingerPeriod(analysis.optimalBollingerPeriod)
                .bollingerMultiplier(analysis.optimalBollingerMult)
                .rsiPeriod(analysis.optimalRsiPeriod)
                .rsiBuyThreshold(analysis.optimalRsiBuy)
                .rsiSellThreshold(analysis.optimalRsiSell)
                .volumeIncreaseRate(analysis.optimalVolumeRate)
                .stopLossRate(analysis.optimalStopLoss)
                .takeProfitRate(analysis.optimalTakeProfit)
                .trailingStopRate(1.5)
                .bandWidthMinPercent(0.8)
                .upperWickMaxRatio(0.45)
                .minTradeAmount(50_000_000)
                .expectedWinRate(analysis.estimatedWinRate)
                .expectedProfitRate(analysis.estimatedProfitRate)
                .totalSignals(analysis.signalCount)
                .winCount(analysis.winCount)
                .lossCount(analysis.lossCount)
                .build();
    }

    /**
     * 시뮬레이션 실행 (캐싱된 데이터 사용)
     */
    private SimulationResult runSimulation(Map<String, List<CandleData>> marketCandles, Map<String, Object> params) {
        int totalTrades = 0;
        int wins = 0;
        double totalReturn = 0;
        double maxDrawdown = 0;
        double peak = 100;
        double equity = 100;

        int bp = (int) params.get("bollingerPeriod");
        double bm = (double) params.get("bollingerMultiplier");
        int rp = (int) params.get("rsiPeriod");
        double rbt = (double) params.get("rsiBuyThreshold");
        double rst = (double) params.get("rsiSellThreshold");
        double vr = (double) params.get("volumeRate");
        double sl = (double) params.get("stopLossRate");
        double tp = (double) params.get("takeProfitRate");

        for (Map.Entry<String, List<CandleData>> entry : marketCandles.entrySet()) {
            List<CandleData> candles = entry.getValue();

            if (candles.size() < bp + rp + 10) continue;

            List<TradeResult> trades = simulateTrades(candles, bp, bm, rp, rbt, rst, vr, sl, tp);

            for (TradeResult trade : trades) {
                totalTrades++;
                totalReturn += trade.getProfitRate();
                equity *= (1 + trade.getProfitRate() / 100);

                if (equity > peak) peak = equity;
                double drawdown = (peak - equity) / peak * 100;
                if (drawdown > maxDrawdown) maxDrawdown = drawdown;

                if (trade.isWin()) wins++;
            }
        }

        return SimulationResult.builder()
                .totalReturn(totalReturn)
                .winRate(totalTrades > 0 ? (double) wins / totalTrades : 0)
                .totalTrades(totalTrades)
                .wins(wins)
                .losses(totalTrades - wins)
                .maxDrawdown(maxDrawdown)
                .params(params)
                .build();
    }

    /**
     * 개별 마켓 거래 시뮬레이션
     */
    private List<TradeResult> simulateTrades(List<CandleData> candles,
            int bp, double bm, int rp, double rbt, double rst, double vr, double sl, double tp) {

        List<TradeResult> trades = new ArrayList<>();
        boolean holding = false;
        double buyPrice = 0;
        int holdingCandles = 0;

        for (int i = Math.max(bp, rp) + 5; i < candles.size(); i++) {
            double currentPrice = candles.get(i).getTradePrice().doubleValue();

            if (holding) {
                holdingCandles++;
                double profitRate = (currentPrice - buyPrice) / buyPrice * 100;

                // 손절
                if (profitRate <= sl) {
                    trades.add(TradeResult.builder()
                            .buyPrice(buyPrice)
                            .sellPrice(currentPrice)
                            .profitRate(profitRate)
                            .isWin(false)
                            .holdingCandles(holdingCandles)
                            .build());
                    holding = false;
                    continue;
                }

                // 익절
                if (profitRate >= tp) {
                    trades.add(TradeResult.builder()
                            .buyPrice(buyPrice)
                            .sellPrice(currentPrice)
                            .profitRate(profitRate)
                            .isWin(true)
                            .holdingCandles(holdingCandles)
                            .build());
                    holding = false;
                    continue;
                }

                // RSI 매도 신호
                double rsi = calculateRSI(candles, i, rp);
                if (rsi >= rst) {
                    trades.add(TradeResult.builder()
                            .buyPrice(buyPrice)
                            .sellPrice(currentPrice)
                            .profitRate(profitRate)
                            .isWin(profitRate > 0)
                            .holdingCandles(holdingCandles)
                            .build());
                    holding = false;
                }
            } else {
                // 매수 조건 체크
                if (checkBuySignal(candles, i, bp, bm, rp, rbt, vr)) {
                    holding = true;
                    buyPrice = currentPrice;
                    holdingCandles = 0;
                }
            }
        }

        return trades;
    }

    /**
     * 매수 신호 체크
     */
    private boolean checkBuySignal(List<CandleData> candles, int idx,
            int bp, double bm, int rp, double rbt, double vr) {

        if (idx < bp + rp) return false;

        double currentPrice = candles.get(idx).getTradePrice().doubleValue();

        // 볼린저 밴드 계산
        double[] bands = calculateBollingerBands(candles, idx, bp, bm);
        double middleBand = bands[0];
        double lowerBand = bands[2];

        // RSI 계산
        double rsi = calculateRSI(candles, idx, rp);

        // 거래량 체크
        double currentVolume = candles.get(idx).getCandleAccTradePrice().doubleValue();
        double avgVolume = 0;
        for (int j = 1; j <= 5; j++) {
            avgVolume += candles.get(idx - j).getCandleAccTradePrice().doubleValue();
        }
        avgVolume /= 5;
        double volumeRate = (currentVolume / avgVolume) * 100;

        // 매수 조건
        boolean nearLowerBand = currentPrice <= lowerBand * 1.02;
        boolean rsiOversold = rsi <= rbt;
        boolean volumeIncrease = volumeRate >= vr;
        boolean aboveMiddle = currentPrice > middleBand * 0.98;

        // 조건 조합
        return (nearLowerBand && rsiOversold) ||
               (rsiOversold && volumeIncrease && aboveMiddle);
    }

    /**
     * 볼린저 밴드 계산
     */
    private double[] calculateBollingerBands(List<CandleData> candles, int idx, int period, double mult) {
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(idx - i).getTradePrice().doubleValue();
        }
        double sma = sum / period;

        double variance = 0;
        for (int i = 0; i < period; i++) {
            double diff = candles.get(idx - i).getTradePrice().doubleValue() - sma;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / period);

        return new double[]{sma, sma + mult * stdDev, sma - mult * stdDev};
    }

    /**
     * RSI 계산
     */
    private double calculateRSI(List<CandleData> candles, int idx, int period) {
        double gain = 0, loss = 0;

        for (int i = 0; i < period; i++) {
            double diff = candles.get(idx - i).getTradePrice().doubleValue() -
                         candles.get(idx - i - 1).getTradePrice().doubleValue();
            if (diff > 0) gain += diff;
            else loss -= diff;
        }

        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * 패턴 분석
     */
    @Data
    private static class PatternAnalysis {
        int optimalBollingerPeriod = 20;
        double optimalBollingerMult = 2.0;
        int optimalRsiPeriod = 14;
        double optimalRsiBuy = 30;
        double optimalRsiSell = 70;
        double optimalVolumeRate = 100;
        double optimalStopLoss = -2.5;
        double optimalTakeProfit = 3.0;
        double estimatedWinRate = 0;
        double estimatedProfitRate = 0;
        int signalCount = 0;
        int winCount = 0;
        int lossCount = 0;
    }

    private PatternAnalysis analyzePatterns(List<CandleData> candles) {
        PatternAnalysis best = new PatternAnalysis();
        double bestScore = 0;

        // 간단한 그리드 서치
        for (int bp : new int[]{15, 20, 25}) {
            for (double bm : new double[]{1.8, 2.0, 2.2}) {
                for (int rp : new int[]{10, 14, 18}) {
                    for (double rbt : new double[]{25, 30, 35}) {
                        for (double rst : new double[]{65, 70, 75}) {
                            List<TradeResult> trades = simulateTrades(candles, bp, bm, rp, rbt, rst, 100, -2.5, 3.0);

                            if (trades.size() < 5) continue;

                            double totalProfit = trades.stream().mapToDouble(TradeResult::getProfitRate).sum();
                            int wins = (int) trades.stream().filter(TradeResult::isWin).count();
                            double winRate = (double) wins / trades.size();

                            double score = totalProfit * winRate;

                            if (score > bestScore) {
                                bestScore = score;
                                best.optimalBollingerPeriod = bp;
                                best.optimalBollingerMult = bm;
                                best.optimalRsiPeriod = rp;
                                best.optimalRsiBuy = rbt;
                                best.optimalRsiSell = rst;
                                best.estimatedWinRate = winRate * 100;
                                best.estimatedProfitRate = totalProfit;
                                best.signalCount = trades.size();
                                best.winCount = wins;
                                best.lossCount = trades.size() - wins;
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * 기본 파라미터 반환
     */
    private OptimizedParams getDefaultParams() {
        return OptimizedParams.builder()
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
                .expectedWinRate(50)
                .expectedProfitRate(0)
                .totalSignals(0)
                .winCount(0)
                .lossCount(0)
                .build();
    }

    /**
     * 최적화된 파라미터를 DB에 저장 (글로벌 또는 마켓별)
     */
    public void saveOptimizedParams(String strategyName, Long userId, String market, OptimizedParams params) {
        log.info("[{}] 최적화 파라미터 저장 중... (Market: {})", strategyName, market);

        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("bollinger.period", String.valueOf(params.getBollingerPeriod()));
        paramMap.put("bollinger.multiplier", String.valueOf(params.getBollingerMultiplier()));
        paramMap.put("rsi.period", String.valueOf(params.getRsiPeriod()));
        paramMap.put("rsi.oversold", String.valueOf(params.getRsiBuyThreshold()));
        paramMap.put("rsi.overbought", String.valueOf(params.getRsiSellThreshold()));
        paramMap.put("volume.threshold", String.valueOf(params.getVolumeIncreaseRate()));
        paramMap.put("stopLoss.rate", String.valueOf(params.getStopLossRate()));
        paramMap.put("takeProfit.rate", String.valueOf(params.getTakeProfitRate()));
        paramMap.put("trailingStop.rate", String.valueOf(params.getTrailingStopRate()));

        // StrategyParameterService를 통해 저장 (현재는 글로벌 기반으로만 저장하는 예시)
        // market 정보를 paramKey에 포함시키거나 별도의 구조가 필요할 수 있음
        // 여기서는 일단 기본 키값으로 저장
        strategyParameterService.setUserParameters(userId, strategyName, paramMap);

        log.info("[{}] 최적화 파라미터 저장 완료", strategyName);
    }

    /**
     * 데이터 통계 조회
     */
    public Map<String, Object> getDataStatistics() {
        Map<String, Object> stats = new HashMap<>();

        List<Object[]> marketCounts = candleDataRepository.countByMarketAndUnit(1);
        stats.put("marketCount", marketCounts.size());
        stats.put("totalCandles", marketCounts.stream()
                .mapToLong(arr -> (Long) arr[1])
                .sum());

        List<Map<String, Object>> topMarkets = marketCounts.stream()
                .limit(10)
                .map(arr -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("market", arr[0]);
                    m.put("count", arr[1]);
                    return m;
                })
                .collect(Collectors.toList());
        stats.put("topMarkets", topMarkets);

        return stats;
    }
}