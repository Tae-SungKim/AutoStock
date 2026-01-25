package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.ImpulsePosition;
import autostock.taesung.com.autostock.entity.ImpulsePosition.PositionStatus;
import autostock.taesung.com.autostock.repository.ImpulsePositionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Impulse 포지션 관리 서비스
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [핵심 책임]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 1. 포지션 영속화: 모든 상태 변경을 DB에 즉시 반영
 * 2. 메모리 캐시: 빠른 조회를 위한 ConcurrentHashMap 관리
 * 3. 서버 재기동 복구: OPEN 포지션 자동 복구
 * 4. 동시성 안전: ConcurrentHashMap + @Transactional
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [설계 원칙]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * - DB가 진실의 원천 (Source of Truth)
 * - 메모리 캐시는 성능 최적화용
 * - 모든 상태 변경은 DB 먼저, 캐시 나중
 * - 불일치 시 DB 우선
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [서버 재기동 복구 흐름]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 1. @PostConstruct 호출
 * 2. DB에서 status=OPEN 전체 조회
 * 3. 메모리 캐시에 복원
 * 4. 로그 출력 (복구된 포지션 수)
 * 5. 정상 운영 시작
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImpulsePositionService {

    private final ImpulsePositionRepository positionRepository;
    private final ImpulseStatService impulseStatService;

    /**
     * 메모리 캐시
     * - Key: 마켓 코드 (예: "KRW-XRP")
     * - Value: OPEN 상태의 포지션
     * - ConcurrentHashMap으로 스레드 안전성 보장
     */
    private final Map<String, ImpulsePosition> positionCache = new ConcurrentHashMap<>();

    // ==================== 상수 ====================

    /** 손절 기준 수익률 */
    private static final double STOP_LOSS_RATE = -0.01; // -1%

    /** FAKE IMPULSE 판정 시간 (초) */
    private static final int FAKE_CHECK_WINDOW_SECONDS = 90;

    /** Impulse 최대 보유 시간 (분) */
    private static final int MAX_HOLDING_MINUTES = 5;

    // ==================== 초기화 ====================

    /**
     * 서버 시작 시 OPEN 포지션 복구
     *
     * [실행 시점]
     * - Spring 컨테이너 초기화 완료 후
     * - 다른 Bean 주입 완료 후
     *
     * [복구 프로세스]
     * 1. DB에서 status=OPEN 전체 조회
     * 2. 각 포지션을 메모리 캐시에 등록
     * 3. 로그 출력
     *
     * [장기 미청산 포지션 처리]
     * - 6시간 이상 OPEN: 경고 로그 출력
     * - 운영자 수동 확인 필요
     */
    @PostConstruct
    public void initialize() {
        log.info("━━━ Impulse Position Recovery Start ━━━");

        List<ImpulsePosition> openPositions = positionRepository.findAllOpen();

        if (openPositions.isEmpty()) {
            log.info("No open positions to recover");
            return;
        }

        int recovered = 0;
        int stale = 0;
        LocalDateTime staleCutoff = LocalDateTime.now().minusHours(6);

        for (ImpulsePosition position : openPositions) {
            // 메모리 캐시에 복원
            positionCache.put(position.getMarket(), position);
            recovered++;

            // 장기 미청산 경고
            if (position.getEntryTime().isBefore(staleCutoff)) {
                stale++;
                log.warn("STALE_POSITION,{},{},{}min",
                        position.getMarket(),
                        position.getEntryTime(),
                        position.getHoldingMinutes());
            } else {
                log.info("RECOVERED,{},{},entry={},Z={}",
                        position.getMarket(),
                        position.getEntryTime(),
                        position.getEntryPrice(),
                        position.getEntryZScore());
            }
        }

        log.info("━━━ Recovery Complete: {} positions, {} stale ━━━", recovered, stale);

        // 장기 미청산 포지션이 있으면 알림
        if (stale > 0) {
            log.warn("!!! {} stale positions detected (>6h). Manual check required !!!", stale);
        }
    }

    // ==================== 포지션 조회 ====================

    /**
     * 마켓별 OPEN 포지션 조회 (캐시 우선)
     *
     * @param market 마켓 코드
     * @return OPEN 포지션 (없으면 empty)
     */
    public Optional<ImpulsePosition> getOpenPosition(String market) {
        // 1. 캐시 확인
        ImpulsePosition cached = positionCache.get(market);
        if (cached != null && cached.isOpen()) {
            return Optional.of(cached);
        }

        // 2. 캐시 미스 시 DB 조회 (정합성 보장)
        Optional<ImpulsePosition> dbPosition = positionRepository.findOpenByMarket(market);
        dbPosition.ifPresent(p -> positionCache.put(market, p));

        return dbPosition;
    }

    /**
     * 마켓별 OPEN 포지션 존재 여부
     *
     * @param market 마켓 코드
     * @return true: 이미 보유 중
     */
    public boolean hasOpenPosition(String market) {
        return getOpenPosition(market).isPresent();
    }

    /**
     * 전체 OPEN 포지션 수
     */
    public long countOpenPositions() {
        return positionCache.values().stream()
                .filter(ImpulsePosition::isOpen)
                .count();
    }

    /**
     * 전체 OPEN 포지션 목록
     */
    public List<ImpulsePosition> getAllOpenPositions() {
        return positionCache.values().stream()
                .filter(ImpulsePosition::isOpen)
                .toList();
    }

    // ==================== 포지션 생성 ====================

    /**
     * 신규 포지션 생성 (진입)
     *
     * [호출 시점]
     * - VolumeImpulseStrategy에서 진입 신호 발생 시
     *
     * [처리 순서]
     * 1. 중복 진입 체크
     * 2. 포지션 엔티티 생성
     * 3. DB 저장 (영속화 우선)
     * 4. 메모리 캐시 등록
     * 5. 통계 기록
     *
     * @param market 마켓 코드
     * @param entryPrice 진입가
     * @param quantity 수량
     * @param zScore 진입 시 Z-score
     * @param deltaZ Z-score 변화량
     * @param executionStrength 체결강도
     * @param volume 거래량
     * @return 생성된 포지션 (중복 시 empty)
     */
    @Transactional
    public Optional<ImpulsePosition> openPosition(
            String market,
            BigDecimal entryPrice,
            BigDecimal quantity,
            double zScore,
            double deltaZ,
            double executionStrength,
            double volume) {

        // 1. 중복 진입 체크
        if (hasOpenPosition(market)) {
            log.warn("DUPLICATE_ENTRY_BLOCKED,{}", market);
            return Optional.empty();
        }

        // 2. 포지션 생성
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        int hour = now.getHour();

        ImpulsePosition position = ImpulsePosition.builder()
                .market(market)
                .status(PositionStatus.OPEN)
                .entryTime(now)
                .entryPrice(entryPrice)
                .quantity(quantity)
                .totalInvested(entryPrice.multiply(quantity))
                .entryZScore(BigDecimal.valueOf(zScore))
                .entryDeltaZ(BigDecimal.valueOf(deltaZ))
                .entryExecutionStrength(BigDecimal.valueOf(executionStrength))
                .entryVolume(BigDecimal.valueOf(volume))
                .entryHour(hour)
                // 손절가: 진입가 × (1 - 1%)
                .stopLossPrice(entryPrice.multiply(BigDecimal.valueOf(1 + STOP_LOSS_RATE)))
                .highestPrice(entryPrice)
                .build();

        // 3. DB 저장 (영속화 우선)
        position = positionRepository.save(position);

        // 4. 메모리 캐시 등록
        positionCache.put(market, position);

        // 5. 통계 기록
        impulseStatService.recordEntry(market, entryPrice.doubleValue(),
                zScore, deltaZ, executionStrength, volume);

        log.info("IMPULSE_ENTRY,{},{},{},Z={},dZ={},exec={},vol={}",
                market,
                hour,
                entryPrice,
                String.format("%.2f", zScore),
                String.format("%.2f", deltaZ),
                String.format("%.1f", executionStrength),
                String.format("%.0f", volume));

        return Optional.of(position);
    }

    // ==================== 포지션 청산 ====================

    /**
     * 포지션 청산
     *
     * [호출 시점]
     * - VolumeImpulseStrategy.exit() 에서 청산 조건 충족 시
     *
     * [처리 순서]
     * 1. 포지션 조회
     * 2. 청산 정보 업데이트
     * 3. DB 저장 (영속화 우선)
     * 4. 메모리 캐시 제거
     * 5. 통계 기록
     * 6. 성공 시 Breakout 연계 캐시 등록
     *
     * @param market 마켓 코드
     * @param exitPrice 청산가
     * @param reason 청산 사유
     * @return 청산된 포지션 (없으면 empty)
     */
    @Transactional
    public Optional<ImpulsePosition> closePosition(String market, BigDecimal exitPrice, String reason) {

        // 1. 포지션 조회
        Optional<ImpulsePosition> positionOpt = getOpenPosition(market);
        if (positionOpt.isEmpty()) {
            log.warn("NO_OPEN_POSITION_TO_CLOSE,{}", market);
            return Optional.empty();
        }

        ImpulsePosition position = positionOpt.get();

        // 2. 청산 정보 업데이트
        position.close(exitPrice, reason);

        // 3. DB 저장 (영속화 우선)
        position = positionRepository.save(position);

        // 4. 메모리 캐시 제거
        positionCache.remove(market);

        // 5. 통계 기록
        impulseStatService.recordExit(market, exitPrice.doubleValue(), reason);

        // 6. 성공 시 Breakout 연계 캐시 등록
        if (position.getProfitRate() != null &&
                position.getProfitRate().doubleValue() > 0) {
            impulseStatService.markSuccess(market);
        }

        log.info("IMPULSE_EXIT,{},{},{},{},pnl={}%",
                market,
                reason,
                position.getEntryPrice(),
                exitPrice,
                position.getProfitRate() != null
                        ? String.format("%.4f", position.getProfitRate().doubleValue() * 100)
                        : "N/A");

        return Optional.of(position);
    }

    /**
     * 손절 청산
     */
    @Transactional
    public Optional<ImpulsePosition> closeWithStopLoss(String market, BigDecimal exitPrice) {
        return closePosition(market, exitPrice, "STOP_LOSS");
    }

    /**
     * FAKE IMPULSE 청산
     */
    @Transactional
    public Optional<ImpulsePosition> closeWithFakeImpulse(String market, BigDecimal exitPrice) {
        return closePosition(market, exitPrice, "FAKE_IMPULSE");
    }

    /**
     * Impulse 종료 (5분 타임아웃) 청산
     */
    @Transactional
    public Optional<ImpulsePosition> closeWithImpulseEnd(String market, BigDecimal exitPrice) {
        return closePosition(market, exitPrice, "IMPULSE_END");
    }

    /**
     * 트레일링 스탑 청산
     */
    @Transactional
    public Optional<ImpulsePosition> closeWithTrailingStop(String market, BigDecimal exitPrice) {
        return closePosition(market, exitPrice, "TRAILING_STOP");
    }

    /**
     * 익절 청산
     */
    @Transactional
    public Optional<ImpulsePosition> closeWithSuccess(String market, BigDecimal exitPrice) {
        return closePosition(market, exitPrice, "SUCCESS");
    }

    // ==================== 포지션 상태 업데이트 ====================

    /**
     * 고점 업데이트
     *
     * @param market 마켓 코드
     * @param currentPrice 현재가
     * @return 갱신 여부
     */
    @Transactional
    public boolean updateHighest(String market, BigDecimal currentPrice) {
        Optional<ImpulsePosition> positionOpt = getOpenPosition(market);
        if (positionOpt.isEmpty()) {
            return false;
        }

        ImpulsePosition position = positionOpt.get();
        boolean updated = position.updateHighest(currentPrice);

        if (updated) {
            positionRepository.save(position);
        }

        return updated;
    }

    // ==================== 청산 조건 체크 ====================

    /**
     * 손절 조건 체크
     *
     * @param market 마켓 코드
     * @param currentPrice 현재가
     * @return true: 손절 필요
     */
    public boolean shouldStopLoss(String market, BigDecimal currentPrice) {
        return getOpenPosition(market)
                .map(p -> p.isStopLossTriggered(currentPrice))
                .orElse(false);
    }

    /**
     * FAKE IMPULSE 조건 체크
     *
     * [조건] (ALL, 진입 후 90초 이내)
     * 1. 가격 상승률 < +0.2%
     * 2. Z-score < entryZScore × 0.7
     *
     * @param market 마켓 코드
     * @param currentPrice 현재가
     * @param currentZScore 현재 Z-score
     * @return true: FAKE IMPULSE 판정
     */
    public boolean isFakeImpulse(String market, BigDecimal currentPrice, double currentZScore) {
        Optional<ImpulsePosition> positionOpt = getOpenPosition(market);
        if (positionOpt.isEmpty()) {
            return false;
        }

        ImpulsePosition position = positionOpt.get();

        // 90초 이내인지 확인
        if (!position.isWithinFakeCheckWindow()) {
            return false;
        }

        // 조건 1: 가격 상승률 < +0.2%
        double profitRate = position.getCurrentProfitRate(currentPrice).doubleValue();
        if (profitRate >= 0.002) {
            return false; // 이미 +0.2% 이상 상승
        }

        // 조건 2: Z-score 급감 (진입 시 대비 70% 미만)
        double entryZ = position.getEntryZScore().doubleValue();
        if (currentZScore >= entryZ * 0.7) {
            return false; // Z-score 유지 중
        }

        return true; // FAKE IMPULSE 판정
    }

    /**
     * Impulse 종료 조건 체크 (5분 타임아웃)
     *
     * @param market 마켓 코드
     * @return true: Impulse 종료
     */
    public boolean isImpulseExpired(String market) {
        return getOpenPosition(market)
                .map(ImpulsePosition::isImpulseExpired)
                .orElse(false);
    }

    // ==================== 통계 ====================

    /**
     * 오늘의 성과 요약
     */
    public Map<String, Object> getTodayStats() {
        LocalDateTime today = LocalDateTime.now()
                .withHour(0).withMinute(0).withSecond(0).withNano(0);

        Object[] stats = positionRepository.findOverallStats(today);

        long total = stats[0] != null ? ((Number) stats[0]).longValue() : 0;
        long success = stats[1] != null ? ((Number) stats[1]).longValue() : 0;
        double avgProfitRate = stats[2] != null ? ((Number) stats[2]).doubleValue() : 0;
        double totalPnl = stats[3] != null ? ((Number) stats[3]).doubleValue() : 0;

        double winRate = total > 0 ? (double) success / total : 0;

        return Map.of(
                "total", total,
                "success", success,
                "winRate", String.format("%.1f%%", winRate * 100),
                "avgProfitRate", String.format("%.4f%%", avgProfitRate * 100),
                "totalPnl", String.format("%.0f KRW", totalPnl),
                "openPositions", countOpenPositions()
        );
    }

    /**
     * 캐시 상태 로그 출력 (디버깅용)
     */
    public void logCacheStatus() {
        log.info("━━━ Position Cache Status ━━━");
        log.info("Total cached: {}", positionCache.size());
        positionCache.forEach((market, pos) ->
                log.info("  {} | {} | entry={} | Z={} | {}min",
                        market,
                        pos.getStatus(),
                        pos.getEntryPrice(),
                        pos.getEntryZScore(),
                        pos.getHoldingMinutes()));
    }
}