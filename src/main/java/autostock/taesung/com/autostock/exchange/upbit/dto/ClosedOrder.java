package autostock.taesung.com.autostock.exchange.upbit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 업비트 체결 완료 주문 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClosedOrder {
    private String uuid;              // 주문 고유 ID
    private String side;              // bid(매수), ask(매도)
    private String ordType;           // 주문 타입 (limit, price, market)
    private BigDecimal price;         // 주문 가격
    private String state;             // 주문 상태 (done, cancel)
    private String market;            // 마켓 코드
    private String createdAt;         // 주문 생성 시간
    private BigDecimal volume;        // 주문량
    private BigDecimal remainingVolume;  // 체결 후 남은 수량
    private BigDecimal executedVolume;   // 체결된 수량
    private Integer tradesCount;      // 체결 횟수
    private BigDecimal paidFee;       // 지불한 수수료
    private BigDecimal locked;        // 묶인 금액
    private BigDecimal executedFunds; // 실제 체결된 금액 (수수료 제외)
}