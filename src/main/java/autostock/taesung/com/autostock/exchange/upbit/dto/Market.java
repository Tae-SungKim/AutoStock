package autostock.taesung.com.autostock.exchange.upbit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Market {
    private String market;
    private String koreanName;
    private String englishName;
    private String marketWarning;
}