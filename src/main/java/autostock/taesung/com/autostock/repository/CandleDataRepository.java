package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.CandleData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CandleDataRepository extends JpaRepository<CandleData, Long> {
    Optional<CandleData> findByMarketAndCandleDateTimeKst(String market, String candleDateTimeKst);
    List<CandleData> findByMarketOrderByCandleDateTimeKstAsc(String market);
    List<CandleData> findByMarketAndUnitOrderByCandleDateTimeKstAsc(String market, int unit);
    
    // 가장 최신 캔들 하나 가져오기
    Optional<CandleData> findFirstByMarketAndUnitOrderByCandleDateTimeKstDesc(String market, int unit);

    void deleteByCreatedAtBefore(java.time.LocalDateTime dateTime);
}
