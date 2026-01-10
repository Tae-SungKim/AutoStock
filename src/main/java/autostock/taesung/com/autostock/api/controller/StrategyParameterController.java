package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.entity.StrategyParameter;
import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.service.StrategyParameterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전략 파라미터 동적 조정 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy-params")
@RequiredArgsConstructor
public class StrategyParameterController {

    private final StrategyParameterService parameterService;

    /**
     * 사용 가능한 전략 목록 조회
     */
    @GetMapping("/strategies")
    public ResponseEntity<List<String>> getStrategies() {
        List<String> strategies = parameterService.getAvailableStrategies();
        return ResponseEntity.ok(strategies);
    }

    /**
     * 전략별 파라미터 정의 조회
     */
    @GetMapping("/definitions/{strategyName}")
    public ResponseEntity<List<StrategyParameterService.ParameterDefinition>> getDefinitions(
            @PathVariable String strategyName) {

        List<StrategyParameterService.ParameterDefinition> definitions =
                parameterService.getParameterDefinitions(strategyName);
        return ResponseEntity.ok(definitions);
    }

    /**
     * 현재 유효 파라미터 조회 (글로벌 + 사용자 병합)
     */
    @GetMapping("/{strategyName}")
    public ResponseEntity<Map<String, Object>> getEffectiveParameters(
            @AuthenticationPrincipal User user,
            @PathVariable String strategyName) {

        Map<String, Object> params = parameterService.getEffectiveParameters(strategyName, user.getId());
        return ResponseEntity.ok(params);
    }

    /**
     * 파라미터 상세 목록 조회 (UI용)
     */
    @GetMapping("/{strategyName}/details")
    public ResponseEntity<List<StrategyParameterService.ParameterValue>> getParameterDetails(
            @AuthenticationPrincipal User user,
            @PathVariable String strategyName) {

        List<StrategyParameterService.ParameterValue> details =
                parameterService.getParameterDetails(strategyName, user.getId());
        return ResponseEntity.ok(details);
    }

    /**
     * 단일 파라미터 설정
     */
    @PutMapping("/{strategyName}/{paramKey}")
    public ResponseEntity<Map<String, Object>> setParameter(
            @AuthenticationPrincipal User user,
            @PathVariable String strategyName,
            @PathVariable String paramKey,
            @RequestBody Map<String, String> request) {

        String value = request.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "value가 필요합니다."
            ));
        }

        try {
            StrategyParameter saved = parameterService.setUserParameter(
                    user.getId(), strategyName, paramKey, value);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "파라미터가 설정되었습니다.");
            response.put("parameter", Map.of(
                    "key", saved.getParamKey(),
                    "value", saved.getParamValue(),
                    "type", saved.getParamType()
            ));
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 다중 파라미터 일괄 설정
     */
    @PutMapping("/{strategyName}")
    public ResponseEntity<Map<String, Object>> setParameters(
            @AuthenticationPrincipal User user,
            @PathVariable String strategyName,
            @RequestBody Map<String, String> params) {

        try {
            parameterService.setUserParameters(user.getId(), strategyName, params);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", params.size() + "개의 파라미터가 설정되었습니다.");
            response.put("updatedParams", params.keySet());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 파라미터 초기화 (기본값으로)
     */
    @DeleteMapping("/{strategyName}")
    public ResponseEntity<Map<String, Object>> resetParameters(
            @AuthenticationPrincipal User user,
            @PathVariable String strategyName) {

        parameterService.resetUserParameters(user.getId(), strategyName);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", strategyName + " 전략의 파라미터가 기본값으로 초기화되었습니다.");
        return ResponseEntity.ok(response);
    }

    /**
     * 특정 파라미터 삭제 (기본값으로 되돌림)
     */
    @DeleteMapping("/{strategyName}/{paramKey}")
    public ResponseEntity<Map<String, Object>> deleteParameter(
            @AuthenticationPrincipal User user,
            @PathVariable String strategyName,
            @PathVariable String paramKey) {

        parameterService.deleteUserParameter(user.getId(), strategyName, paramKey);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", paramKey + " 파라미터가 기본값으로 복원되었습니다.");
        return ResponseEntity.ok(response);
    }

    /**
     * 모든 전략의 사용자 파라미터 요약 조회
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getParametersSummary(
            @AuthenticationPrincipal User user) {

        List<String> strategies = parameterService.getAvailableStrategies();
        Map<String, Object> summary = new HashMap<>();

        for (String strategy : strategies) {
            List<StrategyParameterService.ParameterValue> details =
                    parameterService.getParameterDetails(strategy, user.getId());

            long customCount = details.stream().filter(StrategyParameterService.ParameterValue::isCustom).count();

            summary.put(strategy, Map.of(
                    "totalParams", details.size(),
                    "customParams", customCount,
                    "usingDefaults", customCount == 0
            ));
        }

        return ResponseEntity.ok(summary);
    }
}