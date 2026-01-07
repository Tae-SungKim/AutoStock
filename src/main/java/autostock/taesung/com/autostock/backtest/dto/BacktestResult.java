package autostock.taesung.com.autostock.backtest.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BacktestResult {
    // 기본 정보
    private String market;              // 마켓 (예: KRW-BTC)
    private String strategy;            // 사용된 전략
    private String startDate;           // 시뮬레이션 시작일
    private String endDate;             // 시뮬레이션 종료일
    private Integer totalDays;          // 총 기간 (일)

    // 초기/최종 자산
    private Double initialBalance;      // 초기 자본금
    private Double finalBalance;        // 최종 KRW 잔고
    private Double finalCoinBalance;    // 최종 코인 잔고
    private Double finalCoinValue;      // 최종 코인 가치 (KRW)
    private Double finalTotalAsset;     // 최종 총 자산

    // 수익률
    private Double totalProfitRate;     // 총 수익률 (%)
    private Double maxProfitRate;       // 최대 수익률 (%)
    private Double maxLossRate;         // 최대 손실률 (MDD, %)
    private Double buyAndHoldRate;      // 단순 보유 수익률 (%)

    // 거래 통계
    private Integer totalTrades;        // 총 거래 횟수
    private Integer buyCount;           // 매수 횟수
    private Integer sellCount;          // 매도 횟수
    private Integer winCount;           // 수익 거래 횟수
    private Integer loseCount;          // 손실 거래 횟수
    private Double winRate;             // 승률 (%)

    // 거래 내역
    private List<TradeRecord> tradeHistory;  // 거래 기록
}