package autostock.taesung.com.autostock.exchange.upbit;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 사용자별 Upbit API 서비스
 * 각 사용자의 API 키를 사용하여 API 호출
 */
@Slf4j
@Service
public class UserUpbitApiService {

    private static final String API_URL = "https://api.upbit.com/v1";
    private final RestTemplate restTemplate;

    public UserUpbitApiService() {
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
    private String generateToken(String accessKey, String secretKeyStr) {
        SecretKey key = Keys.hmacShaKeyFor(secretKeyStr.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claim("access_key", accessKey)
                .claim("nonce", UUID.randomUUID().toString())
                .signWith(key)
                .compact();
    }

    /**
     * JWT 토큰 생성 (파라미터 있음)
     */
    private String generateToken(String accessKey, String secretKeyStr, Map<String, String> params) {
        try {
            StringBuilder queryString = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (queryString.length() > 0) queryString.append("&");
                queryString.append(entry.getKey()).append("=").append(entry.getValue());
            }

            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(queryString.toString().getBytes(StandardCharsets.UTF_8));
            String queryHash = String.format("%0128x", new BigInteger(1, md.digest()));

            SecretKey key = Keys.hmacShaKeyFor(secretKeyStr.getBytes(StandardCharsets.UTF_8));
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

    private HttpHeaders createAuthHeaders(User user, Map<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + generateToken(user.getUpbitAccessKey(), user.getUpbitSecretKey(), params));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 마켓 목록 조회 (인증 불필요)
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
     * 분봉 캔들 조회 (인증 불필요)
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
     * 현재가 조회 (인증 불필요)
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
     * 계좌 조회 (사용자별)
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
     * KRW 잔고 조회 (사용자별)
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
     * 코인 잔고 조회 (사용자별)
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
     * 시장가 매수 (사용자별)
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
     * 시장가 매도 (사용자별)
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
}