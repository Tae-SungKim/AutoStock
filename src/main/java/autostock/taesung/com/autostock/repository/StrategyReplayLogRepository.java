package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.StrategyReplayLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 전략 리플레이 로그 Repository
 */
@Repository
public interface StrategyReplayLogRepository extends JpaRepository<StrategyReplayLog, Long> {

    // ==================== 기본 조회 ====================

    /** 마켓별 로그 조회 (최신순) */
    List<StrategyReplayLog> findByMarketOrderByLogTimeDesc(String market);

    /** 전략별 로그 조회 (최신순) */
    List<StrategyReplayLog> findByStrategyNameOrderByLogTimeDesc(String strategyName);

    /** 세션별 로그 조회 (시간순) */
    List<StrategyReplayLog> findBySessionIdOrderByLogTimeAsc(String sessionId);

    /** 서버별 로그 조회 */
    List<StrategyReplayLog> findByServerIdOrderByLogTimeDesc(String serverId);

    /** 액션별 로그 조회 */
    List<StrategyReplayLog> findByActionOrderByLogTimeDesc(String action);

    // ==================== 기간 조회 ====================

    /** 기간별 로그 조회 */
    @Query("""
        SELECT r FROM StrategyReplayLog r
        WHERE r.logTime BETWEEN :from AND :to
        ORDER BY r.logTime DESC
    """)
    List<StrategyReplayLog> findByPeriod(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 마켓 + 기간 조회 */
    @Query("""
        SELECT r FROM StrategyReplayLog r
        WHERE r.market = :market
          AND r.logTime BETWEEN :from AND :to
        ORDER BY r.logTime ASC
    """)
    List<StrategyReplayLog> findByMarketAndPeriod(
            @Param("market") String market,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 전략 + 마켓 + 기간 조회 */
    @Query("""
        SELECT r FROM StrategyReplayLog r
        WHERE r.strategyName = :strategy
          AND r.market = :market
          AND r.logTime BETWEEN :from AND :to
        ORDER BY r.logTime ASC
    """)
    List<StrategyReplayLog> findByStrategyAndMarketAndPeriod(
            @Param("strategy") String strategy,
            @Param("market") String market,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // ==================== 통계 조회 ====================

    /** 액션별 통계 */
    @Query("""
        SELECT r.action, COUNT(r), AVG(r.profitRate)
        FROM StrategyReplayLog r
        WHERE r.strategyName = :strategy
          AND r.logTime >= :from
        GROUP BY r.action
    """)
    List<Object[]> findActionStats(
            @Param("strategy") String strategy,
            @Param("from") LocalDateTime from);

    /** 마켓별 진입 횟수 */
    @Query("""
        SELECT r.market, COUNT(r)
        FROM StrategyReplayLog r
        WHERE r.strategyName = :strategy
          AND r.action IN ('BUY', 'ENTRY')
          AND r.logTime >= :from
        GROUP BY r.market
        ORDER BY COUNT(r) DESC
    """)
    List<Object[]> findEntryCountByMarket(
            @Param("strategy") String strategy,
            @Param("from") LocalDateTime from);

    /** 손실 패턴 분석 */
    @Query("""
        SELECT r FROM StrategyReplayLog r
        WHERE r.strategyName = :strategy
          AND r.action = 'EXIT'
          AND r.profitRate < 0
          AND r.logTime >= :from
        ORDER BY r.profitRate ASC
    """)
    List<StrategyReplayLog> findLossPatterns(
            @Param("strategy") String strategy,
            @Param("from") LocalDateTime from);

    /** 수익 패턴 분석 */
    @Query("""
        SELECT r FROM StrategyReplayLog r
        WHERE r.strategyName = :strategy
          AND r.action = 'EXIT'
          AND r.profitRate > 0
          AND r.logTime >= :from
        ORDER BY r.profitRate DESC
    """)
    List<StrategyReplayLog> findProfitPatterns(
            @Param("strategy") String strategy,
            @Param("from") LocalDateTime from);

    // ==================== 서버 동기화 ====================

    /** 서버별 최신 로그 시각 */
    @Query("""
        SELECT MAX(r.logTime)
        FROM StrategyReplayLog r
        WHERE r.serverId = :serverId
    """)
    LocalDateTime findLatestLogTimeByServer(@Param("serverId") String serverId);

    /** 세션 목록 조회 (최신순) */
    @Query("""
        SELECT DISTINCT r.sessionId, MIN(r.logTime), MAX(r.logTime), COUNT(r)
        FROM StrategyReplayLog r
        WHERE r.strategyName = :strategy
        GROUP BY r.sessionId
        ORDER BY MAX(r.logTime) DESC
    """)
    List<Object[]> findSessionList(@Param("strategy") String strategy);

    // ==================== 정리 ====================

    /** 오래된 로그 삭제 */
    @Query("""
        DELETE FROM StrategyReplayLog r
        WHERE r.logTime < :cutoff
    """)
    int deleteOldLogs(@Param("cutoff") LocalDateTime cutoff);
}