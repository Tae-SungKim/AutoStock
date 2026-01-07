package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.api.service.ApiService;
import autostock.taesung.com.autostock.exchange.ExchangeApi;
import autostock.taesung.com.autostock.exchange.koreaInvestment.KoreaInvestmentImpl;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;


@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class ApiController {
    private final ApiService apiService;
    @GetMapping("/stock-code")
    public JSONArray getStockCode(@RequestParam(required = false) String stockCode) throws JSONException, IOException {
        if(!StringUtils.hasText(stockCode)){
            stockCode = "005930"; // 삼성전자 (종목코드)
        }
        return apiService.getStockCode(stockCode);
        /*JSONObject marketData = koreaInvestment.getForeignInstitutionData("005930"); // 삼성전자 (종목코드)
        return ResponseEntity.ok(stockCode);*/
    }

}
