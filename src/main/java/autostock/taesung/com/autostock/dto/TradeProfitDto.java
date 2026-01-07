package autostock.taesung.com.autostock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 매매 손익 DTO
 * 동일 코인의 매수/매도를 매칭하여 손익을 계산
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeProfitDto {

    // 마켓 정보
    private String market;

    // 매수 정보
    private LocalDate buyDate;
    private LocalTime buyTime;
    private BigDecimal buyPrice;        // 매수 단가
    private BigDecimal buyAmount;       // 매수 금액
    private BigDecimal buyVolume;       // 매수 수량
    private BigDecimal buyFee;          // 매수 수수료
    private String buyOrderUuid;
    private BigDecimal targetPrice;     // 목표 판매가 (볼린저밴드 상단 등)

    // 매도 정보
    private LocalDate sellDate;
    private LocalTime sellTime;
    private BigDecimal sellPrice;       // 매도 단가
    private BigDecimal sellAmount;      // 매도 금액
    private BigDecimal sellVolume;      // 매도 수량
    private BigDecimal sellFee;         // 매도 수수료
    private String sellOrderUuid;

    // 손익 정보
    private BigDecimal totalFee;        // 총 수수료 (매수 + 매도)
    private BigDecimal grossProfit;     // 총 이익 (매도금액 - 매수금액)
    private BigDecimal netProfit;       // 순이익 (총이익 - 수수료)
    private BigDecimal profitRate;      // 수익률 (%)

    // 보유 기간 (일)
    private Long holdingDays;

    // 사용된 전략
    private String buyStrategy;
    private String sellStrategy;

    // 상태 (MATCHED: 매칭완료, HOLDING: 보유중-미매도)
    private String status;
}
