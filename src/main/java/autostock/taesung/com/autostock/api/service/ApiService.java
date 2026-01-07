package autostock.taesung.com.autostock.api.service;

import autostock.taesung.com.autostock.exchange.ExchangeApi;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class ApiService {
    private final ExchangeApi exchangeApi;

    public JSONArray getStockCode(String stockCode) throws JSONException, IOException{
        return exchangeApi.getForeignInstitutionData(stockCode);
    }
}
