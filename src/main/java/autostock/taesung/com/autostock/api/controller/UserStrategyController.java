package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.entity.UserStrategy;
import autostock.taesung.com.autostock.repository.UserRepository;
import autostock.taesung.com.autostock.service.UserStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/user/strategies")
@RequiredArgsConstructor
public class UserStrategyController {

    private final UserStrategyService userStrategyService;
    private final UserRepository userRepository;

    /**
     * 사용 가능한 모든 전략 목록 조회
     */
    @GetMapping("/available")
    public ResponseEntity<List<Map<String, Object>>> getAvailableStrategies() {
        return ResponseEntity.ok(userStrategyService.getAllAvailableStrategies());
    }

    /**
     * 현재 사용자의 전략 설정 조회 (전체 전략 + 활성화 여부)
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getUserStrategies(
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        return ResponseEntity.ok(userStrategyService.getUserStrategySettings(user.getId()));
    }

    /**
     * 특정 사용자의 전략 설정 조회 (관리자용)
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getUserStrategiesById(@PathVariable Long userId) {
        return ResponseEntity.ok(userStrategyService.getUserStrategySettings(userId));
    }

    /**
     * 전략 활성화/비활성화 토글
     */
    @PostMapping("/toggle")
    public ResponseEntity<UserStrategy> toggleStrategy(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        String strategyName = (String) request.get("strategyName");
        Boolean enabled = (Boolean) request.get("enabled");

        if (strategyName == null || enabled == null) {
            throw new IllegalArgumentException("strategyName과 enabled는 필수입니다.");
        }

        UserStrategy result = userStrategyService.toggleStrategy(user.getId(), strategyName, enabled);
        return ResponseEntity.ok(result);
    }

    /**
     * 여러 전략 한번에 설정 (기존 설정 초기화 후 새로 설정)
     */
    @PostMapping("/set")
    public ResponseEntity<Map<String, Object>> setStrategies(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, List<String>> request) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        List<String> strategyNames = request.get("strategies");
        if (strategyNames == null) {
            throw new IllegalArgumentException("strategies는 필수입니다.");
        }

        List<UserStrategy> result = userStrategyService.setStrategies(user.getId(), strategyNames);

        return ResponseEntity.ok(Map.of(
                "message", "전략 설정 완료",
                "enabledStrategies", strategyNames,
                "count", result.size()
        ));
    }

    /**
     * 활성화된 전략만 조회
     */
    @GetMapping("/enabled")
    public ResponseEntity<List<String>> getEnabledStrategies(
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        return ResponseEntity.ok(userStrategyService.getEnabledStrategyNames(user.getId()));
    }

    /**
     * 모든 전략 설정 초기화
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteAllStrategies(
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        userStrategyService.deleteAllStrategies(user.getId());
        return ResponseEntity.ok(Map.of("message", "모든 전략 설정이 초기화되었습니다."));
    }
}