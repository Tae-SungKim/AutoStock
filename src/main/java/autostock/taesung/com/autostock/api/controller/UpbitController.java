package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.entity.TradeHistory.TradeType;
import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.*;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.trading.AutoTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/upbit")
@RequiredArgsConstructor
public class UpbitController {

    private final UpbitApiService upbitApiService;
    private final AutoTradingService autoTradingService;
    private final TradeHistoryRepository tradeHistoryRepository;

    private static final double UPBIT_FEE_RATE = 0.0005;

    /**
     * 계좌 조회
     */
    @GetMapping("/accounts")
    public ResponseEntity<List<Account>> getAccounts(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(upbitApiService.getAccounts(user));
    }

    /**
     * 마켓 목록 조회
     */
    @GetMapping("/markets")
    public ResponseEntity<List<Market>> getMarkets() {
        return ResponseEntity.ok(upbitApiService.getMarkets());
    }

    /**
     * 현재가 조회
     */
    @GetMapping("/ticker")
    public ResponseEntity<List<Ticker>> getTicker(@RequestParam String markets) {
        return ResponseEntity.ok(upbitApiService.getTicker(markets));
    }

    /**
     * 분봉 캔들 조회
     */
    @GetMapping("/candles/minutes/{unit}")
    public ResponseEntity<List<Candle>> getMinuteCandles(
            @PathVariable int unit,
            @RequestParam String market,
            @RequestParam(defaultValue = "100") int count) {
        return ResponseEntity.ok(upbitApiService.getMinuteCandles(market, unit, count));
    }

    /**
     * 일봉 캔들 조회
     */
    @GetMapping("/candles/days")
    public ResponseEntity<List<Candle>> getDayCandles(
            @RequestParam String market,
            @RequestParam(defaultValue = "100") int count) {
        return ResponseEntity.ok(upbitApiService.getDayCandles(market, count));
    }

    /**
     * 시장가 매수
     */
    @PostMapping("/orders/buy/market")
    public ResponseEntity<OrderResponse> buyMarketOrder(
            @RequestParam String market,
            @RequestParam double price,
            @AuthenticationPrincipal User user) {
        OrderResponse order = upbitApiService.buyMarketOrder(user, market, price);

        // 현재가 조회 후 거래 내역 저장
        try {
            List<Ticker> tickers = upbitApiService.getTicker(market);
            double currentPrice = tickers.get(0).getTradePrice().doubleValue();
            saveTradeHistory(market, TradeType.BUY, price, currentPrice, order.getUuid(), "Manual (Market)");
        } catch (Exception e) {
            log.error("[{}] 거래 내역 저장 실패: {}", market, e.getMessage());
        }

        return ResponseEntity.ok(order);
    }

    /**
     * 시장가 매도
     */
    @PostMapping("/orders/sell/market")
    public ResponseEntity<OrderResponse> sellMarketOrder(
            @RequestParam String market,
            @RequestParam double volume,
            @AuthenticationPrincipal User user) {
        // 현재가 먼저 조회
        double currentPrice = 0;
        try {
            List<Ticker> tickers = upbitApiService.getTicker(market);
            currentPrice = tickers.get(0).getTradePrice().doubleValue();
        } catch (Exception e) {
            log.error("[{}] 현재가 조회 실패: {}", market, e.getMessage());
        }

        OrderResponse order = upbitApiService.sellMarketOrder(user, market, volume);

        // 거래 내역 저장
        if (currentPrice > 0) {
            double sellAmount = volume * currentPrice;
            saveTradeHistory(market, TradeType.SELL, sellAmount, currentPrice, order.getUuid(), "Manual (Market)");
        }

        return ResponseEntity.ok(order);
    }

    /**
     * 지정가 매수
     */
    @PostMapping("/orders/buy/limit")
    public ResponseEntity<OrderResponse> buyLimitOrder(
            @RequestParam String market,
            @RequestParam double volume,
            @RequestParam double price,
            @AuthenticationPrincipal User user) {
        OrderResponse order = upbitApiService.buyLimitOrder(user, market, volume, price);

        // 거래 내역 저장 (지정가는 주문가격 사용)
        double orderAmount = volume * price;
        saveTradeHistory(market, TradeType.BUY, orderAmount, price, order.getUuid(), "Manual (Limit)");

        return ResponseEntity.ok(order);
    }

    /**
     * 지정가 매도
     */
    @PostMapping("/orders/sell/limit")
    public ResponseEntity<OrderResponse> sellLimitOrder(
            @RequestParam String market,
            @RequestParam double volume,
            @RequestParam double price,
            @AuthenticationPrincipal User user) {
        OrderResponse order = upbitApiService.sellLimitOrder(user, market, volume, price);

        // 거래 내역 저장 (지정가는 주문가격 사용)
        double sellAmount = volume * price;
        saveTradeHistory(market, TradeType.SELL, sellAmount, price, order.getUuid(), "Manual (Limit)");

        return ResponseEntity.ok(order);
    }

    /**
     * 주문 취소
     */
    @DeleteMapping("/orders/{uuid}")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable String uuid, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(upbitApiService.cancelOrder(user, uuid));
    }

    /**
     * 주문 조회
     */
    @GetMapping("/orders/{uuid}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String uuid, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(upbitApiService.getOrder(user, uuid));
    }

    /**
     * 자동매매 수동 실행
     */
    @PostMapping("/trading/execute")
    public ResponseEntity<Map<String, String>> executeAutoTrading(@AuthenticationPrincipal User user) {
        autoTradingService.executeAutoTrading(user);
        Map<String, String> response = new HashMap<>();
        response.put("status", "executed");
        response.put("message", "자동매매가 실행되었습니다. 로그를 확인해주세요.");
        return ResponseEntity.ok(response);
    }

    /**
     * 보유 현황 조회
     */
    @GetMapping("/trading/status")
    public ResponseEntity<Map<String, Object>> getTradingStatus(@AuthenticationPrincipal User user) {
        List<Account> accounts = upbitApiService.getAccounts(user);
        Map<String, Object> response = new HashMap<>();
        response.put("accounts", accounts);
        return ResponseEntity.ok(response);
    }

    /**
     * 거래 내역 저장 (수동 주문)
     */
    private void saveTradeHistory(String market, TradeType tradeType, double amount,
                                   double price, String orderUuid, String strategyName) {
        try {
            double volume = amount / price;
            double fee = amount * UPBIT_FEE_RATE;

            TradeHistory history = TradeHistory.builder()
                    .market(market)
                    .tradeMethod(strategyName.contains("Limit") ? "LIMIT" : "MARKET")
                    .tradeDate(LocalDate.now())
                    .tradeTime(LocalTime.now())
                    .tradeType(tradeType)
                    .amount(BigDecimal.valueOf(amount))
                    .volume(BigDecimal.valueOf(volume))
                    .price(BigDecimal.valueOf(price))
                    .fee(BigDecimal.valueOf(fee))
                    .orderUuid(orderUuid)
                    .strategyName(strategyName)
                    .build();

            tradeHistoryRepository.save(history);
            log.info("[{}] 수동 주문 거래 내역 저장 - {}, 금액: {}, 가격: {}",
                    market, tradeType, String.format("%.0f", amount), String.format("%.0f", price));

        } catch (Exception e) {
            log.error("[{}] 거래 내역 저장 실패: {}", market, e.getMessage());
        }
    }

    // ==================== 체결 내역 조회 API (업비트 실제 데이터) ====================

    /**
     * 체결 완료 주문 목록 조회 (업비트 API 직접 조회)
     */
    @GetMapping("/orders/closed")
    public ResponseEntity<List<ClosedOrder>> getClosedOrders(
            @RequestParam(required = false) String market,
            @RequestParam(defaultValue = "100") int limit,
            @AuthenticationPrincipal User user) {
        List<ClosedOrder> orders;
        if (market != null && !market.isEmpty()) {
            orders = upbitApiService.getClosedOrdersByMarket(user, market.toUpperCase());
        } else {
            orders = upbitApiService.getClosedOrders(user);
        }
        return ResponseEntity.ok(orders);
    }

    /**
     * 업비트 실제 체결 기반 수익률 조회
     * - 매수/매도 매칭하여 실제 수익률 계산
     */
    @GetMapping("/profit/real")
    public ResponseEntity<Map<String, Object>> getRealProfit(
            @RequestParam(required = false) String market,
            @AuthenticationPrincipal User user) {
        List<ClosedOrder> orders;
        if (market != null && !market.isEmpty()) {
            orders = upbitApiService.getClosedOrdersByMarket(user, market.toUpperCase());
        } else {
            orders = upbitApiService.getClosedOrders(user);
        }

        return ResponseEntity.ok(calculateRealProfit(orders));
    }

    /**
     * 업비트 실제 체결 기반 일자별 수익률 조회
     */
    @GetMapping("/profit/real/daily")
    public ResponseEntity<List<Map<String, Object>>> getRealDailyProfit(
            @RequestParam(required = false) String market,
            @AuthenticationPrincipal User user) {
        List<ClosedOrder> orders;
        if (market != null && !market.isEmpty()) {
            orders = upbitApiService.getClosedOrdersByMarket(user, market.toUpperCase());
        } else {
            orders = upbitApiService.getClosedOrders(user);
        }

        return ResponseEntity.ok(calculateRealDailyProfit(orders));
    }

    /**
     * 업비트 실제 체결 기반 전체 수익률 조회 (일자 그룹핑 없음)
     * @param market 마켓 코드 (선택)
     * @param from 시작일 (선택, yyyy-MM-dd)
     * @param to 종료일 (선택, yyyy-MM-dd)
     */
    @GetMapping("/profit/real/total")
    public ResponseEntity<Map<String, Object>> getRealTotalProfit(
            @RequestParam(required = false) String market,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User user) {
        List<ClosedOrder> orders;
        if (market != null && !market.isEmpty()) {
            orders = upbitApiService.getClosedOrdersByMarket(user, market.toUpperCase());
        } else {
            orders = upbitApiService.getClosedOrders(user);
        }

        // 날짜 필터링
        if (from != null || to != null) {
            orders = filterOrdersByDate(orders, from, to);
        }

        Map<String, Object> result = calculateRealTotalProfit(orders);

        // 조회 기간 정보 추가
        if (from != null) {
            result.put("fromDate", from.toString());
        }
        if (to != null) {
            result.put("toDate", to.toString());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 주문 목록을 날짜로 필터링
     */
    private List<ClosedOrder> filterOrdersByDate(List<ClosedOrder> orders, LocalDate from, LocalDate to) {
        return orders.stream()
                .filter(order -> {
                    if (order.getCreatedAt() == null) return false;
                    String dateStr = order.getCreatedAt().substring(0, 10);
                    LocalDate orderDate = LocalDate.parse(dateStr);

                    if (from != null && orderDate.isBefore(from)) return false;
                    if (to != null && orderDate.isAfter(to)) return false;
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * 실제 체결 데이터 기반 수익 계산
     */
    private Map<String, Object> calculateRealProfit(List<ClosedOrder> orders) {
        // 마켓별로 그룹화
        Map<String, List<ClosedOrder>> ordersByMarket = orders.stream()
                .collect(Collectors.groupingBy(ClosedOrder::getMarket));

        BigDecimal totalBuyAmount = BigDecimal.ZERO;
        BigDecimal totalSellAmount = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        int buyCount = 0;
        int sellCount = 0;

        List<Map<String, Object>> marketProfits = new ArrayList<>();

        for (Map.Entry<String, List<ClosedOrder>> entry : ordersByMarket.entrySet()) {
            String mkt = entry.getKey();
            List<ClosedOrder> marketOrders = entry.getValue();

            BigDecimal marketBuy = BigDecimal.ZERO;
            BigDecimal marketSell = BigDecimal.ZERO;
            BigDecimal marketFee = BigDecimal.ZERO;

            for (ClosedOrder order : marketOrders) {
                BigDecimal executedFunds = order.getExecutedFunds() != null ? order.getExecutedFunds() : BigDecimal.ZERO;
                BigDecimal paidFee = order.getPaidFee() != null ? order.getPaidFee() : BigDecimal.ZERO;

                if ("bid".equals(order.getSide())) {
                    // 매수: executedFunds는 실제 지불한 금액 (코인 가치)
                    marketBuy = marketBuy.add(executedFunds).add(paidFee);
                    buyCount++;
                } else if ("ask".equals(order.getSide())) {
                    // 매도: executedFunds는 실제 받은 금액
                    marketSell = marketSell.add(executedFunds);
                    sellCount++;
                }
                marketFee = marketFee.add(paidFee);
            }

            totalBuyAmount = totalBuyAmount.add(marketBuy);
            totalSellAmount = totalSellAmount.add(marketSell);
            totalFee = totalFee.add(marketFee);

            BigDecimal marketProfit = marketSell.subtract(marketBuy);
            BigDecimal marketProfitRate = marketBuy.compareTo(BigDecimal.ZERO) > 0
                    ? marketProfit.divide(marketBuy, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            Map<String, Object> mktProfit = new LinkedHashMap<>();
            mktProfit.put("market", mkt);
            mktProfit.put("buyAmount", marketBuy.setScale(0, RoundingMode.HALF_UP));
            mktProfit.put("sellAmount", marketSell.setScale(0, RoundingMode.HALF_UP));
            mktProfit.put("fee", marketFee.setScale(0, RoundingMode.HALF_UP));
            mktProfit.put("netProfit", marketProfit.setScale(0, RoundingMode.HALF_UP));
            mktProfit.put("profitRate", marketProfitRate.setScale(2, RoundingMode.HALF_UP) + "%");
            marketProfits.add(mktProfit);
        }

        // 전체 손익
        BigDecimal totalNetProfit = totalSellAmount.subtract(totalBuyAmount);
        BigDecimal totalProfitRate = totalBuyAmount.compareTo(BigDecimal.ZERO) > 0
                ? totalNetProfit.divide(totalBuyAmount, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalBuyAmount", totalBuyAmount.setScale(0, RoundingMode.HALF_UP));
        result.put("totalSellAmount", totalSellAmount.setScale(0, RoundingMode.HALF_UP));
        result.put("totalFee", totalFee.setScale(0, RoundingMode.HALF_UP));
        result.put("totalNetProfit", totalNetProfit.setScale(0, RoundingMode.HALF_UP));
        result.put("totalProfitRate", totalProfitRate.setScale(2, RoundingMode.HALF_UP) + "%");
        result.put("buyCount", buyCount);
        result.put("sellCount", sellCount);
        result.put("marketProfits", marketProfits);

        return result;
    }

    /**
     * 실제 체결 데이터 기반 일자별 수익 계산
     */
    private List<Map<String, Object>> calculateRealDailyProfit(List<ClosedOrder> orders) {
        // 날짜별로 그룹화 (createdAt 기준)
        Map<String, List<ClosedOrder>> ordersByDate = orders.stream()
                .filter(o -> o.getCreatedAt() != null)
                .collect(Collectors.groupingBy(o -> o.getCreatedAt().substring(0, 10))); // yyyy-MM-dd

        List<Map<String, Object>> dailyResults = new ArrayList<>();

        for (Map.Entry<String, List<ClosedOrder>> entry : ordersByDate.entrySet()) {
            String date = entry.getKey();
            List<ClosedOrder> dayOrders = entry.getValue();

            BigDecimal dayBuy = BigDecimal.ZERO;
            BigDecimal daySell = BigDecimal.ZERO;
            BigDecimal dayFee = BigDecimal.ZERO;
            int buyCount = 0;
            int sellCount = 0;

            // 마켓별 상세 내역
            Map<String, Map<String, Object>> marketDetails = new LinkedHashMap<>();

            for (ClosedOrder order : dayOrders) {
                BigDecimal executedFunds = order.getExecutedFunds() != null ? order.getExecutedFunds() : BigDecimal.ZERO;
                BigDecimal paidFee = order.getPaidFee() != null ? order.getPaidFee() : BigDecimal.ZERO;
                String market = order.getMarket();

                // 마켓별 집계
                marketDetails.computeIfAbsent(market, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("market", k);
                    m.put("buyAmount", BigDecimal.ZERO);
                    m.put("sellAmount", BigDecimal.ZERO);
                    m.put("fee", BigDecimal.ZERO);
                    m.put("buyCount", 0);
                    m.put("sellCount", 0);
                    return m;
                });

                Map<String, Object> mktData = marketDetails.get(market);

                if ("bid".equals(order.getSide())) {
                    dayBuy = dayBuy.add(executedFunds).add(paidFee);
                    buyCount++;
                    mktData.put("buyAmount", ((BigDecimal) mktData.get("buyAmount")).add(executedFunds).add(paidFee));
                    mktData.put("buyCount", (int) mktData.get("buyCount") + 1);
                } else if ("ask".equals(order.getSide())) {
                    daySell = daySell.add(executedFunds);
                    sellCount++;
                    mktData.put("sellAmount", ((BigDecimal) mktData.get("sellAmount")).add(executedFunds));
                    mktData.put("sellCount", (int) mktData.get("sellCount") + 1);
                }
                dayFee = dayFee.add(paidFee);
                mktData.put("fee", ((BigDecimal) mktData.get("fee")).add(paidFee));
            }

            // 마켓별 수익률 계산
            List<Map<String, Object>> marketList = new ArrayList<>();
            for (Map<String, Object> mktData : marketDetails.values()) {
                BigDecimal mktBuy = (BigDecimal) mktData.get("buyAmount");
                BigDecimal mktSell = (BigDecimal) mktData.get("sellAmount");
                BigDecimal mktProfit = mktSell.subtract(mktBuy);
                BigDecimal mktProfitRate = mktBuy.compareTo(BigDecimal.ZERO) > 0
                        ? mktProfit.divide(mktBuy, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        : BigDecimal.ZERO;

                mktData.put("buyAmount", mktBuy.setScale(0, RoundingMode.HALF_UP));
                mktData.put("sellAmount", mktSell.setScale(0, RoundingMode.HALF_UP));
                mktData.put("fee", ((BigDecimal) mktData.get("fee")).setScale(0, RoundingMode.HALF_UP));
                mktData.put("netProfit", mktProfit.setScale(0, RoundingMode.HALF_UP));
                mktData.put("profitRate", mktProfitRate.setScale(2, RoundingMode.HALF_UP) + "%");
                marketList.add(mktData);
            }

            BigDecimal dayNetProfit = daySell.subtract(dayBuy);
            BigDecimal dayProfitRate = dayBuy.compareTo(BigDecimal.ZERO) > 0
                    ? dayNetProfit.divide(dayBuy, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            Map<String, Object> dailySummary = new LinkedHashMap<>();
            dailySummary.put("date", date);
            dailySummary.put("buyCount", buyCount);
            dailySummary.put("sellCount", sellCount);
            dailySummary.put("buyAmount", dayBuy.setScale(0, RoundingMode.HALF_UP));
            dailySummary.put("sellAmount", daySell.setScale(0, RoundingMode.HALF_UP));
            dailySummary.put("fee", dayFee.setScale(0, RoundingMode.HALF_UP));
            dailySummary.put("netProfit", dayNetProfit.setScale(0, RoundingMode.HALF_UP));
            dailySummary.put("profitRate", dayProfitRate.setScale(2, RoundingMode.HALF_UP) + "%");
            dailySummary.put("markets", marketList);

            dailyResults.add(dailySummary);
        }

        // 날짜 내림차순 정렬
        dailyResults.sort((a, b) -> ((String) b.get("date")).compareTo((String) a.get("date")));

        return dailyResults;
    }

    /**
     * 실제 체결 데이터 기반 전체 수익 계산 (일자 그룹핑 없음)
     */
    private Map<String, Object> calculateRealTotalProfit(List<ClosedOrder> orders) {
        BigDecimal totalBuy = BigDecimal.ZERO;
        BigDecimal totalSell = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        int buyCount = 0;
        int sellCount = 0;

        // 마켓별 상세 내역
        Map<String, Map<String, Object>> marketDetails = new LinkedHashMap<>();

        for (ClosedOrder order : orders) {
            BigDecimal executedFunds = order.getExecutedFunds() != null ? order.getExecutedFunds() : BigDecimal.ZERO;
            BigDecimal paidFee = order.getPaidFee() != null ? order.getPaidFee() : BigDecimal.ZERO;
            String market = order.getMarket();

            // 마켓별 집계
            marketDetails.computeIfAbsent(market, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("market", k);
                m.put("buyAmount", BigDecimal.ZERO);
                m.put("sellAmount", BigDecimal.ZERO);
                m.put("fee", BigDecimal.ZERO);
                m.put("buyCount", 0);
                m.put("sellCount", 0);
                return m;
            });

            Map<String, Object> mktData = marketDetails.get(market);

            if ("bid".equals(order.getSide())) {
                totalBuy = totalBuy.add(executedFunds).add(paidFee);
                buyCount++;
                mktData.put("buyAmount", ((BigDecimal) mktData.get("buyAmount")).add(executedFunds).add(paidFee));
                mktData.put("buyCount", (int) mktData.get("buyCount") + 1);
            } else if ("ask".equals(order.getSide())) {
                totalSell = totalSell.add(executedFunds);
                sellCount++;
                mktData.put("sellAmount", ((BigDecimal) mktData.get("sellAmount")).add(executedFunds));
                mktData.put("sellCount", (int) mktData.get("sellCount") + 1);
            }
            totalFee = totalFee.add(paidFee);
            mktData.put("fee", ((BigDecimal) mktData.get("fee")).add(paidFee));
        }

        // 마켓별 수익률 계산
        List<Map<String, Object>> marketList = new ArrayList<>();
        for (Map<String, Object> mktData : marketDetails.values()) {
            BigDecimal mktBuy = (BigDecimal) mktData.get("buyAmount");
            BigDecimal mktSell = (BigDecimal) mktData.get("sellAmount");
            BigDecimal mktProfit = mktSell.subtract(mktBuy);
            BigDecimal mktProfitRate = mktBuy.compareTo(BigDecimal.ZERO) > 0
                    ? mktProfit.divide(mktBuy, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            mktData.put("buyAmount", mktBuy.setScale(0, RoundingMode.HALF_UP));
            mktData.put("sellAmount", mktSell.setScale(0, RoundingMode.HALF_UP));
            mktData.put("fee", ((BigDecimal) mktData.get("fee")).setScale(0, RoundingMode.HALF_UP));
            mktData.put("netProfit", mktProfit.setScale(0, RoundingMode.HALF_UP));
            mktData.put("profitRate", mktProfitRate.setScale(2, RoundingMode.HALF_UP) + "%");
            marketList.add(mktData);
        }

        BigDecimal totalNetProfit = totalSell.subtract(totalBuy);
        BigDecimal totalProfitRate = totalBuy.compareTo(BigDecimal.ZERO) > 0
                ? totalNetProfit.divide(totalBuy, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buyCount", buyCount);
        result.put("sellCount", sellCount);
        result.put("buyAmount", totalBuy.setScale(0, RoundingMode.HALF_UP));
        result.put("sellAmount", totalSell.setScale(0, RoundingMode.HALF_UP));
        result.put("fee", totalFee.setScale(0, RoundingMode.HALF_UP));
        result.put("netProfit", totalNetProfit.setScale(0, RoundingMode.HALF_UP));
        result.put("profitRate", totalProfitRate.setScale(2, RoundingMode.HALF_UP) + "%");
        result.put("markets", marketList);

        return result;
    }
}
