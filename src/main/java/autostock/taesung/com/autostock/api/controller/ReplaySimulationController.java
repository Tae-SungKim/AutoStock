package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.entity.StrategyReplayLog;
import autostock.taesung.com.autostock.service.StrategyReplayLogService;
import autostock.taesung.com.autostock.service.StrategyReplayLogService.*;
import autostock.taesung.com.autostock.strategy.impl.VolumeConfirmedBreakoutStrategy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 리플레이 로그 시뮬레이션 API
 *
 * DB에 저장된 리플레이 로그를 기반으로 시뮬레이션 실행 및 분석
 */
@Slf4j
@RestController
@RequestMapping("/api/replay")
@RequiredArgsConstructor
public class ReplaySimulationController {

    private final StrategyReplayLogService replayLogService;
    private final VolumeConfirmedBreakoutStrategy breakoutStrategy;

    // ==================== 로그 조회 ====================

    /**
     * 마켓별 로그 조회
     */
    @GetMapping("/logs/market/{market}")
    public ResponseEntity<List<StrategyReplayLog>> getLogsByMarket(
            @PathVariable String market,
            @RequestParam(defaultValue = "100") int limit) {

        return ResponseEntity.ok(replayLogService.getLogsByMarket(market, limit));
    }

    /**
     * 세션별 로그 조회
     */
    @GetMapping("/logs/session/{sessionId}")
    public ResponseEntity<List<StrategyReplayLog>> getLogsBySession(@PathVariable String sessionId) {
        return ResponseEntity.ok(replayLogService.getLogsBySession(sessionId));
    }

    /**
     * 기간별 로그 조회
     */
    @GetMapping("/logs")
    public ResponseEntity<List<StrategyReplayLog>> getLogs(
            @RequestParam String strategy,
            @RequestParam String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        return ResponseEntity.ok(replayLogService.getLogs(strategy, market, from, to));
    }

    /**
     * 세션 목록 조회
     */
    @GetMapping("/sessions/{strategy}")
    public ResponseEntity<List<SessionInfo>> getSessionList(@PathVariable String strategy) {
        return ResponseEntity.ok(replayLogService.getSessionList(strategy));
    }

    // ==================== 시뮬레이션 ====================

    /**
     * 단일 마켓 시뮬레이션 실행
     */
    @GetMapping("/simulate")
    public ResponseEntity<SimulationResult> runSimulation(
            @RequestParam String strategy,
            @RequestParam String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1000000") double capital) {

        log.info("[SIMULATE] strategy={}, market={}, from={}, to={}, capital={}",
                strategy, market, from, to, capital);

        SimulationResult result = replayLogService.runSimulation(strategy, market, from, to, capital);
        return ResponseEntity.ok(result);
    }

    /**
     * 멀티 마켓 시뮬레이션 실행
     */
    @PostMapping("/simulate/multi")
    public ResponseEntity<List<SimulationResult>> runMultiSimulation(
            @RequestBody MultiSimulationRequest request) {

        log.info("[MULTI_SIMULATE] strategy={}, markets={}, capital={}",
                request.getStrategy(), request.getMarkets().size(), request.getCapital());

        List<SimulationResult> results = replayLogService.runMultiMarketSimulation(
                request.getStrategy(),
                request.getMarkets(),
                request.getFrom(),
                request.getTo(),
                request.getCapital()
        );

        return ResponseEntity.ok(results);
    }

    /**
     * 세션 기반 시뮬레이션 (특정 세션의 로그로 시뮬레이션)
     */
    @GetMapping("/simulate/session/{sessionId}")
    public ResponseEntity<SimulationResult> runSessionSimulation(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "1000000") double capital) {

        List<StrategyReplayLog> logs = replayLogService.getLogsBySession(sessionId);
        if (logs.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    SimulationResult.builder().error("Session not found").build());
        }

        String strategy = logs.get(0).getStrategyName();
        String market = logs.get(0).getMarket();
        LocalDateTime from = logs.get(0).getLogTime();
        LocalDateTime to = logs.get(logs.size() - 1).getLogTime();

        return ResponseEntity.ok(
                replayLogService.runSimulation(strategy, market, from, to, capital));
    }

    // ==================== 분석 ====================

    /**
     * 손실 패턴 분석
     */
    @GetMapping("/analysis/loss-pattern")
    public ResponseEntity<LossPatternAnalysis> analyzeLossPatterns(
            @RequestParam String strategy,
            @RequestParam(defaultValue = "7") int days) {

        LocalDateTime from = LocalDateTime.now().minusDays(days);
        return ResponseEntity.ok(replayLogService.analyzeLossPatterns(strategy, from));
    }

    /**
     * 전체 통계 조회
     */
    @GetMapping("/analysis/stats")
    public ResponseEntity<Map<String, Object>> getOverallStats(
            @RequestParam String strategy,
            @RequestParam(defaultValue = "7") int days) {

        LocalDateTime from = LocalDateTime.now().minusDays(days);
        return ResponseEntity.ok(replayLogService.getOverallStats(strategy, from));
    }

    // ==================== 메모리 로그 관리 ====================

    /**
     * VolumeConfirmedBreakoutStrategy 현재 메모리 로그 조회
     */
    @GetMapping("/memory/breakout")
    public ResponseEntity<Map<String, Object>> getBreakoutMemoryLogs() {
        return ResponseEntity.ok(Map.of(
                "sessionId", breakoutStrategy.getSessionId(),
                "logCount", breakoutStrategy.getReplayLogs().size(),
                "logs", breakoutStrategy.getReplayLogs()
        ));
    }

    /**
     * VolumeConfirmedBreakoutStrategy 메모리 로그 클리어
     */
    @DeleteMapping("/memory/breakout")
    public ResponseEntity<String> clearBreakoutMemoryLogs() {
        int count = breakoutStrategy.getReplayLogs().size();
        breakoutStrategy.clearReplayLogs();
        return ResponseEntity.ok("Cleared " + count + " logs");
    }

    /**
     * DB 로깅 활성화/비활성화
     */
    @PutMapping("/config/db-logging")
    public ResponseEntity<String> setDbLogging(@RequestParam boolean enabled) {
        breakoutStrategy.setDbLoggingEnabled(enabled);
        return ResponseEntity.ok("DB logging " + (enabled ? "enabled" : "disabled"));
    }

    // ==================== 정리 ====================

    /**
     * 오래된 로그 삭제
     */
    @DeleteMapping("/logs/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupOldLogs(
            @RequestParam(defaultValue = "30") int daysToKeep) {

        int deleted = replayLogService.cleanupOldLogs(daysToKeep);
        return ResponseEntity.ok(Map.of(
                "deleted", deleted,
                "daysToKeep", daysToKeep
        ));
    }

    // ==================== Request DTOs ====================

    @Data
    public static class MultiSimulationRequest {
        private String strategy;
        private List<String> markets;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime to;
        private double capital = 1000000;
    }
}