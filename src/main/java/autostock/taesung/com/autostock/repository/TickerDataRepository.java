package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.TickerData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TickerDataRepository extends JpaRepository<TickerData, Long> {
    void deleteByCreatedAtBefore(java.time.LocalDateTime dateTime);
}
