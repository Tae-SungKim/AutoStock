package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.UserUpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Account;
import autostock.taesung.com.autostock.exchange.upbit.dto.OrderResponse;
import autostock.taesung.com.autostock.exchange.upbit.dto.Ticker;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 포트폴리오 리밸런싱 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RebalanceService {

    private final UserUpbitApiService upbitApiService;

    private static final double MIN_ORDER_AMOUNT = 5000;  // 최소 주문 금액
    private static final double REBALANCE_THRESHOLD = 3.0;  // 리밸런싱 임계값 (3%)

    /**
     * 목표 배분 설정
     */
    @Data
    @Builder
    public static class TargetAllocation {
        private String market;
        private double targetPercent;  // 목표 비율 (%)
    }

    /**
     * 현재 포트폴리오 상태
     */
    @Data
    @Builder
    public static class PortfolioStatus {
        private double totalAsset;
        private double krwBalance;
        private double krwPercent;
        private List<AssetAllocation> allocations;
    }

    /**
     * 자산 배분 상태
     */
    @Data
    @Builder
    public static class AssetAllocation {
        private String market;
        private String currency;
        private double balance;
        private double currentPrice;
        private double evaluationAmount;
        private double currentPercent;
        private double targetPercent;
        private double deviation;  // 목표 대비 편차
        private String action;     // BUY, SELL, HOLD
        private double actionAmount;  // 매수/매도 금액
    }

    /**
     * 리밸런싱 계획
     */
    @Data
    @Builder
    public static class RebalancePlan {
        private PortfolioStatus currentStatus;
        private List<RebalanceAction> actions;
        private double totalBuyAmount;
        private double totalSellAmount;
        private boolean executable;
        private String message;
    }

    /**
     * 리밸런싱 실행 액션
     */
    @Data
    @Builder
    public static class RebalanceAction {
        private String market;
        private String actionType;  // BUY, SELL
        private double amount;      // 금액
        private double volume;      // 수량
        private int priority;       // 실행 우선순위 (매도 먼저)
    }

    /**
     * 리밸런싱 결과
     */
    @Data
    @Builder
    public static class RebalanceResult {
        private boolean success;
        private int executedCount;
        private int failedCount;
        private List<OrderResponse> orders;
        private String message;
    }

    /**
     * 현재 포트폴리오 상태 조회
     */
    public PortfolioStatus getPortfolioStatus(User user, List<TargetAllocation> targets) {
        try {
            List<Account> accounts = upbitApiService.getAccounts(user);

            double krwBalance = 0;
            Map<String, Account> coinAccounts = new HashMap<>();

            for (Account account : accounts) {
                if ("KRW".equals(account.getCurrency())) {
                    krwBalance = Double.parseDouble(account.getBalance());
                } else {
                    coinAccounts.put("KRW-" + account.getCurrency(), account);
                }
            }

            // 현재가 조회
            Set<String> allMarkets = new HashSet<>(coinAccounts.keySet());
            targets.forEach(t -> allMarkets.add(t.getMarket()));

            Map<String, Double> priceMap = new HashMap<>();
            if (!allMarkets.isEmpty()) {
                String marketsParam = String.join(",", allMarkets);
                List<Ticker> tickers = upbitApiService.getTicker(marketsParam);
                tickers.forEach(t -> priceMap.put(t.getMarket(), t.getTradePrice().doubleValue()));
            }

            // 총 자산 계산
            double totalCoinValue = 0;
            for (Map.Entry<String, Account> entry : coinAccounts.entrySet()) {
                Double price = priceMap.get(entry.getKey());
                if (price != null) {
                    totalCoinValue += Double.parseDouble(entry.getValue().getBalance()) * price;
                }
            }
            double totalAsset = krwBalance + totalCoinValue;

            // 목표 배분 맵
            Map<String, Double> targetMap = targets.stream()
                    .collect(Collectors.toMap(TargetAllocation::getMarket, TargetAllocation::getTargetPercent));

            // 자산별 배분 계산
            List<AssetAllocation> allocations = new ArrayList<>();

            for (String market : allMarkets) {
                Account account = coinAccounts.get(market);
                Double price = priceMap.get(market);
                double targetPercent = targetMap.getOrDefault(market, 0.0);

                double balance = account != null ? Double.parseDouble(account.getBalance()) : 0;
                double evaluation = price != null ? balance * price : 0;
                double currentPercent = totalAsset > 0 ? (evaluation / totalAsset) * 100 : 0;
                double deviation = currentPercent - targetPercent;

                String action = "HOLD";
                double actionAmount = 0;
                if (Math.abs(deviation) > REBALANCE_THRESHOLD) {
                    if (deviation > 0) {
                        action = "SELL";
                        actionAmount = (deviation / 100) * totalAsset;
                    } else {
                        action = "BUY";
                        actionAmount = Math.abs(deviation / 100) * totalAsset;
                    }
                }

                allocations.add(AssetAllocation.builder()
                        .market(market)
                        .currency(market.replace("KRW-", ""))
                        .balance(balance)
                        .currentPrice(price != null ? price : 0)
                        .evaluationAmount(Math.round(evaluation))
                        .currentPercent(Math.round(currentPercent * 100.0) / 100.0)
                        .targetPercent(targetPercent)
                        .deviation(Math.round(deviation * 100.0) / 100.0)
                        .action(action)
                        .actionAmount(Math.round(actionAmount))
                        .build());
            }

            // 평가금액 기준 정렬
            allocations.sort((a, b) -> Double.compare(b.getEvaluationAmount(), a.getEvaluationAmount()));

            return PortfolioStatus.builder()
                    .totalAsset(Math.round(totalAsset))
                    .krwBalance(Math.round(krwBalance))
                    .krwPercent(Math.round((krwBalance / totalAsset) * 10000.0) / 100.0)
                    .allocations(allocations)
                    .build();

        } catch (Exception e) {
            log.error("포트폴리오 상태 조회 오류: {}", e.getMessage());
            return PortfolioStatus.builder()
                    .totalAsset(0)
                    .allocations(new ArrayList<>())
                    .build();
        }
    }

    /**
     * 리밸런싱 계획 생성
     */
    public RebalancePlan createRebalancePlan(User user, List<TargetAllocation> targets) {
        PortfolioStatus status = getPortfolioStatus(user, targets);

        List<RebalanceAction> actions = new ArrayList<>();
        double totalBuy = 0;
        double totalSell = 0;

        // 매도 먼저, 매수 나중에 (우선순위)
        int priority = 1;

        // 매도 액션
        for (AssetAllocation alloc : status.getAllocations()) {
            if ("SELL".equals(alloc.getAction()) && alloc.getActionAmount() >= MIN_ORDER_AMOUNT) {
                double volume = alloc.getActionAmount() / alloc.getCurrentPrice();
                actions.add(RebalanceAction.builder()
                        .market(alloc.getMarket())
                        .actionType("SELL")
                        .amount(alloc.getActionAmount())
                        .volume(volume)
                        .priority(priority++)
                        .build());
                totalSell += alloc.getActionAmount();
            }
        }

        // 매수 액션
        for (AssetAllocation alloc : status.getAllocations()) {
            if ("BUY".equals(alloc.getAction()) && alloc.getActionAmount() >= MIN_ORDER_AMOUNT) {
                actions.add(RebalanceAction.builder()
                        .market(alloc.getMarket())
                        .actionType("BUY")
                        .amount(alloc.getActionAmount())
                        .volume(0)  // 시장가 매수는 금액 기준
                        .priority(priority++)
                        .build());
                totalBuy += alloc.getActionAmount();
            }
        }

        // 실행 가능 여부 확인
        double availableKrw = status.getKrwBalance() + totalSell;
        boolean executable = actions.isEmpty() || availableKrw >= totalBuy;
        String message = executable ?
                (actions.isEmpty() ? "리밸런싱이 필요하지 않습니다." : "리밸런싱 준비 완료") :
                "KRW 잔고 부족 (필요: " + totalBuy + ", 가용: " + availableKrw + ")";

        return RebalancePlan.builder()
                .currentStatus(status)
                .actions(actions)
                .totalBuyAmount(Math.round(totalBuy))
                .totalSellAmount(Math.round(totalSell))
                .executable(executable)
                .message(message)
                .build();
    }

    /**
     * 리밸런싱 실행
     */
    public RebalanceResult executeRebalance(User user, RebalancePlan plan) {
        if (!plan.isExecutable()) {
            return RebalanceResult.builder()
                    .success(false)
                    .executedCount(0)
                    .failedCount(0)
                    .orders(new ArrayList<>())
                    .message("실행 불가: " + plan.getMessage())
                    .build();
        }

        List<OrderResponse> orders = new ArrayList<>();
        int executedCount = 0;
        int failedCount = 0;

        // 우선순위 순으로 정렬 (매도 먼저)
        List<RebalanceAction> sortedActions = plan.getActions().stream()
                .sorted(Comparator.comparingInt(RebalanceAction::getPriority))
                .collect(Collectors.toList());

        for (RebalanceAction action : sortedActions) {
            try {
                OrderResponse order;

                if ("SELL".equals(action.getActionType())) {
                    order = upbitApiService.sellMarketOrderWithConfirm(
                            user, action.getMarket(), action.getVolume());
                } else {
                    order = upbitApiService.buyMarketOrderWithConfirm(
                            user, action.getMarket(), action.getAmount());
                }

                if (order != null && order.isDone()) {
                    orders.add(order);
                    executedCount++;
                    log.info("[리밸런싱] {} {} 체결 완료: {}",
                            action.getActionType(), action.getMarket(), action.getAmount());
                } else {
                    failedCount++;
                    log.warn("[리밸런싱] {} {} 체결 실패",
                            action.getActionType(), action.getMarket());
                }

                // API 속도 제한
                Thread.sleep(200);

            } catch (Exception e) {
                failedCount++;
                log.error("[리밸런싱] {} {} 오류: {}",
                        action.getActionType(), action.getMarket(), e.getMessage());
            }
        }

        boolean success = failedCount == 0;
        String message = success ?
                "리밸런싱 완료 (" + executedCount + "건 체결)" :
                "리밸런싱 부분 완료 (성공: " + executedCount + ", 실패: " + failedCount + ")";

        return RebalanceResult.builder()
                .success(success)
                .executedCount(executedCount)
                .failedCount(failedCount)
                .orders(orders)
                .message(message)
                .build();
    }

    /**
     * 균등 배분 목표 생성 (KRW 제외)
     */
    public List<TargetAllocation> createEqualAllocation(List<String> markets, double krwReservePercent) {
        if (markets.isEmpty()) return new ArrayList<>();

        double perCoinPercent = (100 - krwReservePercent) / markets.size();

        return markets.stream()
                .map(market -> TargetAllocation.builder()
                        .market(market)
                        .targetPercent(Math.round(perCoinPercent * 100.0) / 100.0)
                        .build())
                .collect(Collectors.toList());
    }
}