package autostock.taesung.com.autostock.realtrading.service;

import autostock.taesung.com.autostock.realtrading.entity.Position;
import autostock.taesung.com.autostock.realtrading.entity.Position.PositionStatus;
import autostock.taesung.com.autostock.realtrading.repository.ExecutionLogRepository;
import autostock.taesung.com.autostock.realtrading.repository.PositionRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 거래 통계 서비스
 * - 백테스트/실거래 공용 통계
 * - 성과 지표 계산
 * - 슬리피지 분석
 * - 전략별/마켓별 분석
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingStatisticsService {

    private final PositionRepository positionRepository;
    private final ExecutionLogRepository executionLogRepository;

    /**
     * 전체 성과 통계 계산
     */
    public PerformanceStats calculatePerformanceStats(Long userId, LocalDateTime since) {
        List<Position> closedPositions = positionRepository.findAll().stream()
                .filter(p -> p.getUserId().equals(userId))
                .filter(p -> p.getStatus() == PositionStatus.CLOSED)
                .filter(p -> p.getFinalExitTime() != null && p.getFinalExitTime().isAfter(since))
                .collect(Collectors.toList());

        if (closedPositions.isEmpty()) {
            return PerformanceStats.empty();
        }

        int totalTrades = closedPositions.size();
        int winningTrades = (int) closedPositions.stream()
                .filter(p -> p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int losingTrades = totalTrades - winningTrades;

        BigDecimal totalPnl = closedPositions.stream()
                .map(Position::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalProfit = closedPositions.stream()
                .map(Position::getRealizedPnl)
                .filter(pnl -> pnl.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalLoss = closedPositions.stream()
                .map(Position::getRealizedPnl)
                .filter(pnl -> pnl.compareTo(BigDecimal.ZERO) < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();

        BigDecimal totalFees = closedPositions.stream()
                .map(Position::getTotalFees)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSlippage = closedPositions.stream()
                .map(Position::getTotalSlippage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 승률 계산
        double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;

        // 손익비 계산
        BigDecimal avgWin = winningTrades > 0
                ? totalProfit.divide(BigDecimal.valueOf(winningTrades), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal avgLoss = losingTrades > 0
                ? totalLoss.divide(BigDecimal.valueOf(losingTrades), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        double profitFactor = avgLoss.compareTo(BigDecimal.ZERO) > 0
                ? avgWin.divide(avgLoss, 4, RoundingMode.HALF_UP).doubleValue()
                : 0;

        // 최대 연속 손익 계산
        int[] streaks = calculateStreaks(closedPositions);

        // MDD 계산
        BigDecimal maxDrawdown = calculateMaxDrawdown(closedPositions);

        // 샤프 비율 근사 계산
        double sharpeRatio = calculateSharpeRatio(closedPositions);

        return PerformanceStats.builder()
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(winRate)
                .totalPnl(totalPnl)
                .totalProfit(totalProfit)
                .totalLoss(totalLoss)
                .avgWin(avgWin)
                .avgLoss(avgLoss)
                .profitFactor(profitFactor)
                .maxConsecutiveWins(streaks[0])
                .maxConsecutiveLosses(streaks[1])
                .maxDrawdown(maxDrawdown)
                .sharpeRatio(sharpeRatio)
                .totalFees(totalFees)
                .totalSlippage(totalSlippage)
                .build();
    }

    /**
     * 마켓별 성과 통계
     */
    public Map<String, MarketStats> getMarketStats(Long userId, LocalDateTime since) {
        List<Object[]> results = positionRepository.getMarketPerformance(userId, since);
        Map<String, MarketStats> stats = new HashMap<>();

        for (Object[] row : results) {
            String market = (String) row[0];
            Long tradeCount = (Long) row[1];
            BigDecimal totalPnl = (BigDecimal) row[2];
            BigDecimal avgSlippage = row[3] != null ? (BigDecimal) row[3] : BigDecimal.ZERO;

            stats.put(market, MarketStats.builder()
                    .market(market)
                    .tradeCount(tradeCount.intValue())
                    .totalPnl(totalPnl)
                    .avgSlippage(avgSlippage)
                    .build());
        }

        return stats;
    }

    /**
     * 전략별 성과 통계
     */
    public Map<String, StrategyStats> getStrategyStats(Long userId) {
        List<Object[]> results = positionRepository.getStrategyPerformance(userId);
        Map<String, StrategyStats> stats = new HashMap<>();

        for (Object[] row : results) {
            String strategy = (String) row[0];
            Long tradeCount = (Long) row[1];
            BigDecimal totalPnl = (BigDecimal) row[2];
            Long winCount = (Long) row[3];

            double winRate = tradeCount > 0 ? (double) winCount / tradeCount * 100 : 0;

            stats.put(strategy, StrategyStats.builder()
                    .strategyName(strategy)
                    .tradeCount(tradeCount.intValue())
                    .totalPnl(totalPnl)
                    .winRate(winRate)
                    .build());
        }

        return stats;
    }

    /**
     * 청산 사유별 통계
     */
    public Map<String, ExitReasonStats> getExitReasonStats(Long userId) {
        List<Object[]> results = positionRepository.getExitReasonStats(userId);
        Map<String, ExitReasonStats> stats = new HashMap<>();

        for (Object[] row : results) {
            String reason = row[0] != null ? row[0].toString() : "UNKNOWN";
            Long count = (Long) row[1];
            BigDecimal totalPnl = (BigDecimal) row[2];

            stats.put(reason, ExitReasonStats.builder()
                    .exitReason(reason)
                    .count(count.intValue())
                    .totalPnl(totalPnl)
                    .build());
        }

        return stats;
    }

    /**
     * 슬리피지 분석 통계
     */
    public SlippageAnalysis getSlippageAnalysis(LocalDateTime since) {
        // 마켓별 평균 슬리피지
        List<Object[]> byMarket = executionLogRepository.getAvgSlippageByMarket(since);
        Map<String, BigDecimal> marketSlippage = new HashMap<>();
        for (Object[] row : byMarket) {
            marketSlippage.put((String) row[0], (BigDecimal) row[1]);
        }

        // 시간대별 평균 슬리피지
        List<Object[]> byHour = executionLogRepository.getAvgSlippageByHour(since);
        Map<Integer, BigDecimal> hourlySlippage = new HashMap<>();
        for (Object[] row : byHour) {
            hourlySlippage.put((Integer) row[0], (BigDecimal) row[1]);
        }

        // 총 슬리피지 비용
        BigDecimal totalSlippageCost = executionLogRepository.getTotalSlippageCost(since);

        // 체결 성공률
        Double successRate = executionLogRepository.getExecutionSuccessRate(since);

        // 평균 체결 시간
        Double avgExecTime = executionLogRepository.getAvgExecutionTimeMs();

        return SlippageAnalysis.builder()
                .marketSlippage(marketSlippage)
                .hourlySlippage(hourlySlippage)
                .totalSlippageCost(totalSlippageCost != null ? totalSlippageCost : BigDecimal.ZERO)
                .executionSuccessRate(successRate != null ? successRate : 0.0)
                .avgExecutionTimeMs(avgExecTime != null ? avgExecTime : 0.0)
                .build();
    }

    /**
     * 백테스트 vs 실거래 비교 분석
     */
    public BacktestComparison getBacktestComparison() {
        Object[] backtestStats = executionLogRepository.getBacktestSlippageStats();
        Object[] realStats = executionLogRepository.getRealSlippageStats();

        return BacktestComparison.builder()
                .backtestAvgSlippage(backtestStats[0] != null ? (BigDecimal) backtestStats[0] : BigDecimal.ZERO)
                .backtestSlippageStdDev(backtestStats[1] != null ? ((Number) backtestStats[1]).doubleValue() : 0.0)
                .backtestTradeCount(backtestStats[2] != null ? ((Number) backtestStats[2]).intValue() : 0)
                .realAvgSlippage(realStats[0] != null ? (BigDecimal) realStats[0] : BigDecimal.ZERO)
                .realSlippageStdDev(realStats[1] != null ? ((Number) realStats[1]).doubleValue() : 0.0)
                .realTradeCount(realStats[2] != null ? ((Number) realStats[2]).intValue() : 0)
                .build();
    }

    /**
     * 연속 승/패 계산
     */
    private int[] calculateStreaks(List<Position> positions) {
        int maxWinStreak = 0;
        int maxLossStreak = 0;
        int currentWinStreak = 0;
        int currentLossStreak = 0;

        // 시간순 정렬
        positions.sort((a, b) -> a.getFinalExitTime().compareTo(b.getFinalExitTime()));

        for (Position p : positions) {
            if (p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0) {
                currentWinStreak++;
                currentLossStreak = 0;
                maxWinStreak = Math.max(maxWinStreak, currentWinStreak);
            } else {
                currentLossStreak++;
                currentWinStreak = 0;
                maxLossStreak = Math.max(maxLossStreak, currentLossStreak);
            }
        }

        return new int[]{maxWinStreak, maxLossStreak};
    }

    /**
     * 최대 낙폭 (MDD) 계산
     */
    private BigDecimal calculateMaxDrawdown(List<Position> positions) {
        if (positions.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 시간순 정렬
        positions.sort((a, b) -> a.getFinalExitTime().compareTo(b.getFinalExitTime()));

        BigDecimal cumulativePnl = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (Position p : positions) {
            cumulativePnl = cumulativePnl.add(p.getRealizedPnl());

            if (cumulativePnl.compareTo(peak) > 0) {
                peak = cumulativePnl;
            }

            BigDecimal drawdown = peak.subtract(cumulativePnl);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown;
    }

    /**
     * 샤프 비율 근사 계산
     * (일별 수익률의 평균 / 표준편차)
     */
    private double calculateSharpeRatio(List<Position> positions) {
        if (positions.size() < 2) {
            return 0;
        }

        List<BigDecimal> pnls = positions.stream()
                .map(Position::getRealizedPnl)
                .collect(Collectors.toList());

        // 평균 계산
        BigDecimal sum = pnls.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(pnls.size()), 8, RoundingMode.HALF_UP);

        // 표준편차 계산
        BigDecimal variance = pnls.stream()
                .map(pnl -> pnl.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(pnls.size()), 8, RoundingMode.HALF_UP);

        double stdDev = Math.sqrt(variance.doubleValue());

        if (stdDev == 0) {
            return 0;
        }

        // 단순 샤프 비율 (무위험 수익률 = 0 가정)
        return mean.doubleValue() / stdDev;
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class PerformanceStats {
        private int totalTrades;
        private int winningTrades;
        private int losingTrades;
        private double winRate;
        private BigDecimal totalPnl;
        private BigDecimal totalProfit;
        private BigDecimal totalLoss;
        private BigDecimal avgWin;
        private BigDecimal avgLoss;
        private double profitFactor;
        private int maxConsecutiveWins;
        private int maxConsecutiveLosses;
        private BigDecimal maxDrawdown;
        private double sharpeRatio;
        private BigDecimal totalFees;
        private BigDecimal totalSlippage;

        public static PerformanceStats empty() {
            return PerformanceStats.builder()
                    .totalTrades(0)
                    .totalPnl(BigDecimal.ZERO)
                    .totalProfit(BigDecimal.ZERO)
                    .totalLoss(BigDecimal.ZERO)
                    .avgWin(BigDecimal.ZERO)
                    .avgLoss(BigDecimal.ZERO)
                    .maxDrawdown(BigDecimal.ZERO)
                    .totalFees(BigDecimal.ZERO)
                    .totalSlippage(BigDecimal.ZERO)
                    .build();
        }
    }

    @Data
    @Builder
    public static class MarketStats {
        private String market;
        private int tradeCount;
        private BigDecimal totalPnl;
        private BigDecimal avgSlippage;
    }

    @Data
    @Builder
    public static class StrategyStats {
        private String strategyName;
        private int tradeCount;
        private BigDecimal totalPnl;
        private double winRate;
    }

    @Data
    @Builder
    public static class ExitReasonStats {
        private String exitReason;
        private int count;
        private BigDecimal totalPnl;
    }

    @Data
    @Builder
    public static class SlippageAnalysis {
        private Map<String, BigDecimal> marketSlippage;
        private Map<Integer, BigDecimal> hourlySlippage;
        private BigDecimal totalSlippageCost;
        private double executionSuccessRate;
        private double avgExecutionTimeMs;
    }

    @Data
    @Builder
    public static class BacktestComparison {
        private BigDecimal backtestAvgSlippage;
        private double backtestSlippageStdDev;
        private int backtestTradeCount;
        private BigDecimal realAvgSlippage;
        private double realSlippageStdDev;
        private int realTradeCount;
    }
}