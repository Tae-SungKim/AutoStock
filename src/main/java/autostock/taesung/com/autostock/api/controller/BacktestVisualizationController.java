package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.service.BacktestVisualizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 백테스트 시각화 API 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest/visualization")
@RequiredArgsConstructor
public class BacktestVisualizationController {

    private final BacktestVisualizationService visualizationService;

    /**
     * 단일 백테스트 시각화 데이터
     */
    @GetMapping("/single")
    public ResponseEntity<BacktestVisualizationService.VisualizationData> getSingleVisualization(
            @RequestParam String market,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(defaultValue = "5") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        BacktestVisualizationService.VisualizationData data =
                visualizationService.getVisualizationData(market, strategy, initialBalance, candleUnit, candleCount);
        return ResponseEntity.ok(data);
    }

    /**
     * DB 데이터 기반 시각화 데이터
     */
    @GetMapping("/single/db")
    public ResponseEntity<BacktestVisualizationService.VisualizationData> getSingleVisualizationFromDb(
            @RequestParam String market,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(required = false) Integer unit) {

        BacktestVisualizationService.VisualizationData data =
                visualizationService.getVisualizationDataFromDb(market, strategy, initialBalance, unit);
        return ResponseEntity.ok(data);
    }

    /**
     * 전략 비교 차트 데이터
     */
    @GetMapping("/compare-strategies")
    public ResponseEntity<BacktestVisualizationService.StrategyComparisonData> compareStrategies(
            @RequestParam String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(defaultValue = "5") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        BacktestVisualizationService.StrategyComparisonData data =
                visualizationService.compareStrategies(market, initialBalance, candleUnit, candleCount);
        return ResponseEntity.ok(data);
    }

    /**
     * 멀티코인 히트맵 데이터
     */
    @PostMapping("/coin-heatmap")
    public ResponseEntity<BacktestVisualizationService.CoinHeatmapData> getCoinHeatmap(
            @RequestBody Map<String, Object> request) {

        @SuppressWarnings("unchecked")
        List<String> markets = (List<String>) request.get("markets");
        String strategy = (String) request.get("strategy");
        double initialBalance = request.containsKey("initialBalance") ?
                Double.parseDouble(request.get("initialBalance").toString()) : 1000000;
        int candleUnit = request.containsKey("candleUnit") ?
                Integer.parseInt(request.get("candleUnit").toString()) : 5;
        int candleCount = request.containsKey("candleCount") ?
                Integer.parseInt(request.get("candleCount").toString()) : 200;

        BacktestVisualizationService.CoinHeatmapData data =
                visualizationService.getCoinHeatmap(markets, strategy, initialBalance, candleUnit, candleCount);
        return ResponseEntity.ok(data);
    }

    /**
     * DB 기반 멀티코인 히트맵 데이터
     */
    @PostMapping("/coin-heatmap/db")
    public ResponseEntity<BacktestVisualizationService.CoinHeatmapData> getCoinHeatmapFromDb(
            @RequestBody Map<String, Object> request) {

        @SuppressWarnings("unchecked")
        List<String> markets = (List<String>) request.get("markets");
        String strategy = (String) request.get("strategy");
        double initialBalance = request.containsKey("initialBalance") ?
                Double.parseDouble(request.get("initialBalance").toString()) : 1000000;
        Integer unit = request.containsKey("unit") ?
                Integer.parseInt(request.get("unit").toString()) : null;

        BacktestVisualizationService.CoinHeatmapData data =
                visualizationService.getCoinHeatmapFromDb(markets, strategy, initialBalance, unit);
        return ResponseEntity.ok(data);
    }
}