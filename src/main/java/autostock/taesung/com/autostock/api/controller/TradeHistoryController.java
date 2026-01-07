package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.dto.TradeProfitDto;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.entity.TradeHistory.TradeType;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.service.TradeProfitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/trade-history")
@RequiredArgsConstructor
public class TradeHistoryController {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final TradeProfitService tradeProfitService;

    /**
     * 전체 거래 내역 조회 (최근 100건)
     */
    @GetMapping
    public ResponseEntity<List<TradeHistory>> getAllTradeHistory() {
        List<TradeHistory> histories = tradeHistoryRepository.findTop100ByOrderByCreatedAtDesc();
        return ResponseEntity.ok(histories);
    }

    /**
     * 마켓별 거래 내역 조회
     */
    @GetMapping("/market/{market}")
    public ResponseEntity<List<TradeHistory>> getByMarket(@PathVariable String market) {
        List<TradeHistory> histories = tradeHistoryRepository.findByMarketOrderByCreatedAtDesc(market.toUpperCase());
        return ResponseEntity.ok(histories);
    }

    /**
     * 거래 유형별 조회 (BUY/SELL)
     */
    @GetMapping("/type/{tradeType}")
    public ResponseEntity<List<TradeHistory>> getByTradeType(@PathVariable String tradeType) {
        TradeType type = TradeType.valueOf(tradeType.toUpperCase());
        List<TradeHistory> histories = tradeHistoryRepository.findByTradeTypeOrderByCreatedAtDesc(type);
        return ResponseEntity.ok(histories);
    }

    /**
     * 기간별 거래 내역 조회
     */
    @GetMapping("/period")
    public ResponseEntity<List<TradeHistory>> getByPeriod(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<TradeHistory> histories = tradeHistoryRepository.findByTradeDateBetweenOrderByCreatedAtDesc(startDate, endDate);
        return ResponseEntity.ok(histories);
    }

    /**
     * 전체 손익 요약
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new HashMap<>();

        BigDecimal totalProfitLoss = tradeHistoryRepository.getTotalProfitLoss();
        long totalTrades = tradeHistoryRepository.count();

        summary.put("totalProfitLoss", totalProfitLoss);
        summary.put("totalTrades", totalTrades);

        return ResponseEntity.ok(summary);
    }

    /**
     * 마켓별 손익 요약
     */
    @GetMapping("/summary/{market}")
    public ResponseEntity<Map<String, Object>> getMarketSummary(@PathVariable String market) {
        String marketUpper = market.toUpperCase();
        Map<String, Object> summary = new HashMap<>();

        BigDecimal totalBuy = tradeHistoryRepository.getTotalBuyAmountByMarket(marketUpper);
        BigDecimal totalSell = tradeHistoryRepository.getTotalSellAmountByMarket(marketUpper);
        BigDecimal totalFee = tradeHistoryRepository.getTotalFeeByMarket(marketUpper);
        BigDecimal profitLoss = tradeHistoryRepository.getProfitLossByMarket(marketUpper);

        summary.put("market", marketUpper);
        summary.put("totalBuyAmount", totalBuy);
        summary.put("totalSellAmount", totalSell);
        summary.put("totalFee", totalFee);
        summary.put("profitLoss", profitLoss);

        return ResponseEntity.ok(summary);
    }

    /**
     * 오늘 거래 내역 조회
     */
    @GetMapping("/today")
    public ResponseEntity<List<TradeHistory>> getTodayTrades() {
        LocalDate today = LocalDate.now();
        List<TradeHistory> histories = tradeHistoryRepository.findByTradeDateBetweenOrderByCreatedAtDesc(today, today);
        return ResponseEntity.ok(histories);
    }

    // ==================== 매매 손익 API ====================

    /**
     * 전체 매매 손익 조회 (매수/매도 매칭)
     * - 구매일, 구매가격, 판매일, 판매가격, 수수료, 순이익
     * - from/to 파라미터로 기간 필터 가능
     */
    @GetMapping("/profit")
    public ResponseEntity<List<TradeProfitDto>> getAllProfits(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<TradeProfitDto> profits;
        if (from != null && to != null) {
            profits = tradeProfitService.getAllTradeProfits(from, to);
        } else {
            profits = tradeProfitService.getAllTradeProfits();
        }
        return ResponseEntity.ok(profits);
    }

    /**
     * 마켓별 매매 손익 조회
     */
    @GetMapping("/profit/market/{market}")
    public ResponseEntity<List<TradeProfitDto>> getProfitsByMarket(@PathVariable String market) {
        List<TradeProfitDto> profits = tradeProfitService.getTradeProfitsByMarket(market);
        return ResponseEntity.ok(profits);
    }

    /**
     * 전체 손익 요약 (매칭 기반)
     * - 총 순이익, 승률, 매매 횟수 등
     * - from/to 파라미터로 기간 필터 가능
     */
    @GetMapping("/profit/summary")
    public ResponseEntity<Map<String, Object>> getProfitSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Map<String, Object> summary;
        if (from != null && to != null) {
            summary = tradeProfitService.getProfitSummary(from, to);
        } else {
            summary = tradeProfitService.getProfitSummary();
        }
        return ResponseEntity.ok(summary);
    }

    /**
     * 마켓별 손익 요약 (매칭 기반)
     */
    @GetMapping("/profit/summary/{market}")
    public ResponseEntity<Map<String, Object>> getProfitSummaryByMarket(@PathVariable String market) {
        Map<String, Object> summary = tradeProfitService.getProfitSummaryByMarket(market);
        return ResponseEntity.ok(summary);
    }

    /**
     * 일자별 수익률 조회
     * - 매도일 기준으로 일별 손익 집계
     * - from/to 파라미터로 기간 필터 가능
     */
    @GetMapping("/profit/daily")
    public ResponseEntity<List<Map<String, Object>>> getDailyProfitSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<Map<String, Object>> dailyProfits;
        if (from != null && to != null) {
            dailyProfits = tradeProfitService.getDailyProfitSummary(from, to);
        } else {
            dailyProfits = tradeProfitService.getDailyProfitSummary();
        }
        return ResponseEntity.ok(dailyProfits);
    }
}