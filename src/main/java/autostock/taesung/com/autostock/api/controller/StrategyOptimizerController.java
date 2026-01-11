package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.service.StrategyOptimizerService;
import autostock.taesung.com.autostock.service.StrategyParameterService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import autostock.taesung.com.autostock.strategy.impl.DataDrivenStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전략 최적화 API 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy-optimizer")
@RequiredArgsConstructor
public class StrategyOptimizerController {

    private final StrategyOptimizerService optimizerService;
    private final DataDrivenStrategy dataDrivenStrategy;
    private final StrategyParameterService strategyParameterService;
    private final List<TradingStrategy> allStrategies;  // 모든 전략 주입

    /**
     * 전체 데이터 기반 최적 파라미터 도출 및 모든 지원 전략에 저장
     */
    @PostMapping("/optimize")
    public ResponseEntity<?> optimizeStrategy() {
        log.info("전략 최적화 요청");
        try {
            StrategyOptimizerService.OptimizedParams params = optimizerService.optimizeStrategy();
            
            // 모든 전략에 대해 최적화된 파라미터 저장 (글로벌)
            for (TradingStrategy strategy : allStrategies) {
                optimizerService.saveOptimizedParams(strategy.getStrategyName(), null, "GLOBAL", params);
            }
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "최적화 및 전체 전략 저장 완료",
                    "params", params
            ));
        } catch (Exception e) {
            log.error("최적화 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "최적화 실패: " + e.getMessage()
            ));
        }
    }

    /**
     * 특정 마켓에 대한 최적 파라미터 도출 및 저장
     */
    @PostMapping("/optimize/{market}")
    public ResponseEntity<?> optimizeForMarket(@PathVariable String market) {
        log.info("마켓별 최적화 요청: {}", market);
        try {
            StrategyOptimizerService.OptimizedParams params = optimizerService.optimizeForMarket(market);

            // 모든 전략에 대해 최적화된 파라미터 저장 (마켓별 - 현재는 글로벌 파라미터 저장 로직과 동일하지만 확장을 위해 분리)
            for (TradingStrategy strategy : allStrategies) {
                optimizerService.saveOptimizedParams(strategy.getStrategyName(), null, market, params);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", market + " 최적화 및 저장 완료",
                    "params", params
            ));
        } catch (Exception e) {
            log.error("마켓 최적화 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "최적화 실패: " + e.getMessage()
            ));
        }
    }

    /**
     * 데이터 통계 조회
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getDataStatistics() {
        try {
            Map<String, Object> stats = optimizerService.getDataStatistics();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "stats", stats
            ));
        } catch (Exception e) {
            log.error("통계 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "통계 조회 실패"
            ));
        }
    }

    /**
     * 최적화 실행 후 DataDrivenStrategy에 적용
     */
    @PostMapping("/optimize-and-apply")
    public ResponseEntity<?> optimizeAndApply() {
        log.info("최적화 및 전략 적용 요청");
        try {
            // 최적화 실행
            StrategyOptimizerService.OptimizedParams params = optimizerService.optimizeStrategy();

            // DataDrivenStrategy에 적용
            dataDrivenStrategy.runOptimization();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "최적화 완료 및 전략에 적용됨",
                    "params", params,
                    "expectedWinRate", params.getExpectedWinRate(),
                    "expectedProfitRate", params.getExpectedProfitRate(),
                    "totalSignals", params.getTotalSignals()
            ));
        } catch (Exception e) {
            log.error("최적화 적용 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "최적화 적용 실패: " + e.getMessage()
            ));
        }
    }

    /**
     * 마켓별 최적화 후 전략에 적용
     */
    @PostMapping("/optimize-and-apply/{market}")
    public ResponseEntity<?> optimizeAndApplyForMarket(@PathVariable String market) {
        log.info("마켓별 최적화 및 적용 요청: {}", market);
        try {
            dataDrivenStrategy.optimizeForMarket(market);
            Map<String, Object> currentParams = dataDrivenStrategy.getCurrentParams(market);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", market + " 최적화 완료 및 적용됨",
                    "params", currentParams
            ));
        } catch (Exception e) {
            log.error("마켓 최적화 적용 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "최적화 적용 실패: " + e.getMessage()
            ));
        }
    }

    /**
     * 현재 적용된 파라미터 조회
     */
    @GetMapping("/current-params")
    public ResponseEntity<?> getCurrentParams(
            @RequestParam(defaultValue = "GLOBAL") String market) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("market", market);

            // 1. DataDrivenStrategy 파라미터
            Map<String, Object> dataDrivenParams = dataDrivenStrategy.getCurrentParams(market);
            response.put("dataDrivenParams", dataDrivenParams);

            // 2. 다른 주요 전략들의 실시간 파라미터 (DB 기반)
            Map<String, Map<String, Object>> strategyParams = new HashMap<>();
            for (TradingStrategy strategy : allStrategies) {
                if (!(strategy instanceof DataDrivenStrategy)) {
                    Map<String, Object> params = strategyParameterService.getEffectiveParameters(strategy.getStrategyName(), null);
                    strategyParams.put(strategy.getStrategyName(), params);
                }
            }
            response.put("strategyParams", strategyParams);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("파라미터 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "파라미터 조회 실패"
            ));
        }
    }

    /**
     * 등록된 모든 전략 목록 및 상태 조회
     */
    @GetMapping("/strategies")
    public ResponseEntity<?> getAllStrategies() {
        try {
            List<Map<String, Object>> strategyList = allStrategies.stream()
                    .map(strategy -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", strategy.getStrategyName());
                        info.put("class", strategy.getClass().getSimpleName());

                        // DataDrivenStrategy인 경우 파라미터도 포함
                        if (strategy instanceof DataDrivenStrategy dds) {
                            info.put("params", dds.getCurrentParams("GLOBAL"));
                            info.put("isOptimized", true);
                        } else {
                            // 일반 전략도 DB에서 현재 파라미터 가져오기
                            info.put("params", strategyParameterService.getEffectiveParameters(strategy.getStrategyName(), null));
                            info.put("isOptimized", false);
                        }
                        return info;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "totalStrategies", allStrategies.size(),
                    "strategies", strategyList
            ));
        } catch (Exception e) {
            log.error("전략 목록 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "전략 목록 조회 실패"
            ));
        }
    }

    /**
     * OptimizedParams 객체를 직접 받아서 DataDrivenStrategy에 적용
     */
    @PostMapping("/apply-params")
    public ResponseEntity<?> applyOptimizedParams(@RequestBody StrategyOptimizerService.OptimizedParams params) {
        log.info("최적화 파라미터 직접 적용 요청: {}", params);
        try {
            // StrategyOptimizerService.OptimizedParams 객체를 Map으로 변환하여 setParams 호출
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("bollingerPeriod", params.getBollingerPeriod());
            paramMap.put("bollingerMultiplier", params.getBollingerMultiplier());
            paramMap.put("rsiPeriod", params.getRsiPeriod());
            paramMap.put("rsiBuyThreshold", params.getRsiBuyThreshold());
            paramMap.put("rsiSellThreshold", params.getRsiSellThreshold());
            paramMap.put("volumeIncreaseRate", params.getVolumeIncreaseRate());
            paramMap.put("stopLossRate", params.getStopLossRate());
            paramMap.put("takeProfitRate", params.getTakeProfitRate());
            paramMap.put("trailingStopRate", params.getTrailingStopRate());
            paramMap.put("bandWidthMinPercent", params.getBandWidthMinPercent());
            paramMap.put("upperWickMaxRatio", params.getUpperWickMaxRatio());
            paramMap.put("minTradeAmount", params.getMinTradeAmount());

            dataDrivenStrategy.setParams(paramMap);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "파라미터가 성공적으로 적용되었습니다",
                    "appliedParams", dataDrivenStrategy.getCurrentParams("GLOBAL")
            ));
        } catch (Exception e) {
            log.error("파라미터 적용 실패: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "파라미터 적용 실패: " + e.getMessage()
            ));
        }
    }

    /**
     * DataDrivenStrategy 파라미터 수동 설정
     */
    @PutMapping("/params")
    public ResponseEntity<?> setParams(@RequestBody Map<String, Object> params) {
        try {
            dataDrivenStrategy.setParams(params);
            log.info("파라미터 수동 설정: {}", params);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "파라미터가 설정되었습니다",
                    "appliedParams", dataDrivenStrategy.getCurrentParams("GLOBAL")
            ));
        } catch (Exception e) {
            log.error("파라미터 설정 실패: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "파라미터 설정 실패: " + e.getMessage()
            ));
        }
    }
}