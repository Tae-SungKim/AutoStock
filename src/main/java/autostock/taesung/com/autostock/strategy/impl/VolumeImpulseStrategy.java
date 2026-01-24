package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.entity.ImpulseHourParam;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.service.ImpulseStatService;
import autostock.taesung.com.autostock.service.MarketVolumeService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volume Impulse 전략 (거래량 급등 초입 포착)
 *
 * [전략 목표]
 * - 급등 "초입"만 진입 (이미 오른 코인은 절대 진입 금지)
 * - 세력 테스트용 가짜 거래량을 조기 차단
 * - 데이터 기반 자동 파라미터 튜닝 적용
 *
 * [진입 조건] (모두 충족 시 매수 신호)
 * 1. 거래량 급증: 최근 1분 거래량 >= 직전 20분 평균 × volumeMultiplier(기본 4)
 * 2. 가격 필터: 최근 5분 가격 상승률 < 2% (이미 오른 코인 차단)
 * 3. 체결강도: 최근 30초 기준 매수 체결량/전체 >= minExecutionStrength(기본 65%)
 * 4. Z-score 증가: 현재 Z-score > 직전 Z-score && >= minZScore(기본 1.5)
 *
 * [조기 실패 필터] (진입 후 30초 이내)
 * - 아래 조건 모두 충족 시 "가짜 임펄스"로 판단하여 즉시 청산
 *   1. 가격 상승률 < +0.2%
 *   2. 현재 Z-score < 진입 시 Z-score × 0.7
 *   3. 체결강도 < 55%
 *
 * [시간대별 파라미터 자동 적용]
 * - ImpulseStatService.getHourParam()으로 현재 시간대의 최적화된 파라미터 조회
 * - 매일 새벽 자동 튜닝 스케줄러가 전날 통계 기반으로 파라미터 갱신
 *
 * [Breakout 연계]
 * - Impulse 성공 시 successCache 등록
 * - VolumeBreakoutStrategy는 15분 이내 Impulse 성공 이력 필수
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeImpulseStrategy implements TradingStrategy {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final MarketVolumeService marketVolumeService;
    private final ImpulseStatService impulseStatService;

    // ============ 고정 파라미터 ============

    /** Z-score 계산 윈도우 (최근 20분) */
    private static final int Z_WINDOW = 20;

    /** 최대 5분 가격 상승률 (이 이상이면 진입 차단) */
    private static final double MAX_PRICE_RISE_5M = 0.02;

    /** 체결강도 계산 기준 시간 (초) */
    private static final int EXECUTION_STRENGTH_SECONDS = 30;

    // ============ 조기 실패 필터 파라미터 ============

    /** 가짜 임펄스 체크 시간 (진입 후 30초 이내) */
    private static final int FAKE_IMPULSE_CHECK_SECONDS = 30;

    /** 가짜 임펄스 판정: 최소 가격 상승률 */
    private static final double FAKE_IMPULSE_MIN_RISE = 0.002;

    /** 가짜 임펄스 판정: Z-score 하락 비율 */
    private static final double FAKE_IMPULSE_Z_RATIO = 0.7;

    /** 가짜 임펄스 판정: 최소 체결강도 */
    private static final double FAKE_IMPULSE_MIN_STRENGTH = 55.0;

    /**
     * 마켓별 상태 관리
     * - Key: 마켓 코드 (예: "KRW-XRP")
     * - Value: 진입 시점의 상태 정보
     */
    private final Map<String, ImpulseState> states = new ConcurrentHashMap<>();

    @Override
    public String getStrategyName() {
        return "VolumeImpulseStrategy";
    }

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    /**
     * 매매 신호 분석 (1분 스케줄러에서 호출)
     *
     * @param market 마켓 코드
     * @param candles 캔들 데이터 (최신순)
     * @return 1: 매수, -1: 매도, 0: 대기
     */
    @Override
    public int analyze(String market, List<Candle> candles) {

        // KRW 마켓만 대상 (BTC, ETH 제외)
        if (!market.startsWith("KRW-")) return 0;
        if (market.equals("KRW-BTC") || market.equals("KRW-ETH")) return 0;
        if (candles.size() < Z_WINDOW + 5) return 0;

        int last = candles.size() - 1;
        Candle cur = candles.get(last - 1);  // 최근 완성된 캔들 (진행 중인 캔들 제외)
        double price = cur.getTradePrice().doubleValue();

        // 마켓별 상태 조회/생성
        ImpulseState state = states.computeIfAbsent(market, k -> new ImpulseState());

        // 현재 보유 상태 확인
        TradeHistory latest = tradeHistoryRepository.findLatestByMarket(market)
                .stream().findFirst().orElse(null);
        boolean holding = latest != null && latest.getTradeType() == TradeHistory.TradeType.BUY;

        if (holding) {
            // 보유 중: 청산 로직 (가짜 임펄스 필터)
            return evaluateExit(market, candles, price, state);
        }

        // 미보유: 진입 로직
        return evaluateEntry(market, candles, cur, price, state);
    }

    /**
     * 진입 신호 평가
     *
     * [시간대별 파라미터 적용]
     * - 현재 시간대(hour)의 최적화된 파라미터 자동 조회
     * - 승률 낮은 시간대: 조건 강화, 높은 시간대: 조건 완화
     *
     * @return 1: 매수 신호, 0: 대기
     */
    private int evaluateEntry(String market, List<Candle> candles, Candle cur, double price, ImpulseState state) {

        int last = candles.size() - 1;

        // ============ 시간대별 파라미터 조회 ============
        int currentHour = impulseStatService.getCurrentHour();
        ImpulseHourParam hourParam = impulseStatService.getHourParam(currentHour);

        double volumeMultiplier = hourParam.getVolumeMultiplierValue();      // 기본 4.0
        double minExecutionStrength = hourParam.getMinExecutionStrengthValue(); // 기본 65.0
        double minZScore = hourParam.getMinZScoreValue();                    // 기본 1.5

        // ============ 조건 1: 거래량 급증 ============
        double curVolume = cur.getCandleAccTradeVolume().doubleValue();
        double avgVolume20 = calculateAvgVolume(candles, last, Z_WINDOW);

        // 현재 거래량이 평균 × volumeMultiplier 미만이면 탈락
        if (curVolume < avgVolume20 * volumeMultiplier) {
            return 0;
        }

        // ============ 조건 2: 가격 필터 (이미 오른 코인 차단) ============
        double price5mAgo = candles.get(last - 5).getTradePrice().doubleValue();
        double priceRise5m = (price - price5mAgo) / price5mAgo;

        // 5분 동안 2% 이상 상승했으면 탈락 (추격 매수 방지)
        if (priceRise5m >= MAX_PRICE_RISE_5M) {
            return 0;
        }

        // ============ 조건 3: 체결강도 ============
        // 최근 30초 기준: 매수체결량 / 전체체결량 × 100
        double executionStrength = marketVolumeService.getExecutionStrength(market, EXECUTION_STRENGTH_SECONDS);

        // 매수세가 minExecutionStrength% 미만이면 탈락
        if (executionStrength < minExecutionStrength) {
            return 0;
        }

        // ============ 조건 4: Z-score 증가 ============
        double zScore = volumeZScore(candles, Z_WINDOW);
        double prevZ = volumeZScore(candles.subList(0, candles.size() - 1), Z_WINDOW);
        double dz = zScore - prevZ;

        // Z-score가 증가하지 않으면 탈락 (모멘텀 없음)
        if (zScore <= prevZ) {
            return 0;
        }

        // Z-score가 minZScore 미만이면 탈락 (신호 강도 부족)
        if (zScore < minZScore) {
            return 0;
        }

        // ============ 진입 확정: 상태 저장 ============
        state.entryTime = LocalDateTime.now();
        state.entryPrice = price;
        state.entryZScore = zScore;
        state.entryDeltaZ = dz;
        state.entryExecutionStrength = executionStrength;
        state.entryVolume = curVolume;

        // DB에 진입 통계 기록 (자동 튜닝 학습 데이터)
        impulseStatService.recordEntry(market, price, zScore, dz, executionStrength, curVolume);

        // 로그 출력 (CSV 분석 가능 포맷)
        log.info("IMPULSE_ENTRY,{},{},{},{},{},{},hour={}",
                market,
                String.format("%.4f", price),
                String.format("%.3f", zScore),
                String.format("%.3f", dz),
                String.format("%.1f", executionStrength),
                String.format("%.0f", curVolume),
                currentHour);

        return 1; // 매수 신호
    }

    /**
     * 청산 신호 평가 (조기 실패 필터)
     *
     * [가짜 임펄스 판정]
     * - 진입 후 30초 이내에 아래 조건 모두 충족 시 즉시 청산
     *   1. 가격 상승률 < +0.2% (기대한 급등 없음)
     *   2. Z-score < 진입 시 × 0.7 (거래량 급감)
     *   3. 체결강도 < 55% (매수세 약화)
     *
     * @return -1: 매도 신호 (가짜 임펄스), 0: 대기 (정상 진행)
     */
    private int evaluateExit(String market, List<Candle> candles, double price, ImpulseState state) {

        // 진입 기록이 없으면 스킵
        if (state.entryTime == null) {
            return 0;
        }

        // 진입 후 경과 시간 (초)
        long secondsSinceEntry = Duration.between(state.entryTime, LocalDateTime.now()).toSeconds();

        // 30초 초과 시 조기 실패 필터 비활성화 (정상 청산 로직으로 위임)
        if (secondsSinceEntry > FAKE_IMPULSE_CHECK_SECONDS) {
            return 0;
        }

        // ============ 가짜 임펄스 조건 체크 ============
        double priceRise = (price - state.entryPrice) / state.entryPrice;
        double executionStrength = marketVolumeService.getExecutionStrength(market, EXECUTION_STRENGTH_SECONDS);
        double currentZ = volumeZScore(candles, Z_WINDOW);
        double zThreshold = state.entryZScore * FAKE_IMPULSE_Z_RATIO;

        // 세 조건 모두 충족 시 가짜 임펄스
        boolean fakeImpulse =
                priceRise < FAKE_IMPULSE_MIN_RISE &&      // 가격 상승 미미
                currentZ < zThreshold &&                  // Z-score 급락
                executionStrength < FAKE_IMPULSE_MIN_STRENGTH;  // 체결강도 약화

        if (!fakeImpulse) {
            return 0; // 정상 진행 중
        }

        // ============ 가짜 임펄스 확정: 즉시 청산 ============
        // DB에 청산 통계 기록
        impulseStatService.recordExit(market, price, "FAKE_IMPULSE");

        log.warn("FAKE_IMPULSE_EXIT,{},{},{},{},{},{},{}s",
                market,
                String.format("%.4f", price),
                String.format("%.4f", priceRise * 100),
                String.format("%.3f", currentZ),
                String.format("%.3f", zThreshold),
                String.format("%.1f", executionStrength),
                secondsSinceEntry);

        clearState(state);
        return -1; // 매도 신호
    }

    /**
     * 성공적 익절 청산 기록 (외부 트레이딩 서비스에서 호출)
     *
     * @param market 마켓 코드
     * @param exitPrice 청산 가격
     */
    public void recordSuccessfulExit(String market, double exitPrice) {
        impulseStatService.recordExit(market, exitPrice, "SUCCESS");
        impulseStatService.markSuccess(market);  // Breakout 연계용 캐시 등록
    }

    /**
     * 손절 청산 기록 (외부 트레이딩 서비스에서 호출)
     */
    public void recordStopLossExit(String market, double exitPrice) {
        impulseStatService.recordExit(market, exitPrice, "STOP_LOSS");
    }

    /**
     * 트레일링 스탑 청산 기록 (외부 트레이딩 서비스에서 호출)
     */
    public void recordTrailingExit(String market, double exitPrice) {
        impulseStatService.recordExit(market, exitPrice, "TRAILING_STOP");
    }

    /**
     * 평균 거래량 계산
     *
     * @param candles 캔들 데이터
     * @param last 마지막 인덱스
     * @param window 윈도우 크기
     * @return 평균 거래량
     */
    private double calculateAvgVolume(List<Candle> candles, int last, int window) {
        double sum = 0;
        int count = 0;
        for (int i = last - window; i < last; i++) {
            if (i >= 0) {
                sum += candles.get(i).getCandleAccTradeVolume().doubleValue();
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /**
     * 거래량 Z-score 계산
     *
     * Z-score = (현재 거래량 - 평균) / 표준편차
     * - 양수: 평균 이상
     * - 2.0 이상: 상위 2.3% (강한 이상치)
     *
     * @param candles 캔들 데이터
     * @param window 윈도우 크기
     * @return Z-score
     */
    private double volumeZScore(List<Candle> candles, int window) {
        if (candles.size() < window + 1) return 0;

        int last = candles.size() - 1;

        // 평균 계산
        double mean = candles.subList(last - window, last).stream()
                .mapToDouble(c -> c.getCandleAccTradeVolume().doubleValue())
                .average().orElse(0);

        // 분산 계산
        double variance = candles.subList(last - window, last).stream()
                .mapToDouble(c -> Math.pow(c.getCandleAccTradeVolume().doubleValue() - mean, 2))
                .average().orElse(0);

        // 표준편차
        double std = Math.sqrt(variance);
        if (std == 0) return 0;

        // 현재 거래량
        double curVolume = candles.get(last).getCandleAccTradeVolume().doubleValue();

        return (curVolume - mean) / std;
    }

    /**
     * 상태 초기화
     */
    private void clearState(ImpulseState state) {
        state.entryTime = null;
        state.entryPrice = 0;
        state.entryZScore = 0;
        state.entryDeltaZ = 0;
        state.entryExecutionStrength = 0;
        state.entryVolume = 0;
    }

    @Override
    public void clearPosition(String market) {
        ImpulseState state = states.get(market);
        if (state != null) {
            clearState(state);
        }
    }

    /**
     * 최근 Impulse 성공 여부 조회 (Breakout 연계용)
     *
     * @param market 마켓 코드
     * @return true: 15분 이내 성공 있음, false: 없음
     */
    public boolean hasRecentSuccess(String market) {
        return impulseStatService.hasRecentSuccess(market);
    }

    /**
     * 마켓별 진입 상태 정보
     */
    private static class ImpulseState {
        /** 진입 시각 */
        LocalDateTime entryTime;
        /** 진입 가격 */
        double entryPrice;
        /** 진입 시 Z-score */
        double entryZScore;
        /** 진입 시 Z-score 변화량 */
        double entryDeltaZ;
        /** 진입 시 체결강도 */
        double entryExecutionStrength;
        /** 진입 시 거래량 */
        double entryVolume;
    }
}