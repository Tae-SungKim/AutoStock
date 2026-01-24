package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.ImpulseHourParam;
import autostock.taesung.com.autostock.entity.ImpulseTradeStat;
import autostock.taesung.com.autostock.repository.ImpulseHourParamRepository;
import autostock.taesung.com.autostock.repository.ImpulseTradeStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Impulse 전략 통계 및 캐시 관리 서비스
 *
 * [핵심 역할]
 * 1. 거래 통계 기록: 진입/청산 시점의 데이터를 DB에 저장
 * 2. 성공 캐시 관리: Breakout 연계를 위한 최근 성공 이력 캐싱
 * 3. 파라미터 조회: 시간대별 최적화된 진입 조건 제공
 *
 * [데이터 흐름]
 * VolumeImpulseStrategy.evaluateEntry() → recordEntry() → DB 저장
 * VolumeImpulseStrategy.evaluateExit()  → recordExit()  → DB 업데이트 + 캐시 갱신
 * VolumeBreakoutStrategy.entry()        → hasRecentSuccess() → 캐시 확인
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImpulseStatService {

    private final ImpulseTradeStatRepository statRepository;
    private final ImpulseHourParamRepository hourParamRepository;

    /**
     * 성공 캐시 유효 시간 (분)
     * - Impulse 성공 후 15분 이내에만 Breakout 연계 허용
     */
    private static final int SUCCESS_CACHE_MINUTES = 15;

    /**
     * 성공 캐시
     * - Key: 마켓 코드 (예: "KRW-XRP")
     * - Value: 마지막 성공 시각
     * - ConcurrentHashMap으로 스레드 안전성 보장
     */
    private final Map<String, LocalDateTime> successCache = new ConcurrentHashMap<>();

    /**
     * Impulse 진입 기록
     *
     * [호출 시점]
     * - VolumeImpulseStrategy.evaluateEntry()에서 매수 신호 발생 시
     *
     * [저장 데이터]
     * - 마켓, 진입가, Z-score, Delta Z, 체결강도, 거래량
     * - exitTime은 NULL (미청산 상태)
     *
     * @param market 마켓 코드
     * @param entryPrice 진입 가격
     * @param zScore 진입 시 거래량 Z-score
     * @param deltaZ Z-score 변화량 (현재 - 직전)
     * @param executionStrength 진입 시 체결강도 (%)
     * @param volume 진입 시 거래량
     * @return 저장된 ImpulseTradeStat 엔티티
     */
    @Transactional
    public ImpulseTradeStat recordEntry(String market, double entryPrice,
                                         double zScore, double deltaZ,
                                         double executionStrength, double volume) {

        ImpulseTradeStat stat = ImpulseTradeStat.builder()
                .market(market)
                .entryTime(LocalDateTime.now())
                .entryPrice(BigDecimal.valueOf(entryPrice))
                .entryZScore(BigDecimal.valueOf(zScore))
                .entryDeltaZ(BigDecimal.valueOf(deltaZ))
                .entryExecutionStrength(BigDecimal.valueOf(executionStrength))
                .entryVolume(BigDecimal.valueOf(volume))
                .entryHour(LocalDateTime.now().getHour())
                .build();

        return statRepository.save(stat);
    }

    /**
     * Impulse 청산 기록
     *
     * [호출 시점]
     * - VolumeImpulseStrategy.evaluateExit()에서 매도 신호 발생 시
     * - 외부 트레이딩 서비스에서 익절/손절 실행 후
     *
     * [업데이트 데이터]
     * - exitTime: 청산 시각
     * - exitPrice: 청산 가격
     * - profitRate: 수익률 계산 ((exitPrice - entryPrice) / entryPrice)
     * - success: 수익 여부 (profitRate > 0)
     * - exitReason: 청산 사유
     *
     * [성공 시 추가 동작]
     * - successCache에 마켓 등록 → Breakout 연계 허용
     *
     * @param market 마켓 코드
     * @param exitPrice 청산 가격
     * @param exitReason 청산 사유 (SUCCESS, STOP_LOSS, TRAILING_STOP, FAKE_IMPULSE)
     */
    @Transactional
    public void recordExit(String market, double exitPrice, String exitReason) {

        // 미청산 포지션 조회
        Optional<ImpulseTradeStat> openStat = statRepository.findByMarketAndExitTimeIsNull(market);

        if (openStat.isEmpty()) {
            log.warn("[{}] No open impulse stat found for exit", market);
            return;
        }

        ImpulseTradeStat stat = openStat.get();
        stat.setExitTime(LocalDateTime.now());
        stat.setExitPrice(BigDecimal.valueOf(exitPrice));
        stat.setExitReason(exitReason);

        // 수익률 계산
        double entryPrice = stat.getEntryPrice().doubleValue();
        double profitRate = (exitPrice - entryPrice) / entryPrice;

        stat.setProfitRate(BigDecimal.valueOf(profitRate));
        stat.setSuccess(profitRate > 0);

        statRepository.save(stat);

        // 성공 시 캐시에 등록 (Breakout 연계용)
        if (stat.getSuccess()) {
            successCache.put(market, LocalDateTime.now());
            log.info("IMPULSE_SUCCESS,{},{},{}%,{}",
                    market,
                    exitReason,
                    String.format("%.4f", profitRate * 100),
                    stat.getEntryHour());
        } else {
            log.info("IMPULSE_FAIL,{},{},{}%,{}",
                    market,
                    exitReason,
                    String.format("%.4f", profitRate * 100),
                    stat.getEntryHour());
        }
    }

    /**
     * 최근 Impulse 성공 여부 확인 (Breakout 연계용)
     *
     * [목적]
     * - Breakout 전략 진입 전 필수 체크
     * - 최근 15분 내 Impulse 성공 이력이 있어야 Breakout 진입 허용
     *
     * [캐시 정책]
     * - 15분 초과된 캐시는 자동 제거
     * - DB 조회 없이 메모리 캐시만 확인 (성능 최적화)
     *
     * @param market 마켓 코드
     * @return true: 최근 성공 있음 (Breakout 진입 허용), false: 성공 없음 (진입 차단)
     */
    public boolean hasRecentSuccess(String market) {
        LocalDateTime cachedTime = successCache.get(market);
        if (cachedTime == null) {
            return false;
        }

        // 15분 이내인지 확인
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(SUCCESS_CACHE_MINUTES);
        if (cachedTime.isBefore(cutoff)) {
            // 만료된 캐시 제거
            successCache.remove(market);
            return false;
        }

        return true;
    }

    /**
     * 수동으로 성공 캐시 등록
     *
     * [사용 사례]
     * - 외부 트레이딩 서비스에서 익절 성공 시 호출
     * - recordExit() 외부에서 성공 상태 갱신 필요 시
     *
     * @param market 마켓 코드
     */
    public void markSuccess(String market) {
        successCache.put(market, LocalDateTime.now());
    }

    /**
     * 성공 캐시 제거
     *
     * [사용 사례]
     * - 포지션 완전 청산 시
     * - 전략 리셋 시
     *
     * @param market 마켓 코드
     */
    public void clearSuccessCache(String market) {
        successCache.remove(market);
    }

    /**
     * 시간대별 파라미터 조회
     *
     * [동작]
     * 1. DB에서 해당 시간대의 활성화된 파라미터 조회
     * 2. 없으면 기본 파라미터 반환
     *
     * [기본 파라미터]
     * - minExecutionStrength: 65.0%
     * - minZScore: 1.5
     * - volumeMultiplier: 4.0
     *
     * @param hour 시간대 (0~23)
     * @return 해당 시간대의 파라미터
     */
    public ImpulseHourParam getHourParam(int hour) {
        return hourParamRepository.findByHourAndEnabledTrue(hour)
                .orElse(getDefaultParam(hour));
    }

    /**
     * 기본 파라미터 생성
     *
     * @param hour 시간대
     * @return 기본값이 설정된 ImpulseHourParam
     */
    private ImpulseHourParam getDefaultParam(int hour) {
        return ImpulseHourParam.builder()
                .hour(hour)
                .minExecutionStrength(BigDecimal.valueOf(65.0))
                .minZScore(BigDecimal.valueOf(1.5))
                .volumeMultiplier(BigDecimal.valueOf(4.0))
                .enabled(true)
                .build();
    }

    /**
     * 현재 시간대 조회 (한국 시간 기준)
     *
     * @return 현재 시간 (0~23)
     */
    public int getCurrentHour() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul")).getHour();
    }
}