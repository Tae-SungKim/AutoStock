package autostock.taesung.com.autostock.scheduler;

import autostock.taesung.com.autostock.repository.CandleDataRepository;
import autostock.taesung.com.autostock.repository.TickerDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataCleanupScheduler {

    private final CandleDataRepository candleDataRepository;
    private final TickerDataRepository tickerDataRepository;

    /**
     * 5일이 지난 CandleData 및 TickerData 삭제
     * 실행 시간은 application.properties의 trading.schedule.cleanup-cron 설정에 따름
     */
    @Transactional
    @Scheduled(cron = "${trading.schedule.cleanup-cron:0 0 3 * * *}")
    public void cleanupOldData() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(5);
        log.info("===== 데이터 정리 스케줄러 시작 (기준 일시: {}) =====", cutoffDate);

        try {
            candleDataRepository.deleteByCreatedAtBefore(cutoffDate);
            log.info("5일 이전의 CandleData 삭제 완료");

            tickerDataRepository.deleteByCreatedAtBefore(cutoffDate);
            log.info("5일 이전의 TickerData 삭제 완료");

        } catch (Exception e) {
            log.error("데이터 정리 중 오류 발생: {}", e.getMessage(), e);
        }

        log.info("===== 데이터 정리 스케줄러 종료 =====");
    }
}
