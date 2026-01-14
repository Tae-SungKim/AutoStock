package autostock.taesung.com.autostock.realtrading.risk;

import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.realtrading.entity.Position;
import autostock.taesung.com.autostock.realtrading.repository.PositionRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 리스크 관리자
 * - 포지션당 최대 손실 제한
 * - 일일 최대 손실 제한
 * - 연속 손실 거래 중단
 * - 쿨다운 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskManager {

    private final PositionRepository positionRepository;
    private final RealTradingConfig config;

    // 쿨다운 상태 관리 (userId -> 쿨다운 종료 시간)
    private final Map<Long, LocalDateTime> cooldownUntil = new ConcurrentHashMap<>();

    /**
     * 진입 허용 여부 검사
     * @return RiskCheckResult (허용/거부 + 사유)
     */
    public RiskCheckResult canEnterPosition(Long userId, String market, BigDecimal accountBalance,
                                            BigDecimal requestedAmount) {
        // 1. 쿨다운 체크
        if (isInCooldown(userId)) {
            LocalDateTime until = cooldownUntil.get(userId);
            return RiskCheckResult.denied("COOLDOWN",
                    String.format("연속 손실로 인한 거래 중단 중 (해제: %s)", until));
        }

        // 2. 동시 포지션 수 체크
        int activeCount = positionRepository.countActivePositions(userId);
        if (activeCount >= config.getMaxConcurrentPositions()) {
            return RiskCheckResult.denied("MAX_POSITIONS",
                    String.format("최대 동시 포지션 초과 (%d/%d)",
                            activeCount, config.getMaxConcurrentPositions()));
        }

        // 3. 기존 포지션 중복 체크
        if (positionRepository.findActivePosition(userId, market).isPresent()) {
            return RiskCheckResult.denied("DUPLICATE_POSITION",
                    "이미 해당 마켓에 포지션 보유 중");
        }

        // 4. 포지션 크기 제한 체크
        BigDecimal maxPositionSize = accountBalance
                .multiply(BigDecimal.valueOf(config.getMaxPositionSizeRate()));
        if (requestedAmount.compareTo(maxPositionSize) > 0) {
            return RiskCheckResult.denied("POSITION_SIZE_LIMIT",
                    String.format("포지션 크기 초과 (요청: %s, 한도: %s)",
                            requestedAmount.setScale(0, RoundingMode.HALF_UP),
                            maxPositionSize.setScale(0, RoundingMode.HALF_UP)));
        }

        // 5. 일일 손실 한도 체크
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        BigDecimal todayLoss = positionRepository.getTodayLoss(userId, startOfDay);
        BigDecimal maxDailyLoss = accountBalance
                .multiply(BigDecimal.valueOf(config.getMaxDailyLossRate()))
                .negate(); // 음수로 변환

        if (todayLoss.compareTo(maxDailyLoss) <= 0) {
            return RiskCheckResult.denied("DAILY_LOSS_LIMIT",
                    String.format("일일 손실 한도 초과 (현재: %s, 한도: %s)",
                            todayLoss.setScale(0, RoundingMode.HALF_UP),
                            maxDailyLoss.setScale(0, RoundingMode.HALF_UP)));
        }

        // 6. 연속 손실 체크
        int consecutiveLosses = getConsecutiveLosses(userId);
        if (consecutiveLosses >= config.getMaxConsecutiveLosses()) {
            // 쿨다운 활성화
            activateCooldown(userId);
            return RiskCheckResult.denied("CONSECUTIVE_LOSSES",
                    String.format("연속 손실 %d회로 거래 중단 (쿨다운 %d분)",
                            consecutiveLosses, config.getCooldownMinutes()));
        }

        return RiskCheckResult.allowed();
    }

    /**
     * 추가 진입 허용 여부 검사
     */
    public RiskCheckResult canAddEntry(Long userId, Position position, BigDecimal accountBalance,
                                       BigDecimal additionalAmount) {
        // 1. 쿨다운 체크
        if (isInCooldown(userId)) {
            return RiskCheckResult.denied("COOLDOWN", "쿨다운 중");
        }

        // 2. 진입 단계 체크
        if (!position.canAddEntry()) {
            return RiskCheckResult.denied("MAX_ENTRIES", "최대 진입 횟수 초과 (3회)");
        }

        // 3. 포지션 크기 체크 (기존 + 추가)
        BigDecimal totalInvestment = position.getTotalInvested().add(additionalAmount);
        BigDecimal maxPositionSize = accountBalance
                .multiply(BigDecimal.valueOf(config.getMaxPositionSizeRate()));

        if (totalInvestment.compareTo(maxPositionSize) > 0) {
            return RiskCheckResult.denied("POSITION_SIZE_LIMIT",
                    String.format("포지션 크기 초과 (총: %s, 한도: %s)",
                            totalInvestment.setScale(0, RoundingMode.HALF_UP),
                            maxPositionSize.setScale(0, RoundingMode.HALF_UP)));
        }

        return RiskCheckResult.allowed();
    }

    /**
     * 포지션별 손실 한도 체크
     * @return true면 손절 필요
     */
    public boolean shouldStopLoss(Position position, BigDecimal currentPrice, BigDecimal accountBalance) {
        BigDecimal unrealizedPnl = position.getUnrealizedPnl(currentPrice);
        BigDecimal maxLoss = accountBalance
                .multiply(BigDecimal.valueOf(config.getMaxPositionLossRate()))
                .negate();

        if (unrealizedPnl.compareTo(maxLoss) <= 0) {
            log.warn("[RISK] 포지션 손실 한도 초과: market={}, PnL={}, limit={}",
                    position.getMarket(), unrealizedPnl, maxLoss);
            return true;
        }

        return false;
    }

    /**
     * 손절가 계산 (ATR 기반)
     */
    public BigDecimal calculateStopLossPrice(BigDecimal entryPrice, BigDecimal atr, boolean isLong) {
        BigDecimal stopDistance = atr.multiply(BigDecimal.valueOf(config.getStopLossAtrMultiplier()));

        // 최대/최소 손절률 제한
        BigDecimal maxStopDistance = entryPrice.multiply(
                BigDecimal.valueOf(Math.abs(config.getMaxStopLossRate())));
        BigDecimal minStopDistance = entryPrice.multiply(
                BigDecimal.valueOf(Math.abs(config.getMinStopLossRate())));

        stopDistance = stopDistance.max(minStopDistance).min(maxStopDistance);

        if (isLong) {
            return entryPrice.subtract(stopDistance);
        } else {
            return entryPrice.add(stopDistance);
        }
    }

    /**
     * 트레일링 스탑가 계산 (ATR 기반)
     */
    public BigDecimal calculateTrailingStopPrice(BigDecimal highPrice, BigDecimal atr) {
        BigDecimal trailingDistance = atr.multiply(
                BigDecimal.valueOf(config.getTrailingAtrMultiplier()));

        // 최소 트레일링 거리 보장
        BigDecimal minDistance = highPrice.multiply(
                BigDecimal.valueOf(config.getTrailingStopRate()));
        trailingDistance = trailingDistance.max(minDistance);

        return highPrice.subtract(trailingDistance);
    }

    /**
     * 포지션 크기 계산
     */
    public BigDecimal calculatePositionSize(BigDecimal accountBalance, int entryPhase) {
        double ratio = config.getEntryRatio(entryPhase);
        BigDecimal maxPositionSize = accountBalance
                .multiply(BigDecimal.valueOf(config.getMaxPositionSizeRate()));
        return maxPositionSize.multiply(BigDecimal.valueOf(ratio));
    }

    /**
     * 쿨다운 활성화
     */
    public void activateCooldown(Long userId) {
        LocalDateTime until = LocalDateTime.now().plusMinutes(config.getCooldownMinutes());
        cooldownUntil.put(userId, until);
        log.warn("[RISK] 쿨다운 활성화: userId={}, until={}", userId, until);
    }

    /**
     * 쿨다운 상태 확인
     */
    public boolean isInCooldown(Long userId) {
        LocalDateTime until = cooldownUntil.get(userId);
        if (until == null) {
            return false;
        }
        if (LocalDateTime.now().isAfter(until)) {
            cooldownUntil.remove(userId);
            return false;
        }
        return true;
    }

    /**
     * 연속 손실 횟수 조회
     */
    public int getConsecutiveLosses(Long userId) {
        try {
            return positionRepository.countConsecutiveLosses(userId);
        } catch (Exception e) {
            log.warn("연속 손실 횟수 조회 실패: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 거래 후 리스크 상태 업데이트
     */
    public void onTradeComplete(Long userId, Position position) {
        // 손실 거래면 연속 손실 체크
        if (position.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
            int losses = getConsecutiveLosses(userId);
            log.info("[RISK] 손실 거래 기록: userId={}, market={}, PnL={}, 연속손실={}",
                    userId, position.getMarket(), position.getRealizedPnl(), losses);

            if (losses >= config.getMaxConsecutiveLosses()) {
                activateCooldown(userId);
            }
        }
    }

    /**
     * 리스크 상태 요약
     */
    public RiskStatus getRiskStatus(Long userId, BigDecimal accountBalance) {
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);

        int activePositions = positionRepository.countActivePositions(userId);
        BigDecimal todayLoss = positionRepository.getTodayLoss(userId, startOfDay);
        int todayTrades = positionRepository.countTodayTrades(userId, startOfDay);
        int consecutiveLosses = getConsecutiveLosses(userId);

        BigDecimal maxDailyLoss = accountBalance
                .multiply(BigDecimal.valueOf(config.getMaxDailyLossRate()));

        double riskScore = calculateRiskScore(activePositions, todayLoss, maxDailyLoss,
                consecutiveLosses, isInCooldown(userId));

        return RiskStatus.builder()
                .userId(userId)
                .activePositions(activePositions)
                .maxPositions(config.getMaxConcurrentPositions())
                .todayLoss(todayLoss)
                .maxDailyLoss(maxDailyLoss)
                .todayTrades(todayTrades)
                .consecutiveLosses(consecutiveLosses)
                .maxConsecutiveLosses(config.getMaxConsecutiveLosses())
                .inCooldown(isInCooldown(userId))
                .cooldownUntil(cooldownUntil.get(userId))
                .riskScore(riskScore)
                .canTrade(riskScore < 100)
                .build();
    }

    /**
     * 리스크 점수 계산 (0~100, 100 이상이면 거래 중단)
     */
    private double calculateRiskScore(int activePositions, BigDecimal todayLoss,
                                       BigDecimal maxDailyLoss, int consecutiveLosses,
                                       boolean inCooldown) {
        if (inCooldown) {
            return 100;
        }

        double score = 0;

        // 포지션 비율 (30% 가중치)
        score += (activePositions * 100.0 / config.getMaxConcurrentPositions()) * 0.3;

        // 일일 손실 비율 (40% 가중치)
        if (maxDailyLoss.compareTo(BigDecimal.ZERO) != 0) {
            double lossRatio = todayLoss.abs().divide(maxDailyLoss.abs(), 4, RoundingMode.HALF_UP)
                    .doubleValue();
            score += lossRatio * 100 * 0.4;
        }

        // 연속 손실 (30% 가중치)
        score += (consecutiveLosses * 100.0 / config.getMaxConsecutiveLosses()) * 0.3;

        return Math.min(100, score);
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class RiskCheckResult {
        private boolean allowed;
        private String code;
        private String reason;

        public static RiskCheckResult allowed() {
            return RiskCheckResult.builder()
                    .allowed(true)
                    .code("OK")
                    .build();
        }

        public static RiskCheckResult denied(String code, String reason) {
            return RiskCheckResult.builder()
                    .allowed(false)
                    .code(code)
                    .reason(reason)
                    .build();
        }
    }

    @Data
    @Builder
    public static class RiskStatus {
        private Long userId;
        private int activePositions;
        private int maxPositions;
        private BigDecimal todayLoss;
        private BigDecimal maxDailyLoss;
        private int todayTrades;
        private int consecutiveLosses;
        private int maxConsecutiveLosses;
        private boolean inCooldown;
        private LocalDateTime cooldownUntil;
        private double riskScore;
        private boolean canTrade;
    }
}