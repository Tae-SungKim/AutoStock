package autostock.taesung.com.autostock.realtrading.controller;

import autostock.taesung.com.autostock.realtrading.engine.RealTradingEngine;
import autostock.taesung.com.autostock.realtrading.engine.RealTradingEngine.*;
import autostock.taesung.com.autostock.realtrading.risk.RiskManager.RiskStatus;
import autostock.taesung.com.autostock.realtrading.service.TradingStatisticsService;
import autostock.taesung.com.autostock.realtrading.service.TradingStatisticsService.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 실거래 REST API 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/realtrading")
@RequiredArgsConstructor
public class RealTradingController {

    private final RealTradingEngine tradingEngine;
    private final TradingStatisticsService statisticsService;

    // ==================== 엔진 제어 ====================

    @PostMapping("/engine/start")
    public ResponseEntity<ApiResponse<String>> startEngine() {
        tradingEngine.start();
        return ResponseEntity.ok(ApiResponse.success("거래 엔진 시작됨"));
    }

    @PostMapping("/engine/stop")
    public ResponseEntity<ApiResponse<String>> stopEngine() {
        tradingEngine.stop();
        return ResponseEntity.ok(ApiResponse.success("거래 엔진 중지됨"));
    }

    @GetMapping("/engine/status")
    public ResponseEntity<ApiResponse<EngineStatus>> getEngineStatus() {
        return ResponseEntity.ok(ApiResponse.success(tradingEngine.getEngineStatus()));
    }

    // ==================== 신호 처리 ====================

    @PostMapping("/signal/entry")
    public ResponseEntity<ApiResponse<TradingResult>> processEntrySignal(@RequestBody EntrySignalRequest request) {
        log.info("진입 신호 수신: {}", request);

        TradingResult result = tradingEngine.processEntrySignal(
                request.getUserId(),
                request.getMarket(),
                request.getSignalStrength(),
                request.getAccountBalance()
        );

        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else if (result.isRejected()) {
            return ResponseEntity.ok(ApiResponse.rejected(result.getErrorCode(), result.getErrorMessage()));
        } else {
            return ResponseEntity.ok(ApiResponse.error(result.getErrorMessage()));
        }
    }

    @PostMapping("/signal/exit")
    public ResponseEntity<ApiResponse<TradingResult>> processExitSignal(@RequestBody ExitSignalRequest request) {
        log.info("청산 신호 수신: {}", request);

        TradingResult result = tradingEngine.processExitSignal(
                request.getUserId(),
                request.getMarket(),
                request.getReason()
        );

        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(result.getErrorMessage()));
        }
    }

    // ==================== 포지션 관리 ====================

    @GetMapping("/positions/{userId}")
    public ResponseEntity<ApiResponse<PositionSummary>> getPositionSummary(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(tradingEngine.getPositionSummary(userId)));
    }

    @PostMapping("/positions/{userId}/{market}/exit")
    public ResponseEntity<ApiResponse<TradingResult>> manualExit(
            @PathVariable Long userId,
            @PathVariable String market) {

        TradingResult result = tradingEngine.manualExit(userId, market);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/positions/{userId}/exit-all")
    public ResponseEntity<ApiResponse<List<TradingResult>>> emergencyExitAll(@PathVariable Long userId) {
        List<TradingResult> results = tradingEngine.emergencyExitAll(userId);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    // ==================== 리스크 관리 ====================

    @GetMapping("/risk/{userId}")
    public ResponseEntity<ApiResponse<RiskStatus>> getRiskStatus(
            @PathVariable Long userId,
            @RequestParam BigDecimal accountBalance) {

        return ResponseEntity.ok(ApiResponse.success(
                tradingEngine.getRiskStatus(userId, accountBalance)));
    }

    // ==================== 통계 ====================

    @GetMapping("/stats/{userId}/performance")
    public ResponseEntity<ApiResponse<PerformanceStats>> getPerformanceStats(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "30") int days) {

        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return ResponseEntity.ok(ApiResponse.success(
                statisticsService.calculatePerformanceStats(userId, since)));
    }

    @GetMapping("/stats/{userId}/markets")
    public ResponseEntity<ApiResponse<Map<String, MarketStats>>> getMarketStats(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "30") int days) {

        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return ResponseEntity.ok(ApiResponse.success(
                statisticsService.getMarketStats(userId, since)));
    }

    @GetMapping("/stats/{userId}/strategies")
    public ResponseEntity<ApiResponse<Map<String, StrategyStats>>> getStrategyStats(
            @PathVariable Long userId) {

        return ResponseEntity.ok(ApiResponse.success(
                statisticsService.getStrategyStats(userId)));
    }

    @GetMapping("/stats/{userId}/exit-reasons")
    public ResponseEntity<ApiResponse<Map<String, ExitReasonStats>>> getExitReasonStats(
            @PathVariable Long userId) {

        return ResponseEntity.ok(ApiResponse.success(
                statisticsService.getExitReasonStats(userId)));
    }

    @GetMapping("/stats/slippage")
    public ResponseEntity<ApiResponse<SlippageAnalysis>> getSlippageAnalysis(
            @RequestParam(defaultValue = "30") int days) {

        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return ResponseEntity.ok(ApiResponse.success(
                statisticsService.getSlippageAnalysis(since)));
    }

    @GetMapping("/stats/backtest-comparison")
    public ResponseEntity<ApiResponse<BacktestComparison>> getBacktestComparison() {
        return ResponseEntity.ok(ApiResponse.success(
                statisticsService.getBacktestComparison()));
    }

    // ==================== Request/Response DTOs ====================

    @Data
    public static class EntrySignalRequest {
        private Long userId;
        private String market;
        private int signalStrength;
        private BigDecimal accountBalance;
    }

    @Data
    public static class ExitSignalRequest {
        private Long userId;
        private String market;
        private String reason;
    }

    @Data
    public static class ApiResponse<T> {
        private boolean success;
        private String code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.success = true;
            response.code = "OK";
            response.data = data;
            return response;
        }

        public static <T> ApiResponse<T> rejected(String code, String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.success = false;
            response.code = code;
            response.message = message;
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.success = false;
            response.code = "ERROR";
            response.message = message;
            return response;
        }
    }
}