package autostock.taesung.com.autostock.realtrading.strategy;

import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.ExecutionType;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.OrderSide;
import autostock.taesung.com.autostock.realtrading.entity.Position;
import autostock.taesung.com.autostock.realtrading.entity.Position.ExitPhase;
import autostock.taesung.com.autostock.realtrading.entity.Position.ExitReason;
import autostock.taesung.com.autostock.realtrading.repository.PositionRepository;
import autostock.taesung.com.autostock.realtrading.risk.RiskManager;
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

/**
 * 분할 청산 전략
 * - 1차 익절: 목표가 도달 시 50% 청산
 * - 트레일링 스탑: 나머지 50% 고점 추적
 * - 손절: ATR 기반 손절가 또는 리스크 한도 초과 시
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScaledExitStrategy {

    private final PositionRepository positionRepository;
    private final ExecutionService executionService;
    private final RiskManager riskManager;
    private final RealTradingConfig config;

    /**
     * 청산 조건 체크 및 실행
     * @return ExitResult (청산 발생 여부 및 결과)
     */
    @Transactional
    public ExitResult checkAndExecuteExit(Position position, BigDecimal accountBalance,
                                           MarketData marketData) {

        BigDecimal currentPrice = marketData.getCurrentPrice();

        // 1. 손절 체크 (최우선)
        if (shouldStopLoss(position, currentPrice, accountBalance)) {
            return executeStopLoss(position, marketData);
        }

        // 2. 트레일링 스탑 체크 (이미 활성화된 경우)
        if (position.getTrailingActive()) {
            ExitResult trailingResult = checkTrailingStop(position, currentPrice, marketData);
            if (trailingResult.isExitTriggered()) {
                return trailingResult;
            }
        }

        // 3. 1차 익절 체크 (아직 부분 청산 안 했으면)
        if (position.getExitPhase() == ExitPhase.NONE && shouldTakePartialProfit(position, currentPrice)) {
            return executePartialTakeProfit(position, marketData);
        }

        // 4. 트레일링 스탑 활성화 체크 (1차 익절 후)
        if (position.getExitPhase() == ExitPhase.PARTIAL && !position.getTrailingActive()) {
            if (shouldActivateTrailingStop(position, currentPrice)) {
                activateTrailingStop(position, currentPrice, marketData.getAtr());
            }
        }

        return ExitResult.noExit();
    }

    /**
     * 손절 조건 체크
     */
    private boolean shouldStopLoss(Position position, BigDecimal currentPrice, BigDecimal accountBalance) {
        // 1. 손절가 도달
        if (position.getStopLossPrice() != null &&
            currentPrice.compareTo(position.getStopLossPrice()) <= 0) {
            log.warn("[EXIT] 손절가 도달: position={}, price={}, stopLoss={}",
                    position.getId(), currentPrice, position.getStopLossPrice());
            return true;
        }

        // 2. 리스크 한도 초과
        if (riskManager.shouldStopLoss(position, currentPrice, accountBalance)) {
            log.warn("[EXIT] 리스크 한도 초과: position={}, PnL={}",
                    position.getId(), position.getUnrealizedPnl(currentPrice));
            return true;
        }

        return false;
    }

    /**
     * 손절 실행
     */
    @Transactional
    public ExitResult executeStopLoss(Position position, MarketData marketData) {
        log.info("[EXIT] 손절 실행: position={}, market={}, qty={}",
                position.getId(), position.getMarket(), position.getTotalQuantity());

        BigDecimal exitPrice = calculateExitPrice(marketData, true); // 손절은 급하게
        BigDecimal quantity = position.getTotalQuantity();

        ExecutionResult execResult = executionService.executeWithRetry(
                position.getMarket(), position.getId(), OrderSide.SELL, exitPrice, quantity,
                ExecutionType.STOP_LOSS, marketData);

        if (execResult.isSuccess() || execResult.isPartial()) {
            BigDecimal slippage = calculateSlippageCost(exitPrice, execResult.getExecutedPrice(),
                    execResult.getExecutedQuantity());

            position.fullExit(execResult.getExecutedPrice(), execResult.getExecutedQuantity(),
                    execResult.getFee(), slippage, ExitReason.STOP_LOSS);
            positionRepository.save(position);

            // 리스크 관리자에 거래 완료 알림
            riskManager.onTradeComplete(position.getUserId(), position);

            log.info("[EXIT] 손절 완료: position={}, PnL={}, fees={}",
                    position.getId(), position.getRealizedPnl(), position.getTotalFees());

            return ExitResult.stopLoss(position, execResult);
        }

        log.error("[EXIT] 손절 실패: position={}, error={}", position.getId(), execResult.getErrorMessage());
        return ExitResult.failed(execResult.getErrorMessage());
    }

    /**
     * 1차 익절 조건 체크
     */
    private boolean shouldTakePartialProfit(Position position, BigDecimal currentPrice) {
        // 목표가 도달
        if (position.getTargetPrice() != null &&
            currentPrice.compareTo(position.getTargetPrice()) >= 0) {
            log.info("[EXIT] 목표가 도달: position={}, price={}, target={}",
                    position.getId(), currentPrice, position.getTargetPrice());
            return true;
        }

        // 또는 수익률 기준 달성
        BigDecimal returnRate = position.getCurrentReturnRate(currentPrice);
        if (returnRate.compareTo(BigDecimal.valueOf(config.getPartialTakeProfitRate())) >= 0) {
            log.info("[EXIT] 익절 수익률 도달: position={}, return={}%",
                    position.getId(), returnRate.multiply(BigDecimal.valueOf(100)));
            return true;
        }

        return false;
    }

    /**
     * 1차 익절 실행 (50%)
     */
    @Transactional
    public ExitResult executePartialTakeProfit(Position position, MarketData marketData) {
        // 청산 수량 계산 (50%)
        BigDecimal exitRatio = BigDecimal.valueOf(config.getPartialExitRatio());
        BigDecimal quantity = position.getTotalQuantity().multiply(exitRatio)
                .setScale(8, RoundingMode.DOWN);

        log.info("[EXIT] 1차 익절 실행: position={}, market={}, qty={} ({}%)",
                position.getId(), position.getMarket(), quantity,
                exitRatio.multiply(BigDecimal.valueOf(100)));

        BigDecimal exitPrice = calculateExitPrice(marketData, false);

        ExecutionResult execResult = executionService.executeWithRetry(
                position.getMarket(), position.getId(), OrderSide.SELL, exitPrice, quantity,
                ExecutionType.EXIT_PARTIAL, marketData);

        if (execResult.isSuccess() || execResult.isPartial()) {
            BigDecimal slippage = calculateSlippageCost(exitPrice, execResult.getExecutedPrice(),
                    execResult.getExecutedQuantity());

            position.partialExit(execResult.getExecutedPrice(), execResult.getExecutedQuantity(),
                    execResult.getFee(), slippage);
            positionRepository.save(position);

            log.info("[EXIT] 1차 익절 완료: position={}, 실현PnL={}, 잔여수량={}",
                    position.getId(), position.getRealizedPnl(), position.getTotalQuantity());

            return ExitResult.partialTakeProfit(position, execResult);
        }

        log.warn("[EXIT] 1차 익절 실패: position={}, error={}", position.getId(), execResult.getErrorMessage());
        return ExitResult.failed(execResult.getErrorMessage());
    }

    /**
     * 트레일링 스탑 활성화 조건 체크
     */
    private boolean shouldActivateTrailingStop(Position position, BigDecimal currentPrice) {
        // 1차 익절 후 추가 상승 시 트레일링 활성화
        BigDecimal returnRate = position.getCurrentReturnRate(currentPrice);
        BigDecimal activationThreshold = BigDecimal.valueOf(config.getTrailingActivationThreshold());

        return returnRate.compareTo(activationThreshold) >= 0;
    }

    /**
     * 트레일링 스탑 활성화
     */
    private void activateTrailingStop(Position position, BigDecimal currentPrice, BigDecimal atr) {
        BigDecimal trailingStopPrice = riskManager.calculateTrailingStopPrice(currentPrice, atr);

        position.activateTrailingStop(currentPrice, trailingStopPrice);
        positionRepository.save(position);

        log.info("[EXIT] 트레일링 스탑 활성화: position={}, high={}, stop={}",
                position.getId(), currentPrice, trailingStopPrice);
    }

    /**
     * 트레일링 스탑 체크 및 실행
     */
    @Transactional
    public ExitResult checkTrailingStop(Position position, BigDecimal currentPrice,
                                         MarketData marketData) {

        // 고점 갱신 체크
        if (currentPrice.compareTo(position.getTrailingHighPrice()) > 0) {
            BigDecimal newStopPrice = riskManager.calculateTrailingStopPrice(
                    currentPrice, position.getAtrAtEntry());
            position.updateTrailingHigh(currentPrice, newStopPrice);
            positionRepository.save(position);

            log.debug("[EXIT] 트레일링 고점 갱신: position={}, newHigh={}, newStop={}",
                    position.getId(), currentPrice, newStopPrice);
        }

        // 트레일링 스탑 도달 체크
        if (currentPrice.compareTo(position.getTrailingStopPrice()) <= 0) {
            return executeTrailingStop(position, marketData);
        }

        return ExitResult.noExit();
    }

    /**
     * 트레일링 스탑 실행
     */
    @Transactional
    public ExitResult executeTrailingStop(Position position, MarketData marketData) {
        log.info("[EXIT] 트레일링 스탑 실행: position={}, high={}, stop={}, current={}",
                position.getId(), position.getTrailingHighPrice(),
                position.getTrailingStopPrice(), marketData.getCurrentPrice());

        BigDecimal quantity = position.getTotalQuantity();
        BigDecimal exitPrice = calculateExitPrice(marketData, true); // 트레일링은 급하게

        ExecutionResult execResult = executionService.executeWithRetry(
                position.getMarket(), position.getId(), OrderSide.SELL, exitPrice, quantity,
                ExecutionType.TRAILING, marketData);

        if (execResult.isSuccess() || execResult.isPartial()) {
            BigDecimal slippage = calculateSlippageCost(exitPrice, execResult.getExecutedPrice(),
                    execResult.getExecutedQuantity());

            position.fullExit(execResult.getExecutedPrice(), execResult.getExecutedQuantity(),
                    execResult.getFee(), slippage, ExitReason.TRAILING_STOP);
            positionRepository.save(position);

            // 리스크 관리자에 거래 완료 알림
            riskManager.onTradeComplete(position.getUserId(), position);

            log.info("[EXIT] 트레일링 스탑 청산 완료: position={}, 총PnL={}, 고점대비={}%",
                    position.getId(), position.getRealizedPnl(),
                    calculateDropFromHigh(position, execResult.getExecutedPrice()));

            return ExitResult.trailingStop(position, execResult);
        }

        log.error("[EXIT] 트레일링 스탑 실패: position={}, error={}",
                position.getId(), execResult.getErrorMessage());
        return ExitResult.failed(execResult.getErrorMessage());
    }

    /**
     * 전략 신호에 의한 청산
     */
    @Transactional
    public ExitResult executeSignalExit(Position position, MarketData marketData, String reason) {
        log.info("[EXIT] 신호 청산 실행: position={}, reason={}", position.getId(), reason);

        BigDecimal quantity = position.getTotalQuantity();
        BigDecimal exitPrice = calculateExitPrice(marketData, false);

        ExecutionResult execResult = executionService.executeWithRetry(
                position.getMarket(), position.getId(), OrderSide.SELL, exitPrice, quantity,
                ExecutionType.EXIT_FULL, marketData);

        if (execResult.isSuccess() || execResult.isPartial()) {
            BigDecimal slippage = calculateSlippageCost(exitPrice, execResult.getExecutedPrice(),
                    execResult.getExecutedQuantity());

            position.fullExit(execResult.getExecutedPrice(), execResult.getExecutedQuantity(),
                    execResult.getFee(), slippage, ExitReason.SIGNAL_EXIT);
            position.setNote(position.getNote() + " / 청산사유: " + reason);
            positionRepository.save(position);

            riskManager.onTradeComplete(position.getUserId(), position);

            log.info("[EXIT] 신호 청산 완료: position={}, PnL={}",
                    position.getId(), position.getRealizedPnl());

            return ExitResult.signalExit(position, execResult);
        }

        return ExitResult.failed(execResult.getErrorMessage());
    }

    /**
     * 수동 청산
     */
    @Transactional
    public ExitResult executeManualExit(Position position, MarketData marketData) {
        log.info("[EXIT] 수동 청산 실행: position={}", position.getId());

        BigDecimal quantity = position.getTotalQuantity();
        BigDecimal exitPrice = calculateExitPrice(marketData, false);

        ExecutionResult execResult = executionService.executeWithRetry(
                position.getMarket(), position.getId(), OrderSide.SELL, exitPrice, quantity,
                ExecutionType.EXIT_FULL, marketData);

        if (execResult.isSuccess() || execResult.isPartial()) {
            BigDecimal slippage = calculateSlippageCost(exitPrice, execResult.getExecutedPrice(),
                    execResult.getExecutedQuantity());

            position.fullExit(execResult.getExecutedPrice(), execResult.getExecutedQuantity(),
                    execResult.getFee(), slippage, ExitReason.MANUAL);
            positionRepository.save(position);

            riskManager.onTradeComplete(position.getUserId(), position);

            return ExitResult.manualExit(position, execResult);
        }

        return ExitResult.failed(execResult.getErrorMessage());
    }

    /**
     * 청산 가격 계산
     */
    private BigDecimal calculateExitPrice(MarketData marketData, boolean urgent) {
        BigDecimal bidPrice = marketData.getBidPrice();

        if (urgent) {
            // 급한 청산: 매수 호가보다 약간 낮게 (빠른 체결)
            BigDecimal discountRate = BigDecimal.valueOf(config.getUrgentExitDiscountPercent());
            return bidPrice.multiply(BigDecimal.ONE.subtract(discountRate))
                    .setScale(8, RoundingMode.DOWN);
        } else {
            // 일반 청산: 매수 호가 근처
            BigDecimal discountRate = BigDecimal.valueOf(config.getNormalExitDiscountPercent());
            return bidPrice.multiply(BigDecimal.ONE.subtract(discountRate))
                    .setScale(8, RoundingMode.DOWN);
        }
    }

    /**
     * 슬리피지 비용 계산
     */
    private BigDecimal calculateSlippageCost(BigDecimal requestedPrice, BigDecimal executedPrice,
                                              BigDecimal quantity) {
        return requestedPrice.subtract(executedPrice).abs().multiply(quantity);
    }

    /**
     * 고점 대비 하락률 계산
     */
    private BigDecimal calculateDropFromHigh(Position position, BigDecimal exitPrice) {
        if (position.getTrailingHighPrice() == null ||
            position.getTrailingHighPrice().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return position.getTrailingHighPrice().subtract(exitPrice)
                .divide(position.getTrailingHighPrice(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    // ==================== DTO ====================

    @Data
    @Builder
    public static class ExitResult {
        private boolean exitTriggered;
        private ExitType exitType;
        private String errorCode;
        private String errorMessage;

        private Position position;
        private ExecutionResult executionResult;

        public enum ExitType {
            NONE, STOP_LOSS, PARTIAL_TAKE_PROFIT, TRAILING_STOP, SIGNAL_EXIT, MANUAL
        }

        public static ExitResult noExit() {
            return ExitResult.builder()
                    .exitTriggered(false)
                    .exitType(ExitType.NONE)
                    .build();
        }

        public static ExitResult stopLoss(Position position, ExecutionResult execResult) {
            return ExitResult.builder()
                    .exitTriggered(true)
                    .exitType(ExitType.STOP_LOSS)
                    .position(position)
                    .executionResult(execResult)
                    .build();
        }

        public static ExitResult partialTakeProfit(Position position, ExecutionResult execResult) {
            return ExitResult.builder()
                    .exitTriggered(true)
                    .exitType(ExitType.PARTIAL_TAKE_PROFIT)
                    .position(position)
                    .executionResult(execResult)
                    .build();
        }

        public static ExitResult trailingStop(Position position, ExecutionResult execResult) {
            return ExitResult.builder()
                    .exitTriggered(true)
                    .exitType(ExitType.TRAILING_STOP)
                    .position(position)
                    .executionResult(execResult)
                    .build();
        }

        public static ExitResult signalExit(Position position, ExecutionResult execResult) {
            return ExitResult.builder()
                    .exitTriggered(true)
                    .exitType(ExitType.SIGNAL_EXIT)
                    .position(position)
                    .executionResult(execResult)
                    .build();
        }

        public static ExitResult manualExit(Position position, ExecutionResult execResult) {
            return ExitResult.builder()
                    .exitTriggered(true)
                    .exitType(ExitType.MANUAL)
                    .position(position)
                    .executionResult(execResult)
                    .build();
        }

        public static ExitResult failed(String message) {
            return ExitResult.builder()
                    .exitTriggered(false)
                    .errorCode("EXECUTION_FAILED")
                    .errorMessage(message)
                    .build();
        }
    }
}