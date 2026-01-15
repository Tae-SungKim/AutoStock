package autostock.taesung.com.autostock.exchange.upbit;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;
import java.util.List;

/**
 * Upbit 호가창 조회 서비스 (GCP 저사양용, 캐싱 없음)
 */
@Service
@Slf4j
public class UpbitOrderbookService {

    private static final String API_URL = "https://api.upbit.com/v1/orderbook?markets=";
    private final RestTemplate restTemplate;

    public UpbitOrderbookService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(3))
                .build();
    }

    public Orderbook getOrderbook(String market) {
        try {
            Orderbook[] result = restTemplate.getForObject(API_URL + market, Orderbook[].class);
            if (result == null || result.length == 0) {
                log.warn("[{}] 호가창 데이터 없음", market);
                return null;
            }
            return result[0];
        } catch (Exception e) {
            log.error("[{}] 호가창 조회 실패: {}", market, e.getMessage());
            return null;
        }
    }

    public double getSpread(String market) {
        Orderbook ob = getOrderbook(market);
        if (ob == null || ob.orderbookUnits == null || ob.orderbookUnits.isEmpty()) return 0.01;
        double ask = ob.getAskPrice(0), bid = ob.getBidPrice(0);
        return bid > 0 ? (ask - bid) / bid : 0.01;
    }

    public double getImbalance(String market) {
        Orderbook ob = getOrderbook(market);
        if (ob == null) return 0.5;
        double total = ob.totalBidSize + ob.totalAskSize;
        return total > 0 ? ob.totalBidSize / total : 0.5;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Orderbook {
        private String market;
        @JsonProperty("total_ask_size") private double totalAskSize;
        @JsonProperty("total_bid_size") private double totalBidSize;
        @JsonProperty("orderbook_units") private List<OrderbookUnit> orderbookUnits;

        public double getAskPrice(int i) { return orderbookUnits.get(i).askPrice; }
        public double getBidPrice(int i) { return orderbookUnits.get(i).bidPrice; }
        public double getAskSize(int i) { return orderbookUnits.get(i).askSize; }
        public double getBidSize(int i) { return orderbookUnits.get(i).bidSize; }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class OrderbookUnit {
        @JsonProperty("ask_price") private double askPrice;
        @JsonProperty("bid_price") private double bidPrice;
        @JsonProperty("ask_size") private double askSize;
        @JsonProperty("bid_size") private double bidSize;
    }
}