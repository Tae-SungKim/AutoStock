package autostock.taesung.com.autostock.service;

public interface MarketVolumeService {

    /**
     * KRW 알트코인 (BTC, ETH 제외) 최근 N분 평균 거래대금
     */
    double getKrwAltAvgTradeAmount(int minutes);
}