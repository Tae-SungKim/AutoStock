package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.ImpulseTradeStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Impulse 거래 통계 Repository
 *
 * [주요 기능]
 * 1. 미청산 포지션 조회 (진입 후 아직 청산 안 된 건)
 * 2. 시간대별 승률/수익률 통계 조회 (자동 튜닝용)
 * 3. 최근 성공 이력 조회 (Breakout 연계용)
 */
@Repository
public interface ImpulseTradeStatRepository extends JpaRepository<ImpulseTradeStat, Long> {

    /**
     * 미청산 포지션 조회
     * - 특정 마켓에서 exitTime이 NULL인 레코드
     * - 진입 후 아직 청산되지 않은 포지션
     *
     * @param market 마켓 코드
     * @return 미청산 포지션 (없으면 Optional.empty())
     */
    Optional<ImpulseTradeStat> findByMarketAndExitTimeIsNull(String market);

    /**
     * 마켓별 거래 이력 조회 (최신순)
     *
     * @param market 마켓 코드
     * @return 거래 이력 리스트
     */
    List<ImpulseTradeStat> findByMarketOrderByEntryTimeDesc(String market);

    /**
     * 시간대별 기본 통계 조회
     *
     * [반환 컬럼]
     * - row[0]: entryHour (시간대 0~23)
     * - row[1]: count (총 거래 수)
     * - row[2]: winCount (성공 거래 수)
     * - row[3]: avgProfitRate (평균 수익률)
     *
     * @param from 조회 시작 시각
     * @return 시간대별 통계 배열
     */
    @Query("""
        SELECT i.entryHour,
               COUNT(i),
               SUM(CASE WHEN i.success = true THEN 1 ELSE 0 END),
               AVG(i.profitRate)
        FROM ImpulseTradeStat i
        WHERE i.exitTime IS NOT NULL
          AND i.entryTime >= :from
        GROUP BY i.entryHour
        ORDER BY i.entryHour
    """)
    List<Object[]> findHourlyStats(@Param("from") LocalDateTime from);

    /**
     * 자동 튜닝용 상세 통계 조회
     *
     * [반환 컬럼]
     * - row[0]: entryHour (시간대)
     * - row[1]: count (총 거래 수)
     * - row[2]: winCount (성공 거래 수)
     * - row[3]: avgProfitRate (평균 수익률)
     * - row[4]: avgZScore (평균 진입 Z-score)
     * - row[5]: avgExecStrength (평균 진입 체결강도)
     *
     * [필터 조건]
     * - 청산 완료된 건만 (exitTime IS NOT NULL)
     * - 지정 기간 내 (from ~ to)
     * - 최소 샘플 수 충족 (minSamples 이상)
     *
     * @param from 시작 시각
     * @param to 종료 시각
     * @param minSamples 최소 샘플 수 (기본 20)
     * @return 시간대별 상세 통계
     */
    @Query("""
        SELECT i.entryHour,
               COUNT(i),
               SUM(CASE WHEN i.success = true THEN 1 ELSE 0 END),
               AVG(i.profitRate),
               AVG(i.entryZScore),
               AVG(i.entryExecutionStrength)
        FROM ImpulseTradeStat i
        WHERE i.exitTime IS NOT NULL
          AND i.entryTime BETWEEN :from AND :to
        GROUP BY i.entryHour
        HAVING COUNT(i) >= :minSamples
        ORDER BY i.entryHour
    """)
    List<Object[]> findHourlyStatsForTuning(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("minSamples") long minSamples);

    /**
     * 전체 통계 조회 (대시보드용)
     *
     * [반환 컬럼]
     * - row[0]: totalCount (총 거래 수)
     * - row[1]: winCount (성공 거래 수)
     * - row[2]: avgProfitRate (평균 수익률)
     *
     * @param from 조회 시작 시각
     * @return 전체 통계 배열
     */
    @Query("""
        SELECT COUNT(i),
               SUM(CASE WHEN i.success = true THEN 1 ELSE 0 END),
               AVG(i.profitRate)
        FROM ImpulseTradeStat i
        WHERE i.exitTime IS NOT NULL
          AND i.entryTime >= :from
    """)
    Object[] findOverallStats(@Param("from") LocalDateTime from);

    /**
     * 최근 성공 이력 조회 (Breakout 연계용)
     *
     * [목적]
     * - Breakout 전략 진입 전, 해당 마켓이 최근 15분 내 Impulse 성공 이력이 있는지 확인
     * - 성공 이력 없으면 Breakout 진입 차단
     *
     * @param market 마켓 코드
     * @param since 기준 시각 (현재 - 15분)
     * @return 최근 성공 거래 리스트
     */
    @Query("""
        SELECT i FROM ImpulseTradeStat i
        WHERE i.market = :market
          AND i.success = true
          AND i.exitTime >= :since
        ORDER BY i.exitTime DESC
    """)
    List<ImpulseTradeStat> findRecentSuccessByMarket(
            @Param("market") String market,
            @Param("since") LocalDateTime since);

    /**
     * 시간대별 청산 완료 거래 수
     *
     * @param hour 시간대 (0~23)
     * @return 해당 시간대의 청산 완료 거래 수
     */
    long countByEntryHourAndExitTimeIsNotNull(int hour);
}