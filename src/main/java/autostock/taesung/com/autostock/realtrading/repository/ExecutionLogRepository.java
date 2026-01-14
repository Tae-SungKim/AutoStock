package autostock.taesung.com.autostock.realtrading.repository;

import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.ExecutionStatus;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.OrderSide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, Long> {

    /** 포지션별 체결 로그 */
    List<ExecutionLog> findByPositionIdOrderByCreatedAtAsc(Long positionId);

    /** 마켓별 체결 로그 */
    List<ExecutionLog> findByMarketOrderByCreatedAtDesc(String market);

    /** 특정 기간 체결 로그 */
    List<ExecutionLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime start, LocalDateTime end);

    // ==================== 슬리피지 통계 ====================

    /** 마켓별 평균 슬리피지 */
    @Query("SELECT e.market, AVG(e.slippagePercent), COUNT(e) " +
           "FROM ExecutionLog e WHERE e.status = 'FILLED' " +
           "AND e.createdAt >= :since GROUP BY e.market")
    List<Object[]> getAvgSlippageByMarket(@Param("since") LocalDateTime since);

    /** 시간대별 평균 슬리피지 */
    @Query("SELECT HOUR(e.executedAt), AVG(e.slippagePercent), COUNT(e) " +
           "FROM ExecutionLog e WHERE e.status = 'FILLED' " +
           "AND e.createdAt >= :since GROUP BY HOUR(e.executedAt)")
    List<Object[]> getAvgSlippageByHour(@Param("since") LocalDateTime since);

    /** 주문 유형별 슬리피지 */
    @Query("SELECT e.side, e.executionType, AVG(e.slippagePercent), COUNT(e) " +
           "FROM ExecutionLog e WHERE e.status = 'FILLED' " +
           "GROUP BY e.side, e.executionType")
    List<Object[]> getSlippageByOrderType();

    /** 총 슬리피지 비용 */
    @Query("SELECT COALESCE(SUM(ABS(e.slippage) * e.executedQuantity), 0) " +
           "FROM ExecutionLog e WHERE e.status = 'FILLED' AND e.createdAt >= :since")
    BigDecimal getTotalSlippageCost(@Param("since") LocalDateTime since);

    // ==================== 체결 품질 통계 ====================

    /** 평균 체결 시간 */
    @Query("SELECT AVG(e.executionTimeMs) FROM ExecutionLog e " +
           "WHERE e.status = 'FILLED' AND e.executionTimeMs IS NOT NULL")
    Double getAvgExecutionTimeMs();

    /** 체결 성공률 */
    @Query("SELECT " +
           "SUM(CASE WHEN e.status = 'FILLED' THEN 1 ELSE 0 END) * 100.0 / COUNT(e) " +
           "FROM ExecutionLog e WHERE e.createdAt >= :since")
    Double getExecutionSuccessRate(@Param("since") LocalDateTime since);

    /** 부분 체결 비율 */
    @Query("SELECT " +
           "SUM(CASE WHEN e.status = 'PARTIAL' THEN 1 ELSE 0 END) * 100.0 / COUNT(e) " +
           "FROM ExecutionLog e WHERE e.createdAt >= :since")
    Double getPartialFillRate(@Param("since") LocalDateTime since);

    /** 실패 사유별 통계 */
    @Query("SELECT e.failureReason, COUNT(e) FROM ExecutionLog e " +
           "WHERE e.status = 'FAILED' GROUP BY e.failureReason")
    List<Object[]> getFailureReasonStats();

    // ==================== 수수료 통계 ====================

    /** 총 수수료 */
    @Query("SELECT COALESCE(SUM(e.fee), 0) FROM ExecutionLog e " +
           "WHERE e.status = 'FILLED' AND e.createdAt >= :since")
    BigDecimal getTotalFees(@Param("since") LocalDateTime since);

    /** 주문 유형별 수수료 */
    @Query("SELECT e.side, COALESCE(SUM(e.fee), 0) FROM ExecutionLog e " +
           "WHERE e.status = 'FILLED' GROUP BY e.side")
    List<Object[]> getFeesBySide();

    // ==================== 유동성 분석 ====================

    /** 스프레드별 슬리피지 상관관계 */
    @Query("SELECT " +
           "CASE WHEN e.spread <= 0.1 THEN 'LOW' " +
           "     WHEN e.spread <= 0.3 THEN 'MEDIUM' " +
           "     ELSE 'HIGH' END, " +
           "AVG(e.slippagePercent), COUNT(e) " +
           "FROM ExecutionLog e WHERE e.status = 'FILLED' AND e.spread IS NOT NULL " +
           "GROUP BY " +
           "CASE WHEN e.spread <= 0.1 THEN 'LOW' " +
           "     WHEN e.spread <= 0.3 THEN 'MEDIUM' " +
           "     ELSE 'HIGH' END")
    List<Object[]> getSlippageBySpread();

    /** 거래대금별 슬리피지 상관관계 */
    @Query("SELECT " +
           "CASE WHEN e.tradingVolume >= 10000000000 THEN 'VERY_HIGH' " +
           "     WHEN e.tradingVolume >= 1000000000 THEN 'HIGH' " +
           "     WHEN e.tradingVolume >= 500000000 THEN 'MEDIUM' " +
           "     ELSE 'LOW' END, " +
           "AVG(e.slippagePercent), COUNT(e) " +
           "FROM ExecutionLog e WHERE e.status = 'FILLED' AND e.tradingVolume IS NOT NULL " +
           "GROUP BY " +
           "CASE WHEN e.tradingVolume >= 10000000000 THEN 'VERY_HIGH' " +
           "     WHEN e.tradingVolume >= 1000000000 THEN 'HIGH' " +
           "     WHEN e.tradingVolume >= 500000000 THEN 'MEDIUM' " +
           "     ELSE 'LOW' END")
    List<Object[]> getSlippageByVolume();

    // ==================== 백테스트 vs 실거래 비교 ====================

    /** 백테스트 슬리피지 통계 */
    @Query("SELECT AVG(e.slippagePercent), STDDEV(e.slippagePercent), COUNT(e) " +
           "FROM ExecutionLog e WHERE e.isBacktest = true AND e.status = 'FILLED'")
    Object[] getBacktestSlippageStats();

    /** 실거래 슬리피지 통계 */
    @Query("SELECT AVG(e.slippagePercent), STDDEV(e.slippagePercent), COUNT(e) " +
           "FROM ExecutionLog e WHERE e.isBacktest = false AND e.status = 'FILLED'")
    Object[] getRealSlippageStats();
}