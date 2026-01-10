package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.repository.UserRepository;
import autostock.taesung.com.autostock.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * API 키 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;

    /**
     * 사용자 API 키 저장 (암호화)
     */
    @PostMapping("/upbit")
    public ResponseEntity<Map<String, Object>> saveUpbitApiKeys(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> request) {

        String accessKey = request.get("accessKey");
        String secretKey = request.get("secretKey");

        if (accessKey == null || secretKey == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "accessKey와 secretKey가 필요합니다."
            ));
        }

        apiKeyService.saveUpbitApiKeys(user.getId(), accessKey, secretKey);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "API 키가 암호화되어 저장되었습니다.");
        return ResponseEntity.ok(response);
    }

    /**
     * API 키 암호화 상태 확인
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getApiKeyStatus(
            @AuthenticationPrincipal User user) {

        User freshUser = userRepository.findById(user.getId()).orElse(user);

        Map<String, Object> response = new HashMap<>();
        response.put("hasAccessKey", freshUser.getUpbitAccessKey() != null);
        response.put("hasSecretKey", freshUser.getUpbitSecretKey() != null);
        response.put("isEncrypted", apiKeyService.isEncrypted(freshUser));
        return ResponseEntity.ok(response);
    }

    /**
     * 기존 평문 API 키를 암호화로 마이그레이션 (관리자용)
     */
    @PostMapping("/migrate")
    public ResponseEntity<Map<String, Object>> migrateApiKeys(
            @AuthenticationPrincipal User user) {

        // 관리자 권한 체크
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "관리자 권한이 필요합니다."
            ));
        }

        int count = apiKeyService.migrateAllUsers();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("migratedCount", count);
        response.put("message", count + "명의 사용자 API 키가 암호화되었습니다.");
        return ResponseEntity.ok(response);
    }
}