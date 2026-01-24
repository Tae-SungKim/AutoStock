package autostock.taesung.com.autostock.service;

public interface MarketVolumeService {

    /**
     * KRW 알트코인 (BTC, ETH 제외) 최근 N분 평균 거래대금
     */
    double getKrwAltAvgTradeAmount(int minutes);

    /**
     * 체결강도 조회 (최근 N초 기준)
     * @param market 마켓 코드
     * @param seconds 조회 시간(초)
     * @return 매수 체결량 / 전체 체결량 × 100 (0~100)
     */
    double getExecutionStrength(String market, int seconds);
}