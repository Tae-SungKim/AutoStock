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
    public ResponseEntity<?> getDashboard(
            @AuthenticationPrincipal User user) {

        if (user == null) {
            log.error("대시보드 조회 실패: 인증된 사용자 없음");
            return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "인증이 필요합니다."
            ));
        }

        // API 키 확인
        if (user.getUpbitAccessKey() == null || user.getUpbitSecretKey() == null) {
            log.warn("대시보드 조회: API 키 미설정 - userId: {}", user.getId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "API 키가 설정되지 않았습니다. 설정 페이지에서 Upbit API 키를 등록해주세요.",
                    "apiKeyRequired", true,
                    "data", dashboardService.getDashboardData(user)
            ));
        }

        log.info("대시보드 조회 요청 - userId: {}", user.getId());
        DashboardService.DashboardData data = dashboardService.getDashboardData(user);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "apiKeyRequired", false,
                "data", data
        ));
    }

    /**
     * 자산 요약 조회 (빠른 조회용)
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getAssetSummary(
            @AuthenticationPrincipal User user) {

        if (user == null) {
            log.error("자산 요약 조회 실패: 인증된 사용자 없음");
            return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "인증이 필요합니다."
            ));
        }

        log.info("자산 요약 조회 요청 - userId: {}", user.getId());
        Map<String, Object> summary = dashboardService.getAssetSummary(user);
        return ResponseEntity.ok(summary);
    }
}