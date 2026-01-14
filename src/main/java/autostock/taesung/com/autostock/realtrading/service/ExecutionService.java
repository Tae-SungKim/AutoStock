package autostock.taesung.com.autostock.realtrading.service;

import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.ExecutionStatus;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.ExecutionType;
import autostock.taesung.com.autostock.realtrading.entity.ExecutionLog.OrderSide;
import autostock.taesung.com.autostock.realtrading.repository.ExecutionLogRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 체결 관리 서비스
 * - 지정가 주문 전용 (시장가 사용 안 함)
 * - 슬리피지 필터링
 * - 주문 타임아웃 및 재시도
 * - 부분 체결 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final ExecutionLogRepository executionLogRepository;
    private final RealTradingConfig config;

    // 주문 대기 큐 (비동기 체결 추적용)
    private final ConcurrentMap<String, CompletableFuture<ExecutionResult>> pendingOrders = new ConcurrentHashMap<>();

    // 스케줄러 (타임아웃 관리)
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * 지정가 매수 주문 실행
     * @param market 마켓 코드
     * @param positionId 포지션 ID
     * @param requestedPrice 요청 가격
     * @param quantity 주문 수량
     * @param executionType 실행 유형 (ENTRY_1, ENTRY_2, ENTRY_3)
     * @param marketData 현재 시장 데이터
     * @return ExecutionResult
     */
    @Transactional
    public ExecutionResult executeBuyOrder(String market, Long positionId, BigDecimal requestedPrice,
                                           BigDecimal quantity, ExecutionType executionType,
                                           MarketData marketData) {

        // 1. 슬리피지 사전 검증
        SlippageCheck slippageCheck = checkSlippage(requestedPrice, marketData, OrderSide.BUY);
        if (!slippageCheck.isAcceptable()) {
            log.warn("[EXEC] 슬리피지 초과로 주문 거부: market={}, requested={}, market={}, slippage={}%",
                    market, requestedPrice, marketData.getCurrentPrice(), slippageCheck.getSlippagePercent());
            return ExecutionResult.rejected("SLIPPAGE_LIMIT", slippageCheck.getReason());
        }

        // 2. 체결 로그 생성
        ExecutionLog execLog = createExecutionLog(market, positionId, OrderSide.BUY, executionType,
                requestedPrice, quantity, marketData);
        executionLogRepository.save(execLog);

        // 3. 주문 실행 (지정가)
        try {
            OrderResult orderResult = placeLimitOrder(market, OrderSide.BUY, requestedPrice, quantity);

            if (orderResult.isSuccess()) {
                execLog.setOrderId(orderResult.getOrderId());

                // 4. 체결 대기 (타임아웃 포함)
                ExecutionResult result = waitForExecution(execLog, orderResult.getOrderId());

                // 5. 결과 처리
                if (result.isSuccess()) {
                    log.info("[EXEC] 매수 체결 완료: market={}, price={}, qty={}, slippage={}%",
                            market, result.getExecutedPrice(), result.getExecutedQuantity(),
                            result.getSlippagePercent());
                }

                return result;
            } else {
                execLog.markFailed(orderResult.getErrorMessage());
                executionLogRepository.save(execLog);
                return ExecutionResult.failed(orderResult.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("[EXEC] 주문 실행 오류: market={}, error={}", market, e.getMessage(), e);
            execLog.markFailed(e.getMessage());
            executionLogRepository.save(execLog);
            return ExecutionResult.failed(e.getMessage());
        }
    }

    /**
     * 지정가 매도 주문 실행
     */
    @Transactional
    public ExecutionResult executeSellOrder(String market, Long positionId, BigDecimal requestedPrice,
                                            BigDecimal quantity, ExecutionType executionType,
                                            MarketData marketData) {

        // 1. 슬리피지 사전 검증
        SlippageCheck slippageCheck = checkSlippage(requestedPrice, marketData, OrderSide.SELL);
        if (!slippageCheck.isAcceptable()) {
            log.warn("[EXEC] 슬리피지 초과로 주문 거부: market={}, requested={}, market={}, slippage={}%",
                    market, requestedPrice, marketData.getCurrentPrice(), slippageCheck.getSlippagePercent());
            return ExecutionResult.rejected("SLIPPAGE_LIMIT", slippageCheck.getReason());
        }

        // 2. 체결 로그 생성
        ExecutionLog execLog = createExecutionLog(market, positionId, OrderSide.SELL, executionType,
                requestedPrice, quantity, marketData);
        executionLogRepository.save(execLog);

        // 3. 주문 실행 (지정가)
        try {
            OrderResult orderResult = placeLimitOrder(market, OrderSide.SELL, requestedPrice, quantity);

            if (orderResult.isSuccess()) {
                execLog.setOrderId(orderResult.getOrderId());

                // 4. 체결 대기 (타임아웃 포함)
                ExecutionResult result = waitForExecution(execLog, orderResult.getOrderId());

                // 5. 결과 처리
                if (result.isSuccess()) {
                    log.info("[EXEC] 매도 체결 완료: market={}, price={}, qty={}, slippage={}%",
                            market, result.getExecutedPrice(), result.getExecutedQuantity(),
                            result.getSlippagePercent());
                }

                return result;
            } else {
                execLog.markFailed(orderResult.getErrorMessage());
                executionLogRepository.save(execLog);
                return ExecutionResult.failed(orderResult.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("[EXEC] 주문 실행 오류: market={}, error={}", market, e.getMessage(), e);
            execLog.markFailed(e.getMessage());
            executionLogRepository.save(execLog);
            return ExecutionResult.failed(e.getMessage());
        }
    }

    /**
     * 슬리피지 사전 검증
     */
    public SlippageCheck checkSlippage(BigDecimal requestedPrice, MarketData marketData, OrderSide side) {
        BigDecimal currentPrice = marketData.getCurrentPrice();
        BigDecimal slippage;
        BigDecimal slippagePercent;

        if (side == OrderSide.BUY) {
            // 매수: 요청가가 현재가보다 높으면 불리한 슬리피지
            slippage = requestedPrice.subtract(currentPrice);
            slippagePercent = slippage.divide(currentPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } else {
            // 매도: 요청가가 현재가보다 낮으면 불리한 슬리피지
            slippage = currentPrice.subtract(requestedPrice);
            slippagePercent = slippage.divide(currentPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        BigDecimal maxSlippage = BigDecimal.valueOf(config.getMaxSlippagePercent() * 100);
        boolean acceptable = slippagePercent.compareTo(maxSlippage) <= 0;

        return SlippageCheck.builder()
                .acceptable(acceptable)
                .slippage(slippage)
                .slippagePercent(slippagePercent)
                .maxAllowed(maxSlippage)
                .reason(acceptable ? null : String.format("슬리피지 %.4f%% > 허용치 %.4f%%",
                        slippagePercent.doubleValue(), maxSlippage.doubleValue()))
                .build();
    }

    /**
     * 체결 대기 (타임아웃 포함)
     */
    private ExecutionResult waitForExecution(ExecutionLog execLog, String orderId) {
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        pendingOrders.put(orderId, future);

        // 타임아웃 스케줄링
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            if (!future.isDone()) {
                log.warn("[EXEC] 주문 타임아웃: orderId={}", orderId);
                handleOrderTimeout(execLog, orderId, future);
            }
        }, config.getOrderTimeoutSeconds(), TimeUnit.SECONDS);

        try {
            // 체결 대기 (폴링 방식)
            ExecutionResult result = pollOrderStatus(execLog, orderId, future);
            timeoutTask.cancel(false);
            pendingOrders.remove(orderId);
            return result;

        } catch (Exception e) {
            timeoutTask.cancel(false);
            pendingOrders.remove(orderId);
            log.error("[EXEC] 체결 대기 오류: orderId={}, error={}", orderId, e.getMessage());
            return ExecutionResult.failed("체결 대기 중 오류: " + e.getMessage());
        }
    }

    /**
     * 주문 상태 폴링
     */
    private ExecutionResult pollOrderStatus(ExecutionLog execLog, String orderId,
                                            CompletableFuture<ExecutionResult> future) {
        int maxAttempts = config.getOrderTimeoutSeconds() / config.getOrderPollIntervalSeconds();

        for (int attempt = 0; attempt < maxAttempts && !future.isDone(); attempt++) {
            try {
                Thread.sleep(config.getOrderPollIntervalSeconds() * 1000L);

                // 주문 상태 조회 (실제 거래소 API 호출)
                OrderStatusResult status = getOrderStatus(orderId);

                if (status.isFilled()) {
                    // 전체 체결
                    execLog.markFilled(status.getExecutedPrice(), status.getExecutedQuantity(),
                            status.getFee(), status.getExecutedAt());
                    executionLogRepository.save(execLog);

                    ExecutionResult result = ExecutionResult.success(
                            status.getExecutedPrice(),
                            status.getExecutedQuantity(),
                            status.getFee(),
                            execLog.getSlippagePercent()
                    );
                    future.complete(result);
                    return result;

                } else if (status.isPartiallyFilled()) {
                    // 부분 체결 - 남은 수량 재시도 여부 결정
                    log.info("[EXEC] 부분 체결: orderId={}, filled={}/{}",
                            orderId, status.getFilledQuantity(), execLog.getRequestedQuantity());

                    // 부분 체결 처리 (설정에 따라)
                    if (config.isAcceptPartialFill()) {
                        execLog.markPartialFill(status.getExecutedPrice(),
                                status.getFilledQuantity(), status.getFee());
                        executionLogRepository.save(execLog);

                        ExecutionResult result = ExecutionResult.partial(
                                status.getExecutedPrice(),
                                status.getFilledQuantity(),
                                status.getFee(),
                                execLog.getSlippagePercent(),
                                execLog.getRequestedQuantity().subtract(status.getFilledQuantity())
                        );
                        future.complete(result);
                        return result;
                    }
                    // 전체 체결 대기 계속

                } else if (status.isCancelled()) {
                    // 취소됨
                    execLog.setStatus(ExecutionStatus.CANCELLED);
                    executionLogRepository.save(execLog);

                    ExecutionResult result = ExecutionResult.cancelled("주문이 취소됨");
                    future.complete(result);
                    return result;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 타임아웃 처리
        if (!future.isDone()) {
            return handleOrderTimeout(execLog, orderId, future);
        }

        return future.getNow(ExecutionResult.failed("알 수 없는 오류"));
    }

    /**
     * 주문 타임아웃 처리
     */
    private ExecutionResult handleOrderTimeout(ExecutionLog execLog, String orderId,
                                               CompletableFuture<ExecutionResult> future) {
        // 미체결 주문 취소 시도
        try {
            boolean cancelled = cancelOrder(orderId);
            if (cancelled) {
                execLog.setStatus(ExecutionStatus.TIMEOUT);
                execLog.setFailureReason("주문 타임아웃으로 취소됨");
                executionLogRepository.save(execLog);

                // 재시도 여부 확인
                if (execLog.getRetryCount() < config.getMaxOrderRetries()) {
                    log.info("[EXEC] 주문 재시도: orderId={}, attempt={}/{}",
                            orderId, execLog.getRetryCount() + 1, config.getMaxOrderRetries());
                    // 재시도 로직은 호출부에서 처리
                }
            }
        } catch (Exception e) {
            log.error("[EXEC] 주문 취소 실패: orderId={}, error={}", orderId, e.getMessage());
        }

        ExecutionResult result = ExecutionResult.timeout("주문 체결 대기 시간 초과");
        future.complete(result);
        return result;
    }

    /**
     * ExecutionLog 생성
     */
    private ExecutionLog createExecutionLog(String market, Long positionId, OrderSide side,
                                            ExecutionType executionType, BigDecimal requestedPrice,
                                            BigDecimal quantity, MarketData marketData) {
        return ExecutionLog.builder()
                .positionId(positionId)
                .market(market)
                .side(side)
                .executionType(executionType)
                .requestedPrice(requestedPrice)
                .requestedQuantity(quantity)
                .marketPriceAtOrder(marketData.getCurrentPrice())
                .bidPrice(marketData.getBidPrice())
                .askPrice(marketData.getAskPrice())
                .spread(marketData.getSpread())
                .tradingVolume(marketData.getTradingVolume())
                .orderedAt(LocalDateTime.now())
                .status(ExecutionStatus.PENDING)
                .isBacktest(false)
                .retryCount(0)
                .build();
    }

    /**
     * 재시도 가능한 주문 실행
     */
    @Transactional
    public ExecutionResult executeWithRetry(String market, Long positionId, OrderSide side,
                                            BigDecimal requestedPrice, BigDecimal quantity,
                                            ExecutionType executionType, MarketData marketData) {
        int maxRetries = config.getMaxOrderRetries();
        ExecutionResult lastResult = null;

        for (int retry = 0; retry <= maxRetries; retry++) {
            if (retry > 0) {
                log.info("[EXEC] 재시도 {}/{}: market={}", retry, maxRetries, market);
                // 재시도 전 가격 재조정 (현재 시장가 기준)
                requestedPrice = adjustRetryPrice(requestedPrice, marketData, side);
            }

            if (side == OrderSide.BUY) {
                lastResult = executeBuyOrder(market, positionId, requestedPrice, quantity,
                        executionType, marketData);
            } else {
                lastResult = executeSellOrder(market, positionId, requestedPrice, quantity,
                        executionType, marketData);
            }

            if (lastResult.isSuccess() || lastResult.isPartial()) {
                return lastResult;
            }

            if (!lastResult.isRetryable()) {
                break;
            }

            // 재시도 전 대기
            try {
                Thread.sleep(config.getRetryDelayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return lastResult != null ? lastResult : ExecutionResult.failed("모든 재시도 실패");
    }

    /**
     * 재시도 시 가격 조정
     */
    private BigDecimal adjustRetryPrice(BigDecimal originalPrice, MarketData marketData, OrderSide side) {
        BigDecimal currentPrice = marketData.getCurrentPrice();
        BigDecimal priceAdjustment = currentPrice.multiply(
                BigDecimal.valueOf(config.getRetryPriceAdjustmentPercent()));

        if (side == OrderSide.BUY) {
            // 매수: 가격을 약간 올려서 체결 확률 높임
            return currentPrice.add(priceAdjustment);
        } else {
            // 매도: 가격을 약간 낮춰서 체결 확률 높임
            return currentPrice.subtract(priceAdjustment);
        }
    }

    // ==================== 거래소 API 인터페이스 (실제 구현 필요) ====================

    /**
     * 지정가 주문 실행 (거래소 API)
     * TODO: 실제 업비트 API 연동
     */
    protected OrderResult placeLimitOrder(String market, OrderSide side, BigDecimal price, BigDecimal quantity) {
        // 실제 구현에서는 업비트 API 호출
        log.debug("[EXEC] 지정가 주문: market={}, side={}, price={}, qty={}",
                market, side, price, quantity);

        // 임시 구현 (실제 API 연동 시 교체)
        return OrderResult.builder()
                .success(true)
                .orderId("ORDER_" + System.currentTimeMillis())
                .build();
    }

    /**
     * 주문 상태 조회 (거래소 API)
     * TODO: 실제 업비트 API 연동
     */
    protected OrderStatusResult getOrderStatus(String orderId) {
        // 실제 구현에서는 업비트 API 호출
        log.debug("[EXEC] 주문 상태 조회: orderId={}", orderId);

        // 임시 구현
        return OrderStatusResult.builder()
                .orderId(orderId)
                .filled(true)
                .executedPrice(BigDecimal.ZERO)
                .executedQuantity(BigDecimal.ZERO)
                .fee(BigDecimal.ZERO)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 주문 취소 (거래소 API)
     * TODO: 실제 업비트 API 연동
     */
    protected boolean cancelOrder(String orderId) {
        // 실제 구현에서는 업비트 API 호출
        log.debug("[EXEC] 주문 취소: orderId={}", orderId);
        return true;
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class ExecutionResult {
        private boolean success;
        private boolean partial;
        private boolean timeout;
        private boolean rejected;
        private boolean retryable;

        private BigDecimal executedPrice;
        private BigDecimal executedQuantity;
        private BigDecimal fee;
        private BigDecimal slippagePercent;
        private BigDecimal remainingQuantity;

        private String errorCode;
        private String errorMessage;

        public static ExecutionResult success(BigDecimal price, BigDecimal quantity,
                                              BigDecimal fee, BigDecimal slippage) {
            return ExecutionResult.builder()
                    .success(true)
                    .executedPrice(price)
                    .executedQuantity(quantity)
                    .fee(fee)
                    .slippagePercent(slippage)
                    .build();
        }

        public static ExecutionResult partial(BigDecimal price, BigDecimal filledQty,
                                              BigDecimal fee, BigDecimal slippage, BigDecimal remaining) {
            return ExecutionResult.builder()
                    .partial(true)
                    .success(true)
                    .executedPrice(price)
                    .executedQuantity(filledQty)
                    .fee(fee)
                    .slippagePercent(slippage)
                    .remainingQuantity(remaining)
                    .build();
        }

        public static ExecutionResult timeout(String message) {
            return ExecutionResult.builder()
                    .timeout(true)
                    .retryable(true)
                    .errorCode("TIMEOUT")
                    .errorMessage(message)
                    .build();
        }

        public static ExecutionResult rejected(String code, String reason) {
            return ExecutionResult.builder()
                    .rejected(true)
                    .retryable(false)
                    .errorCode(code)
                    .errorMessage(reason)
                    .build();
        }

        public static ExecutionResult cancelled(String reason) {
            return ExecutionResult.builder()
                    .errorCode("CANCELLED")
                    .errorMessage(reason)
                    .build();
        }

        public static ExecutionResult failed(String message) {
            return ExecutionResult.builder()
                    .errorCode("FAILED")
                    .errorMessage(message)
                    .retryable(true)
                    .build();
        }
    }

    @Data
    @Builder
    public static class SlippageCheck {
        private boolean acceptable;
        private BigDecimal slippage;
        private BigDecimal slippagePercent;
        private BigDecimal maxAllowed;
        private String reason;
    }

    @Data
    @Builder
    public static class OrderResult {
        private boolean success;
        private String orderId;
        private String errorMessage;
    }

    @Data
    @Builder
    public static class OrderStatusResult {
        private String orderId;
        private boolean filled;
        private boolean partiallyFilled;
        private boolean cancelled;
        private BigDecimal executedPrice;
        private BigDecimal executedQuantity;
        private BigDecimal filledQuantity;
        private BigDecimal fee;
        private LocalDateTime executedAt;
    }

    @Data
    @Builder
    public static class MarketData {
        private BigDecimal currentPrice;
        private BigDecimal bidPrice;
        private BigDecimal askPrice;
        private BigDecimal spread;
        private BigDecimal tradingVolume;
        private BigDecimal atr;
    }
}