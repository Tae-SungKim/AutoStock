package autostock.taesung.com.autostock.realtrading.engine;

import autostock.taesung.com.autostock.realtrading.config.RealTradingConfig;
import autostock.taesung.com.autostock.realtrading.entity.Position;
import autostock.taesung.com.autostock.realtrading.entity.Position.PositionStatus;
import autostock.taesung.com.autostock.realtrading.repository.PositionRepository;
import autostock.taesung.com.autostock.realtrading.risk.RiskManager;
import autostock.taesung.com.autostock.realtrading.risk.RiskManager.RiskStatus;
import autostock.taesung.com.autostock.realtrading.service.ExecutionService.MarketData;
import autostock.taesung.com.autostock.realtrading.strategy.ScaledEntryStrategy;
import autostock.taesung.com.autostock.realtrading.strategy.ScaledEntryStrategy.EntryResult;
import autostock.taesung.com.autostock.realtrading.strategy.ScaledExitStrategy;
import autostock.taesung.com.autostock.realtrading.strategy.ScaledExitStrategy.ExitResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 실거래 엔진
 * - 신호 수신 및 처리
 * - 포지션 모니터링
 * - 진입/청산 전략 실행 조율
 * - 리스크 상태 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealTradingEngine {

    private final PositionRepository positionRepository;
    private final ScaledEntryStrategy entryStrategy;
    private final ScaledExitStrategy exitStrategy;
    private final RiskManager riskManager;
    private final RealTradingConfig config;

    // 마켓별 최근 시장 데이터 캐시
    private final Map<String, MarketData> marketDataCache = new ConcurrentHashMap<>();

    // 엔진 상태
    private volatile boolean running = false;
    private volatile LocalDateTime lastProcessTime;

    /**
     * 엔진 시작
     */
    public void start() {
        running = true;
        log.info("[ENGINE] 실거래 엔진 시작");
    }

    /**
     * 엔진 중지
     */
    public void stop() {
        running = false;
        log.info("[ENGINE] 실거래 엔진 중지");
    }

    /**
     * 매수 신호 처리
     * @param userId 사용자 ID
     * @param market 마켓 코드
     * @param signalStrength 신호 강도 (0~100)
     * @param accountBalance 계좌 잔고
     * @return TradingResult
     */
    public TradingResult processEntrySignal(Long userId, String market, int signalStrength,
                                             BigDecimal accountBalance) {
        if (!running) {
            return TradingResult.rejected("ENGINE_STOPPED", "거래 엔진이 중지됨");
        }

        log.info("[ENGINE] 매수 신호 수신: user={}, market={}, signal={}", userId, market, signalStrength);

        try {
            // 시장 데이터 조회
            MarketData marketData = getMarketData(market);
            if (marketData == null) {
                return TradingResult.rejected("NO_MARKET_DATA", "시장 데이터 없음");
            }

            // 기존 포지션 확인
            Optional<Position> existingPosition = positionRepository.findActivePosition(userId, market);

            if (existingPosition.isPresent()) {
                // 기존 포지션 있으면 추가 진입 시도
                Position position = existingPosition.get();
                int priority = entryStrategy.getEntryPriority(position, marketData);

                if (priority > 0) {
                    EntryResult result = entryStrategy.addEntry(userId, position, accountBalance, marketData);
                    return convertEntryResult(result);
                } else {
                    return TradingResult.rejected("NO_ENTRY_CONDITION", "추가 진입 조건 미달");
                }
            } else {
                // 신규 진입
                EntryResult result = entryStrategy.enterNewPosition(userId, market, signalStrength,
                        accountBalance, marketData);
                return convertEntryResult(result);
            }

        } catch (Exception e) {
            log.error("[ENGINE] 매수 신호 처리 오류: market={}, error={}", market, e.getMessage(), e);
            return TradingResult.error(e.getMessage());
        }
    }

    /**
     * 매도 신호 처리
     */
    public TradingResult processExitSignal(Long userId, String market, String reason) {
        if (!running) {
            return TradingResult.rejected("ENGINE_STOPPED", "거래 엔진이 중지됨");
        }

        log.info("[ENGINE] 매도 신호 수신: user={}, market={}, reason={}", userId, market, reason);

        try {
            Optional<Position> positionOpt = positionRepository.findActivePosition(userId, market);
            if (positionOpt.isEmpty()) {
                return TradingResult.rejected("NO_POSITION", "해당 마켓에 포지션 없음");
            }

            Position position = positionOpt.get();
            MarketData marketData = getMarketData(market);

            if (marketData == null) {
                return TradingResult.rejected("NO_MARKET_DATA", "시장 데이터 없음");
            }

            ExitResult result = exitStrategy.executeSignalExit(position, marketData, reason);
            return convertExitResult(result);

        } catch (Exception e) {
            log.error("[ENGINE] 매도 신호 처리 오류: market={}, error={}", market, e.getMessage(), e);
            return TradingResult.error(e.getMessage());
        }
    }

    /**
     * 수동 청산
     */
    public TradingResult manualExit(Long userId, String market) {
        log.info("[ENGINE] 수동 청산 요청: user={}, market={}", userId, market);

        try {
            Optional<Position> positionOpt = positionRepository.findActivePosition(userId, market);
            if (positionOpt.isEmpty()) {
                return TradingResult.rejected("NO_POSITION", "해당 마켓에 포지션 없음");
            }

            Position position = positionOpt.get();
            MarketData marketData = getMarketData(market);

            if (marketData == null) {
                return TradingResult.rejected("NO_MARKET_DATA", "시장 데이터 없음");
            }

            ExitResult result = exitStrategy.executeManualExit(position, marketData);
            return convertExitResult(result);

        } catch (Exception e) {
            log.error("[ENGINE] 수동 청산 오류: market={}, error={}", market, e.getMessage(), e);
            return TradingResult.error(e.getMessage());
        }
    }

    /**
     * 모든 포지션 청산 (긴급 청산)
     */
    public List<TradingResult> emergencyExitAll(Long userId) {
        log.warn("[ENGINE] 긴급 전체 청산: user={}", userId);

        List<Position> positions = positionRepository.findByUserIdAndStatusIn(
                userId, List.of(PositionStatus.PENDING, PositionStatus.ENTERING,
                        PositionStatus.ACTIVE, PositionStatus.EXITING));

        List<TradingResult> results = new ArrayList<>();
        for (Position position : positions) {
            TradingResult result = manualExit(userId, position.getMarket());
            results.add(result);
        }

        return results;
    }

    /**
     * 정기 포지션 모니터링 (1분마다)
     */
    @Scheduled(fixedRate = 60000)
    public void monitorPositions() {
        if (!running) return;

        lastProcessTime = LocalDateTime.now();

        try {
            // 모든 활성 포지션 조회 (유저별로 그룹화하지 않고 전체 처리)
            List<Position> activePositions = positionRepository.findAll().stream()
                    .filter(p -> p.getStatus() != PositionStatus.CLOSED)
                    .collect(Collectors.toList());

            for (Position position : activePositions) {
                try {
                    processPosition(position);
                } catch (Exception e) {
                    log.error("[ENGINE] 포지션 처리 오류: position={}, error={}",
                            position.getId(), e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[ENGINE] 포지션 모니터링 오류: {}", e.getMessage(), e);
        }
    }

    /**
     * 개별 포지션 처리
     */
    private void processPosition(Position position) {
        String market = position.getMarket();
        MarketData marketData = getMarketData(market);

        if (marketData == null) {
            log.warn("[ENGINE] 시장 데이터 없음: market={}", market);
            return;
        }

        // TODO: accountBalance를 실제로 조회해야 함
        BigDecimal accountBalance = BigDecimal.valueOf(10000000); // 임시 값

        // 청산 조건 체크
        ExitResult exitResult = exitStrategy.checkAndExecuteExit(position, accountBalance, marketData);

        if (exitResult.isExitTriggered()) {
            log.info("[ENGINE] 청산 실행됨: position={}, type={}, PnL={}",
                    position.getId(), exitResult.getExitType(),
                    exitResult.getPosition() != null ? exitResult.getPosition().getRealizedPnl() : "N/A");
        }
    }

    /**
     * 시장 데이터 업데이트 (외부에서 호출)
     */
    public void updateMarketData(String market, MarketData data) {
        marketDataCache.put(market, data);
    }

    /**
     * 시장 데이터 조회
     */
    public MarketData getMarketData(String market) {
        return marketDataCache.get(market);
    }

    /**
     * 사용자 리스크 상태 조회
     */
    public RiskStatus getRiskStatus(Long userId, BigDecimal accountBalance) {
        return riskManager.getRiskStatus(userId, accountBalance);
    }

    /**
     * 사용자 포지션 요약 조회
     */
    public PositionSummary getPositionSummary(Long userId) {
        List<Position> positions = positionRepository.findByUserIdAndStatusIn(
                userId, List.of(PositionStatus.PENDING, PositionStatus.ENTERING,
                        PositionStatus.ACTIVE, PositionStatus.EXITING));

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        BigDecimal totalRealizedPnl = BigDecimal.ZERO;

        List<PositionInfo> positionInfos = new ArrayList<>();

        for (Position p : positions) {
            totalInvested = totalInvested.add(p.getTotalInvested());
            totalRealizedPnl = totalRealizedPnl.add(p.getRealizedPnl());

            MarketData md = getMarketData(p.getMarket());
            BigDecimal unrealized = BigDecimal.ZERO;
            if (md != null) {
                unrealized = p.getUnrealizedPnl(md.getCurrentPrice());
                totalUnrealizedPnl = totalUnrealizedPnl.add(unrealized);
            }

            positionInfos.add(PositionInfo.builder()
                    .positionId(p.getId())
                    .market(p.getMarket())
                    .status(p.getStatus().name())
                    .entryPhase(p.getEntryPhase())
                    .avgEntryPrice(p.getAvgEntryPrice())
                    .totalQuantity(p.getTotalQuantity())
                    .totalInvested(p.getTotalInvested())
                    .unrealizedPnl(unrealized)
                    .realizedPnl(p.getRealizedPnl())
                    .currentPrice(md != null ? md.getCurrentPrice() : null)
                    .stopLossPrice(p.getStopLossPrice())
                    .targetPrice(p.getTargetPrice())
                    .trailingActive(p.getTrailingActive())
                    .trailingStopPrice(p.getTrailingStopPrice())
                    .createdAt(p.getCreatedAt())
                    .build());
        }

        return PositionSummary.builder()
                .userId(userId)
                .totalPositions(positions.size())
                .totalInvested(totalInvested)
                .totalUnrealizedPnl(totalUnrealizedPnl)
                .totalRealizedPnl(totalRealizedPnl)
                .positions(positionInfos)
                .build();
    }

    /**
     * 엔진 상태 조회
     */
    public EngineStatus getEngineStatus() {
        return EngineStatus.builder()
                .running(running)
                .lastProcessTime(lastProcessTime)
                .cachedMarkets(marketDataCache.size())
                .build();
    }

    // ==================== 유틸리티 메서드 ====================

    private TradingResult convertEntryResult(EntryResult result) {
        if (result.isSuccess()) {
            return TradingResult.success(
                    "ENTRY",
                    result.getPosition().getId(),
                    result.getPosition().getMarket(),
                    result.getExecutionResult().getExecutedPrice(),
                    result.getExecutionResult().getExecutedQuantity()
            );
        } else if (result.isRejected()) {
            return TradingResult.rejected(result.getErrorCode(), result.getErrorMessage());
        } else {
            return TradingResult.error(result.getErrorMessage());
        }
    }

    private TradingResult convertExitResult(ExitResult result) {
        if (result.isExitTriggered() && result.getExecutionResult() != null) {
            return TradingResult.success(
                    "EXIT_" + result.getExitType().name(),
                    result.getPosition().getId(),
                    result.getPosition().getMarket(),
                    result.getExecutionResult().getExecutedPrice(),
                    result.getExecutionResult().getExecutedQuantity()
            );
        } else if (result.getErrorMessage() != null) {
            return TradingResult.error(result.getErrorMessage());
        } else {
            return TradingResult.rejected("NO_EXIT", "청산 조건 미달");
        }
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class TradingResult {
        private boolean success;
        private boolean rejected;
        private String action;
        private String errorCode;
        private String errorMessage;

        private Long positionId;
        private String market;
        private BigDecimal executedPrice;
        private BigDecimal executedQuantity;

        public static TradingResult success(String action, Long positionId, String market,
                                            BigDecimal price, BigDecimal quantity) {
            return TradingResult.builder()
                    .success(true)
                    .action(action)
                    .positionId(positionId)
                    .market(market)
                    .executedPrice(price)
                    .executedQuantity(quantity)
                    .build();
        }

        public static TradingResult rejected(String code, String reason) {
            return TradingResult.builder()
                    .rejected(true)
                    .errorCode(code)
                    .errorMessage(reason)
                    .build();
        }

        public static TradingResult error(String message) {
            return TradingResult.builder()
                    .errorCode("ERROR")
                    .errorMessage(message)
                    .build();
        }
    }

    @Data
    @Builder
    public static class PositionSummary {
        private Long userId;
        private int totalPositions;
        private BigDecimal totalInvested;
        private BigDecimal totalUnrealizedPnl;
        private BigDecimal totalRealizedPnl;
        private List<PositionInfo> positions;
    }

    @Data
    @Builder
    public static class PositionInfo {
        private Long positionId;
        private String market;
        private String status;
        private int entryPhase;
        private BigDecimal avgEntryPrice;
        private BigDecimal totalQuantity;
        private BigDecimal totalInvested;
        private BigDecimal unrealizedPnl;
        private BigDecimal realizedPnl;
        private BigDecimal currentPrice;
        private BigDecimal stopLossPrice;
        private BigDecimal targetPrice;
        private Boolean trailingActive;
        private BigDecimal trailingStopPrice;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class EngineStatus {
        private boolean running;
        private LocalDateTime lastProcessTime;
        private int cachedMarkets;
    }
}