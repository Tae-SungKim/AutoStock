package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.repository.UserRepository;
import autostock.taesung.com.autostock.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 키 암호화 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final EncryptionUtil encryptionUtil;
    private final UserRepository userRepository;

    /**
     * 사용자의 Upbit API 키 저장 (암호화)
     */
    @Transactional
    public void saveUpbitApiKeys(Long userId, String accessKey, String secretKey) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));

        // 암호화하여 저장
        user.setUpbitAccessKey(encryptionUtil.encrypt(accessKey));
        user.setUpbitSecretKey(encryptionUtil.encrypt(secretKey));
        userRepository.save(user);

        log.info("[{}] Upbit API 키가 암호화되어 저장되었습니다.", user.getUsername());
    }

    /**
     * 사용자의 복호화된 Upbit Access Key 조회
     */
    public String getDecryptedAccessKey(User user) {
        if (user.getUpbitAccessKey() == null) {
            return null;
        }
        return encryptionUtil.decrypt(user.getUpbitAccessKey());
    }

    /**
     * 사용자의 복호화된 Upbit Secret Key 조회
     */
    public String getDecryptedSecretKey(User user) {
        if (user.getUpbitSecretKey() == null) {
            return null;
        }
        return encryptionUtil.decrypt(user.getUpbitSecretKey());
    }

    /**
     * API 키가 암호화되어 있는지 확인
     */
    public boolean isEncrypted(User user) {
        return encryptionUtil.isEncrypted(user.getUpbitAccessKey());
    }

    /**
     * 기존 평문 API 키를 암호화로 마이그레이션
     */
    @Transactional
    public void migrateToEncrypted(User user) {
        if (user.getUpbitAccessKey() != null && !isEncrypted(user)) {
            log.info("[{}] API 키 암호화 마이그레이션 시작", user.getUsername());

            String plainAccessKey = user.getUpbitAccessKey();
            String plainSecretKey = user.getUpbitSecretKey();

            user.setUpbitAccessKey(encryptionUtil.encrypt(plainAccessKey));
            user.setUpbitSecretKey(encryptionUtil.encrypt(plainSecretKey));
            userRepository.save(user);

            log.info("[{}] API 키 암호화 마이그레이션 완료", user.getUsername());
        }
    }

    /**
     * 모든 사용자의 API 키 암호화 마이그레이션
     */
    @Transactional
    public int migrateAllUsers() {
        int count = 0;
        for (User user : userRepository.findAll()) {
            if (user.getUpbitAccessKey() != null && !isEncrypted(user)) {
                migrateToEncrypted(user);
                count++;
            }
        }
        log.info("총 {}명의 사용자 API 키가 암호화되었습니다.", count);
        return count;
    }
}