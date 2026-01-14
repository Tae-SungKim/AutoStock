package autostock.taesung.com.autostock.realtrading.strategy;

import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.ExecutionType;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.OrderSide;
import autostock.taesung.com.autostock.realtrading.entity.Position;
import autostock.taesung.com.autostock.realtrading.repository.PositionRepository;
import autostock.taesung.com.autostock.realtrading.risk.RiskManager;
import autostock.taesung.com.autostock.realtrading.risk.RiskManager.RiskCheckResult;
import autostock.taesung.com.autostock.realtrading.service.ExecutionService;
import autostock.taesung.com.autostock.realtrading.service.ExecutionService.ExecutionResult;
import autostock.taesung.com.autostock.realtrading.service.ExecutionService.MarketData;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 분할 진입 전략
 * - 3단계 분할 진입 (30%/30%/40%)
 * - 하락 시 추가 매수 (물타기)
 * - ATR 기반 진입 간격 조절
 * - 리스크 검증 통합
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScaledEntryStrategy {

    private final PositionRepository positionRepository;
    private final ExecutionService executionService;
    private final RiskManager riskManager;
    private final RealTradingConfig config;

    /**
     * 신규 포지션 진입 (1차 진입)
     * @param userId 사용자 ID
     * @param market 마켓 코드
     * @param signalStrength 신호 강도 (0~100)
     * @param accountBalance 계좌 잔고
     * @param marketData 현재 시장 데이터
     * @return EntryResult
     */
    @Transactional
    public EntryResult enterNewPosition(Long userId, String market, int signalStrength,
                                         BigDecimal accountBalance, MarketData marketData) {

        log.info("[ENTRY] 신규 진입 시도: user={}, market={}, signal={}, balance={}",
                userId, market, signalStrength, accountBalance);

        // 1. 신호 강도 검증
        if (signalStrength < config.getMinSignalStrength()) {
            return EntryResult.rejected("WEAK_SIGNAL",
                    String.format("신호 강도 부족 (%d < %d)", signalStrength, config.getMinSignalStrength()));
        }

        // 2. 진입 금액 계산 (1차: 30%)
        BigDecimal positionSize = riskManager.calculatePositionSize(accountBalance, 1);

        // 3. 리스크 검증
        RiskCheckResult riskCheck = riskManager.canEnterPosition(userId, market, accountBalance, positionSize);
        if (!riskCheck.isAllowed()) {
            log.warn("[ENTRY] 리스크 검증 실패: code={}, reason={}",
                    riskCheck.getCode(), riskCheck.getReason());
            return EntryResult.rejected(riskCheck.getCode(), riskCheck.getReason());
        }

        // 4. 진입 가격 계산 (현재가 기준, 약간의 프리미엄)
        BigDecimal entryPrice = calculateEntryPrice(marketData, 1);

        // 5. 수량 계산
        BigDecimal quantity = positionSize.divide(entryPrice, 8, RoundingMode.DOWN);

        // 6. 손절가 계산 (ATR 기반)
        BigDecimal stopLoss = riskManager.calculateStopLossPrice(entryPrice, marketData.getAtr(), true);

        // 7. 목표가 계산 (1차 익절)
        BigDecimal targetPrice = calculateTargetPrice(entryPrice, marketData.getAtr());

        // 8. 포지션 생성
        Position position = Position.builder()
                .userId(userId)
                .market(market)
                .strategyName(config.getDefaultStrategyName())
                .signalStrength(signalStrength)
                .stopLossPrice(stopLoss)
                .targetPrice(targetPrice)
                .atrAtEntry(marketData.getAtr())
                .note(String.format("1차 진입 - 신호강도: %d", signalStrength))
                .build();

        position = positionRepository.save(position);

        // 9. 주문 실행
        ExecutionResult execResult = executionService.executeWithRetry(
                market, position.getId(), OrderSide.BUY, entryPrice, quantity,
                ExecutionType.ENTRY_1, marketData);

        if (execResult.isSuccess() || execResult.isPartial()) {
            // 10. 포지션 업데이트
            BigDecimal slippage = calculateSlippageCost(entryPrice, execResult.getExecutedPrice(),
                    execResult.getExecutedQuantity());

            position.addEntry(execResult.getExecutedPrice(), execResult.getExecutedQuantity(),
                    execResult.getFee(), slippage);
            positionRepository.save(position);

            log.info("[ENTRY] 1차 진입 완료: position={}, market={}, price={}, qty={}, invested={}",
                    position.getId(), market, execResult.getExecutedPrice(),
                    execResult.getExecutedQuantity(), position.getTotalInvested());

            return EntryResult.success(position, 1, execResult);
        } else {
            // 실패 시 포지션 삭제
            positionRepository.delete(position);
            log.warn("[ENTRY] 1차 진입 실패: market={}, error={}",
                    market, execResult.getErrorMessage());
            return EntryResult.failed(execResult.getErrorMessage());
        }
    }

    /**
     * 추가 진입 (2차, 3차)
     * 가격이 일정 비율 하락했을 때 추가 매수
     */
    @Transactional
    public EntryResult addEntry(Long userId, Position position, BigDecimal accountBalance,
                                 MarketData marketData) {

        int nextPhase = position.getEntryPhase() + 1;
        log.info("[ENTRY] {}차 추가 진입 시도: position={}, market={}, phase={}",
                nextPhase, position.getId(), position.getMarket(), nextPhase);

        // 1. 추가 진입 가능 여부 확인
        if (!position.canAddEntry()) {
            return EntryResult.rejected("MAX_ENTRIES", "최대 진입 횟수 초과 (3회)");
        }

        // 2. 추가 진입 조건 확인 (하락률)
        BigDecimal currentPrice = marketData.getCurrentPrice();
        BigDecimal requiredDropRate = getRequiredDropRate(nextPhase);
        BigDecimal dropRate = position.getAvgEntryPrice().subtract(currentPrice)
                .divide(position.getAvgEntryPrice(), 6, RoundingMode.HALF_UP);

        if (dropRate.compareTo(requiredDropRate) < 0) {
            return EntryResult.rejected("INSUFFICIENT_DROP",
                    String.format("하락률 부족 (현재: %.2f%%, 필요: %.2f%%)",
                            dropRate.multiply(BigDecimal.valueOf(100)).doubleValue(),
                            requiredDropRate.multiply(BigDecimal.valueOf(100)).doubleValue()));
        }

        // 3. 진입 금액 계산
        BigDecimal positionSize = riskManager.calculatePositionSize(accountBalance, nextPhase);

        // 4. 리스크 검증
        RiskCheckResult riskCheck = riskManager.canAddEntry(userId, position, accountBalance, positionSize);
        if (!riskCheck.isAllowed()) {
            return EntryResult.rejected(riskCheck.getCode(), riskCheck.getReason());
        }

        // 5. 진입 가격 계산
        BigDecimal entryPrice = calculateEntryPrice(marketData, nextPhase);

        // 6. 수량 계산
        BigDecimal quantity = positionSize.divide(entryPrice, 8, RoundingMode.DOWN);

        // 7. 주문 실행
        ExecutionType execType = nextPhase == 2 ? ExecutionType.ENTRY_2 : ExecutionType.ENTRY_3;
        ExecutionResult execResult = executionService.executeWithRetry(
                position.getMarket(), position.getId(), OrderSide.BUY, entryPrice, quantity,
                execType, marketData);

        if (execResult.isSuccess() || execResult.isPartial()) {
            // 8. 포지션 업데이트
            BigDecimal slippage = calculateSlippageCost(entryPrice, execResult.getExecutedPrice(),
                    execResult.getExecutedQuantity());

            position.addEntry(execResult.getExecutedPrice(), execResult.getExecutedQuantity(),
                    execResult.getFee(), slippage);

            // 9. 손절가 재계산 (평균 매수가 기준)
            BigDecimal newStopLoss = riskManager.calculateStopLossPrice(
                    position.getAvgEntryPrice(), position.getAtrAtEntry(), true);
            position.setStopLossPrice(newStopLoss);

            // 10. 목표가 재계산
            BigDecimal newTarget = calculateTargetPrice(position.getAvgEntryPrice(), position.getAtrAtEntry());
            position.setTargetPrice(newTarget);

            position.setNote(position.getNote() + String.format(" / %d차 진입", nextPhase));
            positionRepository.save(position);

            log.info("[ENTRY] {}차 진입 완료: position={}, avgPrice={}, totalQty={}, totalInvested={}",
                    nextPhase, position.getId(), position.getAvgEntryPrice(),
                    position.getTotalQuantity(), position.getTotalInvested());

            return EntryResult.success(position, nextPhase, execResult);
        } else {
            log.warn("[ENTRY] {}차 진입 실패: position={}, error={}",
                    nextPhase, position.getId(), execResult.getErrorMessage());
            return EntryResult.failed(execResult.getErrorMessage());
        }
    }

    /**
     * 추가 진입 조건 확인
     * @return 추가 진입이 필요한 포지션 목록의 우선순위 점수
     */
    public int getEntryPriority(Position position, MarketData marketData) {
        if (!position.canAddEntry()) {
            return 0;
        }

        int nextPhase = position.getEntryPhase() + 1;
        BigDecimal currentPrice = marketData.getCurrentPrice();
        BigDecimal requiredDropRate = getRequiredDropRate(nextPhase);

        BigDecimal actualDropRate = position.getAvgEntryPrice().subtract(currentPrice)
                .divide(position.getAvgEntryPrice(), 6, RoundingMode.HALF_UP);

        if (actualDropRate.compareTo(requiredDropRate) >= 0) {
            // 하락률 초과분에 따라 우선순위 부여 (최대 100)
            BigDecimal excess = actualDropRate.subtract(requiredDropRate)
                    .multiply(BigDecimal.valueOf(1000));
            return Math.min(100, 50 + excess.intValue());
        }

        return 0;
    }

    /**
     * 진입 가격 계산
     * 단계별로 약간의 프리미엄 적용 (체결률 향상)
     */
    private BigDecimal calculateEntryPrice(MarketData marketData, int phase) {
        BigDecimal currentPrice = marketData.getCurrentPrice();
        BigDecimal askPrice = marketData.getAskPrice();

        // 매수 호가보다 약간 높게 (체결률 향상)
        BigDecimal premiumRate = BigDecimal.valueOf(config.getEntryPricePremiumPercent());

        // 단계가 높을수록 프리미엄 증가 (더 급하게 체결 필요)
        BigDecimal phaseMultiplier = BigDecimal.valueOf(1 + (phase - 1) * 0.5);
        BigDecimal premium = askPrice.multiply(premiumRate).multiply(phaseMultiplier);

        return askPrice.add(premium).setScale(8, RoundingMode.UP);
    }

    /**
     * 목표가 계산 (1차 익절용)
     */
    private BigDecimal calculateTargetPrice(BigDecimal entryPrice, BigDecimal atr) {
        // ATR의 일정 배수를 목표로
        BigDecimal targetDistance = atr.multiply(BigDecimal.valueOf(config.getTakeProfitAtrMultiplier()));

        // 최소 익절률 보장
        BigDecimal minTargetDistance = entryPrice.multiply(
                BigDecimal.valueOf(config.getPartialTakeProfitRate()));
        targetDistance = targetDistance.max(minTargetDistance);

        return entryPrice.add(targetDistance);
    }

    /**
     * 단계별 필요 하락률
     */
    private BigDecimal getRequiredDropRate(int phase) {
        return switch (phase) {
            case 2 -> BigDecimal.valueOf(config.getEntry2DropThreshold());
            case 3 -> BigDecimal.valueOf(config.getEntry3DropThreshold());
            default -> BigDecimal.ONE; // 불가능한 하락률
        };
    }

    /**
     * 슬리피지 비용 계산
     */
    private BigDecimal calculateSlippageCost(BigDecimal requestedPrice, BigDecimal executedPrice,
                                              BigDecimal quantity) {
        return executedPrice.subtract(requestedPrice).abs().multiply(quantity);
    }

    // ==================== DTO ====================

    @Data
    @Builder
    public static class EntryResult {
        private boolean success;
        private boolean rejected;
        private String errorCode;
        private String errorMessage;

        private Position position;
        private int entryPhase;
        private ExecutionResult executionResult;

        public static EntryResult success(Position position, int phase, ExecutionResult execResult) {
            return EntryResult.builder()
                    .success(true)
                    .position(position)
                    .entryPhase(phase)
                    .executionResult(execResult)
                    .build();
        }

        public static EntryResult rejected(String code, String reason) {
            return EntryResult.builder()
                    .rejected(true)
                    .errorCode(code)
                    .errorMessage(reason)
                    .build();
        }

        public static EntryResult failed(String reason) {
            return EntryResult.builder()
                    .errorCode("EXECUTION_FAILED")
                    .errorMessage(reason)
                    .build();
        }
    }
}