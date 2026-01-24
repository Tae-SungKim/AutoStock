package autostock.taesung.com.autostock.service.impl;

import autostock.taesung.com.autostock.entity.CandleData;
import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Trade;
import autostock.taesung.com.autostock.repository.CandleDataRepository;
import autostock.taesung.com.autostock.service.MarketVolumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketVolumeServiceImpl implements MarketVolumeService {

    private final CandleDataRepository candleDataRepository;
    private final UpbitApiService upbitApiService;

    private static final int UNIT_1M = 1;
    private static final Set<String> EXCLUDED =
            Set.of("KRW-BTC", "KRW-ETH");

    private volatile double cachedAvg = 0;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedRate = 60_000)
    public void refresh() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            cachedAvg = calculate(30);
        } finally {
            running.set(false);
        }
    }

    @Override
    public double getKrwAltAvgTradeAmount(int minutes) {
        return cachedAvg;
    }

    private double calculate(int minutes) {

        List<String> markets =
                candleDataRepository.findDistinctMarkets()
                        .stream()
                        .filter(m -> m.startsWith("KRW-"))
                        .filter(m -> !EXCLUDED.contains(m))
                        .toList();

        double sum = 0;
        int count = 0;

        for (String market : markets) {

            List<CandleData> candles =
                    candleDataRepository.findRecentMinutes(
                            market,
                            UNIT_1M,
                            PageRequest.of(0, minutes)
                    );

            if (candles.size() < minutes) continue;

            double avg =
                    candles.stream()
                            .mapToDouble(c ->
                                    c.getCandleAccTradePrice().doubleValue()
                            )
                            .average()
                            .orElse(0);

            if (avg > 0) {
                sum += avg;
                count++;
            }
        }

        return count == 0 ? 0 : sum / count;
    }

    @Override
    public double getExecutionStrength(String market, int seconds) {
        try {
            List<Trade> trades = upbitApiService.getTrades(market, 500);
            if (trades == null || trades.isEmpty()) {
                return 50.0;
            }

            long now = System.currentTimeMillis();
            long cutoff = now - (seconds * 1000L);

            double bidVolume = 0;
            double askVolume = 0;

            for (Trade trade : trades) {
                if (trade.getTimestamp() < cutoff) {
                    break;
                }
                double vol = trade.getTradeVolume().doubleValue();
                if (trade.isBid()) {
                    bidVolume += vol;
                } else {
                    askVolume += vol;
                }
            }

            double total = bidVolume + askVolume;
            if (total == 0) {
                return 50.0;
            }

            return (bidVolume / total) * 100;
        } catch (Exception e) {
            log.warn("[{}] 체결강도 조회 실패: {}", market, e.getMessage());
            return 50.0;
        }
    }
}