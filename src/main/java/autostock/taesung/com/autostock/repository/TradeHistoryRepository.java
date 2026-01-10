package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.entity.TradeHistory.TradeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {

    /**
     * 마켓별 거래 내역 조회
     */
    List<TradeHistory> findByMarketOrderByCreatedAtDesc(String market);

    /**
     * 거래 유형별 조회 (매수/매도)
     */
    List<TradeHistory> findByTradeTypeOrderByCreatedAtDesc(TradeType tradeType);

    /**
     * 특정 기간 거래 내역 조회
     */
    List<TradeHistory> findByTradeDateBetweenOrderByCreatedAtDesc(LocalDate startDate, LocalDate endDate);

    /**
     * 마켓 + 거래유형별 조회
     */
    List<TradeHistory> findByMarketAndTradeTypeOrderByCreatedAtDesc(String market, TradeType tradeType);

    /**
     * 최근 N건 조회
     */
    List<TradeHistory> findTop100ByOrderByCreatedAtDesc();

    /**
     * 마켓별 총 매수 금액 합계
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TradeHistory t WHERE t.market = :market AND t.tradeType = 'BUY'")
    BigDecimal getTotalBuyAmountByMarket(@Param("market") String market);

    /**
     * 마켓별 총 매도 금액 합계
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TradeHistory t WHERE t.market = :market AND t.tradeType = 'SELL'")
    BigDecimal getTotalSellAmountByMarket(@Param("market") String market);

    /**
     * 마켓별 총 수수료 합계
     */
    @Query("SELECT COALESCE(SUM(t.fee), 0) FROM TradeHistory t WHERE t.market = :market")
    BigDecimal getTotalFeeByMarket(@Param("market") String market);

    /**
     * 전체 손익 계산 (매도금액 - 매수금액 - 수수료)
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.tradeType = 'SELL' THEN t.amount ELSE -t.amount END), 0) - COALESCE(SUM(t.fee), 0) FROM TradeHistory t")
    BigDecimal getTotalProfitLoss();

    /**
     * 마켓별 손익 계산
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.tradeType = 'SELL' THEN t.amount ELSE -t.amount END), 0) - COALESCE(SUM(t.fee), 0) FROM TradeHistory t WHERE t.market = :market")
    BigDecimal getProfitLossByMarket(@Param("market") String market);

    // ========== 사용자별 조회 메서드 ==========

    /**
     * 사용자별 전체 거래 내역 조회 (정렬 없음)
     */
    List<TradeHistory> findByUserId(Long userId);

    /**
     * 사용자별 전체 거래 내역 조회 (최신순)
     */
    List<TradeHistory> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 사용자별 마켓 거래 내역 조회
     */
    List<TradeHistory> findByUserIdAndMarketOrderByCreatedAtDesc(Long userId, String market);

    /**
     * 사용자별 최근 N건 조회
     */
    List<TradeHistory> findTop100ByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 사용자별 손익 계산
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.tradeType = 'SELL' THEN t.amount ELSE -t.amount END), 0) - COALESCE(SUM(t.fee), 0) FROM TradeHistory t WHERE t.userId = :userId")
    BigDecimal getTotalProfitLossByUserId(@Param("userId") Long userId);

    /**
     * 사용자별 마켓 손익 계산
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.tradeType = 'SELL' THEN t.amount ELSE -t.amount END), 0) - COALESCE(SUM(t.fee), 0) FROM TradeHistory t WHERE t.userId = :userId AND t.market = :market")
    BigDecimal getProfitLossByUserIdAndMarket(@Param("userId") Long userId, @Param("market") String market);

    @Query("""
    SELECT th
    FROM TradeHistory th
    WHERE th.market = :market
    ORDER BY th.createdAt DESC
""")
    List<TradeHistory> findLatestByMarket(@Param("market") String market);

    @Query("""
    SELECT t FROM TradeHistory t
    WHERE t.market = :market
    ORDER BY t.createdAt DESC
""")
    List<TradeHistory> findRecentByMarket(
            @Param("market") String market,
            Pageable pageable);

    @Query("""
SELECT
    t.strategyName,
    COUNT(t),
    SUM(
        CASE
            WHEN t.tradeType = 'SELL' THEN t.amount
            ELSE 0
        END
    )
FROM TradeHistory t
WHERE t.createdAt >= :from
GROUP BY t.strategyName
""")
    List<Object[]> findStrategySummary(@Param("from") LocalDateTime from);

    @Query("""
SELECT t.market, t.tradeType, t.amount, t.createdAt
FROM TradeHistory t
WHERE t.strategyName = :strategy
ORDER BY t.createdAt
""")
    List<Object[]> findTradesByStrategy(String strategy);
}