package autostock.taesung.com.autostock.strategy;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;

import java.util.List;

public interface TradingStrategy {

    /**
     * 매매 신호 분석
     * @param candles 캔들 데이터
     * @return 1: 매수, -1: 매도, 0: 관망
     */
    int analyze(List<Candle> candles);

    /**
     * 매매 신호 분석 (마켓별 상태 관리)
     * @param market 마켓 코드
     * @param candles 캔들 데이터
     * @return 1: 매수, -1: 매도, 0: 관망
     */
    default int analyze(String market, List<Candle> candles) {
        return analyze(candles);
    }

    /**
     * 전략 이름
     */
    String getStrategyName();

    /**
     * 목표 판매가 (매수 신호 발생 시)
     */
    default Double getTargetPrice() {
        return null;
    }

    /**
     * 마켓별 목표 판매가
     */
    default Double getTargetPrice(String market) {
        return getTargetPrice();
    }

    /**
     * 마켓별 손절가
     */
    default Double getStopLossPrice(String market) {
        return null;
    }

    /**
     * 마켓별 진입가
     */
    default Double getEntryPrice(String market) {
        return null;
    }

    /**
     * 포지션 청산 (매도 완료 시 호출)
     */
    default void clearPosition(String market) {
        // 기본 구현 없음
    }

    /**
     * 백테스트용 분석 (DB 대신 포지션 정보를 직접 전달)
     * - 실제 매매와 동일한 로직으로 시뮬레이션 가능
     *
     * @param market 마켓 코드
     * @param candles 캔들 데이터
     * @param position 현재 포지션 정보 (보유 여부, 매수가, 최고가 등)
     * @return 1: 매수, -1: 매도, 0: 관망
     */
    default int analyzeForBacktest(String market, List<Candle> candles, BacktestPosition position) {
        // 기본 구현: 일반 analyze 호출 (포지션 정보 무시)
        return analyze(market, candles);
    }

    /**
     * 백테스트용 목표가 반환
     */
    default Double getTargetPriceForBacktest() {
        return getTargetPrice();
    }
}
