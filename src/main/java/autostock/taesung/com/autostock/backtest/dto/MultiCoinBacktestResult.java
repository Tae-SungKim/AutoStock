package autostock.taesung.com.autostock.backtest.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class MultiCoinBacktestResult {
    private String strategy;                    // 사용된 전략
    private Integer totalMarkets;               // 테스트한 마켓 수
    private Double initialBalancePerMarket;     // 마켓당 초기 자본금
    private Double totalInitialBalance;         // 총 초기 자본금

    // 전체 결과
    private Double totalFinalAsset;             // 총 최종 자산
    private Double totalProfitRate;             // 총 수익률 (%)
    private Double averageProfitRate;           // 평균 수익률 (%)
    private Double averageWinRate;              // 평균 승률 (%)

    // 통계
    private Integer profitableMarkets;          // 수익 낸 마켓 수
    private Integer losingMarkets;              // 손실 낸 마켓 수
    private String bestMarket;                  // 최고 수익 마켓
    private Double bestMarketProfitRate;        // 최고 수익률
    private String worstMarket;                 // 최저 수익 마켓
    private Double worstMarketProfitRate;       // 최저 수익률

    // 개별 결과
    private List<BacktestResult> marketResults; // 각 마켓별 결과

    // 마켓별 수익률 요약
    private Map<String, Double> profitRateByMarket;

    // 통합 종료 사유 통계
    private Map<ExitReason, Integer> totalExitReasonStats;
}
