package autostock.taesung.com.autostock.controller;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.repository.UserRepository;
import autostock.taesung.com.autostock.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 현재 사용자 정보 조회
     * GET /api/user/me
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(@AuthenticationPrincipal User user) {
        Map<String, Object> response = new HashMap<>();
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("autoTradingEnabled", user.getAutoTradingEnabled());
        response.put("hasUpbitKeys", user.getUpbitAccessKey() != null && user.getUpbitSecretKey() != null);
        response.put("createdAt", user.getCreatedAt());
        return ResponseEntity.ok(response);
    }

    /**
     * 자동매매 활성화/비활성화
     * PUT /api/user/auto-trading
     */
    @PutMapping("/auto-trading")
    public ResponseEntity<Map<String, Object>> toggleAutoTrading(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Boolean> request) {

        Boolean enabled = request.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "enabled 값이 필요합니다."));
        }

        // Upbit API 키가 없으면 자동매매 활성화 불가
        if (enabled && (user.getUpbitAccessKey() == null || user.getUpbitSecretKey() == null)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Upbit API 키를 먼저 등록해주세요."));
        }

        user.setAutoTradingEnabled(enabled);
        userRepository.save(user);

        log.info("사용자 {} 자동매매 상태 변경: {}", user.getUsername(), enabled);

        Map<String, Object> response = new HashMap<>();
        response.put("autoTradingEnabled", user.getAutoTradingEnabled());
        response.put("message", enabled ? "자동매매가 활성화되었습니다." : "자동매매가 비활성화되었습니다.");
        return ResponseEntity.ok(response);
    }

    /**
     * Upbit API 키 등록/수정
     * PUT /api/user/upbit-keys
     */
    @PutMapping("/upbit-keys")
    public ResponseEntity<Map<String, Object>> updateUpbitKeys(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> request) {

        String accessKey = request.get("accessKey");
        String secretKey = request.get("secretKey");

        if (accessKey == null || secretKey == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "accessKey와 secretKey가 필요합니다."));
        }
        apiKeyService.saveUpbitApiKeys(user.getId(), accessKey, secretKey);
        userRepository.save(user);

        log.info("사용자 {} Upbit API 키 업데이트", user.getUsername());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Upbit API 키가 등록되었습니다.");
        response.put("hasUpbitKeys", true);
        return ResponseEntity.ok(response);
    }

    /**
     * Upbit API 키 삭제
     * DELETE /api/user/upbit-keys
     */
    @DeleteMapping("/upbit-keys")
    public ResponseEntity<Map<String, Object>> deleteUpbitKeys(@AuthenticationPrincipal User user) {
        user.setUpbitAccessKey(null);
        user.setUpbitSecretKey(null);
        user.setAutoTradingEnabled(false);  // API 키 삭제 시 자동매매도 비활성화
        userRepository.save(user);

        log.info("사용자 {} Upbit API 키 삭제", user.getUsername());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Upbit API 키가 삭제되었습니다.");
        response.put("hasUpbitKeys", false);
        response.put("autoTradingEnabled", false);
        return ResponseEntity.ok(response);
    }

    /**
     * 비밀번호 변경
     * PUT /api/user/password
     */
    @PutMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> request) {

        String currentPassword = request.get("currentPassword");
        String newPassword = request.get("newPassword");

        if (currentPassword == null || newPassword == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "현재 비밀번호와 새 비밀번호가 필요합니다."));
        }

        // 현재 비밀번호 확인
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "현재 비밀번호가 일치하지 않습니다."));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        log.info("사용자 {} 비밀번호 변경", user.getUsername());

        return ResponseEntity.ok(Map.of("message", "비밀번호가 변경되었습니다."));
    }
}