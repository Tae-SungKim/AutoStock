package autostock.taesung.com.autostock.backtest;

import autostock.taesung.com.autostock.backtest.context.BacktestContext;
import autostock.taesung.com.autostock.backtest.dto.*;
import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.exchange.upbit.dto.Market;
import autostock.taesung.com.autostock.repository.CandleDataRepository;
import autostock.taesung.com.autostock.entity.CandleData;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final UpbitApiService upbitApiService;
    private final CandleDataRepository candleDataRepository;
    private final List<TradingStrategy> strategies;

    private static final double TRADING_FEE = 0.0005;  // 업비트 수수료 0.05%
    private static final double STOP_LOSS_RATE = -0.03;  // 손절선 -3%
    private static final double TAKE_PROFIT_RATE = 0.05;  // 익절선 +5%
    private static final double TRAILING_STOP_RATE = 0.02;  // 트레일링 스탑 2%

    /**
     * 백테스팅 실행 (모든 전략 조합)
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param initialBalance 초기 자본금
     * @param candleUnit 캔들 단위 (분)
     * @param candleCount 캔들 개수 (최대 200)
     */
    public BacktestResult runBacktest(String market, double initialBalance, int candleUnit, int candleCount) {
        log.info("========== 백테스팅 시작 ==========");
        log.info("마켓: {}, 초기자본: {}, 캔들: {}분봉 {}개", market, initialBalance, candleUnit, candleCount);

        // 캔들 데이터 조회 (최신순으로 반환됨)
        List<Candle> candles = upbitApiService.getMinuteCandles(market, candleUnit, candleCount);

        // 시간순으로 정렬 (오래된 것부터)
        Collections.reverse(candles);

        return executeBacktest(market, "Combined (All Strategies)", candles, initialBalance);
    }

    /**
     * 특정 전략으로 백테스팅 실행
     */
    public BacktestResult runBacktestWithStrategy(String market, String strategyName,
                                                  double initialBalance, int candleUnit, int candleCount) {
        log.info("========== {} 전략 백테스팅 시작 ==========", strategyName);

        List<Candle> candles = upbitApiService.getMinuteCandles(market, candleUnit, candleCount);
        Collections.reverse(candles);

        TradingStrategy selectedStrategy = strategies.stream()
                .filter(s -> s.getStrategyName().equalsIgnoreCase(strategyName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("전략을 찾을 수 없습니다: " + strategyName));

        return executeBacktestSingleStrategy(market, selectedStrategy, candles, initialBalance);
    }

    /**
     * 모든 전략 개별 백테스팅 결과 반환
     */
    public List<BacktestResult> runAllStrategiesBacktest(String market, double initialBalance,
                                                         int candleUnit, int candleCount) {
        List<Candle> candles = upbitApiService.getMinuteCandles(market, candleUnit, candleCount);
        Collections.reverse(candles);

        List<BacktestResult> results = new ArrayList<>();

        // 각 전략별 백테스팅
        for (TradingStrategy strategy : strategies) {
            BacktestResult result = executeBacktestSingleStrategy(market, strategy, candles, initialBalance);
            results.add(result);
        }

        // 전략 조합 백테스팅
        BacktestResult combinedResult = executeBacktest(market, "Combined (Majority)", candles, initialBalance);
        results.add(combinedResult);

        return results;
    }

    /**
     * 멀티 코인 백테스팅 (여러 코인에 대해 동시 실행)
     * @param markets 마켓 코드 리스트 (예: ["KRW-BTC", "KRW-ETH", "KRW-XRP"])
     * @param strategyName 전략 이름 (null이면 조합 전략)
     * @param initialBalancePerMarket 마켓당 초기 자본금
     */
    public MultiCoinBacktestResult runMultiCoinBacktest(List<String> markets, String strategyName,
                                                        double initialBalancePerMarket, int candleUnit, int candleCount) {
        log.info("========== 멀티 코인 백테스팅 시작 ==========");
        log.info("마켓 수: {}, 전략: {}, 마켓당 자본: {}", markets.size(), strategyName, initialBalancePerMarket);

        List<BacktestResult> marketResults = new ArrayList<>();
        Map<String, Double> profitRateByMarket = new LinkedHashMap<>();
        Map<ExitReason, Integer> totalExitReasonStats = new EnumMap<>(ExitReason.class);

        TradingStrategy selectedStrategy = null;
        if (strategyName != null && !strategyName.isEmpty()) {
            selectedStrategy = strategies.stream()
                    .filter(s -> s.getStrategyName().equalsIgnoreCase(strategyName))
                    .findFirst()
                    .orElse(null);
        }

        for (String market : markets) {
            try {
                log.info("백테스팅 진행 중: {}", market);

                List<Candle> candles = upbitApiService.getMinuteCandles(market, candleUnit, candleCount);
                if (candles == null || candles.size() < 50) {
                    log.warn("{} 캔들 데이터 부족, 스킵", market);
                    continue;
                }

                Collections.reverse(candles);

                BacktestResult result;
                if (selectedStrategy != null) {
                    result = executeBacktestSingleStrategy(market, selectedStrategy, candles, initialBalancePerMarket);
                } else {
                    result = executeBacktest(market, "BollingerBandStrategy", candles, initialBalancePerMarket);
                }

                marketResults.add(result);
                profitRateByMarket.put(market, result.getTotalProfitRate());

                // 종료 사유 통계 합산
                if (result.getExitReasonStats() != null) {
                    result.getExitReasonStats().forEach((reason, count) ->
                            totalExitReasonStats.put(reason, totalExitReasonStats.getOrDefault(reason, 0) + count));
                }

                // API 속도 제한 방지
                Thread.sleep(300);

            } catch (Exception e) {
                log.error("{} 백테스팅 실패: {}", market, e.getMessage());
            }
        }

        if (marketResults.isEmpty()) {
            throw new RuntimeException("백테스팅 결과가 없습니다.");
        }

        // 통계 계산
        double totalFinalAsset = marketResults.stream()
                .mapToDouble(BacktestResult::getFinalTotalAsset)
                .sum();

        double totalInitialBalance = initialBalancePerMarket * marketResults.size();
        double totalProfitRate = ((totalFinalAsset - totalInitialBalance) / totalInitialBalance) * 100;
        double averageProfitRate = marketResults.stream()
                .mapToDouble(BacktestResult::getTotalProfitRate)
                .average()
                .orElse(0);
        double averageWinRate = marketResults.stream()
                .mapToDouble(BacktestResult::getWinRate)
                .average()
                .orElse(0);

        int profitableMarkets = (int) marketResults.stream()
                .filter(r -> r.getTotalProfitRate() > 0)
                .count();
        int losingMarkets = marketResults.size() - profitableMarkets;

        BacktestResult best = marketResults.stream()
                .max(Comparator.comparing(BacktestResult::getTotalProfitRate))
                .orElse(null);

        BacktestResult worst = marketResults.stream()
                .min(Comparator.comparing(BacktestResult::getTotalProfitRate))
                .orElse(null);

        log.info("========== 멀티 코인 백테스팅 완료 ==========");
        log.info("총 수익률: {}%, 평균 수익률: {}%",
                String.format("%.2f", totalProfitRate), String.format("%.2f", averageProfitRate));
        log.info("수익 마켓: {}, 손실 마켓: {}", profitableMarkets, losingMarkets);

        return MultiCoinBacktestResult.builder()
                .strategy(strategyName != null ? strategyName : "Combined")
                .totalMarkets(marketResults.size())
                .initialBalancePerMarket(initialBalancePerMarket)
                .totalInitialBalance(totalInitialBalance)
                .totalFinalAsset(Math.round(totalFinalAsset * 100.0) / 100.0)
                .totalProfitRate(Math.round(totalProfitRate * 100.0) / 100.0)
                .averageProfitRate(Math.round(averageProfitRate * 100.0) / 100.0)
                .averageWinRate(Math.round(averageWinRate * 10.0) / 10.0)
                .profitableMarkets(profitableMarkets)
                .losingMarkets(losingMarkets)
                .bestMarket(best != null ? best.getMarket() : null)
                .bestMarketProfitRate(best != null ? best.getTotalProfitRate() : null)
                .worstMarket(worst != null ? worst.getMarket() : null)
                .worstMarketProfitRate(worst != null ? worst.getTotalProfitRate() : null)
                .marketResults(marketResults)
                .profitRateByMarket(profitRateByMarket)
                .totalExitReasonStats(totalExitReasonStats)
                .build();
    }

    /**
     * KRW 마켓 상위 코인 목록 조회
     */
    public List<String> getTopKrwMarkets(int limit) {
        try {
            List<Market> markets = upbitApiService.getMarkets();
            return markets.stream()
                    .filter(m -> m.getMarket().startsWith("KRW-"))
                    .filter(m -> !"CAUTION".equals(m.getMarketWarning()))  // 유의 종목 제외
                    .map(Market::getMarket)
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("마켓 목록 조회 실패: {}", e.getMessage());
            // 기본 마켓 반환
            return List.of("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE");
        }
    }

    /**
     * 실제 자동매매 로직과 동일한 시뮬레이션 (과반수 전략 동의 시 매매)
     * - 모든 전략을 분석하여 과반수 이상 동일 신호 시에만 매매
     * - 손절/익절 로직 포함
     */
    public BacktestResult runRealTradingSimulation(String market, double initialBalance,
                                                   int candleUnit, int candleCount) {
        log.info("========== 실제 매매 시뮬레이션 시작 ==========");
        log.info("마켓: {}, 초기자본: {}, 전략 수: {}", market, initialBalance, strategies.size());
        log.info("과반수 기준: {}개 이상 동의 시 매매", (strategies.size() / 2) + 1);

        List<Candle> candles = upbitApiService.getMinuteCandles(market, candleUnit, candleCount);
        Collections.reverse(candles);

        return executeRealTradingSimulation(market, candles, initialBalance);
    }

    /**
     * 멀티 코인 실제 매매 시뮬레이션
     */
    public MultiCoinBacktestResult runMultiCoinRealTradingSimulation(List<String> markets,
                                                                     String strategyName,
                                                                     double initialBalancePerMarket,
                                                                     int candleUnit, int candleCount) {
        log.info("========== 멀티 코인 실제 매매 시뮬레이션 시작 ==========");
        log.info("마켓 수: {}, 전략 수: {}, 과반수: {}개",
                markets.size(), strategies.size(), (strategies.size() / 2) + 1);

        List<BacktestResult> marketResults = new ArrayList<>();
        Map<String, Double> profitRateByMarket = new LinkedHashMap<>();

        for (String market : markets) {
            try {
                log.info("시뮬레이션 진행 중: {}", market);

                List<Candle> candles = upbitApiService.getMinuteCandles(market, candleUnit, candleCount);
                if (candles == null || candles.size() < 50) {
                    log.warn("{} 캔들 데이터 부족, 스킵", market);
                    continue;
                }

                Collections.reverse(candles);
                //BacktestResult result = executeBacktest(market, strategyName, candles, initialBalancePerMarket);
                //BacktestResult result = executeRealTradingSimulation(market, candles, initialBalancePerMarket);

                TradingStrategy selectedStrategy = strategies.stream()
                        .filter(s -> s.getStrategyName().equalsIgnoreCase(strategyName))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("전략을 찾을 수 없습니다: " + strategyName));
                BacktestResult result = executeBacktestSingleStrategy(market, selectedStrategy, candles, initialBalancePerMarket);
                marketResults.add(result);
                profitRateByMarket.put(market, result.getTotalProfitRate());
            } catch (Exception e) {
                log.error("{} 시뮬레이션 실패: {}", market, e.getMessage());
            }
        }

        if (marketResults.isEmpty()) {
            throw new RuntimeException("시뮬레이션 결과가 없습니다.");
        }

        // 통계 계산
        double totalFinalAsset = marketResults.stream().mapToDouble(BacktestResult::getFinalTotalAsset).sum();
        double totalInitialBalance = initialBalancePerMarket * marketResults.size();
        double totalProfitRate = ((totalFinalAsset - totalInitialBalance) / totalInitialBalance) * 100;
        double averageProfitRate = marketResults.stream().mapToDouble(BacktestResult::getTotalProfitRate).average().orElse(0);
        double averageWinRate = marketResults.stream().mapToDouble(BacktestResult::getWinRate).average().orElse(0);

        int profitableMarkets = (int) marketResults.stream().filter(r -> r.getTotalProfitRate() > 0).count();
        int losingMarkets = marketResults.size() - profitableMarkets;

        BacktestResult best = marketResults.stream().max(Comparator.comparing(BacktestResult::getTotalProfitRate)).orElse(null);
        BacktestResult worst = marketResults.stream().min(Comparator.comparing(BacktestResult::getTotalProfitRate)).orElse(null);

        log.info("========== 멀티 코인 시뮬레이션 완료 ==========");
        log.info("총 수익률: {}%, 수익 마켓: {}/{}", String.format("%.2f", totalProfitRate), profitableMarkets, marketResults.size());

        return MultiCoinBacktestResult.builder()
                .strategy("Real Trading (Majority Vote: " + ((strategies.size() / 2) + 1) + "/" + strategies.size() + ")")
                .totalMarkets(marketResults.size())
                .initialBalancePerMarket(initialBalancePerMarket)
                .totalInitialBalance(totalInitialBalance)
                .totalFinalAsset(Math.round(totalFinalAsset * 100.0) / 100.0)
                .totalProfitRate(Math.round(totalProfitRate * 100.0) / 100.0)
                .averageProfitRate(Math.round(averageProfitRate * 100.0) / 100.0)
                .averageWinRate(Math.round(averageWinRate * 10.0) / 10.0)
                .profitableMarkets(profitableMarkets)
                .losingMarkets(losingMarkets)
                .bestMarket(best != null ? best.getMarket() : null)
                .bestMarketProfitRate(best != null ? best.getTotalProfitRate() : null)
                .worstMarket(worst != null ? worst.getMarket() : null)
                .worstMarketProfitRate(worst != null ? worst.getTotalProfitRate() : null)
                .marketResults(marketResults)
                .profitRateByMarket(profitRateByMarket)
                .build();
    }

    /**
     * 실제 매매 로직 시뮬레이션 (과반수 전략 동의 + 손절/익절)
     */
    private BacktestResult executeRealTradingSimulation(String market, List<Candle> candles, double initialBalance) {
        double krwBalance = initialBalance;
        double coinBalance = 0;
        double lastBuyPrice = 0;
        double maxPriceAfterBuy = 0;

        List<TradeRecord> tradeHistory = new ArrayList<>();
        double maxTotalAsset = initialBalance;
        double minTotalAsset = initialBalance;
        int winCount = 0;
        int loseCount = 0;

        int minRequiredCandles = 100;  // 충분한 데이터 확보
        int threshold = (strategies.size() / 2) + 1;  // 과반수 기준

        for (int i = minRequiredCandles; i < candles.size(); i++) {
            List<Candle> currentCandles = new ArrayList<>(candles.subList(0, i + 1));
            Collections.reverse(currentCandles);

            Candle currentCandle = candles.get(i);
            double currentPrice = currentCandle.getTradePrice().doubleValue();

            // 포지션 보유 중일 때 손절/익절 체크
            if (coinBalance > 0 && lastBuyPrice > 0) {
                double profitRate = (currentPrice - lastBuyPrice) / lastBuyPrice;
                maxPriceAfterBuy = Math.max(maxPriceAfterBuy, currentPrice);
                double dropFromHigh = (maxPriceAfterBuy - currentPrice) / maxPriceAfterBuy;

                boolean stopLoss = profitRate <= STOP_LOSS_RATE;
                boolean takeProfit = profitRate >= TAKE_PROFIT_RATE;
                boolean trailingStop = profitRate > 0.02 && dropFromHigh >= TRAILING_STOP_RATE;

                if (stopLoss || takeProfit || trailingStop) {
                    double sellAmount = coinBalance * currentPrice;
                    double fee = sellAmount * TRADING_FEE;
                    double actualAmount = sellAmount - fee;

                    if (currentPrice > lastBuyPrice) winCount++;
                    else loseCount++;

                    krwBalance += actualAmount;
                    double prevCoinBalance = coinBalance;
                    coinBalance = 0;
                    maxPriceAfterBuy = 0;

                    String reason = stopLoss ? "STOP_LOSS" : takeProfit ? "TAKE_PROFIT" : "TRAILING_STOP";
                    tradeHistory.add(TradeRecord.builder()
                            .timestamp(currentCandle.getCandleDateTimeKst())
                            .type("SELL")
                            .price(currentPrice)
                            .volume(prevCoinBalance)
                            .amount(sellAmount)
                            .balance(krwBalance)
                            .coinBalance(0.0)
                            .totalAsset(krwBalance)
                            .profitRate(((krwBalance - initialBalance) / initialBalance) * 100)
                            .strategy(reason + " (" + String.format("%.1f", profitRate * 100) + "%)")
                            .build());
                    continue;
                }
            }

            // 전략 분석 (모든 전략 실행)
            int buySignals = 0;
            int sellSignals = 0;
            List<String> buyStrategies = new ArrayList<>();
            List<String> sellStrategies = new ArrayList<>();

            for (TradingStrategy strategy : strategies) {
                try {
                    int signal = strategy.analyze(market, currentCandles);
                    if (signal == 1) {
                        buySignals++;
                        buyStrategies.add(strategy.getStrategyName());
                    } else if (signal == -1) {
                        sellSignals++;
                        sellStrategies.add(strategy.getStrategyName());
                    }
                } catch (Exception e) {
                    // 분석 실패 무시
                }
            }

            // 매수: 과반수 이상 매수 신호 + 포지션 없음
            if (buySignals >= threshold && krwBalance > 5000 && coinBalance == 0) {
                double buyAmount = krwBalance * 0.99;
                double fee = buyAmount * TRADING_FEE;
                double actualAmount = buyAmount - fee;
                double volume = actualAmount / currentPrice;

                coinBalance = volume;
                krwBalance -= buyAmount;
                lastBuyPrice = currentPrice;
                maxPriceAfterBuy = currentPrice;

                double totalAsset = krwBalance + (coinBalance * currentPrice);
                String strategyInfo = buySignals + "/" + strategies.size() + " 동의: " + String.join(", ", buyStrategies);

                tradeHistory.add(TradeRecord.builder()
                        .timestamp(currentCandle.getCandleDateTimeKst())
                        .type("BUY")
                        .price(currentPrice)
                        .volume(volume)
                        .amount(buyAmount)
                        .balance(krwBalance)
                        .coinBalance(coinBalance)
                        .totalAsset(totalAsset)
                        .profitRate(((totalAsset - initialBalance) / initialBalance) * 100)
                        .strategy(strategyInfo)
                        .build());

                log.debug("[매수] {} - {}", currentCandle.getCandleDateTimeKst(), strategyInfo);
            }
            // 매도: 과반수 이상 매도 신호 + 포지션 있음
            else if (sellSignals >= threshold && coinBalance > 0) {
                double sellAmount = coinBalance * currentPrice;
                double fee = sellAmount * TRADING_FEE;
                double actualAmount = sellAmount - fee;

                if (currentPrice > lastBuyPrice) winCount++;
                else loseCount++;

                krwBalance += actualAmount;
                double prevCoinBalance = coinBalance;
                coinBalance = 0;
                maxPriceAfterBuy = 0;

                String strategyInfo = sellSignals + "/" + strategies.size() + " 동의: " + String.join(", ", sellStrategies);

                tradeHistory.add(TradeRecord.builder()
                        .timestamp(currentCandle.getCandleDateTimeKst())
                        .type("SELL")
                        .price(currentPrice)
                        .volume(prevCoinBalance)
                        .amount(sellAmount)
                        .balance(krwBalance)
                        .coinBalance(0.0)
                        .totalAsset(krwBalance)
                        .profitRate(((krwBalance - initialBalance) / initialBalance) * 100)
                        .strategy(strategyInfo)
                        .build());

                log.debug("[매도] {} - {}", currentCandle.getCandleDateTimeKst(), strategyInfo);
            }

            double currentTotalAsset = krwBalance + (coinBalance * currentPrice);
            maxTotalAsset = Math.max(maxTotalAsset, currentTotalAsset);
            minTotalAsset = Math.min(minTotalAsset, currentTotalAsset);
        }

        // 결과 계산
        Candle firstCandle = candles.get(0);
        Candle lastCandle = candles.get(candles.size() - 1);
        double finalPrice = lastCandle.getTradePrice().doubleValue();
        double firstPrice = firstCandle.getTradePrice().doubleValue();

        double finalCoinValue = coinBalance * finalPrice;
        double finalTotalAsset = krwBalance + finalCoinValue;
        double totalProfitRate = ((finalTotalAsset - initialBalance) / initialBalance) * 100;
        double maxProfitRate = ((maxTotalAsset - initialBalance) / initialBalance) * 100;
        double maxLossRate = ((minTotalAsset - initialBalance) / initialBalance) * 100;
        double buyAndHoldRate = ((finalPrice - firstPrice) / firstPrice) * 100;

        int totalTrades = tradeHistory.size();
        int buyCount = (int) tradeHistory.stream().filter(t -> "BUY".equals(t.getType())).count();
        int sellCount = (int) tradeHistory.stream().filter(t -> "SELL".equals(t.getType())).count();
        double winRate = (winCount + loseCount) > 0 ? ((double) winCount / (winCount + loseCount)) * 100 : 0;

        log.info("[{}] 수익률: {}%, 단순보유: {}%, 거래: {}회, 승률: {}%",
                market,
                String.format("%.2f", totalProfitRate),
                String.format("%.2f", buyAndHoldRate),
                totalTrades,
                String.format("%.1f", winRate));

        return BacktestResult.builder()
                .market(market)
                .strategy("Real Trading (Majority " + threshold + "/" + strategies.size() + ")")
                .startDate(firstCandle.getCandleDateTimeKst())
                .endDate(lastCandle.getCandleDateTimeKst())
                .totalDays(candles.size())
                .initialBalance(initialBalance)
                .finalBalance(krwBalance)
                .finalCoinBalance(coinBalance)
                .finalCoinValue(finalCoinValue)
                .finalTotalAsset(finalTotalAsset)
                .totalProfitRate(Math.round(totalProfitRate * 100.0) / 100.0)
                .maxProfitRate(Math.round(maxProfitRate * 100.0) / 100.0)
                .maxLossRate(Math.round(maxLossRate * 100.0) / 100.0)
                .buyAndHoldRate(Math.round(buyAndHoldRate * 100.0) / 100.0)
                .totalTrades(totalTrades)
                .buyCount(buyCount)
                .sellCount(sellCount)
                .winCount(winCount)
                .loseCount(loseCount)
                .winRate(Math.round(winRate * 10.0) / 10.0)
                .tradeHistory(tradeHistory)
                .build();
    }

    /**
     * 백테스팅 실행 (전략 조합 - 다수결)
     */
    private BacktestResult executeBacktest(String market, String strategyName,
                                           List<Candle> candles, double initialBalance) {
        double krwBalance = initialBalance;
        double coinBalance = 0;
        double lastBuyPrice = 0;

        List<TradeRecord> tradeHistory = new ArrayList<>();
        double maxTotalAsset = initialBalance;
        double minTotalAsset = initialBalance;
        int winCount = 0;
        int loseCount = 0;

        // 분석에 필요한 최소 캔들 수
        int minRequiredCandles = 30;

        for (int i = minRequiredCandles; i < candles.size(); i++) {
            // 현재 시점까지의 캔들 데이터 (최신순으로 다시 정렬)
            List<Candle> currentCandles = new ArrayList<>(candles.subList(0, i + 1));
            Collections.reverse(currentCandles);

            Candle currentCandle = candles.get(i);
            double currentPrice = currentCandle.getTradePrice().doubleValue();

            // 전략 분석 (다수결)
            int buySignals = 0;
            int sellSignals = 0;
            StringBuilder signalStrategies = new StringBuilder();

            for (TradingStrategy strategy : strategies) {
                try {
                    int signal = strategy.analyze(market, currentCandles);
                    if (signal == 1) {
                        buySignals++;
                        signalStrategies.append(strategy.getStrategyName()).append(" ");
                    } else if (signal == -1) {
                        sellSignals++;
                        signalStrategies.append(strategy.getStrategyName()).append(" ");
                    }
                } catch (Exception e) {
                    // 데이터 부족 등의 이유로 분석 실패 시 무시
                }
            }

            int threshold = (strategies.size() / 2) + 1;

            // 매수 신호
            if (buySignals >= threshold && krwBalance > 5000) {
                double buyAmount = krwBalance * 0.99;  // 99% 투자 (수수료 고려)
                double fee = buyAmount * TRADING_FEE;
                double actualAmount = buyAmount - fee;
                double volume = actualAmount / currentPrice;

                coinBalance += volume;
                krwBalance -= buyAmount;
                lastBuyPrice = currentPrice;

                double totalAsset = krwBalance + (coinBalance * currentPrice);

                TradeRecord record = TradeRecord.builder()
                        .timestamp(currentCandle.getCandleDateTimeKst())
                        .type("BUY")
                        .price(currentPrice)
                        .volume(volume)
                        .amount(buyAmount)
                        .balance(krwBalance)
                        .coinBalance(coinBalance)
                        .totalAsset(totalAsset)
                        .profitRate(((totalAsset - initialBalance) / initialBalance) * 100)
                        .strategy(signalStrategies.toString().trim())
                        .build();

                tradeHistory.add(record);
                log.debug("[BUY] {} 가격: {}, 수량: {}", currentCandle.getCandleDateTimeKst(), currentPrice, volume);
            }
            // 매도 신호
            else if (sellSignals >= threshold && coinBalance > 0) {
                double sellAmount = coinBalance * currentPrice;
                double fee = sellAmount * TRADING_FEE;
                double actualAmount = sellAmount - fee;

                // 승패 판정
                if (currentPrice > lastBuyPrice) {
                    winCount++;
                } else {
                    loseCount++;
                }

                krwBalance += actualAmount;
                coinBalance = 0;

                double totalAsset = krwBalance;

                TradeRecord record = TradeRecord.builder()
                        .timestamp(currentCandle.getCandleDateTimeKst())
                        .type("SELL")
                        .price(currentPrice)
                        .volume(coinBalance)
                        .amount(sellAmount)
                        .balance(krwBalance)
                        .coinBalance(0.0)
                        .totalAsset(totalAsset)
                        .profitRate(((totalAsset - initialBalance) / initialBalance) * 100)
                        .strategy(signalStrategies.toString().trim())
                        .build();

                tradeHistory.add(record);
                log.debug("[SELL] {} 가격: {}, 금액: {}", currentCandle.getCandleDateTimeKst(), currentPrice, actualAmount);
            }

            // 최대/최소 자산 갱신
            double currentTotalAsset = krwBalance + (coinBalance * currentPrice);
            maxTotalAsset = Math.max(maxTotalAsset, currentTotalAsset);
            minTotalAsset = Math.min(minTotalAsset, currentTotalAsset);
        }

        // 최종 결과 계산
        Candle firstCandle = candles.get(0);
        Candle lastCandle = candles.get(candles.size() - 1);
        double finalPrice = lastCandle.getTradePrice().doubleValue();
        double firstPrice = firstCandle.getTradePrice().doubleValue();

        double finalCoinValue = coinBalance * finalPrice;
        double finalTotalAsset = krwBalance + finalCoinValue;
        double totalProfitRate = ((finalTotalAsset - initialBalance) / initialBalance) * 100;
        double maxProfitRate = ((maxTotalAsset - initialBalance) / initialBalance) * 100;
        double maxLossRate = ((minTotalAsset - initialBalance) / initialBalance) * 100;
        double buyAndHoldRate = ((finalPrice - firstPrice) / firstPrice) * 100;

        int totalTrades = tradeHistory.size();
        int buyCount = (int) tradeHistory.stream().filter(t -> "BUY".equals(t.getType())).count();
        int sellCount = (int) tradeHistory.stream().filter(t -> "SELL".equals(t.getType())).count();
        double winRate = (winCount + loseCount) > 0 ? ((double) winCount / (winCount + loseCount)) * 100 : 0;

        log.info("========== 백테스팅 결과 ==========");
        log.info("총 수익률: {}%", String.format("%.2f", totalProfitRate));
        log.info("단순보유 수익률: {}%", String.format("%.2f", buyAndHoldRate));
        log.info("총 거래: {}회 (매수: {}, 매도: {})", totalTrades, buyCount, sellCount);
        log.info("승률: {}% ({}/{})", String.format("%.1f", winRate), winCount, winCount + loseCount);

        return BacktestResult.builder()
                .market(market)
                .strategy(strategyName)
                .startDate(firstCandle.getCandleDateTimeKst())
                .endDate(lastCandle.getCandleDateTimeKst())
                .totalDays(candles.size())
                .initialBalance(initialBalance)
                .finalBalance(krwBalance)
                .finalCoinBalance(coinBalance)
                .finalCoinValue(finalCoinValue)
                .finalTotalAsset(finalTotalAsset)
                .totalProfitRate(Math.round(totalProfitRate * 100.0) / 100.0)
                .maxProfitRate(Math.round(maxProfitRate * 100.0) / 100.0)
                .maxLossRate(Math.round(maxLossRate * 100.0) / 100.0)
                .buyAndHoldRate(Math.round(buyAndHoldRate * 100.0) / 100.0)
                .totalTrades(totalTrades)
                .buyCount(buyCount)
                .sellCount(sellCount)
                .winCount(winCount)
                .loseCount(loseCount)
                .winRate(Math.round(winRate * 10.0) / 10.0)
                .tradeHistory(tradeHistory)
                .build();
    }

    /**
     * 단일 전략 백테스팅 (실제 전략 로직 사용)
     * - analyzeForBacktest를 호출하여 실제 매매와 동일한 로직으로 시뮬레이션
     * - 성능 최적화: 미리 역순 리스트를 생성하여 O(n²) -> O(n)으로 개선
     */
    private BacktestResult executeBacktestSingleStrategy(String market, TradingStrategy strategy,
                                                         List<Candle> candles, double initialBalance) {
        double krwBalance = initialBalance;
        double coinBalance = 0;
        double lastBuyPrice = 0;
        double maxPriceAfterBuy = 0;
        Double targetPrice = null;

        List<TradeRecord> tradeHistory = new ArrayList<>();
        double maxTotalAsset = initialBalance;
        double minTotalAsset = initialBalance;
        int winCount = 0;
        int loseCount = 0;

        int minRequiredCandles = 30;

        // 종료 사유 통계용 맵
        Map<ExitReason, Integer> exitReasonStats = new EnumMap<>(ExitReason.class);

        // 백테스트용 포지션 객체 (전략에 전달)
        BacktestPosition position = BacktestPosition.empty();

        // 성능 최적화: 미리 역순 리스트 생성 (O(n) 한 번만)
        // 기존: 매 반복마다 복사 + 역순 = O(n²)
        // 개선: 미리 역순 생성 + subList 뷰 사용 = O(n)
        List<Candle> reversedCandles = new ArrayList<>(candles);
        Collections.reverse(reversedCandles);
        int totalSize = candles.size();

        for (int i = minRequiredCandles; i < totalSize; i++) {
            // reversedCandles에서 뒤쪽 (i+1)개가 candles[0..i]의 역순
            // 예: candles = [A,B,C,D,E], reversedCandles = [E,D,C,B,A]
            // i=2 -> candles[0..2] = [A,B,C] 역순 = [C,B,A] = reversedCandles[2..5]
            int fromIndex = totalSize - (i + 1);
            List<Candle> currentCandles = reversedCandles.subList(fromIndex, totalSize);

            Candle currentCandle = candles.get(i);
            double currentPrice = currentCandle.getTradePrice().doubleValue();

            // 포지션 정보 업데이트 (최고가 갱신)
            if (coinBalance > 0) {
                position.updateHighestPrice(currentPrice);
                maxPriceAfterBuy = Math.max(maxPriceAfterBuy, currentPrice);
            }

            // 전략의 analyzeForBacktest 호출 (실제 매매와 동일한 로직)
            int signal;
            BacktestContext.clear(); // 호출 전 클리어
            try {
                signal = strategy.analyzeForBacktest(market, currentCandles, position);
            } catch (Exception e) {
                continue;
            }

            // 매수 신호
            if (signal == 1 && krwBalance > 5000 && coinBalance == 0) {
                double buyAmount = krwBalance * 0.99;
                double fee = buyAmount * TRADING_FEE;
                double actualAmount = buyAmount - fee;
                double volume = actualAmount / currentPrice;

                coinBalance = volume;
                krwBalance -= buyAmount;
                lastBuyPrice = currentPrice;
                maxPriceAfterBuy = currentPrice;

                // 전략에서 목표가 가져오기
                targetPrice = strategy.getTargetPriceForBacktest();

                // 포지션 업데이트
                position = BacktestPosition.buy(currentPrice, volume, targetPrice);
                position.setBuyTime(LocalDateTime.parse(currentCandle.getCandleDateTimeKst()));

                double totalAsset = krwBalance + (coinBalance * currentPrice);

                tradeHistory.add(TradeRecord.builder()
                        .timestamp(currentCandle.getCandleDateTimeKst())
                        .type("BUY")
                        .price(currentPrice)
                        .volume(volume)
                        .amount(buyAmount)
                        .balance(krwBalance)
                        .coinBalance(coinBalance)
                        .totalAsset(totalAsset)
                        .profitRate(((totalAsset - initialBalance) / initialBalance) * 100)
                        .strategy(strategy.getStrategyName())
                        .build());

                log.debug("[백테스트][매수] {} - 가격: {}, 목표가: {}",
                        currentCandle.getCandleDateTimeKst(), currentPrice, targetPrice);
            }
            // 매도 신호 (전략이 -1 반환)
            else if (signal == -1 && coinBalance > 0) {
                // 전략에서 설정한 종료 사유 가져오기
                ExitReason exitReason = BacktestContext.getExitReason();
                if (exitReason == null) {
                    // 명시되지 않은 경우 기본값 추정
                    double profitRate = (currentPrice - lastBuyPrice) / lastBuyPrice;
                    exitReason = profitRate > 0 ? ExitReason.TAKE_PROFIT : ExitReason.STOP_LOSS_FIXED;
                }

                // 통계 업데이트
                exitReasonStats.put(exitReason, exitReasonStats.getOrDefault(exitReason, 0) + 1);

                double sellAmount = coinBalance * currentPrice;
                double fee = sellAmount * TRADING_FEE;
                double actualAmount = sellAmount - fee;

                if (currentPrice > lastBuyPrice) winCount++;
                else loseCount++;

                krwBalance += actualAmount;
                double prevCoinBalance = coinBalance;
                coinBalance = 0;
                maxPriceAfterBuy = 0;

                // 포지션 초기화
                position = BacktestPosition.empty();

                tradeHistory.add(TradeRecord.builder()
                        .timestamp(currentCandle.getCandleDateTimeKst())
                        .type("SELL")
                        .price(currentPrice)
                        .volume(prevCoinBalance)
                        .amount(sellAmount)
                        .balance(krwBalance)
                        .coinBalance(0.0)
                        .totalAsset(krwBalance)
                        .profitRate(((krwBalance - initialBalance) / initialBalance) * 100)
                        .strategy(strategy.getStrategyName())
                        .exitReason(exitReason)
                        .build());

                log.debug("[백테스트][매도] {} - 가격: {}, 사유: {}",
                        currentCandle.getCandleDateTimeKst(), currentPrice, exitReason);
            }

            double currentTotalAsset = krwBalance + (coinBalance * currentPrice);
            maxTotalAsset = Math.max(maxTotalAsset, currentTotalAsset);
            minTotalAsset = Math.min(minTotalAsset, currentTotalAsset);
        }

        // 결과 계산
        Candle firstCandle = candles.get(0);
        Candle lastCandle = candles.get(candles.size() - 1);
        double finalPrice = lastCandle.getTradePrice().doubleValue();
        double firstPrice = firstCandle.getTradePrice().doubleValue();

        double finalCoinValue = coinBalance * finalPrice;
        double finalTotalAsset = krwBalance + finalCoinValue;
        double totalProfitRate = ((finalTotalAsset - initialBalance) / initialBalance) * 100;
        double maxProfitRate = ((maxTotalAsset - initialBalance) / initialBalance) * 100;
        double maxLossRate = ((minTotalAsset - initialBalance) / initialBalance) * 100;
        double buyAndHoldRate = ((finalPrice - firstPrice) / firstPrice) * 100;

        int totalTrades = tradeHistory.size();
        int buyCount = (int) tradeHistory.stream().filter(t -> "BUY".equals(t.getType())).count();
        int sellCount = (int) tradeHistory.stream().filter(t -> "SELL".equals(t.getType())).count();
        double winRate = (winCount + loseCount) > 0 ? ((double) winCount / (winCount + loseCount)) * 100 : 0;

        // 통계 출력
        System.out.println("\n===== Exit Reason Statistics =====");
        exitReasonStats.forEach((reason, count) ->
                System.out.printf("%-17s : %d\n", reason, count));
        System.out.println("==================================\n");

        log.info("[{}] {} 전략 - 수익률: {}%, 단순보유: {}%, 거래: {}회, 승률: {}%",
                market, strategy.getStrategyName(),
                String.format("%.2f", totalProfitRate),
                String.format("%.2f", buyAndHoldRate),
                totalTrades,
                String.format("%.1f", winRate));

        return BacktestResult.builder()
                .market(market)
                .strategy(strategy.getStrategyName())
                .startDate(firstCandle.getCandleDateTimeKst())
                .endDate(lastCandle.getCandleDateTimeKst())
                .totalDays(candles.size())
                .initialBalance(initialBalance)
                .finalBalance(krwBalance)
                .finalCoinBalance(coinBalance)
                .finalCoinValue(finalCoinValue)
                .finalTotalAsset(finalTotalAsset)
                .totalProfitRate(Math.round(totalProfitRate * 100.0) / 100.0)
                .maxProfitRate(Math.round(maxProfitRate * 100.0) / 100.0)
                .maxLossRate(Math.round(maxLossRate * 100.0) / 100.0)
                .buyAndHoldRate(Math.round(buyAndHoldRate * 100.0) / 100.0)
                .totalTrades(totalTrades)
                .buyCount(buyCount)
                .sellCount(sellCount)
                .winCount(winCount)
                .loseCount(loseCount)
                .winRate(Math.round(winRate * 10.0) / 10.0)
                .exitReasonStats(exitReasonStats)
                .tradeHistory(tradeHistory)
                .build();
    }

    /**
     * DB의 캔들 데이터를 Candle DTO 리스트로 변환
     */
    private List<Candle> convertToCandles(List<CandleData> candleDataList) {
        return candleDataList.stream()
                .map(data -> Candle.builder()
                        .market(data.getMarket())
                        .candleDateTimeUtc(data.getCandleDateTimeUtc())
                        .candleDateTimeKst(data.getCandleDateTimeKst())
                        .openingPrice(data.getOpeningPrice())
                        .highPrice(data.getHighPrice())
                        .lowPrice(data.getLowPrice())
                        .tradePrice(data.getTradePrice())
                        .timestamp(data.getTimestamp())
                        .candleAccTradePrice(data.getCandleAccTradePrice())
                        .candleAccTradeVolume(data.getCandleAccTradeVolume())
                        .unit(data.getUnit())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * DB 데이터를 사용한 백테스팅 실행
     */
    public BacktestResult runBacktestFromDb(String market, double initialBalance, Integer unit) {
        log.info("========== DB 데이터 기반 백테스팅 시작 ==========");
        List<CandleData> candleDataList;
        if (unit != null) {
            candleDataList = candleDataRepository.findByMarketAndUnitOrderByCandleDateTimeKstAsc(market, unit);
        } else {
            candleDataList = candleDataRepository.findByMarketOrderByCandleDateTimeKstAsc(market);
        }

        if (candleDataList.isEmpty()) {
            throw new RuntimeException("DB에 해당 마켓의 캔들 데이터가 없습니다: " + market);
        }

        List<Candle> candles = convertToCandles(candleDataList);
        return executeBacktest(market, "Combined (DB Data)", candles, initialBalance);
    }

    /**
     * DB 데이터를 사용한 실제 매매 시뮬레이션
     */
    public BacktestResult runRealTradingSimulationFromDb(String market, double initialBalance, Integer unit) {
        log.info("========== DB 데이터 기반 실제 매매 시뮬레이션 시작 ==========");
        List<CandleData> candleDataList;
        if (unit != null) {
            candleDataList = candleDataRepository.findByMarketAndUnitOrderByCandleDateTimeKstAsc(market, unit);
        } else {
            candleDataList = candleDataRepository.findByMarketOrderByCandleDateTimeKstAsc(market);
        }

        if (candleDataList.isEmpty()) {
            throw new RuntimeException("DB에 해당 마켓의 캔들 데이터가 없습니다: " + market);
        }

        List<Candle> candles = convertToCandles(candleDataList);
        return executeRealTradingSimulation(market, candles, initialBalance);
    }

    /**
     * DB 데이터를 사용한 단일 전략 백테스팅
     */
    public BacktestResult runBacktestWithStrategyFromDb(String market, String strategyName,
                                                        double initialBalance, Integer unit) {
        log.info("========== DB 데이터 기반 {} 전략 백테스팅 시작 ==========", strategyName);

        List<CandleData> candleDataList;
        if (unit != null) {
            candleDataList = candleDataRepository.findByMarketAndUnitOrderByCandleDateTimeKstAsc(market, unit);
        } else {
            candleDataList = candleDataRepository.findByMarketOrderByCandleDateTimeKstAsc(market);
        }

        if (candleDataList.isEmpty()) {
            throw new RuntimeException("DB에 해당 마켓의 캔들 데이터가 없습니다: " + market);
        }

        List<Candle> candles = convertToCandles(candleDataList);
        log.info("DB에서 {}개의 캔들 데이터 로드 완료", candles.size());

        TradingStrategy selectedStrategy = strategies.stream()
                .filter(s -> s.getStrategyName().equalsIgnoreCase(strategyName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("전략을 찾을 수 없습니다: " + strategyName));

        return executeBacktestSingleStrategy(market, selectedStrategy, candles, initialBalance);
    }

    /**
     * yyyyMMdd 형식을 yyyy-MM-dd 형식으로 변환
     */
    private String convertDateFormat(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.length() != 8) {
            return yyyyMMdd;
        }
        return yyyyMMdd.substring(0, 4) + "-" + yyyyMMdd.substring(4, 6) + "-" + yyyyMMdd.substring(6, 8);
    }

    /**
     * yyyy-MM-dd 형식 날짜의 다음 날 계산
     */
    private String getNextDay(String yyyyMMddWithDash) {
        if (yyyyMMddWithDash == null || yyyyMMddWithDash.length() != 10) {
            return yyyyMMddWithDash;
        }
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(yyyyMMddWithDash);
            return date.plusDays(1).toString();
        } catch (Exception e) {
            return yyyyMMddWithDash;
        }
    }

    /**
     * DB 데이터를 사용한 멀티 코인 단일 전략 백테스팅 (병렬 처리)
     */
    public MultiCoinBacktestResult runMultiCoinBacktestFromDb(List<String> markets, String strategyName,
                                                              double initialBalancePerMarket, Integer unit,
                                                              String startDate, String endDate) {
        log.info("========== DB 데이터 기반 멀티 코인 백테스팅 시작 (병렬 처리) ==========");
        log.info("마켓 수: {}, 전략: {}, 마켓당 자본: {}", markets.size(), strategyName, initialBalancePerMarket);

        // yyyyMMdd -> yyyy-MM-dd 변환
        String formattedStartDate = convertDateFormat(startDate);
        String formattedEndDate = convertDateFormat(endDate);
        String endDateNext = (formattedStartDate != null && formattedEndDate != null
                && !formattedStartDate.isEmpty() && !formattedEndDate.isEmpty())
                ? getNextDay(formattedEndDate) : null;

        log.info("원본 날짜 - startDate: {}, endDate: {}", startDate, endDate);
        log.info("변환된 날짜 - startDate: {}, endDate: {}, endDateNext: {}", formattedStartDate, formattedEndDate, endDateNext);

        // 스레드 안전한 컬렉션 사용
        List<BacktestResult> marketResults = Collections.synchronizedList(new ArrayList<>());
        Map<String, Double> profitRateByMarket = new java.util.concurrent.ConcurrentHashMap<>();

        TradingStrategy selectedStrategy = null;
        if (strategyName != null && !strategyName.isEmpty()) {
            selectedStrategy = strategies.stream()
                    .filter(s -> s.getStrategyName().equalsIgnoreCase(strategyName))
                    .findFirst()
                    .orElse(null);
        }

        final TradingStrategy finalSelectedStrategy = selectedStrategy;
        final String finalStartDate = formattedStartDate;
        final String finalEndDateNext = endDateNext;

        // 병렬 처리를 위한 CompletableFuture 리스트
        List<java.util.concurrent.CompletableFuture<Void>> futures = markets.stream()
                .map(market -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        log.info("DB 백테스팅 진행 중: {}", market);

                        List<CandleData> candleDataList;
                        // 날짜 필터가 있는 경우에만 날짜 조건 적용
                        if (finalStartDate != null && finalEndDateNext != null
                                && !finalStartDate.isEmpty() && !finalEndDateNext.isEmpty()) {
                            log.info("날짜 필터 적용: {} ~ {}", finalStartDate, finalEndDateNext);
                            if (unit != null) {
                                candleDataList = candleDataRepository.findByMarketAndUnitAndDateRange(market, unit, finalStartDate, finalEndDateNext);
                            } else {
                                candleDataList = candleDataRepository.findByMarketAndDateRange(market, finalStartDate, finalEndDateNext);
                            }
                        } else {
                            // 날짜 필터 없이 전체 조회
                            log.info("날짜 필터 없음 - 전체 조회");
                            if (unit != null) {
                                candleDataList = candleDataRepository.findByMarketAndUnitOrderByCandleDateTimeKstAsc(market, unit);
                            } else {
                                candleDataList = candleDataRepository.findByMarketOrderByCandleDateTimeKstAsc(market);
                            }
                        }

                        log.info("{} 조회 결과: {}개", market, candleDataList.size());

                        if (candleDataList.isEmpty() || candleDataList.size() < 50) {
                            log.warn("{} DB 캔들 데이터 부족 ({}개), 스킵", market, candleDataList.size());
                            return;
                        }

                        List<Candle> candles = convertToCandles(candleDataList);

                        BacktestResult result;
                        if (finalSelectedStrategy != null) {
                            result = executeBacktestSingleStrategy(market, finalSelectedStrategy, candles, initialBalancePerMarket);
                        } else {
                            result = executeBacktest(market, "Combined", candles, initialBalancePerMarket);
                        }

                        marketResults.add(result);
                        profitRateByMarket.put(market, result.getTotalProfitRate());

                    } catch (Exception e) {
                        log.error("{} DB 백테스팅 실패: {}", market, e.getMessage());
                    }
                }))
                .collect(Collectors.toList());

        // 모든 작업 완료 대기
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

        if (marketResults.isEmpty()) {
            throw new RuntimeException("DB 백테스팅 결과가 없습니다.");
        }

        // 통계 계산
        double totalFinalAsset = marketResults.stream()
                .mapToDouble(BacktestResult::getFinalTotalAsset)
                .sum();

        double totalInitialBalance = initialBalancePerMarket * marketResults.size();
        double totalProfitRate = ((totalFinalAsset - totalInitialBalance) / totalInitialBalance) * 100;
        double averageProfitRate = marketResults.stream()
                .mapToDouble(BacktestResult::getTotalProfitRate)
                .average()
                .orElse(0);
        double averageWinRate = marketResults.stream()
                .mapToDouble(BacktestResult::getWinRate)
                .average()
                .orElse(0);

        int profitableMarkets = (int) marketResults.stream()
                .filter(r -> r.getTotalProfitRate() > 0)
                .count();
        int losingMarkets = marketResults.size() - profitableMarkets;

        BacktestResult best = marketResults.stream()
                .max(Comparator.comparing(BacktestResult::getTotalProfitRate))
                .orElse(null);

        BacktestResult worst = marketResults.stream()
                .min(Comparator.comparing(BacktestResult::getTotalProfitRate))
                .orElse(null);

        log.info("========== DB 멀티 코인 백테스팅 완료 ==========");
        log.info("총 수익률: {}%, 평균 수익률: {}%",
                String.format("%.2f", totalProfitRate), String.format("%.2f", averageProfitRate));
        log.info("수익 마켓: {}, 손실 마켓: {}", profitableMarkets, losingMarkets);

        return MultiCoinBacktestResult.builder()
                .strategy(strategyName != null ? strategyName + " (DB Data)" : "Combined (DB Data)")
                .totalMarkets(marketResults.size())
                .initialBalancePerMarket(initialBalancePerMarket)
                .totalInitialBalance(totalInitialBalance)
                .totalFinalAsset(Math.round(totalFinalAsset * 100.0) / 100.0)
                .totalProfitRate(Math.round(totalProfitRate * 100.0) / 100.0)
                .averageProfitRate(Math.round(averageProfitRate * 100.0) / 100.0)
                .averageWinRate(Math.round(averageWinRate * 10.0) / 10.0)
                .profitableMarkets(profitableMarkets)
                .losingMarkets(losingMarkets)
                .bestMarket(best != null ? best.getMarket() : null)
                .bestMarketProfitRate(best != null ? best.getTotalProfitRate() : null)
                .worstMarket(worst != null ? worst.getMarket() : null)
                .worstMarketProfitRate(worst != null ? worst.getTotalProfitRate() : null)
                .marketResults(marketResults)
                .profitRateByMarket(profitRateByMarket)
                .build();
    }

    /**
     * DB에 저장된 마켓 목록 조회
     */
    public List<String> getAvailableMarketsFromDb() {
        List<CandleData> allData = candleDataRepository.findAll();
        return allData.stream()
                .map(CandleData::getMarket)
                .distinct()
                .collect(Collectors.toList());
    }
}
