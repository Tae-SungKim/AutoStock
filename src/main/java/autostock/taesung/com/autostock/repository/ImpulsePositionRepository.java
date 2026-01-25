package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.ImpulsePosition;
import autostock.taesung.com.autostock.entity.ImpulsePosition.PositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Impulse 포지션 Repository
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [핵심 역할]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 1. 미청산(OPEN) 포지션 조회: 서버 재기동 복구
 * 2. 마켓별 포지션 관리: 중복 진입 방지
 * 3. 통계 집계: 승률, 수익률 분석
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [서버 재기동 복구 시나리오]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 1. 서버 재기동
 * 2. ImpulsePositionService.@PostConstruct 호출
 * 3. findByStatus(OPEN) 호출
 * 4. 각 포지션을 메모리 캐시에 복원
 * 5. exit 로직 정상 동작
 */
@Repository
public interface ImpulsePositionRepository extends JpaRepository<ImpulsePosition, Long> {

    // ==================== 포지션 조회 ====================

    /**
     * 마켓별 OPEN 포지션 조회
     *
     * [사용 시점]
     * - 진입 전: 이미 보유 중인지 확인
     * - exit 시: 해당 마켓 포지션 조회
     *
     * @param market 마켓 코드 (예: KRW-XRP)
     * @return OPEN 상태 포지션 (없으면 empty)
     */
    Optional<ImpulsePosition> findByMarketAndStatus(String market, PositionStatus status);

    /**
     * 마켓별 OPEN 포지션 조회 (편의 메서드)
     */
    default Optional<ImpulsePosition> findOpenByMarket(String market) {
        return findByMarketAndStatus(market, PositionStatus.OPEN);
    }

    /**
     * 상태별 전체 포지션 조회
     *
     * [사용 시점]
     * - 서버 재기동 복구: status=OPEN 전체 조회
     *
     * @param status 포지션 상태
     * @return 해당 상태의 모든 포지션
     */
    List<ImpulsePosition> findByStatus(PositionStatus status);

    /**
     * OPEN 포지션 전체 조회 (편의 메서드)
     *
     * [서버 재기동 시 호출]
     */
    default List<ImpulsePosition> findAllOpen() {
        return findByStatus(PositionStatus.OPEN);
    }

    /**
     * 마켓별 최근 포지션 조회 (상태 무관)
     *
     * @param market 마켓 코드
     * @return 최근 포지션 목록 (진입 시간 역순)
     */
    List<ImpulsePosition> findByMarketOrderByEntryTimeDesc(String market);

    /**
     * 마켓별 OPEN 포지션 존재 여부
     *
     * @param market 마켓 코드
     * @return true: 이미 보유 중
     */
    boolean existsByMarketAndStatus(String market, PositionStatus status);

    /**
     * 마켓별 OPEN 포지션 존재 여부 (편의 메서드)
     */
    default boolean hasOpenPosition(String market) {
        return existsByMarketAndStatus(market, PositionStatus.OPEN);
    }

    // ==================== 통계 조회 ====================

    /**
     * 기간별 청산 완료 포지션 조회
     *
     * @param from 시작 시각
     * @param to 종료 시각
     * @return 해당 기간 청산된 포지션
     */
    @Query("""
        SELECT p FROM ImpulsePosition p
        WHERE p.status = 'CLOSED'
          AND p.exitTime BETWEEN :from AND :to
        ORDER BY p.exitTime DESC
    """)
    List<ImpulsePosition> findClosedBetween(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 청산 사유별 통계
     *
     * [반환 컬럼]
     * - row[0]: exitReason
     * - row[1]: count
     * - row[2]: avgProfitRate
     *
     * @param from 시작 시각
     * @return 청산 사유별 집계
     */
    @Query("""
        SELECT p.exitReason,
               COUNT(p),
               AVG(p.profitRate)
        FROM ImpulsePosition p
        WHERE p.status = 'CLOSED'
          AND p.exitTime >= :from
        GROUP BY p.exitReason
    """)
    List<Object[]> findExitReasonStats(@Param("from") LocalDateTime from);

    /**
     * 시간대별 승률 통계
     *
     * [반환 컬럼]
     * - row[0]: entryHour (0~23)
     * - row[1]: totalCount
     * - row[2]: successCount (profitRate > 0)
     * - row[3]: avgProfitRate
     *
     * @param from 시작 시각
     * @return 시간대별 집계
     */
    @Query("""
        SELECT p.entryHour,
               COUNT(p),
               SUM(CASE WHEN p.profitRate > 0 THEN 1 ELSE 0 END),
               AVG(p.profitRate)
        FROM ImpulsePosition p
        WHERE p.status = 'CLOSED'
          AND p.exitTime >= :from
        GROUP BY p.entryHour
        ORDER BY p.entryHour
    """)
    List<Object[]> findHourlyStats(@Param("from") LocalDateTime from);

    /**
     * 전체 성과 요약
     *
     * [반환 컬럼]
     * - row[0]: totalCount
     * - row[1]: successCount
     * - row[2]: avgProfitRate
     * - row[3]: totalRealizedPnl
     *
     * @param from 시작 시각
     * @return 전체 성과 집계
     */
    @Query("""
        SELECT COUNT(p),
               SUM(CASE WHEN p.profitRate > 0 THEN 1 ELSE 0 END),
               AVG(p.profitRate),
               SUM(p.realizedPnl)
        FROM ImpulsePosition p
        WHERE p.status = 'CLOSED'
          AND p.exitTime >= :from
    """)
    Object[] findOverallStats(@Param("from") LocalDateTime from);

    // ==================== 안전 장치 ====================

    /**
     * 장기 미청산 포지션 조회 (긴급 복구용)
     *
     * [사용 시점]
     * - 버그로 인해 청산되지 않은 포지션 탐지
     * - 6시간 이상 OPEN 상태면 이상 징후
     *
     * @param cutoff 기준 시각 (예: 현재 - 6시간)
     * @return 장기 미청산 포지션
     */
    @Query("""
        SELECT p FROM ImpulsePosition p
        WHERE p.status = 'OPEN'
          AND p.entryTime < :cutoff
        ORDER BY p.entryTime
    """)
    List<ImpulsePosition> findStaleOpenPositions(@Param("cutoff") LocalDateTime cutoff);

    /**
     * OPEN 포지션 강제 청산 (긴급 복구용)
     *
     * [주의] 운영 중 수동 사용 금지
     * 버그 복구 시에만 사용
     */
    @Modifying
    @Query("""
        UPDATE ImpulsePosition p
        SET p.status = 'CLOSED',
            p.exitTime = :now,
            p.exitReason = 'EMERGENCY_CLOSE'
        WHERE p.status = 'OPEN'
          AND p.entryTime < :cutoff
    """)
    int emergencyCloseStalePositions(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("now") LocalDateTime now);

    /**
     * OPEN 포지션 수 조회
     */
    long countByStatus(PositionStatus status);

    /**
     * OPEN 포지션 수 조회 (편의 메서드)
     */
    default long countOpen() {
        return countByStatus(PositionStatus.OPEN);
    }
}