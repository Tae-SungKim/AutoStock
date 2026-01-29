package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.StrategyReplayLog;
import autostock.taesung.com.autostock.repository.StrategyReplayLogRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 전략 리플레이 로그 서비스
 *
 * 서버 2대 환경에서 리플레이 로그를 DB에 저장하고
 * 시뮬레이션 분석 기능 제공
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyReplayLogService {

    private final StrategyReplayLogRepository replayLogRepository;

    @Value("${server.id:server-1}")
    private String serverId;

    // ==================== 로그 저장 ====================

    /**
     * 단일 로그 저장
     */
    @Transactional
    public StrategyReplayLog save(StrategyReplayLog log) {
        if (log.getServerId() == null) {
            log.setServerId(serverId);
        }
        return replayLogRepository.save(log);
    }

    /**
     * 배치 로그 저장 (비동기)
     */
    @Async
    @Transactional
    public void saveBatch(List<StrategyReplayLog> logs) {
        logs.forEach(log -> {
            if (log.getServerId() == null) {
                log.setServerId(serverId);
            }
        });
        replayLogRepository.saveAll(logs);
        log.info("[REPLAY_LOG] Saved {} logs from server {}", logs.size(), serverId);
    }

    /**
     * VolumeConfirmedBreakoutStrategy용 로그 저장
     */
    @Transactional
    public void saveBreakoutLog(String market, LocalDateTime time, String action,
                                 double price, double rsi, double atr,
                                 double volumeRatio, double density, double executionStrength,
                                 Double profitRate, String sessionId) {
        StrategyReplayLog log = StrategyReplayLog.builder()
                .strategyName("VolumeConfirmedBreakoutStrategy")
                .market(market)
                .logTime(time)
                .action(action)
                .price(BigDecimal.valueOf(price))
                .rsi(BigDecimal.valueOf(rsi))
                .atr(BigDecimal.valueOf(atr))
                .volumeRatio(BigDecimal.valueOf(volumeRatio))
                .density(BigDecimal.valueOf(density))
                .executionStrength(BigDecimal.valueOf(executionStrength))
                .profitRate(profitRate != null ? BigDecimal.valueOf(profitRate) : null)
                .serverId(serverId)
                .sessionId(sessionId)
                .build();

        replayLogRepository.save(log);
    }

    /**
     * VolumeImpulseStrategy용 로그 저장
     */
    @Transactional
    public void saveImpulseLog(String market, LocalDateTime time, String action, String reason,
                                double price, double zScore, double prevZScore,
                                double volume, double avgVolume, double density,
                                String sessionId) {
        StrategyReplayLog log = StrategyReplayLog.builder()
                .strategyName("VolumeImpulseStrategy")
                .market(market)
                .logTime(time)
                .action(action)
                .reason(reason)
                .price(BigDecimal.valueOf(price))
                .zScore(BigDecimal.valueOf(zScore))
                .prevZScore(BigDecimal.valueOf(prevZScore))
                .volume(BigDecimal.valueOf(volume))
                .avgVolume(BigDecimal.valueOf(avgVolume))
                .density(BigDecimal.valueOf(density))
                .serverId(serverId)
                .sessionId(sessionId)
                .build();

        replayLogRepository.save(log);
    }

    // ==================== 조회 ====================

    /**
     * 마켓별 최근 로그 조회
     */
    public List<StrategyReplayLog> getLogsByMarket(String market, int limit) {
        return replayLogRepository.findByMarketOrderByLogTimeDesc(market)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 전략 + 마켓 + 기간 조회
     */
    public List<StrategyReplayLog> getLogs(String strategy, String market,
                                            LocalDateTime from, LocalDateTime to) {
        return replayLogRepository.findByStrategyAndMarketAndPeriod(strategy, market, from, to);
    }

    /**
     * 세션별 로그 조회
     */
    public List<StrategyReplayLog> getLogsBySession(String sessionId) {
        return replayLogRepository.findBySessionIdOrderByLogTimeAsc(sessionId);
    }

    /**
     * 세션 목록 조회
     */
    public List<SessionInfo> getSessionList(String strategy) {
        return replayLogRepository.findSessionList(strategy).stream()
                .map(row -> SessionInfo.builder()
                        .sessionId((String) row[0])
                        .startTime((LocalDateTime) row[1])
                        .endTime((LocalDateTime) row[2])
                        .logCount(((Number) row[3]).longValue())
                        .build())
                .collect(Collectors.toList());
    }

    // ==================== 시뮬레이션 분석 ====================

    /**
     * 시뮬레이션 실행 (DB 로그 기반)
     */
    public SimulationResult runSimulation(String strategy, String market,
                                           LocalDateTime from, LocalDateTime to,
                                           double initialCapital) {

        List<StrategyReplayLog> logs = getLogs(strategy, market, from, to);

        if (logs.isEmpty()) {
            return SimulationResult.builder()
                    .market(market)
                    .strategy(strategy)
                    .error("No logs found")
                    .build();
        }

        double capital = initialCapital;
        double position = 0;
        double entryPrice = 0;
        int trades = 0;
        int wins = 0;
        double maxDrawdown = 0;
        double peakCapital = capital;

        List<TradeResult> tradeResults = new ArrayList<>();

        for (StrategyReplayLog log : logs) {
            String action = log.getAction();
            double price = log.getPrice() != null ? log.getPrice().doubleValue() : 0;

            if ("BUY".equals(action) || "ENTRY".equals(action)) {
                if (position == 0 && price > 0) {
                    position = capital * 0.95 / price; // 95% 투자
                    entryPrice = price;
                }
            } else if ("EXIT".equals(action) || "SELL".equals(action)) {
                if (position > 0 && price > 0) {
                    double pnl = (price - entryPrice) * position;
                    capital += pnl;
                    trades++;

                    if (pnl > 0) wins++;

                    tradeResults.add(TradeResult.builder()
                            .entryPrice(entryPrice)
                            .exitPrice(price)
                            .pnl(pnl)
                            .profitRate((price - entryPrice) / entryPrice)
                            .build());

                    // Drawdown 계산
                    if (capital > peakCapital) {
                        peakCapital = capital;
                    }
                    double drawdown = (peakCapital - capital) / peakCapital;
                    if (drawdown > maxDrawdown) {
                        maxDrawdown = drawdown;
                    }

                    position = 0;
                    entryPrice = 0;
                }
            }
        }

        double totalReturn = (capital - initialCapital) / initialCapital;
        double winRate = trades > 0 ? (double) wins / trades : 0;

        return SimulationResult.builder()
                .market(market)
                .strategy(strategy)
                .startTime(from)
                .endTime(to)
                .initialCapital(initialCapital)
                .finalCapital(capital)
                .totalReturn(totalReturn)
                .totalTrades(trades)
                .wins(wins)
                .losses(trades - wins)
                .winRate(winRate)
                .maxDrawdown(maxDrawdown)
                .tradeResults(tradeResults)
                .build();
    }

    /**
     * 멀티 마켓 시뮬레이션
     */
    public List<SimulationResult> runMultiMarketSimulation(String strategy,
                                                            List<String> markets,
                                                            LocalDateTime from,
                                                            LocalDateTime to,
                                                            double initialCapital) {
        return markets.stream()
                .map(market -> runSimulation(strategy, market, from, to, initialCapital))
                .filter(r -> r.getError() == null)
                .sorted((a, b) -> Double.compare(b.getTotalReturn(), a.getTotalReturn()))
                .collect(Collectors.toList());
    }

    // ==================== 패턴 분석 ====================

    /**
     * 손실 패턴 분석
     */
    public LossPatternAnalysis analyzeLossPatterns(String strategy, LocalDateTime from) {
        List<StrategyReplayLog> losses = replayLogRepository.findLossPatterns(strategy, from);

        if (losses.isEmpty()) {
            return LossPatternAnalysis.builder()
                    .totalLosses(0)
                    .build();
        }

        double avgRsi = losses.stream()
                .filter(l -> l.getRsi() != null)
                .mapToDouble(l -> l.getRsi().doubleValue())
                .average().orElse(0);

        double avgVolRatio = losses.stream()
                .filter(l -> l.getVolumeRatio() != null)
                .mapToDouble(l -> l.getVolumeRatio().doubleValue())
                .average().orElse(0);

        double avgDensity = losses.stream()
                .filter(l -> l.getDensity() != null)
                .mapToDouble(l -> l.getDensity().doubleValue())
                .average().orElse(0);

        double avgZScore = losses.stream()
                .filter(l -> l.getZScore() != null)
                .mapToDouble(l -> l.getZScore().doubleValue())
                .average().orElse(0);

        double avgLoss = losses.stream()
                .filter(l -> l.getProfitRate() != null)
                .mapToDouble(l -> l.getProfitRate().doubleValue())
                .average().orElse(0);

        return LossPatternAnalysis.builder()
                .totalLosses(losses.size())
                .avgRsi(avgRsi)
                .avgVolumeRatio(avgVolRatio)
                .avgDensity(avgDensity)
                .avgZScore(avgZScore)
                .avgLossRate(avgLoss)
                .build();
    }

    /**
     * 전체 통계 조회
     */
    public Map<String, Object> getOverallStats(String strategy, LocalDateTime from) {
        List<Object[]> actionStats = replayLogRepository.findActionStats(strategy, from);
        List<Object[]> marketStats = replayLogRepository.findEntryCountByMarket(strategy, from);

        Map<String, Long> actionCounts = new HashMap<>();
        Map<String, Double> actionAvgProfit = new HashMap<>();

        for (Object[] row : actionStats) {
            String action = (String) row[0];
            Long count = ((Number) row[1]).longValue();
            Double avgProfit = row[2] != null ? ((Number) row[2]).doubleValue() : null;

            actionCounts.put(action, count);
            if (avgProfit != null) {
                actionAvgProfit.put(action, avgProfit);
            }
        }

        Map<String, Long> marketEntryCounts = marketStats.stream()
                .limit(20)
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).longValue(),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        return Map.of(
                "actionCounts", actionCounts,
                "actionAvgProfit", actionAvgProfit,
                "topMarkets", marketEntryCounts
        );
    }

    // ==================== 정리 ====================

    /**
     * 오래된 로그 삭제
     */
    @Transactional
    public int cleanupOldLogs(int daysToKeep) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysToKeep);
        int deleted = replayLogRepository.deleteOldLogs(cutoff);
        log.info("[REPLAY_LOG] Deleted {} old logs (before {})", deleted, cutoff);
        return deleted;
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class SessionInfo {
        private String sessionId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private long logCount;
    }

    @Data
    @Builder
    public static class SimulationResult {
        private String market;
        private String strategy;
        private String error;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private double initialCapital;
        private double finalCapital;
        private double totalReturn;
        private int totalTrades;
        private int wins;
        private int losses;
        private double winRate;
        private double maxDrawdown;
        private List<TradeResult> tradeResults;
    }

    @Data
    @Builder
    public static class TradeResult {
        private double entryPrice;
        private double exitPrice;
        private double pnl;
        private double profitRate;
    }

    @Data
    @Builder
    public static class LossPatternAnalysis {
        private int totalLosses;
        private double avgRsi;
        private double avgVolumeRatio;
        private double avgDensity;
        private double avgZScore;
        private double avgLossRate;
    }
}