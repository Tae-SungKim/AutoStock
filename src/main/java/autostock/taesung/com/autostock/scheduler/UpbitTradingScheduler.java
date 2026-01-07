package autostock.taesung.com.autostock.scheduler;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.repository.UserRepository;
import autostock.taesung.com.autostock.trading.UserAutoTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpbitTradingScheduler {

    private final UserAutoTradingService userAutoTradingService;
    private final UserRepository userRepository;

    @Value("${trading.enabled:false}")
    private boolean tradingEnabled;

    /**
     * 5분마다 자동매매 실행 (자동매매 활성화된 모든 사용자)
     * cron: 초 분 시 일 월 요일
     */
    @Scheduled(cron = "${trading.schedule.cron:0 */5 * * * *}")
    public void executeAutoTrading() {
        if (!tradingEnabled) {
            log.debug("자동매매가 비활성화되어 있습니다. (trading.enabled=false)");
            return;
        }

        // 자동매매 활성화된 사용자 목록 조회
        List<User> activeUsers = userRepository.findByEnabledTrueAndAutoTradingEnabledTrue();

        if (activeUsers.isEmpty()) {
            log.debug("자동매매 활성화된 사용자가 없습니다.");
            return;
        }

        log.info("===== 스케줄러 자동매매 시작 ({}명) =====", activeUsers.size());

        for (User user : activeUsers) {
            try {
                userAutoTradingService.executeAutoTradingForUser(user);
            } catch (Exception e) {
                log.error("[{}] 자동매매 실행 중 오류: {}", user.getUsername(), e.getMessage());
            }
        }

        log.info("===== 스케줄러 자동매매 종료 =====");
    }

    /**
     * 매시간 보유 현황 출력
     */
    @Scheduled(cron = "${trading.schedule.status-cron:0 0 * * * *}")
    public void printStatus() {
        if (!tradingEnabled) {
            return;
        }

        List<User> activeUsers = userRepository.findByEnabledTrueAndAutoTradingEnabledTrue();

        for (User user : activeUsers) {
            try {
                userAutoTradingService.printAccountStatus(user);
            } catch (Exception e) {
                log.error("[{}] 보유 현황 조회 중 오류: {}", user.getUsername(), e.getMessage());
            }
        }
    }

    /**
     * 매매 활성화 상태 확인 (1분마다)
     */
    @Scheduled(fixedRate = 60000)
    public void healthCheck() {
        if (tradingEnabled) {
            long activeUserCount = userRepository.findByEnabledTrueAndAutoTradingEnabledTrue().size();
            log.debug("자동매매 시스템 정상 작동 중... (활성 사용자: {}명)", activeUserCount);
        }
    }
}
