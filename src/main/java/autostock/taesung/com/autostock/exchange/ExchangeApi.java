package autostock.taesung.com.autostock.exchange;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;

/**
 * 거래소 API 인터페이스
 */
public interface ExchangeApi {

    /**
     * 외국인/기관 매매 데이터 조회
     */
    JSONArray getForeignInstitutionData(String stockCode) throws JSONException, IOException;
}