package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 실시간 대시보드 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 대시보드 전체 데이터 조회
     */
    @GetMapping
    public ResponseEntity<DashboardService.DashboardData> getDashboard(
            @AuthenticationPrincipal User user) {

        DashboardService.DashboardData data = dashboardService.getDashboardData(user);
        return ResponseEntity.ok(data);
    }

    /**
     * 자산 요약 조회 (빠른 조회용)
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getAssetSummary(
            @AuthenticationPrincipal User user) {

        Map<String, Object> summary = dashboardService.getAssetSummary(user);
        return ResponseEntity.ok(summary);
    }
}