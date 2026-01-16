package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.CandleData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandleDataRepository extends JpaRepository<CandleData, Long> {
    Optional<CandleData> findByMarketAndCandleDateTimeKst(String market, String candleDateTimeKst);
    List<CandleData> findByMarketOrderByCandleDateTimeKstAsc(String market);
    List<CandleData> findByMarketAndUnitOrderByCandleDateTimeKstAsc(String market, int unit);
    List<CandleData> findByMarketAndUnitAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(String market, int unit, String start, String end);

    // 가장 최신 캔들 하나 가져오기
    Optional<CandleData> findFirstByMarketAndUnitOrderByCandleDateTimeKstDesc(String market, int unit);

    void deleteByCreatedAtBefore(java.time.LocalDateTime dateTime);

    // ========== 데이터 분석용 쿼리 ==========

    /**
     * 특정 마켓의 최근 N개 캔들 조회 (최신순)
     */
    List<CandleData> findTop200ByMarketAndUnitOrderByCandleDateTimeKstDesc(String market, int unit);

    /**
     * 전체 마켓 목록 조회
     */
    @Query("SELECT DISTINCT c.market FROM CandleData c")
    List<String> findDistinctMarkets();

    /**
     * 특정 기간 캔들 데이터 조회
     */
    @Query("SELECT c FROM CandleData c WHERE c.market = :market AND c.unit = :unit AND c.createdAt >= :from ORDER BY c.candleDateTimeKst ASC")
    List<CandleData> findByMarketAndUnitAndPeriod(
            @Param("market") String market,
            @Param("unit") int unit,
            @Param("from") LocalDateTime from);

    /**
     * 마켓별 데이터 개수 조회
     */
    @Query("SELECT c.market, COUNT(c) FROM CandleData c WHERE c.unit = :unit GROUP BY c.market ORDER BY COUNT(c) DESC")
    List<Object[]> countByMarketAndUnit(@Param("unit") int unit);

    /**
     * 전체 캔들 데이터 조회 (분석용)
     */
    @Query("SELECT c FROM CandleData c WHERE c.unit = :unit ORDER BY c.market, c.candleDateTimeKst ASC")
    List<CandleData> findAllByUnitOrderByMarketAndTime(@Param("unit") int unit);
}
