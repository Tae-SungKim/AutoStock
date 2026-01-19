package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.backtest.BacktestService;
import autostock.taesung.com.autostock.backtest.dto.BacktestResult;
import autostock.taesung.com.autostock.backtest.dto.MultiCoinBacktestResult;
import autostock.taesung.com.autostock.backtest.dto.TradeRecord;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 백테스트 시각화 서비스
 * 차트 및 그래프에 최적화된 데이터 제공
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestVisualizationService {

    private final BacktestService backtestService;

    /**
     * 수익률 차트 데이터
     */
    @Data
    @Builder
    public static class ProfitChartData {
        private List<String> labels;           // 시간 라벨
        private List<Double> profitRates;      // 수익률
        private List<Double> totalAssets;      // 총 자산
        private List<Double> benchmarkRates;   // 벤치마크 (단순보유) 수익률
    }

    /**
     * 거래 분석 데이터
     */
    @Data
    @Builder
    public static class TradeAnalysisData {
        private int totalTrades;
        private int buyCount;
        private int sellCount;
        private int winCount;
        private int loseCount;
        private double winRate;
        private double avgProfitPerTrade;
        private double avgWinProfit;
        private double avgLossProfit;
        private double profitFactor;           // 총이익 / 총손실
        private double maxDrawdown;            // 최대 낙폭
        private double sharpeRatio;            // 샤프 비율 (단순화)
        private List<TradeDistribution> tradesByHour;      // 시간대별 거래
        private List<TradeDistribution> tradesByDayOfWeek; // 요일별 거래
    }

    /**
     * 거래 분포 데이터
     */
    @Data
    @Builder
    public static class TradeDistribution {
        private String label;
        private int count;
        private double winRate;
        private double avgProfit;
    }

    /**
     * 전략 비교 데이터
     */
    @Data
    @Builder
    public static class StrategyComparisonData {
        private List<String> strategies;
        private List<Double> profitRates;
        private List<Double> winRates;
        private List<Integer> tradeCounts;
        private List<Double> sharpeRatios;
    }

    /**
     * 멀티코인 히트맵 데이터
     */
    @Data
    @Builder
    public static class CoinHeatmapData {
        private List<String> markets;
        private List<Double> profitRates;
        private List<Double> winRates;
        private List<Integer> tradeCounts;
        private String bestMarket;
        private String worstMarket;
    }

    /**
     * 전체 시각화 데이터
     */
    @Data
    @Builder
    public static class VisualizationData {
        private BacktestResult backtestResult;
        private ProfitChartData profitChart;
        private TradeAnalysisData tradeAnalysis;
        private List<TradeMarker> tradeMarkers;  // 차트에 표시할 매수/매도 포인트
    }

    /**
     * 거래 마커 (차트 위 표시용)
     */
    @Data
    @Builder
    public static class TradeMarker {
        private String timestamp;
        private String type;        // BUY, SELL
        private double price;
        private double profitRate;
        private String strategy;
    }

    /**
     * 단일 백테스트 시각화 데이터 생성
     */
    public VisualizationData getVisualizationData(String market, String strategyName,
                                                    double initialBalance, int candleUnit, int candleCount) {
        BacktestResult result = strategyName != null && !strategyName.isEmpty() ?
                backtestService.runBacktestWithStrategy(market, strategyName, initialBalance, candleUnit, candleCount) :
                backtestService.runBacktest(market, initialBalance, candleUnit, candleCount);

        return buildVisualizationData(result);
    }

    /**
     * DB 데이터 기반 시각화 데이터 생성
     */
    public VisualizationData getVisualizationDataFromDb(String market, String strategyName,
                                                          double initialBalance, Integer unit) {
        BacktestResult result = strategyName != null && !strategyName.isEmpty() ?
                backtestService.runBacktestWithStrategyFromDb(market, strategyName, initialBalance, unit) :
                backtestService.runBacktestFromDb(market, initialBalance, unit);

        return buildVisualizationData(result);
    }

    /**
     * 전략 비교 차트 데이터
     */
    public StrategyComparisonData compareStrategies(String market, double initialBalance,
                                                      int candleUnit, int candleCount) {
        List<BacktestResult> results = backtestService.runAllStrategiesBacktest(
                market, initialBalance, candleUnit, candleCount);

        List<String> strategies = new ArrayList<>();
        List<Double> profitRates = new ArrayList<>();
        List<Double> winRates = new ArrayList<>();
        List<Integer> tradeCounts = new ArrayList<>();
        List<Double> sharpeRatios = new ArrayList<>();

        for (BacktestResult result : results) {
            strategies.add(result.getStrategy());
            profitRates.add(result.getTotalProfitRate());
            winRates.add(result.getWinRate());
            tradeCounts.add(result.getTotalTrades());
            sharpeRatios.add(calculateSimpleSharpe(result));
        }

        return StrategyComparisonData.builder()
                .strategies(strategies)
                .profitRates(profitRates)
                .winRates(winRates)
                .tradeCounts(tradeCounts)
                .sharpeRatios(sharpeRatios)
                .build();
    }

    /**
     * 멀티코인 히트맵 데이터
     */
    public CoinHeatmapData getCoinHeatmap(List<String> markets, String strategyName,
                                           double initialBalance, int candleUnit, int candleCount) {
        MultiCoinBacktestResult result = backtestService.runMultiCoinBacktest(
                markets, strategyName, initialBalance, candleUnit, candleCount);

        List<Double> profitRates = new ArrayList<>();
        List<Double> winRates = new ArrayList<>();
        List<Integer> tradeCounts = new ArrayList<>();

        for (BacktestResult mr : result.getMarketResults()) {
            profitRates.add(mr.getTotalProfitRate());
            winRates.add(mr.getWinRate());
            tradeCounts.add(mr.getTotalTrades());
        }

        return CoinHeatmapData.builder()
                .markets(new ArrayList<>(result.getProfitRateByMarket().keySet()))
                .profitRates(profitRates)
                .winRates(winRates)
                .tradeCounts(tradeCounts)
                .bestMarket(result.getBestMarket())
                .worstMarket(result.getWorstMarket())
                .build();
    }

    /**
     * DB 기반 멀티코인 히트맵 데이터
     */
    public CoinHeatmapData getCoinHeatmapFromDb(List<String> markets, String strategyName,
                                                  double initialBalance, Integer unit,
                                                String startDate, String endDate) {
        MultiCoinBacktestResult result = backtestService.runMultiCoinBacktestFromDb(
                markets, strategyName, initialBalance, unit, startDate, endDate);

        List<Double> profitRates = new ArrayList<>();
        List<Double> winRates = new ArrayList<>();
        List<Integer> tradeCounts = new ArrayList<>();

        for (BacktestResult mr : result.getMarketResults()) {
            profitRates.add(mr.getTotalProfitRate());
            winRates.add(mr.getWinRate());
            tradeCounts.add(mr.getTotalTrades());
        }

        return CoinHeatmapData.builder()
                .markets(new ArrayList<>(result.getProfitRateByMarket().keySet()))
                .profitRates(profitRates)
                .winRates(winRates)
                .tradeCounts(tradeCounts)
                .bestMarket(result.getBestMarket())
                .worstMarket(result.getWorstMarket())
                .build();
    }

    private VisualizationData buildVisualizationData(BacktestResult result) {
        ProfitChartData profitChart = buildProfitChart(result);
        TradeAnalysisData tradeAnalysis = buildTradeAnalysis(result);
        List<TradeMarker> markers = buildTradeMarkers(result);

        return VisualizationData.builder()
                .backtestResult(result)
                .profitChart(profitChart)
                .tradeAnalysis(tradeAnalysis)
                .tradeMarkers(markers)
                .build();
    }

    private ProfitChartData buildProfitChart(BacktestResult result) {
        List<String> labels = new ArrayList<>();
        List<Double> profitRates = new ArrayList<>();
        List<Double> totalAssets = new ArrayList<>();
        List<Double> benchmarkRates = new ArrayList<>();

        double initialBalance = result.getInitialBalance();
        double runningAsset = initialBalance;

        for (TradeRecord trade : result.getTradeHistory()) {
            labels.add(trade.getTimestamp());
            profitRates.add(trade.getProfitRate());
            totalAssets.add(trade.getTotalAsset());

            // 벤치마크 (단순 보유) 비율 계산 - 근사치
            double benchmarkRate = (result.getBuyAndHoldRate() *
                    (labels.size() / (double) Math.max(1, result.getTradeHistory().size())));
            benchmarkRates.add(Math.round(benchmarkRate * 100.0) / 100.0);
        }

        return ProfitChartData.builder()
                .labels(labels)
                .profitRates(profitRates)
                .totalAssets(totalAssets)
                .benchmarkRates(benchmarkRates)
                .build();
    }

    private TradeAnalysisData buildTradeAnalysis(BacktestResult result) {
        List<TradeRecord> trades = result.getTradeHistory();

        // 승/패별 수익 계산
        double totalWinProfit = 0;
        double totalLossProfit = 0;
        int winCount = 0;
        int lossCount = 0;

        List<Double> tradeReturns = new ArrayList<>();
        double prevAsset = result.getInitialBalance();

        for (TradeRecord trade : trades) {
            if ("SELL".equals(trade.getType())) {
                double returnPct = ((trade.getTotalAsset() - prevAsset) / prevAsset) * 100;
                tradeReturns.add(returnPct);

                if (returnPct > 0) {
                    totalWinProfit += returnPct;
                    winCount++;
                } else {
                    totalLossProfit += Math.abs(returnPct);
                    lossCount++;
                }
            }
            prevAsset = trade.getTotalAsset();
        }

        double avgWinProfit = winCount > 0 ? totalWinProfit / winCount : 0;
        double avgLossProfit = lossCount > 0 ? totalLossProfit / lossCount : 0;
        double profitFactor = totalLossProfit > 0 ? totalWinProfit / totalLossProfit : totalWinProfit;

        // 최대 낙폭 계산
        double maxDrawdown = calculateMaxDrawdown(trades, result.getInitialBalance());

        // 단순 샤프 비율
        double sharpeRatio = calculateSimpleSharpe(result);

        // 시간대별 거래 분석
        List<TradeDistribution> tradesByHour = analyzeTradesByHour(trades);
        List<TradeDistribution> tradesByDayOfWeek = analyzeTradesByDayOfWeek(trades);

        return TradeAnalysisData.builder()
                .totalTrades(result.getTotalTrades())
                .buyCount(result.getBuyCount())
                .sellCount(result.getSellCount())
                .winCount(result.getWinCount())
                .loseCount(result.getLoseCount())
                .winRate(result.getWinRate())
                .avgProfitPerTrade(tradeReturns.isEmpty() ? 0 :
                        Math.round(tradeReturns.stream().mapToDouble(d -> d).average().orElse(0) * 100.0) / 100.0)
                .avgWinProfit(Math.round(avgWinProfit * 100.0) / 100.0)
                .avgLossProfit(Math.round(avgLossProfit * 100.0) / 100.0)
                .profitFactor(Math.round(profitFactor * 100.0) / 100.0)
                .maxDrawdown(Math.round(maxDrawdown * 100.0) / 100.0)
                .sharpeRatio(Math.round(sharpeRatio * 100.0) / 100.0)
                .tradesByHour(tradesByHour)
                .tradesByDayOfWeek(tradesByDayOfWeek)
                .build();
    }

    private List<TradeMarker> buildTradeMarkers(BacktestResult result) {
        return result.getTradeHistory().stream()
                .map(trade -> TradeMarker.builder()
                        .timestamp(trade.getTimestamp())
                        .type(trade.getType())
                        .price(trade.getPrice())
                        .profitRate(trade.getProfitRate())
                        .strategy(trade.getStrategy())
                        .build())
                .collect(Collectors.toList());
    }

    private double calculateMaxDrawdown(List<TradeRecord> trades, double initialBalance) {
        double peak = initialBalance;
        double maxDrawdown = 0;

        for (TradeRecord trade : trades) {
            double currentAsset = trade.getTotalAsset();
            if (currentAsset > peak) {
                peak = currentAsset;
            }
            double drawdown = ((peak - currentAsset) / peak) * 100;
            maxDrawdown = Math.max(maxDrawdown, drawdown);
        }

        return maxDrawdown;
    }

    private double calculateSimpleSharpe(BacktestResult result) {
        // 단순화된 샤프 비율: (수익률 - 무위험수익률) / 변동성
        // 무위험수익률 2%, 변동성은 최대손실률로 근사
        double riskFreeRate = 2.0;
        double volatility = Math.abs(result.getMaxLossRate());
        if (volatility < 0.01) volatility = 1.0;  // 0으로 나누기 방지

        return (result.getTotalProfitRate() - riskFreeRate) / volatility;
    }

    private List<TradeDistribution> analyzeTradesByHour(List<TradeRecord> trades) {
        Map<Integer, List<TradeRecord>> byHour = new HashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        for (TradeRecord trade : trades) {
            try {
                LocalDateTime dt = LocalDateTime.parse(trade.getTimestamp(), formatter);
                int hour = dt.getHour();
                byHour.computeIfAbsent(hour, k -> new ArrayList<>()).add(trade);
            } catch (Exception e) {
                // 파싱 실패 시 무시
            }
        }

        List<TradeDistribution> result = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            List<TradeRecord> hourTrades = byHour.getOrDefault(h, Collections.emptyList());
            if (!hourTrades.isEmpty()) {
                long wins = hourTrades.stream()
                        .filter(t -> "SELL".equals(t.getType()) && t.getProfitRate() > 0)
                        .count();
                long sells = hourTrades.stream()
                        .filter(t -> "SELL".equals(t.getType()))
                        .count();

                result.add(TradeDistribution.builder()
                        .label(String.format("%02d:00", h))
                        .count(hourTrades.size())
                        .winRate(sells > 0 ? (wins * 100.0 / sells) : 0)
                        .avgProfit(hourTrades.stream()
                                .mapToDouble(TradeRecord::getProfitRate)
                                .average().orElse(0))
                        .build());
            }
        }

        return result;
    }

    private List<TradeDistribution> analyzeTradesByDayOfWeek(List<TradeRecord> trades) {
        Map<Integer, List<TradeRecord>> byDayOfWeek = new HashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        String[] dayNames = {"", "월", "화", "수", "목", "금", "토", "일"};

        for (TradeRecord trade : trades) {
            try {
                LocalDateTime dt = LocalDateTime.parse(trade.getTimestamp(), formatter);
                int dayOfWeek = dt.getDayOfWeek().getValue();
                byDayOfWeek.computeIfAbsent(dayOfWeek, k -> new ArrayList<>()).add(trade);
            } catch (Exception e) {
                // 파싱 실패 시 무시
            }
        }

        List<TradeDistribution> result = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            List<TradeRecord> dayTrades = byDayOfWeek.getOrDefault(d, Collections.emptyList());
            if (!dayTrades.isEmpty()) {
                long wins = dayTrades.stream()
                        .filter(t -> "SELL".equals(t.getType()) && t.getProfitRate() > 0)
                        .count();
                long sells = dayTrades.stream()
                        .filter(t -> "SELL".equals(t.getType()))
                        .count();

                result.add(TradeDistribution.builder()
                        .label(dayNames[d])
                        .count(dayTrades.size())
                        .winRate(sells > 0 ? (wins * 100.0 / sells) : 0)
                        .avgProfit(dayTrades.stream()
                                .mapToDouble(TradeRecord::getProfitRate)
                                .average().orElse(0))
                        .build());
            }
        }

        return result;
    }
}