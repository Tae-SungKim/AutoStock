package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.service.RebalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 포트폴리오 리밸런싱 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/rebalance")
@RequiredArgsConstructor
public class RebalanceController {

    private final RebalanceService rebalanceService;

    /**
     * 현재 포트폴리오 상태 조회
     */
    @PostMapping("/status")
    public ResponseEntity<RebalanceService.PortfolioStatus> getPortfolioStatus(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> request) {

        List<RebalanceService.TargetAllocation> targets = parseTargets(request);
        RebalanceService.PortfolioStatus status = rebalanceService.getPortfolioStatus(user, targets);
        return ResponseEntity.ok(status);
    }

    /**
     * 리밸런싱 계획 생성
     */
    @PostMapping("/plan")
    public ResponseEntity<RebalanceService.RebalancePlan> createPlan(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> request) {

        List<RebalanceService.TargetAllocation> targets = parseTargets(request);
        RebalanceService.RebalancePlan plan = rebalanceService.createRebalancePlan(user, targets);
        return ResponseEntity.ok(plan);
    }

    /**
     * 리밸런싱 실행
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeRebalance(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> request) {

        List<RebalanceService.TargetAllocation> targets = parseTargets(request);
        RebalanceService.RebalancePlan plan = rebalanceService.createRebalancePlan(user, targets);

        if (!plan.isExecutable()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", plan.getMessage());
            response.put("plan", plan);
            return ResponseEntity.ok(response);
        }

        RebalanceService.RebalanceResult result = rebalanceService.executeRebalance(user, plan);

        Map<String, Object> response = new HashMap<>();
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        response.put("executedCount", result.getExecutedCount());
        response.put("failedCount", result.getFailedCount());
        response.put("orders", result.getOrders());
        return ResponseEntity.ok(response);
    }

    /**
     * 균등 배분 목표 생성
     */
    @PostMapping("/equal-allocation")
    public ResponseEntity<List<RebalanceService.TargetAllocation>> createEqualAllocation(
            @RequestBody Map<String, Object> request) {

        @SuppressWarnings("unchecked")
        List<String> markets = (List<String>) request.get("markets");
        double krwReserve = request.containsKey("krwReservePercent") ?
                Double.parseDouble(request.get("krwReservePercent").toString()) : 10.0;

        List<RebalanceService.TargetAllocation> allocations =
                rebalanceService.createEqualAllocation(markets, krwReserve);
        return ResponseEntity.ok(allocations);
    }

    @SuppressWarnings("unchecked")
    private List<RebalanceService.TargetAllocation> parseTargets(Map<String, Object> request) {
        List<Map<String, Object>> targetList = (List<Map<String, Object>>) request.get("targets");
        return targetList.stream()
                .map(t -> RebalanceService.TargetAllocation.builder()
                        .market((String) t.get("market"))
                        .targetPercent(Double.parseDouble(t.get("targetPercent").toString()))
                        .build())
                .collect(Collectors.toList());
    }
}