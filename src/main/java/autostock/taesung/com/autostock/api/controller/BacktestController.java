package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.backtest.BacktestService;
import autostock.taesung.com.autostock.backtest.dto.BacktestResult;
import autostock.taesung.com.autostock.backtest.dto.MultiCoinBacktestResult;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;
    private final List<TradingStrategy> strategies;

    /**
     * 백테스팅 실행 (전략 조합 - 다수결)
     *
     * @param market         마켓 코드 (예: KRW-BTC)
     * @param initialBalance 초기 자본금 (기본: 1,000,000원)
     * @param candleUnit     캔들 단위 - 분 (1, 3, 5, 15, 30, 60, 240)
     * @param candleCount    캔들 개수 (최대 200)
     */
    @GetMapping("/run")
    public ResponseEntity<BacktestResult> runBacktest(
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        BacktestResult result = backtestService.runBacktest(market, initialBalance, candleUnit, candleCount);
        return ResponseEntity.ok(result);
    }

    /**
     * 특정 전략으로 백테스팅 실행
     *
     * @param strategy 전략 이름 (RSI Strategy, Golden Cross Strategy, Bollinger Band Strategy)
     */
    @GetMapping("/run/{strategy}")
    public ResponseEntity<BacktestResult> runBacktestWithStrategy(
            @PathVariable String strategy,
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        BacktestResult result = backtestService.runBacktestWithStrategy(
                market, strategy, initialBalance, candleUnit, candleCount);
        return ResponseEntity.ok(result);
    }

    /**
     * 모든 전략 비교 백테스팅
     */
    @GetMapping("/compare")
    public ResponseEntity<List<BacktestResult>> compareAllStrategies(
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        List<BacktestResult> results = backtestService.runAllStrategiesBacktest(
                market, initialBalance, candleUnit, candleCount);
        return ResponseEntity.ok(results);
    }

    /**
     * 전략 비교 요약 (간단한 비교표)
     */
    @GetMapping("/compare/summary")
    public ResponseEntity<Map<String, Object>> compareStrategiesSummary(
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        List<BacktestResult> results = backtestService.runAllStrategiesBacktest(
                market, initialBalance, candleUnit, candleCount);

        List<Map<String, Object>> summary = results.stream().map(r -> {
            Map<String, Object> item = new HashMap<>();
            item.put("strategy", r.getStrategy());
            item.put("totalProfitRate", r.getTotalProfitRate());
            item.put("buyAndHoldRate", r.getBuyAndHoldRate());
            item.put("winRate", r.getWinRate());
            item.put("totalTrades", r.getTotalTrades());
            item.put("maxLossRate", r.getMaxLossRate());
            return item;
        }).collect(Collectors.toList());

        // 가장 좋은 전략 찾기
        BacktestResult bestStrategy = results.stream()
                .max((a, b) -> Double.compare(a.getTotalProfitRate(), b.getTotalProfitRate()))
                .orElse(null);

        Map<String, Object> response = new HashMap<>();
        response.put("market", market);
        response.put("period", results.get(0).getStartDate() + " ~ " + results.get(0).getEndDate());
        response.put("initialBalance", initialBalance);
        response.put("strategies", summary);
        response.put("bestStrategy", bestStrategy != null ? bestStrategy.getStrategy() : null);
        response.put("bestProfitRate", bestStrategy != null ? bestStrategy.getTotalProfitRate() : null);

        return ResponseEntity.ok(response);
    }

    /**
     * 사용 가능한 전략 목록 조회
     */
    @GetMapping("/strategies")
    public ResponseEntity<List<String>> getAvailableStrategies() {
        List<String> strategyNames = strategies.stream()
                .map(TradingStrategy::getStrategyName)
                .collect(Collectors.toList());
        return ResponseEntity.ok(strategyNames);
    }

    /**
     * 멀티 코인 백테스팅 (여러 코인 동시 테스트)
     *
     * @param markets 마켓 코드 (쉼표로 구분, 예: KRW-BTC,KRW-ETH,KRW-XRP)
     * @param strategy 전략 이름 (선택, 미지정 시 조합 전략)
     * @param initialBalancePerMarket 마켓당 초기 자본금
     */
    @GetMapping("/multi")
    public ResponseEntity<MultiCoinBacktestResult> runMultiCoinBacktest(
            @RequestParam(defaultValue = "KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-DOGE") String markets,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "1000000") double initialBalancePerMarket,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        List<String> marketList = Arrays.asList(markets.split(","));
        MultiCoinBacktestResult result = backtestService.runMultiCoinBacktest(
                marketList, strategy, initialBalancePerMarket, candleUnit, candleCount);
        return ResponseEntity.ok(result);
    }

    /**
     * 상위 N개 KRW 마켓 자동 백테스팅
     *
     * @param topN 상위 N개 마켓 (기본 10개)
     */
    @GetMapping("/multi/top")
    public ResponseEntity<MultiCoinBacktestResult> runTopMarketsBacktest(
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "1000000") double initialBalancePerMarket,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        List<String> markets = backtestService.getTopKrwMarkets(topN);
        log.info("상위 {} 마켓 백테스팅: {}", topN, markets);

        MultiCoinBacktestResult result = backtestService.runMultiCoinBacktest(
                markets, strategy, initialBalancePerMarket, candleUnit, candleCount);
        return ResponseEntity.ok(result);
    }

    /**
     * 멀티 코인 + 멀티 전략 비교 (각 전략별로 모든 코인 테스트)
     */
    @GetMapping("/multi/compare")
    public ResponseEntity<Map<String, Object>> compareMultiCoinMultiStrategy(
            @RequestParam(defaultValue = "KRW-BTC,KRW-ETH,KRW-XRP") String markets,
            @RequestParam(defaultValue = "1000000") double initialBalancePerMarket,
            @RequestParam(defaultValue = "10") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        List<String> marketList = Arrays.asList(markets.split(","));
        Map<String, Object> response = new HashMap<>();

        List<Map<String, Object>> strategyResults = strategies.stream().map(strategy -> {
            MultiCoinBacktestResult result = backtestService.runMultiCoinBacktest(
                    marketList, strategy.getStrategyName(), initialBalancePerMarket, candleUnit, candleCount);

            Map<String, Object> item = new HashMap<>();
            item.put("strategy", strategy.getStrategyName());
            item.put("totalProfitRate", result.getTotalProfitRate());
            item.put("averageProfitRate", result.getAverageProfitRate());
            item.put("averageWinRate", result.getAverageWinRate());
            item.put("profitableMarkets", result.getProfitableMarkets());
            item.put("losingMarkets", result.getLosingMarkets());
            item.put("bestMarket", result.getBestMarket());
            item.put("worstMarket", result.getWorstMarket());
            return item;
        }).collect(Collectors.toList());

        // 최고 전략 찾기
        Map<String, Object> bestStrategy = strategyResults.stream()
                .max((a, b) -> Double.compare(
                        (Double) a.get("totalProfitRate"),
                        (Double) b.get("totalProfitRate")))
                .orElse(null);

        response.put("markets", marketList);
        response.put("initialBalancePerMarket", initialBalancePerMarket);
        response.put("strategyResults", strategyResults);
        response.put("bestStrategy", bestStrategy != null ? bestStrategy.get("strategy") : null);
        response.put("bestTotalProfitRate", bestStrategy != null ? bestStrategy.get("totalProfitRate") : null);

        return ResponseEntity.ok(response);
    }

    /**
     * KRW 마켓 목록 조회
     */
    @GetMapping("/markets")
    public ResponseEntity<List<String>> getKrwMarkets(
            @RequestParam(defaultValue = "200") int limit) {
        List<String> markets = backtestService.getTopKrwMarkets(limit);
        return ResponseEntity.ok(markets);
    }

    /**
     * 실제 매매 시뮬레이션 (단일 코인)
     * - 전체 전략 중 과반수 이상 동일 신호 시에만 매매
     * - 손절/익절 로직 포함
     */
    @GetMapping("/simulate")
    public ResponseEntity<BacktestResult> runRealTradingSimulation(
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        log.info("실제 매매 시뮬레이션 요청 - 마켓: {}, 전략 과반수 동의 시 매매", market);
        BacktestResult result = backtestService.runRealTradingSimulation(market, initialBalance, candleUnit, candleCount);
        return ResponseEntity.ok(result);
    }

    /**
     * 실제 매매 시뮬레이션 (멀티 코인)
     * - 전체 전략 중 과반수 이상 동일 신호 시에만 매매
     */
    @GetMapping("/simulate/multi")
    public ResponseEntity<MultiCoinBacktestResult> runMultiCoinRealTradingSimulation(
            @RequestParam(defaultValue = "KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-DOGE") String markets,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "1000000") double initialBalancePerMarket,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        List<String> marketList = Arrays.asList(markets.split(","));
        log.info("멀티 코인 실제 매매 시뮬레이션 요청 - {}개 마켓", marketList.size());

        MultiCoinBacktestResult result = backtestService.runMultiCoinRealTradingSimulation(
                marketList, "BollingerBandStrategy", initialBalancePerMarket, candleUnit, candleCount);
        return ResponseEntity.ok(result);
    }

    /**
     * 실제 매매 시뮬레이션 (상위 N개 마켓)
     *
     * @param strategy 전략 이름 (선택, 미지정 시 과반수 동의 방식)
     */
    @GetMapping("/simulate/top")
    public ResponseEntity<MultiCoinBacktestResult> runTopMarketsRealTradingSimulation(
            @RequestParam(defaultValue = "60") int topN,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "1000000") double initialBalancePerMarket,
            @RequestParam(defaultValue = "1") int candleUnit,
            @RequestParam(defaultValue = "200") int candleCount) {

        List<String> markets = backtestService.getTopKrwMarkets(topN);

        if (strategy != null && !strategy.isEmpty()) {
            log.info("상위 {} 마켓 {} 전략 시뮬레이션: {}", topN, strategy, markets);
            MultiCoinBacktestResult result = backtestService.runMultiCoinBacktest(
                    markets, strategy, initialBalancePerMarket, candleUnit, candleCount);
            return ResponseEntity.ok(result);
        } else {
            log.info("상위 {} 마켓 실제 매매 시뮬레이션 (과반수 동의): {}", topN, markets);
            MultiCoinBacktestResult result = backtestService.runMultiCoinRealTradingSimulation(
                    markets, "BollingerBandStrategy", initialBalancePerMarket, candleUnit, candleCount);
            return ResponseEntity.ok(result);
        }
    }

    /**
     * DB 데이터를 기반으로 한 백테스팅 실행
     */
    @GetMapping("/run/db")
    public ResponseEntity<BacktestResult> runBacktestFromDb(
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(required = false) Integer unit) {

        log.info("DB 데이터 기반 백테스팅 요청 - 마켓: {}", market);
        BacktestResult result = backtestService.runBacktestFromDb(market, initialBalance, unit);
        return ResponseEntity.ok(result);
    }

    /**
     * DB 데이터를 기반으로 한 실제 매매 시뮬레이션
     */
    @GetMapping("/simulate/db")
    public ResponseEntity<BacktestResult> runRealTradingSimulationFromDb(
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(required = false) Integer unit) {

        log.info("DB 데이터 기반 실제 매매 시뮬레이션 요청 - 마켓: {}", market);
        BacktestResult result = backtestService.runRealTradingSimulationFromDb(market, initialBalance, unit);
        return ResponseEntity.ok(result);
    }

    /**
     * DB 데이터를 기반으로 한 단일 전략 백테스팅
     *
     * @param strategy 전략 이름 (예: BollingerBandStrategy)
     */
    @GetMapping("/run/db/{strategy}")
    public ResponseEntity<BacktestResult> runBacktestWithStrategyFromDb(
            @PathVariable String strategy,
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "1000000") double initialBalance,
            @RequestParam(required = false) Integer unit) {

        log.info("DB 데이터 기반 {} 전략 백테스팅 요청 - 마켓: {}", strategy, market);
        BacktestResult result = backtestService.runBacktestWithStrategyFromDb(market, strategy, initialBalance, unit);
        return ResponseEntity.ok(result);
    }

    /**
     * DB 데이터를 기반으로 한 멀티 코인 백테스팅
     *
     * @param markets 마켓 코드 (쉼표로 구분)
     * @param strategy 전략 이름 (선택)
     */
    @GetMapping("/multi/db")
    public ResponseEntity<MultiCoinBacktestResult> runMultiCoinBacktestFromDb(
            @RequestParam(defaultValue = "KRW-BTC,KRW-ETH,KRW-XRP") String markets,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "1000000") double initialBalancePerMarket,
            @RequestParam(required = false) Integer unit,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        List<String> marketList = Arrays.asList(markets.split(","));
        log.info("DB 데이터 기반 멀티 코인 백테스팅 요청 - {}개 마켓, 전략: {}", marketList.size(), strategy);

        MultiCoinBacktestResult result = backtestService.runMultiCoinBacktestFromDb(
                marketList, strategy, initialBalancePerMarket, unit, startDate, endDate);
        return ResponseEntity.ok(result);
    }

    /**
     * DB에 저장된 마켓 목록 조회
     */
    @GetMapping("/markets/db")
    public ResponseEntity<List<String>> getAvailableMarketsFromDb() {
        List<String> markets = backtestService.getAvailableMarketsFromDb();
        return ResponseEntity.ok(markets);
    }
}
