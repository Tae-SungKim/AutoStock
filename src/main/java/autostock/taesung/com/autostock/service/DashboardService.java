package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.UserUpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Account;
import autostock.taesung.com.autostock.exchange.upbit.dto.Ticker;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 실시간 대시보드 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final UserUpbitApiService upbitApiService;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final PriceAlertService priceAlertService;

    /**
     * 자산 정보
     */
    @Data
    @Builder
    public static class AssetInfo {
        private String currency;
        private String market;
        private double balance;
        private double avgBuyPrice;
        private double currentPrice;
        private double evaluationAmount;
        private double profitLoss;
        private double profitLossRate;
    }

    /**
     * 대시보드 전체 데이터
     */
    @Data
    @Builder
    public static class DashboardData {
        // 자산 요약
        private double totalAsset;
        private double krwBalance;
        private double coinEvaluation;
        private double totalProfitLoss;
        private double totalProfitLossRate;

        // 자산 상세
        private List<AssetInfo> assets;

        // 거래 통계
        private int todayTradeCount;
        private int totalTradeCount;
        private double todayProfitLoss;
        private double winRate;

        // 시장 상태
        private PriceAlertService.MarketStatus marketStatus;

        // 최근 거래
        private List<TradeHistory> recentTrades;

        // 수익률 차트 데이터
        private List<ProfitChartData> profitChart;

        private LocalDateTime updatedAt;
    }

    /**
     * 수익률 차트 데이터
     */
    @Data
    @Builder
    public static class ProfitChartData {
        private String date;
        private double profitLoss;
        private double cumulativeProfit;
        private int tradeCount;
    }

    /**
     * 대시보드 전체 데이터 조회
     */
    public DashboardData getDashboardData(User user) {
        log.info("대시보드 데이터 조회 시작 - userId: {}", user.getId());
        try {
            // API 키 확인
            if (user.getUpbitAccessKey() == null || user.getUpbitSecretKey() == null) {
                log.warn("대시보드 조회 실패: API 키가 설정되지 않음 - userId: {}", user.getId());
                return DashboardData.builder()
                        .totalAsset(0)
                        .krwBalance(0)
                        .coinEvaluation(0)
                        .totalProfitLoss(0)
                        .totalProfitLossRate(0)
                        .assets(new ArrayList<>())
                        .todayTradeCount(0)
                        .totalTradeCount(0)
                        .todayProfitLoss(0)
                        .winRate(0)
                        .marketStatus(null)
                        .recentTrades(new ArrayList<>())
                        .profitChart(new ArrayList<>())
                        .updatedAt(LocalDateTime.now())
                        .build();
            }

            // 계좌 정보 조회
            log.debug("계좌 정보 조회 중...");
            List<Account> accounts = upbitApiService.getAccounts(user)
                    .stream()
                    .filter(it->"KRW".equals(it.getCurrency()) || (Double.parseDouble(it.getBalance()) * Double.parseDouble(it.getAvgBuyPrice()) >= 1))
                    .toList();
            log.debug("계좌 정보 조회 완료 - {} 개 계좌", accounts != null ? accounts.size() : 0);

            double krwBalance = 0;
            double totalCoinEvaluation = 0;
            double totalProfitLoss = 0;
            List<AssetInfo> assetInfos = new ArrayList<>();

            // 코인 마켓 목록 수집
            List<String> coinMarkets = new ArrayList<>();
            Map<String, Account> accountMap = new HashMap<>();

            for (Account account : accounts) {
                if ("KRW".equals(account.getCurrency())) {
                    krwBalance = Double.parseDouble(account.getBalance());
                } else {
                    String market = "KRW-" + account.getCurrency();
                    coinMarkets.add(market);
                    accountMap.put(market, account);
                }
            }

            // 현재가 조회
            if (!coinMarkets.isEmpty()) {
                String marketsParam = String.join(",", coinMarkets);
                List<Ticker> tickers = upbitApiService.getTicker(marketsParam);
                Map<String, Ticker> tickerMap = tickers.stream()
                        .collect(Collectors.toMap(Ticker::getMarket, t -> t));

                for (String market : coinMarkets) {
                    Account account = accountMap.get(market);
                    Ticker ticker = tickerMap.get(market);

                    if (account != null && ticker != null) {
                        double balance = Double.parseDouble(account.getBalance());
                        double avgBuyPrice = Double.parseDouble(account.getAvgBuyPrice());
                        double currentPrice = ticker.getTradePrice().doubleValue();
                        double evaluationAmount = balance * currentPrice;
                        double profitLoss = evaluationAmount - (balance * avgBuyPrice);
                        double profitLossRate = avgBuyPrice > 0 ?
                                ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100 : 0;

                        totalCoinEvaluation += evaluationAmount;
                        totalProfitLoss += profitLoss;

                        if (balance * currentPrice >= 5000) {  // 최소 5000원 이상만 표시
                            assetInfos.add(AssetInfo.builder()
                                    .currency(account.getCurrency())
                                    .market(market)
                                    .balance(balance)
                                    .avgBuyPrice(avgBuyPrice)
                                    .currentPrice(currentPrice)
                                    .evaluationAmount(Math.round(evaluationAmount))
                                    .profitLoss(Math.round(profitLoss))
                                    .profitLossRate(Math.round(profitLossRate * 100.0) / 100.0)
                                    .build());
                        }
                    }
                }
            }

            // 자산 정렬 (평가금액 기준 내림차순)
            assetInfos.sort((a, b) -> Double.compare(b.getEvaluationAmount(), a.getEvaluationAmount()));

            double totalAsset = krwBalance + totalCoinEvaluation;
            double totalProfitLossRate = totalCoinEvaluation > 0 ?
                    (totalProfitLoss / (totalCoinEvaluation - totalProfitLoss)) * 100 : 0;

            // 거래 통계
            LocalDate today = LocalDate.now();
            List<TradeHistory> allTrades = tradeHistoryRepository.findByUserId(user.getId());
            List<TradeHistory> todayTrades = allTrades.stream()
                    .filter(t -> t.getCreatedAt().toLocalDate().equals(today))
                    .collect(Collectors.toList());

            double todayProfitLoss = calculateTodayProfitLoss(todayTrades);
            double winRate = calculateWinRate(allTrades);

            // 시장 상태
            PriceAlertService.MarketStatus marketStatus = priceAlertService.scanAllMarkets(50);

            // 최근 거래 (10건)
            List<TradeHistory> recentTrades = allTrades.stream()
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .limit(10)
                    .collect(Collectors.toList());

            // 수익률 차트 (최근 30일)
            List<ProfitChartData> profitChart = generateProfitChart(allTrades, 30);

            return DashboardData.builder()
                    .totalAsset(Math.round(totalAsset))
                    .krwBalance(Math.round(krwBalance))
                    .coinEvaluation(Math.round(totalCoinEvaluation))
                    .totalProfitLoss(Math.round(totalProfitLoss))
                    .totalProfitLossRate(Math.round(totalProfitLossRate * 100.0) / 100.0)
                    .assets(assetInfos)
                    .todayTradeCount(todayTrades.size())
                    .totalTradeCount(allTrades.size())
                    .todayProfitLoss(Math.round(todayProfitLoss))
                    .winRate(Math.round(winRate * 10.0) / 10.0)
                    .marketStatus(marketStatus)
                    .recentTrades(recentTrades)
                    .profitChart(profitChart)
                    .updatedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("대시보드 데이터 조회 오류: {}", e.getMessage(), e);
            // 에러 발생 시에도 최소한의 정보 제공
            return DashboardData.builder()
                    .totalAsset(0)
                    .krwBalance(0)
                    .coinEvaluation(0)
                    .totalProfitLoss(0)
                    .totalProfitLossRate(0)
                    .assets(new ArrayList<>())
                    .todayTradeCount(0)
                    .totalTradeCount(0)
                    .todayProfitLoss(0)
                    .winRate(0)
                    .marketStatus(null)
                    .recentTrades(new ArrayList<>())
                    .profitChart(new ArrayList<>())
                    .updatedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 자산 요약만 조회 (빠른 조회용)
     */
    public Map<String, Object> getAssetSummary(User user) {
        Map<String, Object> summary = new HashMap<>();

        try {
            List<Account> accounts = upbitApiService.getAccounts(user);

            double krwBalance = 0;
            double totalCoinEvaluation = 0;
            List<String> coinMarkets = new ArrayList<>();
            Map<String, Double> balanceMap = new HashMap<>();

            for (Account account : accounts) {
                if ("KRW".equals(account.getCurrency())) {
                    krwBalance = Double.parseDouble(account.getBalance());
                } else {
                    String market = "KRW-" + account.getCurrency();
                    coinMarkets.add(market);
                    balanceMap.put(market, Double.parseDouble(account.getBalance()));
                }
            }

            if (!coinMarkets.isEmpty()) {
                String marketsParam = String.join(",", coinMarkets);
                List<Ticker> tickers = upbitApiService.getTicker(marketsParam);
                for (Ticker ticker : tickers) {
                    Double balance = balanceMap.get(ticker.getMarket());
                    if (balance != null) {
                        totalCoinEvaluation += balance * ticker.getTradePrice().doubleValue();
                    }
                }
            }

            summary.put("totalAsset", Math.round(krwBalance + totalCoinEvaluation));
            summary.put("krwBalance", Math.round(krwBalance));
            summary.put("coinEvaluation", Math.round(totalCoinEvaluation));
            summary.put("coinCount", coinMarkets.size());
            summary.put("updatedAt", LocalDateTime.now());

        } catch (Exception e) {
            log.error("자산 요약 조회 오류: {}", e.getMessage());
        }

        return summary;
    }

    private double calculateTodayProfitLoss(List<TradeHistory> todayTrades) {
        double profit = 0;
        Map<String, TradeHistory> buyMap = new HashMap<>();

        for (TradeHistory trade : todayTrades) {
            if (trade.getTradeType() == TradeHistory.TradeType.BUY) {
                buyMap.put(trade.getMarket(), trade);
            } else if (trade.getTradeType() == TradeHistory.TradeType.SELL) {
                TradeHistory buyTrade = buyMap.get(trade.getMarket());
                if (buyTrade != null) {
                    double buyAmount = buyTrade.getAmount().doubleValue();
                    double sellAmount = trade.getAmount().doubleValue();
                    profit += (sellAmount - buyAmount);
                }
            }
        }
        return profit;
    }

    private double calculateWinRate(List<TradeHistory> allTrades) {
        int winCount = 0;
        int totalSells = 0;

        Map<String, BigDecimal> lastBuyPrice = new HashMap<>();

        for (TradeHistory trade : allTrades) {
            if (trade.getTradeType() == TradeHistory.TradeType.BUY) {
                lastBuyPrice.put(trade.getMarket(), trade.getPrice());
            } else if (trade.getTradeType() == TradeHistory.TradeType.SELL) {
                totalSells++;
                BigDecimal buyPrice = lastBuyPrice.get(trade.getMarket());
                if (buyPrice != null && trade.getPrice().compareTo(buyPrice) > 0) {
                    winCount++;
                }
            }
        }

        return totalSells > 0 ? ((double) winCount / totalSells) * 100 : 0;
    }

    private List<ProfitChartData> generateProfitChart(List<TradeHistory> trades, int days) {
        List<ProfitChartData> chartData = new ArrayList<>();
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        Map<LocalDate, Double> dailyProfit = new HashMap<>();
        Map<LocalDate, Integer> dailyCount = new HashMap<>();

        // 일별 수익 집계
        Map<String, BigDecimal> buyPriceMap = new HashMap<>();
        for (TradeHistory trade : trades) {
            LocalDate tradeDate = trade.getCreatedAt().toLocalDate();
            if (tradeDate.isBefore(startDate)) continue;

            if (trade.getTradeType() == TradeHistory.TradeType.BUY) {
                buyPriceMap.put(trade.getMarket(), trade.getPrice());
            } else if (trade.getTradeType() == TradeHistory.TradeType.SELL) {
                BigDecimal buyPrice = buyPriceMap.get(trade.getMarket());
                if (buyPrice != null) {
                    double profit = trade.getAmount().doubleValue() -
                            (trade.getVolume().doubleValue() * buyPrice.doubleValue());
                    dailyProfit.merge(tradeDate, profit, Double::sum);
                }
                dailyCount.merge(tradeDate, 1, Integer::sum);
            }
        }

        // 차트 데이터 생성
        double cumulativeProfit = 0;
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            double dayProfit = dailyProfit.getOrDefault(date, 0.0);
            cumulativeProfit += dayProfit;
            int dayCount = dailyCount.getOrDefault(date, 0);

            chartData.add(ProfitChartData.builder()
                    .date(date.toString())
                    .profitLoss(Math.round(dayProfit))
                    .cumulativeProfit(Math.round(cumulativeProfit))
                    .tradeCount(dayCount)
                    .build());
        }

        return chartData;
    }
}