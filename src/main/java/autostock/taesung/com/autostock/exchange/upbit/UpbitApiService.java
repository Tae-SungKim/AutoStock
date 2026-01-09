package autostock.taesung.com.autostock.exchange.upbit;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service
public class UpbitApiService {

    private static final String API_URL = "https://api.upbit.com/v1";

    private String accessKey;
    private String secretKey;

    private final RestTemplate restTemplate;

    public UpbitApiService() {
        // Upbit API는 snake_case로 응답하므로 변환 설정
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        this.restTemplate = new RestTemplate();
        this.restTemplate.getMessageConverters().removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
        this.restTemplate.getMessageConverters().add(converter);
    }

    /**
     * JWT 토큰 생성 (파라미터 없음)
     */
    private String generateToken(String accessKey, String secretKey) {
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claim("access_key", accessKey)
                .claim("nonce", UUID.randomUUID().toString())
                .signWith(key)
                .compact();
    }

    /**
     * JWT 토큰 생성 (파라미터 있음)
     */
    private String generateToken(String accessKey, String secretKey, Map<String, String> params) {
        try {
            StringBuilder queryString = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (queryString.length() > 0) queryString.append("&");
                queryString.append(entry.getKey()).append("=").append(entry.getValue());
            }

            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(queryString.toString().getBytes(StandardCharsets.UTF_8));
            String queryHash = String.format("%0128x", new BigInteger(1, md.digest()));

            SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
            return Jwts.builder()
                    .claim("access_key", accessKey)
                    .claim("nonce", UUID.randomUUID().toString())
                    .claim("query_hash", queryHash)
                    .claim("query_hash_alg", "SHA512")
                    .signWith(key)
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("토큰 생성 실패", e);
        }
    }

    /**
     * 인증 헤더 생성
     */
    private HttpHeaders createAuthHeaders(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + generateToken(user.getUpbitAccessKey(), user.getUpbitSecretKey()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders createAuthHeaders(User user,
                                          Map<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + generateToken(user.getUpbitAccessKey(), user.getUpbitSecretKey(), params));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 마켓 목록 조회
     */
    public List<Market> getMarkets() {
        String url = API_URL + "/market/all?isDetails=true";
        ResponseEntity<List<Market>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Market>>() {}
        );
        return response.getBody();
    }

    /**
     * 분봉 캔들 조회
     */
    public List<Candle> getMinuteCandles(String market, int unit, int count) {
        String url = String.format("%s/candles/minutes/%d?market=%s&count=%d", API_URL, unit, market, count);
        ResponseEntity<List<Candle>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Candle>>() {}
        );
        return response.getBody();
    }

    /**
     * 현재가 조회
     */
    public List<Ticker> getTicker(String markets) {
        String url = API_URL + "/ticker?markets=" + markets;
        ResponseEntity<List<Ticker>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Ticker>>() {}
        );
        return response.getBody();
    }

    /**
     * 계좌 조회
     */
    public List<Account> getAccounts(User user) {
        String url = API_URL + "/accounts";
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(user));
        ResponseEntity<List<Account>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<List<Account>>() {}
        );
        return response.getBody();
    }

    /**
     * KRW 잔고 조회
     */
    public double getKrwBalance(User user) {
        List<Account> accounts = getAccounts(user);
        return accounts.stream()
                .filter(a -> "KRW".equals(a.getCurrency()))
                .findFirst()
                .map(a -> Double.parseDouble(a.getBalance()))
                .orElse(0.0);
    }

    /**
     * 코인 잔고 조회
     */
    public double getCoinBalance(User user, String currency) {
        List<Account> accounts = getAccounts(user);
        return accounts.stream()
                .filter(a -> currency.equals(a.getCurrency()))
                .findFirst()
                .map(a -> Double.parseDouble(a.getBalance()))
                .orElse(0.0);
    }

    /**
     * 시장가 매수
     */
    public OrderResponse buyMarketOrder(User user, String market, double price) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", "bid");
        params.put("ord_type", "price");
        params.put("price", String.valueOf((int) price));

        String url = API_URL + "/orders";
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(params, createAuthHeaders(user, params));
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                OrderResponse.class
        );
        return response.getBody();
    }

    /**
     * 시장가 매도
     */
    public OrderResponse sellMarketOrder(User user, String market, double volume) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", "ask");
        params.put("ord_type", "market");
        params.put("volume", String.valueOf(volume));

        String url = API_URL + "/orders";
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(params, createAuthHeaders(user, params));
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                OrderResponse.class
        );
        return response.getBody();
    }

    /**
     * 일봉 캔들 조회
     */
    public List<Candle> getDayCandles(String market, int count) {
        String url = String.format("%s/candles/days?market=%s&count=%d", API_URL, market, count);
        ResponseEntity<List<Candle>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Candle>>() {}
        );
        return response.getBody();
    }

    /**
     * 지정가 매수
     */
    public OrderResponse buyLimitOrder(User user, String market, double volume, double price) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", "bid");
        params.put("ord_type", "limit");
        params.put("volume", String.valueOf(volume));
        params.put("price", String.valueOf((int) price));

        String url = API_URL + "/orders";
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(params, createAuthHeaders(user, params));
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                OrderResponse.class
        );
        return response.getBody();
    }

    /**
     * 지정가 매도
     */
    public OrderResponse sellLimitOrder(User user, String market, double volume, double price) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", "ask");
        params.put("ord_type", "limit");
        params.put("volume", String.valueOf(volume));
        params.put("price", String.valueOf((int) price));

        String url = API_URL + "/orders";
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(params, createAuthHeaders(user, params));
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                OrderResponse.class
        );
        return response.getBody();
    }

    /**
     * 주문 취소
     */
    public OrderResponse cancelOrder(User user, String uuid) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("uuid", uuid);

        String url = API_URL + "/order?uuid=" + uuid;
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(user, params));
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                url,
                HttpMethod.DELETE,
                entity,
                OrderResponse.class
        );
        return response.getBody();
    }

    /**
     * 주문 조회
     */
    public OrderResponse getOrder(User user, String uuid) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("uuid", uuid);

        String url = API_URL + "/order?uuid=" + uuid;
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(user, params));
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                OrderResponse.class
        );
        return response.getBody();
    }

    /**
     * 체결 완료 주문 목록 조회
     * @param market 마켓 코드 (null이면 전체)
     * @param state 주문 상태 (done: 완료, cancel: 취소)
     * @param limit 조회 개수 (최대 100)
     */
    public List<ClosedOrder> getClosedOrders(User user, String market, String state, int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        if (market != null && !market.isEmpty()) {
            params.put("market", market);
        }
        params.put("state", state != null ? state : "done");
        params.put("limit", String.valueOf(Math.min(limit, 500)));

        StringBuilder queryString = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (queryString.length() > 0) queryString.append("&");
            queryString.append(entry.getKey()).append("=").append(entry.getValue());
        }

        String url = API_URL + "/orders/closed?" + queryString;
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(user, params));
        ResponseEntity<List<ClosedOrder>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<List<ClosedOrder>>() {}
        );
        return response.getBody();
    }

    /**
     * 전체 체결 완료 주문 조회 (최근 500건)
     */
    public List<ClosedOrder> getClosedOrders(User user) {
        return getClosedOrders(user, null, "done", 500);
    }

    /**
     * 마켓별 체결 완료 주문 조회
     */
    public List<ClosedOrder> getClosedOrdersByMarket(User user, String market) {
        return getClosedOrders(user, market, "done", 100);
    }
}