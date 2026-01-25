package autostock.taesung.com.autostock.api.controller;

import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.exchange.upbit.dto.Market;
import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.strategy.impl.VolumeImpulseStrategy;
import autostock.taesung.com.autostock.strategy.impl.VolumeImpulseStrategy.ReplayResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Impulse 급등 리플레이 분석 API
 *
 * 과거 급등 코인의 분봉 데이터를 기반으로
 * 진입 조건을 시뮬레이션하고 분석합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/impulse/replay")
@RequiredArgsConstructor
public class ImpulseReplayController {

    private final VolumeImpulseStrategy impulseStrategy;
    private final UpbitApiService upbitApiService;

    /**
     * 급등 리플레이 분석 실행
     *
     * @param market 마켓 코드 (예: KRW-XRP)
     * @param count  분봉 개수 (기본 200, 최대 200)
     * @return 분 단위 리플레이 결과
     */
    @GetMapping("/run")
    public ResponseEntity<ReplayResponse> runReplay(
            @RequestParam String market,
            @RequestParam(defaultValue = "200") int count) {

        log.info("[REPLAY_API] market={}, count={}", market, count);

        // 1. 분봉 데이터 조회
        List<Candle> candles = upbitApiService.getMinuteCandles(market, 1, Math.min(count, 200));

        if (candles == null || candles.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ReplayResponse.error("캔들 데이터 조회 실패: " + market));
        }

        // 2. 시간순 정렬 (오름차순)
        candles.sort((a, b) -> {
            String aTime = String.valueOf(a.getCandleDateTimeKst());
            String bTime = String.valueOf(b.getCandleDateTimeKst());
            return aTime.compareTo(bTime);
        });

        // 3. 리플레이 실행
        List<ReplayResult> results = impulseStrategy.runReplay(market, candles);

        // 4. 로그 출력
        impulseStrategy.printReplayLog(market, results);

        // 5. 통계 계산
        long totalMinutes = results.size();
        long entryCount = results.stream().filter(r -> "ENTRY".equals(r.getDecision())).count();
        long fakeFilteredCount = results.stream().filter(r -> "FAKE_FILTERED".equals(r.getDecision())).count();
        long noEntryCount = results.stream().filter(r -> "NO_ENTRY".equals(r.getDecision())).count();

        // 6. Z-score가 높았던 순간들
        List<ReplayResult> highZMoments = results.stream()
                .filter(r -> r.getZScore() > 1.5)
                .sorted((a, b) -> Double.compare(b.getZScore(), a.getZScore()))
                .limit(10)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ReplayResponse.builder()
                .market(market)
                .totalMinutes(totalMinutes)
                .entryCount(entryCount)
                .fakeFilteredCount(fakeFilteredCount)
                .noEntryCount(noEntryCount)
                .results(results)
                .highZMoments(highZMoments)
                .build());
    }

    /**
     * 진입 조건별 필터 통과율 분석
     */
    @GetMapping("/analysis")
    public ResponseEntity<Map<String, Object>> analyzeFilters(
            @RequestParam String market,
            @RequestParam(defaultValue = "200") int count) {

        List<Candle> candles = upbitApiService.getMinuteCandles(market, 1, Math.min(count, 200));

        if (candles == null || candles.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "캔들 데이터 조회 실패"));
        }

        candles.sort((a, b) -> {
            String aTime = String.valueOf(a.getCandleDateTimeKst());
            String bTime = String.valueOf(b.getCandleDateTimeKst());
            return aTime.compareTo(bTime);
        });

        List<ReplayResult> results = impulseStrategy.runReplay(market, candles);

        // 필터별 실패 원인 분석
        Map<String, Long> failReasonCounts = results.stream()
                .filter(r -> !"ENTRY".equals(r.getDecision()))
                .flatMap(r -> {
                    String reason = r.getReason();
                    if (reason == null || reason.equals("-")) return java.util.stream.Stream.empty();
                    return java.util.Arrays.stream(reason.split(","));
                })
                .collect(Collectors.groupingBy(
                        reason -> reason.split("=")[0].split("\\(")[0],
                        Collectors.counting()
                ));

        // Z-score 분포
        Map<String, Long> zScoreDistribution = results.stream()
                .collect(Collectors.groupingBy(
                        r -> {
                            double z = r.getZScore();
                            if (z < 0) return "< 0";
                            if (z < 1) return "0-1";
                            if (z < 1.5) return "1-1.5";
                            if (z < 2) return "1.5-2";
                            if (z < 3) return "2-3";
                            return ">= 3";
                        },
                        Collectors.counting()
                ));

        return ResponseEntity.ok(Map.of(
                "market", market,
                "totalMinutes", results.size(),
                "failReasonCounts", failReasonCounts,
                "zScoreDistribution", zScoreDistribution,
                "avgZScore", results.stream().mapToDouble(ReplayResult::getZScore).average().orElse(0),
                "maxZScore", results.stream().mapToDouble(ReplayResult::getZScore).max().orElse(0),
                "avgVolume", results.stream().mapToDouble(ReplayResult::getCurrentVolume).average().orElse(0),
                "avgDensity", results.stream().mapToDouble(ReplayResult::getDensity).average().orElse(0)
        ));
    }

    /**
     * 전체 KRW 마켓 리플레이 실행
     *
     * @param count 분봉 개수 (기본 200)
     * @param onlyWithEntry true면 진입 신호가 있는 마켓만 반환
     * @return 마켓별 리플레이 요약
     */
    @GetMapping("/run/all")
    public ResponseEntity<AllMarketsReplayResponse> runReplayAllMarkets(
            @RequestParam(defaultValue = "200") int count,
            @RequestParam(defaultValue = "false") boolean onlyWithEntry) {

        log.info("[REPLAY_ALL_API] count={}, onlyWithEntry={}", count, onlyWithEntry);

        // 1. 전체 KRW 마켓 조회
        List<String> allMarkets = getKrwMarkets();
        if (allMarkets == null || allMarkets.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    AllMarketsReplayResponse.error("마켓 목록 조회 실패"));
        }

        log.info("[REPLAY_ALL] 총 {} 마켓 분석 시작", allMarkets.size());

        // 2. 병렬 처리
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<CompletableFuture<MarketReplaySummary>> futures = allMarkets.stream()
                .map(market -> CompletableFuture.supplyAsync(() ->
                        analyzeMarket(market, count), executor))
                .toList();

        // 3. 결과 수집
        List<MarketReplaySummary> summaries = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        executor.shutdown();

        // 4. 필터링 (진입 신호가 있는 마켓만)
        if (onlyWithEntry) {
            summaries = summaries.stream()
                    .filter(s -> s.getEntryCount() > 0)
                    .collect(Collectors.toList());
        }

        // 5. 정렬 (진입 횟수 내림차순)
        summaries.sort((a, b) -> Long.compare(b.getEntryCount(), a.getEntryCount()));

        // 6. 전체 통계
        long totalEntries = summaries.stream().mapToLong(MarketReplaySummary::getEntryCount).sum();
        long marketsWithEntry = summaries.stream().filter(s -> s.getEntryCount() > 0).count();
        double avgMaxZ = summaries.stream().mapToDouble(MarketReplaySummary::getMaxZScore).average().orElse(0);

        log.info("[REPLAY_ALL] 완료: {} 마켓, {} 진입신호, {} 마켓에서 신호 발생",
                summaries.size(), totalEntries, marketsWithEntry);

        return ResponseEntity.ok(AllMarketsReplayResponse.builder()
                .totalMarkets(allMarkets.size())
                .analyzedMarkets(summaries.size())
                .marketsWithEntry(marketsWithEntry)
                .totalEntries(totalEntries)
                .avgMaxZScore(avgMaxZ)
                .summaries(summaries)
                .build());
    }

    /**
     * 급등 감지된 마켓만 조회 (실시간 스캔용)
     */
    @GetMapping("/scan")
    public ResponseEntity<List<MarketReplaySummary>> scanImpulseMarkets(
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(defaultValue = "1.5") double minZScore) {

        log.info("[IMPULSE_SCAN] count={}, minZScore={}", count, minZScore);

        List<String> allMarkets = getKrwMarkets();
        if (allMarkets == null || allMarkets.isEmpty()) {
            return ResponseEntity.badRequest().body(List.of());
        }

        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<CompletableFuture<MarketReplaySummary>> futures = allMarkets.stream()
                .map(market -> CompletableFuture.supplyAsync(() ->
                        analyzeMarket(market, count), executor))
                .toList();

        List<MarketReplaySummary> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .filter(s -> s.getMaxZScore() >= minZScore)
                .sorted((a, b) -> Double.compare(b.getMaxZScore(), a.getMaxZScore()))
                .limit(20)
                .collect(Collectors.toList());

        executor.shutdown();

        log.info("[IMPULSE_SCAN] {} 마켓에서 Z>={} 감지", results.size(), minZScore);

        return ResponseEntity.ok(results);
    }

    /**
     * 현재 급등 중인 마켓 실시간 조회
     */
    @GetMapping("/live")
    public ResponseEntity<List<LiveImpulseResult>> getLiveImpulseMarkets(
            @RequestParam(defaultValue = "1.75") double minZScore) {

        log.info("[LIVE_IMPULSE] minZScore={}", minZScore);

        List<String> allMarkets = getKrwMarkets();
        if (allMarkets == null || allMarkets.isEmpty()) {
            return ResponseEntity.badRequest().body(List.of());
        }

        ExecutorService executor = Executors.newFixedThreadPool(15);
        List<CompletableFuture<LiveImpulseResult>> futures = allMarkets.stream()
                .map(market -> CompletableFuture.supplyAsync(() ->
                        checkLiveImpulse(market), executor))
                .toList();

        List<LiveImpulseResult> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .filter(r -> r.getCurrentZScore() >= minZScore)
                .sorted((a, b) -> Double.compare(b.getCurrentZScore(), a.getCurrentZScore()))
                .collect(Collectors.toList());

        executor.shutdown();

        log.info("[LIVE_IMPULSE] {} 마켓에서 급등 감지 (Z>={})", results.size(), minZScore);

        return ResponseEntity.ok(results);
    }

    // ═══════════════════════════════════════════════════════════════
    // Private 헬퍼 메서드
    // ═══════════════════════════════════════════════════════════════

    private List<String> getKrwMarkets() {
        List<Market> markets = upbitApiService.getMarkets();
        if (markets == null) {
            return Collections.emptyList();
        }
        return markets.stream()
                .map(Market::getMarket)
                .filter(m -> m != null && m.startsWith("KRW-"))
                .collect(Collectors.toList());
    }

    private MarketReplaySummary analyzeMarket(String market, int count) {
        try {
            List<Candle> candles = upbitApiService.getMinuteCandles(market, 1, Math.min(count, 200));
            if (candles == null || candles.size() < 20) {
                return null;
            }

            candles.sort((a, b) -> {
                String aTime = String.valueOf(a.getCandleDateTimeKst());
                String bTime = String.valueOf(b.getCandleDateTimeKst());
                return aTime.compareTo(bTime);
            });

            List<ReplayResult> results = impulseStrategy.runReplay(market, candles);
            if (results.isEmpty()) {
                return null;
            }

            long entryCount = results.stream().filter(r -> "ENTRY".equals(r.getDecision())).count();
            double maxZ = results.stream().mapToDouble(ReplayResult::getZScore).max().orElse(0);
            double avgVol = results.stream().mapToDouble(ReplayResult::getCurrentVolume).average().orElse(0);
            double avgDensity = results.stream().mapToDouble(ReplayResult::getDensity).average().orElse(0);

            // 마지막 Z-score
            ReplayResult lastResult = results.get(results.size() - 1);

            return MarketReplaySummary.builder()
                    .market(market)
                    .totalMinutes(results.size())
                    .entryCount(entryCount)
                    .maxZScore(maxZ)
                    .lastZScore(lastResult.getZScore())
                    .avgVolume(avgVol)
                    .avgDensity(avgDensity)
                    .lastPrice(lastResult.getPrice())
                    .build();

        } catch (Exception e) {
            log.debug("[REPLAY] {} 분석 실패: {}", market, e.getMessage());
            return null;
        }
    }

    private LiveImpulseResult checkLiveImpulse(String market) {
        try {
            List<Candle> candles = upbitApiService.getMinuteCandles(market, 1, 30);
            if (candles == null || candles.size() < 20) {
                return null;
            }

            candles.sort((a, b) -> {
                String aTime = String.valueOf(a.getCandleDateTimeKst());
                String bTime = String.valueOf(b.getCandleDateTimeKst());
                return aTime.compareTo(bTime);
            });

            List<ReplayResult> results = impulseStrategy.runReplay(market, candles);
            if (results.isEmpty()) {
                return null;
            }

            ReplayResult last = results.get(results.size() - 1);

            return LiveImpulseResult.builder()
                    .market(market)
                    .currentZScore(last.getZScore())
                    .prevZScore(last.getPrevZScore())
                    .currentVolume(last.getCurrentVolume())
                    .avgVolume(last.getAvgVolume())
                    .density(last.getDensity())
                    .price(last.getPrice())
                    .decision(last.getDecision())
                    .reason(last.getReason())
                    .build();

        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════

    @Data
    @lombok.Builder
    public static class AllMarketsReplayResponse {
        private String error;
        private int totalMarkets;
        private int analyzedMarkets;
        private long marketsWithEntry;
        private long totalEntries;
        private double avgMaxZScore;
        private List<MarketReplaySummary> summaries;

        public static AllMarketsReplayResponse error(String message) {
            return AllMarketsReplayResponse.builder().error(message).build();
        }
    }

    @Data
    @lombok.Builder
    public static class MarketReplaySummary {
        private String market;
        private long totalMinutes;
        private long entryCount;
        private double maxZScore;
        private double lastZScore;
        private double avgVolume;
        private double avgDensity;
        private double lastPrice;
    }

    @Data
    @lombok.Builder
    public static class LiveImpulseResult {
        private String market;
        private double currentZScore;
        private double prevZScore;
        private double currentVolume;
        private double avgVolume;
        private double density;
        private double price;
        private String decision;
        private String reason;
    }

    @Data
    @lombok.Builder
    public static class ReplayResponse {
        private String market;
        private String error;
        private long totalMinutes;
        private long entryCount;
        private long fakeFilteredCount;
        private long noEntryCount;
        private List<ReplayResult> results;
        private List<ReplayResult> highZMoments;

        public static ReplayResponse error(String message) {
            return ReplayResponse.builder().error(message).build();
        }
    }
}