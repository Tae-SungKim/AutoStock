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
        // ===== 볼린저 밴드 =====
        private int bollingerPeriod;
        private double bollingerMultiplier;

        // ===== RSI =====
        private int rsiPeriod;
        private double rsiBuyThreshold;      // rsi.oversold
        private double rsiSellThreshold;     // rsi.overbought

        // ===== 거래량 =====
        private double volumeIncreaseRate;   // volume.threshold
        private double minTradeAmount;

        // ===== 손절/익절 기본 =====
        private double stopLossRate;
        private double takeProfitRate;
        private double trailingStopRate;

        // ===== ATR 기반 손익 =====
        private double stopLossAtrMult;
        private double takeProfitAtrMult;
        private double trailingStopAtrMult;
        private double maxStopLossRate;

        // ===== 캔들 기반 =====
        private int stopLossCooldownCandles;
        private int minHoldCandles;

        // ===== 슬리피지/수수료 =====
        private double totalCost;
        private double minProfitRate;

        // ===== Fast Breakout =====
        private double fastBreakoutUpperMult;
        private double fastBreakoutVolumeMult;
        private double fastBreakoutRsiMin;

        // ===== 급등 차단 및 추격 매수 방지 =====
        private double highVolumeThreshold;
        private double chasePreventionRate;
        private double bandWidthMinPercent;
        private double atrCandleMoveMult;

        // ===== 성과 지표 =====
        private double expectedWinRate;
        private double expectedProfitRate;
        private int totalSignals;
        private int winCount;
        private int lossCount;
        private double maxDrawdown;
        private double sharpeRatio;
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
     * - 새로운 BollingerBandStrategy 파라미터 반영
     * - Fast Breakout, ATR 기반 손익, 추격 매수 방지 등 포함
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

        // 2️⃣ 파라미터 조합 생성 (확장된 파라미터)
        // ===== 기본 볼린저밴드 =====
        int[] bollingerPeriods = {15, 18, 20, 22, 25};
        double[] bollingerMultipliers = {1.7, 1.8, 2.0, 2.2, 2.3};

        // ===== RSI =====
        int[] rsiPeriods = {10, 12, 14, 16, 18};
        double[] rsiBuyThresholds = {25, 28, 30, 33, 35};
        double[] rsiSellThresholds = {65, 68, 70, 73, 75};

        // ===== 거래량 =====
        double[] volumeRates = {80, 100, 120, 140};

        // ===== 손절/익절 기본 =====
        double[] stopLossRates = {-1.5, -2.0, -2.5, -3.0, -3.5};
        double[] takeProfitRates = {1.5, 2.0, 2.5, 3.0, 4.0};

        // ===== ATR 기반 =====
        double[] stopLossAtrMults = {1.5, 2.0, 2.5};
        double[] takeProfitAtrMults = {2.0, 2.5, 3.0};
        double[] trailingStopAtrMults = {1.0, 1.5, 2.0};

        // ===== Fast Breakout =====
        double[] fastBreakoutUpperMults = {1.001, 1.002, 1.003};
        double[] fastBreakoutVolumeMults = {2.0, 2.5, 3.0};
        double[] fastBreakoutRsiMins = {50, 55, 60};

        // ===== 급등 차단/추격 방지 =====
        double[] highVolumeThresholds = {1.5, 2.0, 2.5};
        double[] chasePreventionRates = {0.025, 0.035, 0.045};
        double[] bandWidthMinPercents = {0.6, 0.8, 1.0};

        // 조합 생성 (핵심 파라미터 중심으로 조합)
        List<Map<String, Object>> combinations = new ArrayList<>();

        // 첫 번째 레벨: 기본 파라미터 (5x5x5x5x5x4x5x5 = 125,000개)
        for (int bp : bollingerPeriods) {
            for (double bm : bollingerMultipliers) {
                for (int rp : rsiPeriods) {
                    for (double rbt : rsiBuyThresholds) {
                        for (double rst : rsiSellThresholds) {
                            for (double vr : volumeRates) {
                                for (double sl : stopLossRates) {
                                    for (double tp : takeProfitRates) {
                                        // ATR 기반 파라미터 (대표값 사용 - 조합 수 제한)
                                        double slAtr = 2.0;
                                        double tpAtr = 2.5;
                                        double tsAtr = 1.5;

                                        // Fast Breakout 파라미터 (대표값)
                                        double fbUpper = 1.002;
                                        double fbVol = 2.5;
                                        double fbRsi = 55.0;

                                        // 급등 차단/추격 방지 (대표값)
                                        double hvThreshold = 2.0;
                                        double cpRate = 0.035;
                                        double bwMin = 0.8;

                                        Map<String, Object> params = new HashMap<>();
                                        // 기본 파라미터
                                        params.put("bollingerPeriod", bp);
                                        params.put("bollingerMultiplier", bm);
                                        params.put("rsiPeriod", rp);
                                        params.put("rsiBuyThreshold", rbt);
                                        params.put("rsiSellThreshold", rst);
                                        params.put("volumeRate", vr);
                                        params.put("stopLossRate", sl);
                                        params.put("takeProfitRate", tp);

                                        // ATR 기반
                                        params.put("stopLossAtrMult", slAtr);
                                        params.put("takeProfitAtrMult", tpAtr);
                                        params.put("trailingStopAtrMult", tsAtr);

                                        // Fast Breakout
                                        params.put("fastBreakoutUpperMult", fbUpper);
                                        params.put("fastBreakoutVolumeMult", fbVol);
                                        params.put("fastBreakoutRsiMin", fbRsi);

                                        // 급등 차단/추격 방지
                                        params.put("highVolumeThreshold", hvThreshold);
                                        params.put("chasePreventionRate", cpRate);
                                        params.put("bandWidthMinPercent", bwMin);

                                        combinations.add(params);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 두 번째 레벨: ATR/Fast Breakout/급등 차단 세부 조합 (최적 기본값 기반)
        // 기본값 고정하고 ATR, Fast Breakout, 급등차단 조합만 테스트
        for (double slAtr : stopLossAtrMults) {
            for (double tpAtr : takeProfitAtrMults) {
                for (double tsAtr : trailingStopAtrMults) {
                    for (double fbUpper : fastBreakoutUpperMults) {
                        for (double fbVol : fastBreakoutVolumeMults) {
                            for (double fbRsi : fastBreakoutRsiMins) {
                                for (double hvThreshold : highVolumeThresholds) {
                                    for (double cpRate : chasePreventionRates) {
                                        Map<String, Object> params = new HashMap<>();
                                        // 기본값 고정
                                        params.put("bollingerPeriod", 20);
                                        params.put("bollingerMultiplier", 2.0);
                                        params.put("rsiPeriod", 14);
                                        params.put("rsiBuyThreshold", 30.0);
                                        params.put("rsiSellThreshold", 70.0);
                                        params.put("volumeRate", 120.0);
                                        params.put("stopLossRate", -2.5);
                                        params.put("takeProfitRate", 2.0);

                                        // ATR 기반
                                        params.put("stopLossAtrMult", slAtr);
                                        params.put("takeProfitAtrMult", tpAtr);
                                        params.put("trailingStopAtrMult", tsAtr);

                                        // Fast Breakout
                                        params.put("fastBreakoutUpperMult", fbUpper);
                                        params.put("fastBreakoutVolumeMult", fbVol);
                                        params.put("fastBreakoutRsiMin", fbRsi);

                                        // 급등 차단/추격 방지
                                        params.put("highVolumeThreshold", hvThreshold);
                                        params.put("chasePreventionRate", cpRate);
                                        params.put("bandWidthMinPercent", 0.8);

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
        log.info("테스트할 파라미터 조합 수: {} (기본 + ATR/FB/급등차단 조합)", totalCombinations);

        // 3️⃣ 시뮬레이션 병렬 실행 (ForkJoinPool)
        AtomicInteger progress = new AtomicInteger(0);
        AtomicInteger validResults = new AtomicInteger(0);

        ForkJoinPool customPool = new ForkJoinPool(THREAD_COUNT);
        List<SimulationResult> results;

        try {
            results = customPool.submit(() ->
                combinations.parallelStream()
                    .map(params -> {
                        SimulationResult result = runSimulationExtended(marketCandles, params);

                        // 진행률 로깅 (5% 단위)
                        int current = progress.incrementAndGet();
                        if (current % (totalCombinations / 20 + 1) == 0) {
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

        // 4️⃣ 최적 파라미터 선택 (수익률 * 승률 - MDD 페널티 기준)
        SimulationResult best = results.stream()
                .max(Comparator.comparingDouble(r ->
                        r.getTotalReturn() * r.getWinRate() - r.getMaxDrawdown() * 0.1))
                .orElse(null);

        if (best == null) {
            log.warn("유효한 시뮬레이션 결과 없음, 기본값 반환");
            return getDefaultParams();
        }

        log.info("=== 최적 파라미터 도출 완료 ({} ms) ===", System.currentTimeMillis() - startTime);
        log.info("총 수익률: {}%, 승률: {}%, MDD: {}%, 거래 수: {}",
                String.format("%.2f", best.getTotalReturn()),
                String.format("%.2f", best.getWinRate() * 100),
                String.format("%.2f", best.getMaxDrawdown()),
                best.getTotalTrades());

        Map<String, Object> p = best.getParams();
        return OptimizedParams.builder()
                // 기본 볼린저밴드
                .bollingerPeriod((int) p.get("bollingerPeriod"))
                .bollingerMultiplier((double) p.get("bollingerMultiplier"))
                // RSI
                .rsiPeriod((int) p.get("rsiPeriod"))
                .rsiBuyThreshold((double) p.get("rsiBuyThreshold"))
                .rsiSellThreshold((double) p.get("rsiSellThreshold"))
                // 거래량
                .volumeIncreaseRate((double) p.get("volumeRate"))
                .minTradeAmount(50_000_000)
                // 손절/익절 기본
                .stopLossRate((double) p.get("stopLossRate"))
                .takeProfitRate((double) p.get("takeProfitRate"))
                .trailingStopRate(1.5)
                // ATR 기반
                .stopLossAtrMult((double) p.get("stopLossAtrMult"))
                .takeProfitAtrMult((double) p.get("takeProfitAtrMult"))
                .trailingStopAtrMult((double) p.get("trailingStopAtrMult"))
                .maxStopLossRate(0.03)
                // 캔들 기반
                .stopLossCooldownCandles(5)
                .minHoldCandles(3)
                // 슬리피지/수수료
                .totalCost(0.002)
                .minProfitRate(0.006)
                // Fast Breakout
                .fastBreakoutUpperMult((double) p.get("fastBreakoutUpperMult"))
                .fastBreakoutVolumeMult((double) p.get("fastBreakoutVolumeMult"))
                .fastBreakoutRsiMin((double) p.get("fastBreakoutRsiMin"))
                // 급등 차단/추격 방지
                .highVolumeThreshold((double) p.get("highVolumeThreshold"))
                .chasePreventionRate((double) p.get("chasePreventionRate"))
                .bandWidthMinPercent((double) p.getOrDefault("bandWidthMinPercent", 0.8))
                .atrCandleMoveMult(0.8)
                // 성과 지표
                .expectedWinRate(best.getWinRate() * 100)
                .expectedProfitRate(best.getTotalReturn())
                .totalSignals(best.getTotalTrades())
                .winCount(best.getWins())
                .lossCount(best.getLosses())
                .maxDrawdown(best.getMaxDrawdown())
                .sharpeRatio(best.getSharpeRatio())
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
                // 기본 볼린저밴드
                .bollingerPeriod(analysis.optimalBollingerPeriod)
                .bollingerMultiplier(analysis.optimalBollingerMult)
                // RSI
                .rsiPeriod(analysis.optimalRsiPeriod)
                .rsiBuyThreshold(analysis.optimalRsiBuy)
                .rsiSellThreshold(analysis.optimalRsiSell)
                // 거래량
                .volumeIncreaseRate(analysis.optimalVolumeRate)
                .minTradeAmount(50_000_000)
                // 손절/익절 기본
                .stopLossRate(analysis.optimalStopLoss)
                .takeProfitRate(analysis.optimalTakeProfit)
                .trailingStopRate(1.5)
                // ATR 기반
                .stopLossAtrMult(2.0)
                .takeProfitAtrMult(2.5)
                .trailingStopAtrMult(1.5)
                .maxStopLossRate(0.03)
                // 캔들 기반
                .stopLossCooldownCandles(5)
                .minHoldCandles(3)
                // 슬리피지/수수료
                .totalCost(0.002)
                .minProfitRate(0.006)
                // Fast Breakout
                .fastBreakoutUpperMult(1.002)
                .fastBreakoutVolumeMult(2.5)
                .fastBreakoutRsiMin(55.0)
                // 급등 차단/추격 방지
                .highVolumeThreshold(2.0)
                .chasePreventionRate(0.035)
                .bandWidthMinPercent(0.8)
                .atrCandleMoveMult(0.8)
                // 성과 지표
                .expectedWinRate(analysis.estimatedWinRate)
                .expectedProfitRate(analysis.estimatedProfitRate)
                .totalSignals(analysis.signalCount)
                .winCount(analysis.winCount)
                .lossCount(analysis.lossCount)
                .build();
    }

    /**
     * 확장된 시뮬레이션 실행 (새 파라미터 포함)
     * - ATR 기반 손익, Fast Breakout, 추격 매수 방지 등 반영
     */
    private SimulationResult runSimulationExtended(Map<String, List<CandleData>> marketCandles, Map<String, Object> params) {
        int totalTrades = 0;
        int wins = 0;
        double totalReturn = 0;
        double maxDrawdown = 0;
        double peak = 100;
        double equity = 100;
        List<Double> returns = new ArrayList<>();

        // 기본 파라미터
        int bp = (int) params.get("bollingerPeriod");
        double bm = (double) params.get("bollingerMultiplier");
        int rp = (int) params.get("rsiPeriod");
        double rbt = (double) params.get("rsiBuyThreshold");
        double rst = (double) params.get("rsiSellThreshold");
        double vr = (double) params.get("volumeRate");
        double sl = (double) params.get("stopLossRate");
        double tp = (double) params.get("takeProfitRate");

        // ATR 기반 파라미터
        double slAtrMult = (double) params.get("stopLossAtrMult");
        double tpAtrMult = (double) params.get("takeProfitAtrMult");
        double tsAtrMult = (double) params.get("trailingStopAtrMult");

        // Fast Breakout 파라미터
        double fbUpperMult = (double) params.get("fastBreakoutUpperMult");
        double fbVolMult = (double) params.get("fastBreakoutVolumeMult");
        double fbRsiMin = (double) params.get("fastBreakoutRsiMin");

        // 급등 차단/추격 방지
        double hvThreshold = (double) params.get("highVolumeThreshold");
        double cpRate = (double) params.get("chasePreventionRate");
        double bwMin = (double) params.getOrDefault("bandWidthMinPercent", 0.8);

        for (Map.Entry<String, List<CandleData>> entry : marketCandles.entrySet()) {
            List<CandleData> candles = entry.getValue();

            if (candles.size() < bp + rp + 10) continue;

            List<TradeResult> trades = simulateTradesExtended(candles, bp, bm, rp, rbt, rst, vr, sl, tp,
                    slAtrMult, tpAtrMult, tsAtrMult, fbUpperMult, fbVolMult, fbRsiMin, hvThreshold, cpRate, bwMin);

            for (TradeResult trade : trades) {
                totalTrades++;
                totalReturn += trade.getProfitRate();
                returns.add(trade.getProfitRate());
                equity *= (1 + trade.getProfitRate() / 100);

                if (equity > peak) peak = equity;
                double drawdown = (peak - equity) / peak * 100;
                if (drawdown > maxDrawdown) maxDrawdown = drawdown;

                if (trade.isWin()) wins++;
            }
        }

        // Sharpe Ratio 계산 (간단 버전)
        double sharpeRatio = 0;
        if (!returns.isEmpty() && returns.size() > 1) {
            double avgReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double stdDev = Math.sqrt(returns.stream()
                    .mapToDouble(r -> Math.pow(r - avgReturn, 2))
                    .average().orElse(1));
            if (stdDev > 0) {
                sharpeRatio = avgReturn / stdDev;
            }
        }

        return SimulationResult.builder()
                .totalReturn(totalReturn)
                .winRate(totalTrades > 0 ? (double) wins / totalTrades : 0)
                .totalTrades(totalTrades)
                .wins(wins)
                .losses(totalTrades - wins)
                .maxDrawdown(maxDrawdown)
                .sharpeRatio(sharpeRatio)
                .params(params)
                .build();
    }

    /**
     * 확장된 거래 시뮬레이션 (새 파라미터 포함)
     */
    private List<TradeResult> simulateTradesExtended(List<CandleData> candles,
            int bp, double bm, int rp, double rbt, double rst, double vr, double sl, double tp,
            double slAtrMult, double tpAtrMult, double tsAtrMult,
            double fbUpperMult, double fbVolMult, double fbRsiMin,
            double hvThreshold, double cpRate, double bwMin) {

        List<TradeResult> trades = new ArrayList<>();
        boolean holding = false;
        double buyPrice = 0;
        double highestPrice = 0;
        int holdingCandles = 0;
        int cooldownCandles = 0;  // 손절 후 쿨다운

        for (int i = Math.max(bp, rp) + 5; i < candles.size(); i++) {
            CandleData current = candles.get(i);
            double currentPrice = current.getTradePrice().doubleValue();
            double openPrice = current.getOpeningPrice().doubleValue();

            // 쿨다운 감소
            if (cooldownCandles > 0) cooldownCandles--;

            if (holding) {
                holdingCandles++;
                if (currentPrice > highestPrice) highestPrice = currentPrice;

                // ATR 계산
                double atr = calculateATR(candles, i, rp);

                // 실제 수익률 (비용 0.2% 반영)
                double realProfitRate = ((currentPrice * 0.998) - (buyPrice * 1.002)) / (buyPrice * 1.002) * 100;

                // ATR 기반 손절가 계산 (최대 -3% 제한)
                double atrStopLoss = buyPrice - atr * slAtrMult;
                double fixedStopLoss = buyPrice * (1 + sl / 100);
                double maxStopLoss = buyPrice * 0.97;  // -3% 최대
                double stopLossPrice = Math.max(maxStopLoss, Math.max(fixedStopLoss, atrStopLoss));

                // ATR 기반 익절가
                double atrTakeProfit = buyPrice + atr * tpAtrMult;
                double fixedTakeProfit = buyPrice * (1 + tp / 100);
                double takeProfitPrice = Math.min(fixedTakeProfit, atrTakeProfit);

                // 트레일링 스탑
                double trailingStop = highestPrice - atr * tsAtrMult;

                // 손절
                if (holdingCandles >= 3 && currentPrice <= stopLossPrice) {
                    trades.add(TradeResult.builder()
                            .buyPrice(buyPrice).sellPrice(currentPrice)
                            .profitRate(realProfitRate).isWin(false)
                            .holdingCandles(holdingCandles).build());
                    holding = false;
                    cooldownCandles = 5;  // 손절 후 5캔들 쿨다운
                    continue;
                }

                // 익절 (최소 수익률 0.6% 이상)
                double rsi = calculateRSI(candles, i, rp);
                if (currentPrice >= takeProfitPrice && rsi > rst && realProfitRate >= 0.6) {
                    trades.add(TradeResult.builder()
                            .buyPrice(buyPrice).sellPrice(currentPrice)
                            .profitRate(realProfitRate).isWin(true)
                            .holdingCandles(holdingCandles).build());
                    holding = false;
                    continue;
                }

                // 트레일링 스탑
                if (holdingCandles >= 3 && currentPrice <= trailingStop && highestPrice > buyPrice * 1.01) {
                    trades.add(TradeResult.builder()
                            .buyPrice(buyPrice).sellPrice(currentPrice)
                            .profitRate(realProfitRate).isWin(realProfitRate > 0)
                            .holdingCandles(holdingCandles).build());
                    holding = false;
                }
            } else {
                // 쿨다운 중이면 진입 금지
                if (cooldownCandles > 0) continue;

                // 매수 조건 체크 (확장)
                if (checkBuySignalExtended(candles, i, bp, bm, rp, rbt, vr,
                        fbUpperMult, fbVolMult, fbRsiMin, hvThreshold, cpRate, bwMin)) {
                    holding = true;
                    buyPrice = currentPrice;
                    highestPrice = currentPrice;
                    holdingCandles = 0;
                }
            }
        }

        return trades;
    }

    /**
     * 확장된 매수 신호 체크 (Fast Breakout, 추격 매수 방지 등)
     */
    private boolean checkBuySignalExtended(List<CandleData> candles, int idx,
            int bp, double bm, int rp, double rbt, double vr,
            double fbUpperMult, double fbVolMult, double fbRsiMin,
            double hvThreshold, double cpRate, double bwMin) {

        if (idx < bp + rp + 5) return false;

        CandleData current = candles.get(idx);
        double currentPrice = current.getTradePrice().doubleValue();
        double openPrice = current.getOpeningPrice().doubleValue();

        // 볼린저 밴드 계산
        double[] bands = calculateBollingerBands(candles, idx, bp, bm);
        double middleBand = bands[0];
        double upperBand = bands[1];
        double lowerBand = bands[2];

        // 밴드폭 체크
        double bandWidthPercent = ((upperBand - lowerBand) / middleBand) * 100;
        if (bandWidthPercent < bwMin) return false;

        // RSI 계산
        double rsi = calculateRSI(candles, idx, rp);

        // 거래량 체크
        double currentVolume = current.getCandleAccTradePrice().doubleValue();
        double avgVolume = 0;
        for (int j = 1; j <= 5; j++) {
            avgVolume += candles.get(idx - j).getCandleAccTradePrice().doubleValue();
        }
        avgVolume /= 5;
        double volumeRatio = currentVolume / avgVolume;

        // 🚀 Fast Breakout 체크
        boolean isBullish = currentPrice > openPrice;
        boolean isAboveUpperBand = currentPrice > upperBand * fbUpperMult;
        boolean isHighVolume = volumeRatio >= fbVolMult;
        boolean isRsiAboveThreshold = rsi > fbRsiMin;

        if (isAboveUpperBand && isHighVolume && isRsiAboveThreshold && isBullish) {
            return true;  // Fast Breakout 진입
        }

        // RSI 과매수 차단 (일반 진입에만)
        if (rsi > 70) return false;

        // 추격 매수 방지
        double distanceFromLower = (currentPrice - lowerBand) / lowerBand;
        if (distanceFromLower > cpRate) return false;

        // 급등 차단 (ATR 대비 큰 캔들, 단 고거래량 예외)
        double atr = calculateATR(candles, idx, rp);
        double candleMove = Math.abs(currentPrice - candles.get(idx - 1).getTradePrice().doubleValue());
        if (candleMove > atr * 0.8 && volumeRatio < hvThreshold) return false;

        // 기존 진입 조건
        double volumeRate = volumeRatio * 100;
        boolean nearLowerBand = currentPrice <= lowerBand * 1.02;
        boolean rsiOversold = rsi <= rbt;
        boolean volumeIncrease = volumeRate >= vr;
        boolean aboveMiddle = currentPrice > middleBand * 0.98;

        return (nearLowerBand && rsiOversold) || (rsiOversold && volumeIncrease && aboveMiddle);
    }

    /**
     * ATR 계산
     */
    private double calculateATR(List<CandleData> candles, int idx, int period) {
        double sum = 0;
        for (int i = 0; i < period && idx - i - 1 >= 0; i++) {
            double h = candles.get(idx - i).getHighPrice().doubleValue();
            double l = candles.get(idx - i).getLowPrice().doubleValue();
            double pc = candles.get(idx - i - 1).getTradePrice().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / period;
    }

    /**
     * 시뮬레이션 실행 (기본 버전 - 호환성 유지)
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
                // 기본 볼린저밴드
                .bollingerPeriod(20)
                .bollingerMultiplier(2.0)
                // RSI
                .rsiPeriod(14)
                .rsiBuyThreshold(30)
                .rsiSellThreshold(70)
                // 거래량
                .volumeIncreaseRate(120)
                .minTradeAmount(50_000_000)
                // 손절/익절 기본
                .stopLossRate(-2.5)
                .takeProfitRate(2.0)
                .trailingStopRate(1.5)
                // ATR 기반
                .stopLossAtrMult(2.0)
                .takeProfitAtrMult(2.5)
                .trailingStopAtrMult(1.5)
                .maxStopLossRate(0.03)
                // 캔들 기반
                .stopLossCooldownCandles(5)
                .minHoldCandles(3)
                // 슬리피지/수수료
                .totalCost(0.002)
                .minProfitRate(0.006)
                // Fast Breakout
                .fastBreakoutUpperMult(1.002)
                .fastBreakoutVolumeMult(2.5)
                .fastBreakoutRsiMin(55.0)
                // 급등 차단/추격 방지
                .highVolumeThreshold(2.0)
                .chasePreventionRate(0.035)
                .bandWidthMinPercent(0.8)
                .atrCandleMoveMult(0.8)
                // 성과 지표
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

        // 기본 볼린저밴드
        paramMap.put("bollinger.period", String.valueOf(params.getBollingerPeriod()));
        paramMap.put("bollinger.multiplier", String.valueOf(params.getBollingerMultiplier()));

        // RSI
        paramMap.put("rsi.period", String.valueOf(params.getRsiPeriod()));
        paramMap.put("rsi.oversold", String.valueOf(params.getRsiBuyThreshold()));
        paramMap.put("rsi.overbought", String.valueOf(params.getRsiSellThreshold()));

        // 거래량
        paramMap.put("volume.threshold", String.valueOf(params.getVolumeIncreaseRate()));

        // 손절/익절 기본
        paramMap.put("stopLoss.rate", String.valueOf(params.getStopLossRate()));
        paramMap.put("takeProfit.rate", String.valueOf(params.getTakeProfitRate()));
        paramMap.put("trailingStop.rate", String.valueOf(params.getTrailingStopRate()));

        // ATR 기반
        paramMap.put("stopLoss.atrMult", String.valueOf(params.getStopLossAtrMult()));
        paramMap.put("takeProfit.atrMult", String.valueOf(params.getTakeProfitAtrMult()));
        paramMap.put("trailingStop.atrMult", String.valueOf(params.getTrailingStopAtrMult()));
        paramMap.put("maxStopLoss.rate", String.valueOf(params.getMaxStopLossRate()));

        // 캔들 기반
        paramMap.put("stopLoss.cooldownCandles", String.valueOf(params.getStopLossCooldownCandles()));
        paramMap.put("minHold.candles", String.valueOf(params.getMinHoldCandles()));

        // 슬리피지/수수료
        paramMap.put("total.cost", String.valueOf(params.getTotalCost()));
        paramMap.put("minProfit.rate", String.valueOf(params.getMinProfitRate()));

        // Fast Breakout
        paramMap.put("fastBreakout.upperMult", String.valueOf(params.getFastBreakoutUpperMult()));
        paramMap.put("fastBreakout.volumeMult", String.valueOf(params.getFastBreakoutVolumeMult()));
        paramMap.put("fastBreakout.rsiMin", String.valueOf(params.getFastBreakoutRsiMin()));

        // 급등 차단/추격 방지
        paramMap.put("highVolume.threshold", String.valueOf(params.getHighVolumeThreshold()));
        paramMap.put("chasePrevention.rate", String.valueOf(params.getChasePreventionRate()));
        paramMap.put("bandWidth.minPercent", String.valueOf(params.getBandWidthMinPercent()));
        paramMap.put("atr.candleMoveMult", String.valueOf(params.getAtrCandleMoveMult()));

        // StrategyParameterService를 통해 저장
        strategyParameterService.setUserParameters(userId, strategyName, paramMap);

        log.info("[{}] 최적화 파라미터 저장 완료 (총 {}개 파라미터)", strategyName, paramMap.size());
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