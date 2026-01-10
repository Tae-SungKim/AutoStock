package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.exchange.upbit.dto.Ticker;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 급등/급락 감지 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAlertService {

    private final UpbitApiService upbitApiService;

    // 급등/급락 임계값 설정
    private static final double SURGE_THRESHOLD = 5.0;      // 급등 기준 (5%)
    private static final double PLUNGE_THRESHOLD = -5.0;    // 급락 기준 (-5%)
    private static final double VOLUME_SURGE_THRESHOLD = 300.0;  // 거래량 급증 기준 (300%)

    // 알림 캐시 (중복 알림 방지)
    private final Map<String, LocalDateTime> alertCache = new ConcurrentHashMap<>();
    private static final int ALERT_COOLDOWN_MINUTES = 5;

    /**
     * 급등/급락 알림 데이터
     */
    @Data
    @Builder
    public static class PriceAlert {
        private String market;
        private String alertType;       // SURGE, PLUNGE, VOLUME_SURGE
        private double currentPrice;
        private double changeRate;      // 변화율 (%)
        private double volumeChangeRate; // 거래량 변화율 (%)
        private double previousPrice;
        private LocalDateTime detectedAt;
        private String description;
    }

    /**
     * 시장 상태 분석 결과
     */
    @Data
    @Builder
    public static class MarketStatus {
        private int totalMarkets;
        private int surgingMarkets;
        private int plungingMarkets;
        private int normalMarkets;
        private double avgChangeRate;
        private String marketCondition;  // BULL, BEAR, NEUTRAL
        private List<PriceAlert> alerts;
        private LocalDateTime analyzedAt;
    }

    /**
     * 단일 마켓 급등/급락 감지
     */
    public List<PriceAlert> detectPriceMovement(String market) {
        List<PriceAlert> alerts = new ArrayList<>();

        try {
            // 현재가 조회
            List<Ticker> tickers = upbitApiService.getTicker(market);
            if (tickers == null || tickers.isEmpty()) {
                return alerts;
            }
            Ticker ticker = tickers.get(0);

            // 캔들 데이터 조회 (최근 30개)
            List<Candle> candles = upbitApiService.getMinuteCandles(market, 1, 30);
            if (candles == null || candles.size() < 10) {
                return alerts;
            }

            double currentPrice = ticker.getTradePrice().doubleValue();
            double changeRate = ticker.getSignedChangeRate().doubleValue() * 100;

            // 5분 전 가격 대비 변화율 계산
            double price5MinAgo = candles.get(4).getTradePrice().doubleValue();
            double shortTermChangeRate = ((currentPrice - price5MinAgo) / price5MinAgo) * 100;

            // 거래량 분석
            double currentVolume = candles.get(0).getCandleAccTradePrice().doubleValue();
            double avgVolume = candles.subList(1, 11).stream()
                    .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                    .average()
                    .orElse(1.0);
            double volumeChangeRate = (currentVolume / avgVolume) * 100;

            // 급등 감지
            if (shortTermChangeRate >= SURGE_THRESHOLD) {
                alerts.add(createAlert(market, "SURGE", currentPrice, shortTermChangeRate,
                        volumeChangeRate, price5MinAgo, "5분간 " + String.format("%.2f", shortTermChangeRate) + "% 급등"));
            }

            // 급락 감지
            if (shortTermChangeRate <= PLUNGE_THRESHOLD) {
                alerts.add(createAlert(market, "PLUNGE", currentPrice, shortTermChangeRate,
                        volumeChangeRate, price5MinAgo, "5분간 " + String.format("%.2f", shortTermChangeRate) + "% 급락"));
            }

            // 거래량 급증 감지
            if (volumeChangeRate >= VOLUME_SURGE_THRESHOLD) {
                alerts.add(createAlert(market, "VOLUME_SURGE", currentPrice, changeRate,
                        volumeChangeRate, price5MinAgo, "거래량 " + String.format("%.0f", volumeChangeRate) + "% 급증"));
            }

        } catch (Exception e) {
            log.error("[{}] 급등/급락 감지 오류: {}", market, e.getMessage());
        }

        return alerts;
    }

    /**
     * 전체 시장 스캔 (상위 N개 마켓)
     */
    public MarketStatus scanAllMarkets(int topN) {
        List<PriceAlert> allAlerts = new ArrayList<>();
        int surgingCount = 0;
        int plungingCount = 0;
        double totalChangeRate = 0;

        try {
            // 상위 마켓 목록 조회
            List<String> markets = upbitApiService.getMarkets().stream()
                    .filter(m -> m.getMarket().startsWith("KRW-"))
                    .limit(topN)
                    .map(m -> m.getMarket())
                    .toList();

            // 티커 배치 조회
            String marketsParam = String.join(",", markets);
            List<Ticker> tickers = upbitApiService.getTicker(marketsParam);

            for (Ticker ticker : tickers) {
                double changeRate = ticker.getSignedChangeRate().doubleValue() * 100;
                totalChangeRate += changeRate;

                if (changeRate >= SURGE_THRESHOLD) {
                    surgingCount++;
                    if (!isAlertCooldown(ticker.getMarket())) {
                        allAlerts.add(PriceAlert.builder()
                                .market(ticker.getMarket())
                                .alertType("SURGE")
                                .currentPrice(ticker.getTradePrice().doubleValue())
                                .changeRate(changeRate)
                                .detectedAt(LocalDateTime.now())
                                .description("일간 " + String.format("%.2f", changeRate) + "% 상승")
                                .build());
                        setAlertCooldown(ticker.getMarket());
                    }
                } else if (changeRate <= PLUNGE_THRESHOLD) {
                    plungingCount++;
                    if (!isAlertCooldown(ticker.getMarket())) {
                        allAlerts.add(PriceAlert.builder()
                                .market(ticker.getMarket())
                                .alertType("PLUNGE")
                                .currentPrice(ticker.getTradePrice().doubleValue())
                                .changeRate(changeRate)
                                .detectedAt(LocalDateTime.now())
                                .description("일간 " + String.format("%.2f", changeRate) + "% 하락")
                                .build());
                        setAlertCooldown(ticker.getMarket());
                    }
                }
            }

            double avgChangeRate = tickers.isEmpty() ? 0 : totalChangeRate / tickers.size();
            String marketCondition = determineMarketCondition(avgChangeRate, surgingCount, plungingCount, tickers.size());

            return MarketStatus.builder()
                    .totalMarkets(tickers.size())
                    .surgingMarkets(surgingCount)
                    .plungingMarkets(plungingCount)
                    .normalMarkets(tickers.size() - surgingCount - plungingCount)
                    .avgChangeRate(Math.round(avgChangeRate * 100.0) / 100.0)
                    .marketCondition(marketCondition)
                    .alerts(allAlerts)
                    .analyzedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("시장 스캔 오류: {}", e.getMessage());
            return MarketStatus.builder()
                    .totalMarkets(0)
                    .alerts(new ArrayList<>())
                    .analyzedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 실시간 급등 코인 탐지 (상위 10개)
     */
    public List<PriceAlert> getTopSurgingCoins(int limit) {
        List<PriceAlert> surgingCoins = new ArrayList<>();

        try {
            List<String> markets = upbitApiService.getMarkets().stream()
                    .filter(m -> m.getMarket().startsWith("KRW-"))
                    .limit(100)
                    .map(m -> m.getMarket())
                    .toList();

            String marketsParam = String.join(",", markets);
            List<Ticker> tickers = upbitApiService.getTicker(marketsParam);

            tickers.stream()
                    .filter(t -> t.getSignedChangeRate().doubleValue() > 0)
                    .sorted((a, b) -> Double.compare(
                            b.getSignedChangeRate().doubleValue(),
                            a.getSignedChangeRate().doubleValue()))
                    .limit(limit)
                    .forEach(ticker -> {
                        double changeRate = ticker.getSignedChangeRate().doubleValue() * 100;
                        surgingCoins.add(PriceAlert.builder()
                                .market(ticker.getMarket())
                                .alertType("TOP_GAINER")
                                .currentPrice(ticker.getTradePrice().doubleValue())
                                .changeRate(changeRate)
                                .detectedAt(LocalDateTime.now())
                                .description("상승률 " + String.format("%.2f", changeRate) + "%")
                                .build());
                    });

        } catch (Exception e) {
            log.error("급등 코인 탐지 오류: {}", e.getMessage());
        }

        return surgingCoins;
    }

    /**
     * 실시간 급락 코인 탐지 (상위 10개)
     */
    public List<PriceAlert> getTopPlungingCoins(int limit) {
        List<PriceAlert> plungingCoins = new ArrayList<>();

        try {
            List<String> markets = upbitApiService.getMarkets().stream()
                    .filter(m -> m.getMarket().startsWith("KRW-"))
                    .limit(100)
                    .map(m -> m.getMarket())
                    .toList();

            String marketsParam = String.join(",", markets);
            List<Ticker> tickers = upbitApiService.getTicker(marketsParam);

            tickers.stream()
                    .filter(t -> t.getSignedChangeRate().doubleValue() < 0)
                    .sorted(Comparator.comparingDouble(t -> t.getSignedChangeRate().doubleValue()))
                    .limit(limit)
                    .forEach(ticker -> {
                        double changeRate = ticker.getSignedChangeRate().doubleValue() * 100;
                        plungingCoins.add(PriceAlert.builder()
                                .market(ticker.getMarket())
                                .alertType("TOP_LOSER")
                                .currentPrice(ticker.getTradePrice().doubleValue())
                                .changeRate(changeRate)
                                .detectedAt(LocalDateTime.now())
                                .description("하락률 " + String.format("%.2f", changeRate) + "%")
                                .build());
                    });

        } catch (Exception e) {
            log.error("급락 코인 탐지 오류: {}", e.getMessage());
        }

        return plungingCoins;
    }

    private PriceAlert createAlert(String market, String type, double currentPrice,
                                   double changeRate, double volumeChangeRate,
                                   double previousPrice, String description) {
        return PriceAlert.builder()
                .market(market)
                .alertType(type)
                .currentPrice(currentPrice)
                .changeRate(Math.round(changeRate * 100.0) / 100.0)
                .volumeChangeRate(Math.round(volumeChangeRate * 100.0) / 100.0)
                .previousPrice(previousPrice)
                .detectedAt(LocalDateTime.now())
                .description(description)
                .build();
    }

    private String determineMarketCondition(double avgChangeRate, int surgingCount, int plungingCount, int total) {
        if (total == 0) return "UNKNOWN";

        double surgingRatio = (double) surgingCount / total;
        double plungingRatio = (double) plungingCount / total;

        if (avgChangeRate > 2 || surgingRatio > 0.3) return "BULL";
        if (avgChangeRate < -2 || plungingRatio > 0.3) return "BEAR";
        return "NEUTRAL";
    }

    private boolean isAlertCooldown(String market) {
        LocalDateTime lastAlert = alertCache.get(market);
        if (lastAlert == null) return false;
        return lastAlert.plusMinutes(ALERT_COOLDOWN_MINUTES).isAfter(LocalDateTime.now());
    }

    private void setAlertCooldown(String market) {
        alertCache.put(market, LocalDateTime.now());
    }
}