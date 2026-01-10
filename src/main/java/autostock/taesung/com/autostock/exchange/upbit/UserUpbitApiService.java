package autostock.taesung.com.autostock.exchange.upbit;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.dto.*;
import autostock.taesung.com.autostock.service.ApiKeyService;
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
 * API 키는 암호화되어 저장되며, 사용 시 복호화됨
 */
@Slf4j
@Service
public class UserUpbitApiService {

    private static final String API_URL = "https://api.upbit.com/v1";
    private static final int ORDER_CHECK_MAX_RETRY = 10;  // 체결 확인 최대 재시도
    private static final long ORDER_CHECK_INTERVAL_MS = 500;  // 체결 확인 간격 (ms)

    private final RestTemplate restTemplate;
    private final ApiKeyService apiKeyService;

    public UserUpbitApiService(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;

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
     * 복호화된 API 키 조회
     */
    private String getAccessKey(User user) {
        return apiKeyService.getDecryptedAccessKey(user);
    }

    private String getSecretKey(User user) {
        return apiKeyService.getDecryptedSecretKey(user);
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
     * 인증 헤더 생성 (복호화된 API 키 사용)
     */
    private HttpHeaders createAuthHeaders(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + generateToken(getAccessKey(user), getSecretKey(user)));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders createAuthHeaders(User user, Map<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + generateToken(getAccessKey(user), getSecretKey(user), params));
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

    // ==================== 주문 체결 확인 로직 ====================

    /**
     * 주문 상태 조회
     * @param uuid 주문 UUID
     * @return 주문 정보
     */
    public OrderResponse getOrder(User user, String uuid) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("uuid", uuid);

        String url = API_URL + "/order?uuid=" + uuid;
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(user, params));

        try {
            ResponseEntity<OrderResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    OrderResponse.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("주문 조회 실패 [{}]: {}", uuid, e.getMessage());
            return null;
        }
    }

    /**
     * 주문 체결 여부 확인
     * @return true: 전량 체결, false: 미체결/부분체결
     */
    public boolean isOrderComplete(User user, String uuid) {
        OrderResponse order = getOrder(user, uuid);
        if (order == null) {
            return false;
        }
        // state: wait(대기), watch(예약주문대기), done(완료), cancel(취소)
        return "done".equals(order.getState());
    }

    /**
     * 주문 체결 대기 (동기 방식)
     * 최대 ORDER_CHECK_MAX_RETRY번 체결 확인 후 결과 반환
     *
     * @param uuid 주문 UUID
     * @return 체결 완료된 주문 정보 (미체결 시 마지막 상태 반환)
     */
    public OrderResponse waitForOrderComplete(User user, String uuid) {
        OrderResponse lastOrder = null;

        for (int i = 0; i < ORDER_CHECK_MAX_RETRY; i++) {
            try {
                Thread.sleep(ORDER_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            lastOrder = getOrder(user, uuid);
            if (lastOrder == null) {
                continue;
            }

            String state = lastOrder.getState();
            log.debug("[체결확인 {}/{}] UUID: {}, 상태: {}", i + 1, ORDER_CHECK_MAX_RETRY, uuid, state);

            if ("done".equals(state)) {
                log.info("[체결완료] UUID: {}, 체결량: {}, 체결금액: {}",
                        uuid, lastOrder.getExecutedVolume(), lastOrder.getExecutedFunds());
                return lastOrder;
            } else if ("cancel".equals(state)) {
                log.warn("[주문취소됨] UUID: {}", uuid);
                return lastOrder;
            }
        }

        // 최대 재시도 후에도 미체결
        if (lastOrder != null) {
            log.warn("[미체결] UUID: {}, 상태: {}, 체결량: {}/{}",
                    uuid, lastOrder.getState(),
                    lastOrder.getExecutedVolume(), lastOrder.getVolume());
        }
        return lastOrder;
    }

    /**
     * 시장가 매수 + 체결 확인
     */
    public OrderResponse buyMarketOrderWithConfirm(User user, String market, double price) {
        OrderResponse order = buyMarketOrder(user, market, price);
        if (order != null && order.getUuid() != null) {
            return waitForOrderComplete(user, order.getUuid());
        }
        return order;
    }

    /**
     * 시장가 매도 + 체결 확인
     */
    public OrderResponse sellMarketOrderWithConfirm(User user, String market, double volume) {
        OrderResponse order = sellMarketOrder(user, market, volume);
        if (order != null && order.getUuid() != null) {
            return waitForOrderComplete(user, order.getUuid());
        }
        return order;
    }

    /**
     * 주문 취소
     */
    public OrderResponse cancelOrder(User user, String uuid) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("uuid", uuid);

        String url = API_URL + "/order?uuid=" + uuid;
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(user, params));

        try {
            ResponseEntity<OrderResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.DELETE,
                    entity,
                    OrderResponse.class
            );
            log.info("[주문취소] UUID: {}", uuid);
            return response.getBody();
        } catch (Exception e) {
            log.error("주문 취소 실패 [{}]: {}", uuid, e.getMessage());
            return null;
        }
    }

    /**
     * 미체결 주문 목록 조회
     * @param market 마켓 (null이면 전체)
     */
    public List<OrderResponse> getOpenOrders(User user, String market) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("state", "wait");
        if (market != null && !market.isEmpty()) {
            params.put("market", market);
        }

        StringBuilder queryString = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (queryString.length() > 0) queryString.append("&");
            queryString.append(entry.getKey()).append("=").append(entry.getValue());
        }

        String url = API_URL + "/orders?" + queryString;
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(user, params));

        try {
            ResponseEntity<List<OrderResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<List<OrderResponse>>() {}
            );
            return response.getBody() != null ? response.getBody() : new ArrayList<>();
        } catch (Exception e) {
            log.error("미체결 주문 조회 실패: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 특정 마켓의 미체결 주문 모두 취소
     */
    public int cancelAllOpenOrders(User user, String market) {
        List<OrderResponse> openOrders = getOpenOrders(user, market);
        int cancelCount = 0;

        for (OrderResponse order : openOrders) {
            OrderResponse cancelled = cancelOrder(user, order.getUuid());
            if (cancelled != null) {
                cancelCount++;
            }
            // API 속도 제한 방지
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("[미체결 주문 취소] 마켓: {}, 취소 건수: {}", market, cancelCount);
        return cancelCount;
    }

    /**
     * 모든 미체결 주문 취소
     */
    public int cancelAllOpenOrders(User user) {
        return cancelAllOpenOrders(user, null);
    }
}