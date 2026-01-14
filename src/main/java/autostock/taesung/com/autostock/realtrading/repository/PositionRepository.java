package autostock.taesung.com.autostock.realtrading.repository;

import autostock.taesung.com.autostock.realtrading.entity.Position;
import autostock.taesung.com.autostock.realtrading.entity.Position.PositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

    /** 사용자의 특정 마켓 활성 포지션 */
    Optional<Position> findByUserIdAndMarketAndStatusNot(Long userId, String market, PositionStatus status);

    /** 사용자의 모든 활성 포지션 */
    List<Position> findByUserIdAndStatusIn(Long userId, List<PositionStatus> statuses);

    /** 사용자의 마켓별 활성 포지션 */
    @Query("SELECT p FROM Position p WHERE p.userId = :userId AND p.market = :market " +
           "AND p.status IN ('PENDING', 'ENTERING', 'ACTIVE', 'EXITING')")
    Optional<Position> findActivePosition(@Param("userId") Long userId, @Param("market") String market);

    /** 사용자의 모든 열린 포지션 수 */
    @Query("SELECT COUNT(p) FROM Position p WHERE p.userId = :userId " +
           "AND p.status IN ('PENDING', 'ENTERING', 'ACTIVE', 'EXITING')")
    int countActivePositions(@Param("userId") Long userId);

    /** 오늘 손실 합계 */
    @Query("SELECT COALESCE(SUM(p.realizedPnl), 0) FROM Position p WHERE p.userId = :userId " +
           "AND p.status = 'CLOSED' AND p.realizedPnl < 0 " +
           "AND p.finalExitTime >= :startOfDay")
    BigDecimal getTodayLoss(@Param("userId") Long userId, @Param("startOfDay") LocalDateTime startOfDay);

    /** 오늘 거래 수 */
    @Query("SELECT COUNT(p) FROM Position p WHERE p.userId = :userId " +
           "AND p.status = 'CLOSED' AND p.finalExitTime >= :startOfDay")
    int countTodayTrades(@Param("userId") Long userId, @Param("startOfDay") LocalDateTime startOfDay);

    /** 연속 손실 횟수 */
    @Query(value = "SELECT COUNT(*) FROM ( " +
           "SELECT realized_pnl FROM real_position " +
           "WHERE user_id = :userId AND status = 'CLOSED' " +
           "ORDER BY final_exit_time DESC LIMIT 10) sub " +
           "WHERE sub.realized_pnl < 0 " +
           "AND NOT EXISTS ( " +
           "  SELECT 1 FROM real_position p2 " +
           "  WHERE p2.user_id = :userId AND p2.status = 'CLOSED' " +
           "  AND p2.realized_pnl >= 0 " +
           "  AND p2.final_exit_time > ( " +
           "    SELECT MAX(p3.final_exit_time) FROM real_position p3 " +
           "    WHERE p3.user_id = :userId AND p3.status = 'CLOSED' " +
           "    AND p3.realized_pnl < 0))",
           nativeQuery = true)
    int countConsecutiveLosses(@Param("userId") Long userId);

    /** 트레일링 활성화된 포지션 */
    List<Position> findByUserIdAndTrailingActiveTrue(Long userId);

    /** 진입 대기 중인 포지션 (추가 진입 가능) */
    @Query("SELECT p FROM Position p WHERE p.userId = :userId " +
           "AND p.status IN ('ENTERING', 'ACTIVE') AND p.entryPhase < 3")
    List<Position> findPendingEntryPositions(@Param("userId") Long userId);

    /** 마켓별 최근 거래 성과 */
    @Query("SELECT p.market, COUNT(p), SUM(p.realizedPnl), AVG(p.totalSlippage) " +
           "FROM Position p WHERE p.userId = :userId AND p.status = 'CLOSED' " +
           "AND p.finalExitTime >= :since GROUP BY p.market")
    List<Object[]> getMarketPerformance(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** 전략별 성과 */
    @Query("SELECT p.strategyName, COUNT(p), SUM(p.realizedPnl), " +
           "SUM(CASE WHEN p.realizedPnl > 0 THEN 1 ELSE 0 END) " +
           "FROM Position p WHERE p.userId = :userId AND p.status = 'CLOSED' " +
           "GROUP BY p.strategyName")
    List<Object[]> getStrategyPerformance(@Param("userId") Long userId);

    /** 청산 사유별 통계 */
    @Query("SELECT p.exitReason, COUNT(p), SUM(p.realizedPnl) " +
           "FROM Position p WHERE p.userId = :userId AND p.status = 'CLOSED' " +
           "GROUP BY p.exitReason")
    List<Object[]> getExitReasonStats(@Param("userId") Long userId);
}