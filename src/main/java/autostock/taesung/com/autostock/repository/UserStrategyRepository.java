package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.UserStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserStrategyRepository extends JpaRepository<UserStrategy, Long> {

    /**
     * 사용자의 모든 전략 설정 조회
     */
    List<UserStrategy> findByUserId(Long userId);

    /**
     * 사용자의 활성화된 전략만 조회
     */
    List<UserStrategy> findByUserIdAndEnabledTrue(Long userId);

    /**
     * 사용자의 특정 전략 조회
     */
    Optional<UserStrategy> findByUserIdAndStrategyName(Long userId, String strategyName);

    /**
     * 사용자의 전략 존재 여부 확인
     */
    boolean existsByUserIdAndStrategyName(Long userId, String strategyName);

    /**
     * 사용자의 모든 전략 삭제
     */
    void deleteByUserId(Long userId);

    /**
     * 사용자의 특정 전략 삭제
     */
    void deleteByUserIdAndStrategyName(Long userId, String strategyName);
}