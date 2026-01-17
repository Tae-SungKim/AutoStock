package autostock.taesung.com.autostock.strategy.impl;

import autostock.taesung.com.autostock.backtest.dto.BacktestPosition;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.exchange.upbit.UpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.Candle;
import autostock.taesung.com.autostock.exchange.upbit.dto.Orderbook;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.strategy.TechnicalIndicator;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BollingerBandGPTStrategy implements TradingStrategy {

    /* ==========================
     * 파라미터
     * ========================== */
    private static final int BB_PERIOD = 20;
    private static final double BB_MULT = 2.0;

    private static final int RSI_PERIOD = 7;
    private static final int ATR_PERIOD = 10;

    private static final double STOP_LOSS_ATR_MULT = 2.5;
    private static final double TAKE_PROFIT_ATR_MULT = 1.8;

    private static final int MIN_HOLD_MINUTES = 2;

    /* ==========================
     * 업비트 수수료
     * ========================== */
    private static final double FEE_RATE = 0.0005; // 0.05%

    /* ==========================
     * 의존성
     * ========================== */
    private final TechnicalIndicator indicator;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final UpbitApiService upbitApiService;

    @Override
    public int analyze(List<Candle> candles) {
        return analyze("UNKNOWN", candles);
    }

    /* ========================================================= */
    @Override
    public int analyze(String market, List<Candle> candles) {
        TradeHistory latest = tradeHistoryRepository.findLatestByMarket(market)
                .stream().findFirst().orElse(null);

        boolean holding = latest != null && latest.getTradeType() == TradeHistory.TradeType.BUY;
        double buyPrice = holding ? latest.getPrice().doubleValue() : 0;
        LocalDateTime buyTime = holding ? latest.getCreatedAt() : null;

        LocalDateTime candleTime = parseCandleTime(candles.get(0).getCandleDateTimeKst());

        return analyzeCore(market, candles, holding, buyPrice, buyTime, candleTime, false);
    }

    @Override
    public int analyzeForBacktest(String market, List<Candle> candles, BacktestPosition position) {
        boolean holding = position != null && position.isHolding();
        double buyPrice = holding ? position.getBuyPrice() : 0;
        LocalDateTime buyTime = holding ? position.getBuyTime() : null;

        LocalDateTime candleTime = parseCandleTime(candles.get(0).getCandleDateTimeKst());

        return analyzeCore(market, candles, holding, buyPrice, buyTime, candleTime, true);
    }

    /* =========================================================
     * 핵심 로직
     * ========================================================= */
    private int analyzeCore(
            String market,
            List<Candle> candles,
            boolean holding,
            double buyPrice,
            LocalDateTime buyTime,
            LocalDateTime candleTime,
            boolean backtest
    ) {
        if (candles.size() < 30) return 0;

        double currentPrice = candles.get(0).getTradePrice().doubleValue();

        double rsi = calculateRSI(candles, RSI_PERIOD);
        double atr = calculateATR(candles, ATR_PERIOD);

        double[] bands = indicator.calculateBollingerBands(candles, BB_PERIOD, BB_MULT);
        double middleBand = bands[0];
        double bandWidthPct = (bands[1] - bands[2]) / middleBand * 100;

        /* ==========================
         * 매도 로직 (수수료 반영)
         * ========================== */
        if (holding) {
            long holdingMinutes = Duration.between(buyTime, candleTime).toMinutes();

            double realBuyPrice = buyPrice * (1 + FEE_RATE);
            double realSellPrice = currentPrice * (1 - FEE_RATE);

            double stopLossPrice =
                    (buyPrice - atr * STOP_LOSS_ATR_MULT) * (1 - FEE_RATE);
            double takeProfitPrice =
                    (buyPrice + atr * TAKE_PROFIT_ATR_MULT) * (1 - FEE_RATE);

            if (holdingMinutes >= MIN_HOLD_MINUTES) {
                if (realSellPrice <= stopLossPrice) return -1;
                if (realSellPrice >= takeProfitPrice) return -1;
            }
            return 0;
        }

        /* ==========================
         * 진입 조건
         * ========================== */
        if (bandWidthPct < 0.7) return 0;
        if (!isHigherLowStructure(candles)) return 0;
        if (rsi > 65) return 0;
        if (currentPrice < middleBand * 0.995) return 0;

        /* ==========================
         * 실거래 전용 호가 검증
         * ========================== */
        if (!backtest) {
            if (!validateOrderbook(market)) return 0;
        }

        return 1;
    }

    /* ==========================
     * 시간 파싱 (String → LocalDateTime)
     * ========================== */
    private LocalDateTime parseCandleTime(String candleTimeKst) {
        return LocalDateTime.parse(candleTimeKst);
    }

    /* ==========================
     * 보조 메서드
     * ========================== */
    private boolean validateOrderbook(String market) {
        try {
            Orderbook ob = upbitApiService.getOrderbook(market);
            if (ob == null) return false;

            double bid = ob.getBidPrice(0);
            double ask = ob.getAskPrice(0);
            double spread = (ask - bid) / bid;

            return spread <= 0.003;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHigherLowStructure(List<Candle> candles) {
        return candles.get(0).getLowPrice().doubleValue()
                > candles.get(1).getLowPrice().doubleValue();
    }

    private double calculateRSI(List<Candle> candles, int period) {
        double gain = 0, loss = 0;
        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getTradePrice().doubleValue()
                    - candles.get(i + 1).getTradePrice().doubleValue();
            if (diff > 0) gain += diff;
            else loss -= diff;
        }
        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - (100 / (1 + rs));
    }

    private double calculateATR(List<Candle> candles, int period) {
        double sum = 0;
        for (int i = 0; i < period; i++) {
            double h = candles.get(i).getHighPrice().doubleValue();
            double l = candles.get(i).getLowPrice().doubleValue();
            double pc = candles.get(i + 1).getTradePrice().doubleValue();
            sum += Math.max(h - l,
                    Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / period;
    }

    @Override
    public String getStrategyName() {
        return "BollingerBandGPTStrategy";
    }
}