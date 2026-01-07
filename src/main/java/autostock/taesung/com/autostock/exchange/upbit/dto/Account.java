package autostock.taesung.com.autostock.exchange.upbit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    private String currency;
    private String balance;
    private String locked;
    private String avgBuyPrice;
    private boolean avgBuyPriceModified;
    private String unitCurrency;
}