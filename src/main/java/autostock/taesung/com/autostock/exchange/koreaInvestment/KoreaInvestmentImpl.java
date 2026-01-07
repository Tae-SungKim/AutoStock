package autostock.taesung.com.autostock.exchange.koreaInvestment;

import autostock.taesung.com.autostock.exchange.ExchangeApi;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

/**
 * 한국투자증권 API 구현체
 */
@Slf4j
@Service
public class KoreaInvestmentImpl implements ExchangeApi {

    @Value("${korea-investment.app-key:}")
    private String appKey;

    @Value("${korea-investment.app-secret:}")
    private String appSecret;

    @Value("${korea-investment.base-url:https://openapi.koreainvestment.com:9443}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public JSONArray getForeignInstitutionData(String stockCode) throws JSONException, IOException {
        log.info("외국인/기관 매매 데이터 조회: {}", stockCode);

        // TODO: 실제 한국투자증권 API 연동 구현
        // 현재는 빈 배열 반환
        return new JSONArray();
    }
}