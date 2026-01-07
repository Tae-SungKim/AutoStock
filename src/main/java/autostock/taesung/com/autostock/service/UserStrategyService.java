package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.UserStrategy;
import autostock.taesung.com.autostock.repository.UserStrategyRepository;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserStrategyService {

    private final UserStrategyRepository userStrategyRepository;
    private final List<TradingStrategy> allStrategies;

    /**
     * 사용 가능한 모든 전략 목록 조회
     */
    public List<Map<String, Object>> getAllAvailableStrategies() {
        return allStrategies.stream()
                .map(strategy -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", strategy.getStrategyName());
                    info.put("className", strategy.getClass().getSimpleName());
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * 사용자의 전략 설정 조회
     */
    public List<UserStrategy> getUserStrategies(Long userId) {
        return userStrategyRepository.findByUserId(userId);
    }

    /**
     * 사용자의 활성화된 전략만 조회
     */
    public List<UserStrategy> getEnabledStrategies(Long userId) {
        return userStrategyRepository.findByUserIdAndEnabledTrue(userId);
    }

    /**
     * 사용자의 활성화된 전략 이름 목록 조회
     */
    public List<String> getEnabledStrategyNames(Long userId) {
        return userStrategyRepository.findByUserIdAndEnabledTrue(userId).stream()
                .map(UserStrategy::getStrategyName)
                .collect(Collectors.toList());
    }

    /**
     * 사용자의 전략 설정과 함께 전체 전략 목록 반환
     */
    public List<Map<String, Object>> getUserStrategySettings(Long userId) {
        List<UserStrategy> userStrategies = userStrategyRepository.findByUserId(userId);
        Map<String, Boolean> enabledMap = userStrategies.stream()
                .collect(Collectors.toMap(UserStrategy::getStrategyName, UserStrategy::getEnabled));

        return allStrategies.stream()
                .map(strategy -> {
                    String strategyName = strategy.getStrategyName();
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", strategyName);
                    info.put("className", strategy.getClass().getSimpleName());
                    info.put("enabled", enabledMap.getOrDefault(strategyName, false));
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * 전략 활성화/비활성화 토글
     */
    @Transactional
    public UserStrategy toggleStrategy(Long userId, String strategyName, boolean enabled) {
        Optional<UserStrategy> existing = userStrategyRepository.findByUserIdAndStrategyName(userId, strategyName);

        if (existing.isPresent()) {
            UserStrategy strategy = existing.get();
            strategy.setEnabled(enabled);
            log.info("[사용자 {}] 전략 '{}' {} ", userId, strategyName, enabled ? "활성화" : "비활성화");
            return userStrategyRepository.save(strategy);
        } else {
            UserStrategy newStrategy = UserStrategy.builder()
                    .userId(userId)
                    .strategyName(strategyName)
                    .enabled(enabled)
                    .build();
            log.info("[사용자 {}] 전략 '{}' 추가 및 {}", userId, strategyName, enabled ? "활성화" : "비활성화");
            return userStrategyRepository.save(newStrategy);
        }
    }

    /**
     * 여러 전략 한번에 설정
     */
    @Transactional
    public List<UserStrategy> setStrategies(Long userId, List<String> strategyNames) {
        // 기존 설정 모두 비활성화
        List<UserStrategy> existing = userStrategyRepository.findByUserId(userId);
        for (UserStrategy us : existing) {
            us.setEnabled(false);
        }
        userStrategyRepository.saveAll(existing);

        // 선택된 전략만 활성화
        List<UserStrategy> result = new ArrayList<>();
        for (String strategyName : strategyNames) {
            result.add(toggleStrategy(userId, strategyName, true));
        }

        log.info("[사용자 {}] 전략 설정 완료: {}", userId, strategyNames);
        return result;
    }

    /**
     * 사용자의 활성화된 TradingStrategy 객체 목록 반환
     */
    public List<TradingStrategy> getEnabledTradingStrategies(Long userId) {
        List<String> enabledNames = getEnabledStrategyNames(userId);

        // 설정된 전략이 없으면 모든 전략 사용 (기본값)
        if (enabledNames.isEmpty()) {
            log.debug("[사용자 {}] 설정된 전략이 없어 전체 전략 사용", userId);
            return allStrategies;
        }

        return allStrategies.stream()
                .filter(strategy -> enabledNames.contains(strategy.getStrategyName()))
                .collect(Collectors.toList());
    }

    /**
     * 전략 삭제
     */
    @Transactional
    public void deleteStrategy(Long userId, String strategyName) {
        userStrategyRepository.deleteByUserIdAndStrategyName(userId, strategyName);
        log.info("[사용자 {}] 전략 '{}' 삭제", userId, strategyName);
    }

    /**
     * 사용자의 모든 전략 설정 삭제
     */
    @Transactional
    public void deleteAllStrategies(Long userId) {
        userStrategyRepository.deleteByUserId(userId);
        log.info("[사용자 {}] 모든 전략 설정 삭제", userId);
    }
}