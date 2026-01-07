package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.dto.TradeProfitDto;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.entity.TradeHistory.TradeType;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeProfitService {

    private final TradeHistoryRepository tradeHistoryRepository;

    /**
     * 전체 마켓의 매매 손익 조회
     */
    public List<TradeProfitDto> getAllTradeProfits() {
        List<TradeHistory> allTrades = tradeHistoryRepository.findAll();
        return matchAndCalculateProfits(allTrades);
    }

    /**
     * 전체 마켓의 매매 손익 조회 (기간 필터)
     */
    public List<TradeProfitDto> getAllTradeProfits(LocalDate fromDate, LocalDate toDate) {
        List<TradeHistory> allTrades = tradeHistoryRepository.findByTradeDateBetweenOrderByCreatedAtDesc(fromDate, toDate);
        return matchAndCalculateProfits(allTrades);
    }

    /**
     * 특정 마켓의 매매 손익 조회
     */
    public List<TradeProfitDto> getTradeProfitsByMarket(String market) {
        List<TradeHistory> trades = tradeHistoryRepository.findByMarketOrderByCreatedAtDesc(market.toUpperCase());
        // 시간순 정렬 (오래된 것부터)
        trades.sort(Comparator.comparing(TradeHistory::getCreatedAt));
        return matchAndCalculateProfits(trades);
    }

    /**
     * 매수/매도 매칭 및 손익 계산 (FIFO 방식)
     */
    private List<TradeProfitDto> matchAndCalculateProfits(List<TradeHistory> trades) {
        List<TradeProfitDto> results = new ArrayList<>();

        // 마켓별로 그룹화
        Map<String, List<TradeHistory>> tradesByMarket = trades.stream()
                .collect(Collectors.groupingBy(TradeHistory::getMarket));

        for (Map.Entry<String, List<TradeHistory>> entry : tradesByMarket.entrySet()) {
            String market = entry.getKey();
            List<TradeHistory> marketTrades = entry.getValue();

            // 시간순 정렬
            marketTrades.sort(Comparator.comparing(TradeHistory::getCreatedAt));

            // 매수/매도 분리
            Queue<TradeHistory> buyQueue = new LinkedList<>();
            Queue<TradeHistory> sellQueue = new LinkedList<>();

            for (TradeHistory trade : marketTrades) {
                if (trade.getTradeType() == TradeType.BUY) {
                    buyQueue.offer(trade);
                } else {
                    sellQueue.offer(trade);
                }
            }

            // FIFO 매칭
            while (!buyQueue.isEmpty() && !sellQueue.isEmpty()) {
                TradeHistory buy = buyQueue.poll();
                TradeHistory sell = sellQueue.poll();

                TradeProfitDto profit = calculateProfit(buy, sell);
                results.add(profit);
            }

            // 미매도 보유중 (매수만 있고 매도가 없는 경우)
            while (!buyQueue.isEmpty()) {
                TradeHistory buy = buyQueue.poll();
                TradeProfitDto holding = createHoldingDto(buy);
                results.add(holding);
            }
        }

        // 최신순 정렬
        results.sort((a, b) -> {
            LocalDate dateA = a.getSellDate() != null ? a.getSellDate() : a.getBuyDate();
            LocalDate dateB = b.getSellDate() != null ? b.getSellDate() : b.getBuyDate();
            return dateB.compareTo(dateA);
        });

        return results;
    }

    /**
     * 매수/매도 매칭하여 손익 계산
     */
    private TradeProfitDto calculateProfit(TradeHistory buy, TradeHistory sell) {
        BigDecimal totalFee = buy.getFee().add(sell.getFee());
        BigDecimal grossProfit = sell.getAmount().subtract(buy.getAmount());
        BigDecimal netProfit = grossProfit.subtract(totalFee);

        // 수익률 계산 (순이익 / 매수금액 * 100)
        BigDecimal profitRate = BigDecimal.ZERO;
        if (buy.getAmount().compareTo(BigDecimal.ZERO) > 0) {
            profitRate = netProfit.divide(buy.getAmount(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        // 보유 기간 계산
        long holdingDays = ChronoUnit.DAYS.between(buy.getTradeDate(), sell.getTradeDate());

        return TradeProfitDto.builder()
                .market(buy.getMarket())
                // 매수 정보
                .buyDate(buy.getTradeDate())
                .buyTime(buy.getTradeTime())
                .buyPrice(buy.getPrice())
                .buyAmount(buy.getAmount())
                .buyVolume(buy.getVolume())
                .buyFee(buy.getFee())
                .buyOrderUuid(buy.getOrderUuid())
                .targetPrice(buy.getTargetPrice())
                .buyStrategy(buy.getStrategyName())
                // 매도 정보
                .sellDate(sell.getTradeDate())
                .sellTime(sell.getTradeTime())
                .sellPrice(sell.getPrice())
                .sellAmount(sell.getAmount())
                .sellVolume(sell.getVolume())
                .sellFee(sell.getFee())
                .sellOrderUuid(sell.getOrderUuid())
                .sellStrategy(sell.getStrategyName())
                // 손익 정보
                .totalFee(totalFee)
                .grossProfit(grossProfit)
                .netProfit(netProfit)
                .profitRate(profitRate)
                .holdingDays(holdingDays)
                .status("MATCHED")
                .build();
    }

    /**
     * 보유중 (미매도) DTO 생성
     */
    private TradeProfitDto createHoldingDto(TradeHistory buy) {
        long holdingDays = ChronoUnit.DAYS.between(buy.getTradeDate(), LocalDate.now());

        return TradeProfitDto.builder()
                .market(buy.getMarket())
                // 매수 정보
                .buyDate(buy.getTradeDate())
                .buyTime(buy.getTradeTime())
                .buyPrice(buy.getPrice())
                .buyAmount(buy.getAmount())
                .buyVolume(buy.getVolume())
                .buyFee(buy.getFee())
                .buyOrderUuid(buy.getOrderUuid())
                .targetPrice(buy.getTargetPrice())
                .buyStrategy(buy.getStrategyName())
                // 매도 정보 없음
                .sellDate(null)
                .sellTime(null)
                .sellPrice(null)
                .sellAmount(null)
                .sellVolume(null)
                .sellFee(null)
                .sellOrderUuid(null)
                .sellStrategy(null)
                // 손익 정보 (미확정)
                .totalFee(buy.getFee())
                .grossProfit(null)
                .netProfit(null)
                .profitRate(null)
                .holdingDays(holdingDays)
                .status("HOLDING")
                .build();
    }

    /**
     * 전체 손익 요약
     */
    public Map<String, Object> getProfitSummary() {
        List<TradeProfitDto> profits = getAllTradeProfits();
        return calculateSummary(profits, null, null);
    }

    /**
     * 전체 손익 요약 (기간 필터)
     */
    public Map<String, Object> getProfitSummary(LocalDate fromDate, LocalDate toDate) {
        List<TradeProfitDto> profits = getAllTradeProfits(fromDate, toDate);
        return calculateSummary(profits, fromDate, toDate);
    }

    /**
     * 손익 요약 계산 (공통 로직)
     */
    private Map<String, Object> calculateSummary(List<TradeProfitDto> profits, LocalDate fromDate, LocalDate toDate) {
        BigDecimal totalNetProfit = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        int matchedCount = 0;
        int holdingCount = 0;
        int winCount = 0;
        int loseCount = 0;

        for (TradeProfitDto p : profits) {
            if ("MATCHED".equals(p.getStatus())) {
                matchedCount++;
                totalNetProfit = totalNetProfit.add(p.getNetProfit());
                totalFee = totalFee.add(p.getTotalFee());

                if (p.getNetProfit().compareTo(BigDecimal.ZERO) > 0) {
                    winCount++;
                } else {
                    loseCount++;
                }
            } else {
                holdingCount++;
                totalFee = totalFee.add(p.getTotalFee());
            }
        }

        // 승률 계산
        double winRate = matchedCount > 0 ? (double) winCount / matchedCount * 100 : 0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalNetProfit", totalNetProfit);
        summary.put("totalFee", totalFee);
        summary.put("matchedTrades", matchedCount);
        summary.put("holdingTrades", holdingCount);
        summary.put("winCount", winCount);
        summary.put("loseCount", loseCount);
        summary.put("winRate", String.format("%.2f", winRate) + "%");
        if (fromDate != null && toDate != null) {
            summary.put("fromDate", fromDate.toString());
            summary.put("toDate", toDate.toString());
        }

        return summary;
    }

    /**
     * 일자별 수익률 조회
     */
    public List<Map<String, Object>> getDailyProfitSummary() {
        List<TradeProfitDto> profits = getAllTradeProfits();
        return calculateDailyProfits(profits);
    }

    /**
     * 일자별 수익률 조회 (기간 필터)
     */
    public List<Map<String, Object>> getDailyProfitSummary(LocalDate fromDate, LocalDate toDate) {
        List<TradeProfitDto> profits = getAllTradeProfits(fromDate, toDate);
        return calculateDailyProfits(profits);
    }

    /**
     * 일자별 수익 계산 (공통 로직)
     */
    private List<Map<String, Object>> calculateDailyProfits(List<TradeProfitDto> profits) {
        // 매도일 기준으로 그룹화 (MATCHED 상태만)
        Map<LocalDate, List<TradeProfitDto>> profitsByDate = profits.stream()
                .filter(p -> "MATCHED".equals(p.getStatus()) && p.getSellDate() != null)
                .collect(Collectors.groupingBy(TradeProfitDto::getSellDate));

        List<Map<String, Object>> dailyResults = new ArrayList<>();

        for (Map.Entry<LocalDate, List<TradeProfitDto>> entry : profitsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<TradeProfitDto> dayProfits = entry.getValue();

            BigDecimal dailyNetProfit = BigDecimal.ZERO;
            BigDecimal dailyGrossProfit = BigDecimal.ZERO;
            BigDecimal dailyFee = BigDecimal.ZERO;
            BigDecimal dailyBuyAmount = BigDecimal.ZERO;
            int tradeCount = dayProfits.size();
            int winCount = 0;
            int loseCount = 0;

            for (TradeProfitDto p : dayProfits) {
                dailyNetProfit = dailyNetProfit.add(p.getNetProfit());
                dailyGrossProfit = dailyGrossProfit.add(p.getGrossProfit());
                dailyFee = dailyFee.add(p.getTotalFee());
                dailyBuyAmount = dailyBuyAmount.add(p.getBuyAmount());

                if (p.getNetProfit().compareTo(BigDecimal.ZERO) > 0) {
                    winCount++;
                } else {
                    loseCount++;
                }
            }

            // 일별 수익률 계산 (순이익 / 매수금액 * 100)
            BigDecimal dailyProfitRate = BigDecimal.ZERO;
            if (dailyBuyAmount.compareTo(BigDecimal.ZERO) > 0) {
                dailyProfitRate = dailyNetProfit.divide(dailyBuyAmount, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }

            double winRate = tradeCount > 0 ? (double) winCount / tradeCount * 100 : 0;

            Map<String, Object> dailySummary = new LinkedHashMap<>();
            dailySummary.put("date", date.toString());
            dailySummary.put("tradeCount", tradeCount);
            dailySummary.put("winCount", winCount);
            dailySummary.put("loseCount", loseCount);
            dailySummary.put("winRate", String.format("%.1f", winRate) + "%");
            dailySummary.put("buyAmount", dailyBuyAmount);
            dailySummary.put("grossProfit", dailyGrossProfit);
            dailySummary.put("fee", dailyFee);
            dailySummary.put("netProfit", dailyNetProfit);
            dailySummary.put("profitRate", dailyProfitRate.setScale(2, RoundingMode.HALF_UP) + "%");

            dailyResults.add(dailySummary);
        }

        // 날짜 내림차순 정렬 (최신순)
        dailyResults.sort((a, b) -> {
            String dateA = (String) a.get("date");
            String dateB = (String) b.get("date");
            return dateB.compareTo(dateA);
        });

        return dailyResults;
    }

    /**
     * 마켓별 손익 요약
     */
    public Map<String, Object> getProfitSummaryByMarket(String market) {
        List<TradeProfitDto> profits = getTradeProfitsByMarket(market);

        BigDecimal totalNetProfit = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        int matchedCount = 0;
        int holdingCount = 0;
        int winCount = 0;
        int loseCount = 0;

        for (TradeProfitDto p : profits) {
            if ("MATCHED".equals(p.getStatus())) {
                matchedCount++;
                totalNetProfit = totalNetProfit.add(p.getNetProfit());
                totalFee = totalFee.add(p.getTotalFee());

                if (p.getNetProfit().compareTo(BigDecimal.ZERO) > 0) {
                    winCount++;
                } else {
                    loseCount++;
                }
            } else {
                holdingCount++;
                totalFee = totalFee.add(p.getTotalFee());
            }
        }

        double winRate = matchedCount > 0 ? (double) winCount / matchedCount * 100 : 0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("market", market.toUpperCase());
        summary.put("totalNetProfit", totalNetProfit);
        summary.put("totalFee", totalFee);
        summary.put("matchedTrades", matchedCount);
        summary.put("holdingTrades", holdingCount);
        summary.put("winCount", winCount);
        summary.put("loseCount", loseCount);
        summary.put("winRate", String.format("%.2f", winRate) + "%");

        return summary;
    }
}
