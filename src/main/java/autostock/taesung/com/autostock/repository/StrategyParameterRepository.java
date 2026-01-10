package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.StrategyParameter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StrategyParameterRepository extends JpaRepository<StrategyParameter, Long> {

    /**
     * 사용자별 전략 파라미터 조회
     */
    List<StrategyParameter> findByUserIdAndStrategyName(Long userId, String strategyName);

    /**
     * 사용자별 특정 파라미터 조회
     */
    Optional<StrategyParameter> findByUserIdAndStrategyNameAndParamKey(
            Long userId, String strategyName, String paramKey);

    /**
     * 글로벌 기본 파라미터 조회 (userId가 null)
     */
    List<StrategyParameter> findByUserIdIsNullAndStrategyName(String strategyName);

    /**
     * 글로벌 특정 파라미터 조회
     */
    Optional<StrategyParameter> findByUserIdIsNullAndStrategyNameAndParamKey(
            String strategyName, String paramKey);

    /**
     * 사용자의 모든 전략 파라미터 조회
     */
    List<StrategyParameter> findByUserId(Long userId);

    /**
     * 활성화된 파라미터만 조회
     */
    List<StrategyParameter> findByUserIdAndStrategyNameAndEnabledTrue(Long userId, String strategyName);

    /**
     * 전략별 사용 가능한 파라미터 목록 (글로벌 + 사용자)
     */
    @Query("SELECT sp FROM StrategyParameter sp WHERE sp.strategyName = :strategyName " +
           "AND (sp.userId IS NULL OR sp.userId = :userId) AND sp.enabled = true " +
           "ORDER BY sp.userId NULLS FIRST")
    List<StrategyParameter> findEffectiveParameters(
            @Param("strategyName") String strategyName,
            @Param("userId") Long userId);

    /**
     * 모든 글로벌 파라미터 조회
     */
    List<StrategyParameter> findByUserIdIsNull();

    /**
     * 전략 이름 목록 조회
     */
    @Query("SELECT DISTINCT sp.strategyName FROM StrategyParameter sp")
    List<String> findDistinctStrategyNames();

    /**
     * 사용자 파라미터 삭제
     */
    void deleteByUserIdAndStrategyName(Long userId, String strategyName);

    /**
     * 사용자의 특정 파라미터 삭제
     */
    void deleteByUserIdAndStrategyNameAndParamKey(Long userId, String strategyName, String paramKey);
}