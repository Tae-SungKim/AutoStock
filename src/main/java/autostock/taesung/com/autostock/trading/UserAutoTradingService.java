package autostock.taesung.com.autostock.trading;

import autostock.taesung.com.autostock.entity.CandleData;
import autostock.taesung.com.autostock.entity.TickerData;
import autostock.taesung.com.autostock.entity.TradeHistory;
import autostock.taesung.com.autostock.entity.TradeHistory.TradeType;
import autostock.taesung.com.autostock.entity.User;
import autostock.taesung.com.autostock.exchange.upbit.UserUpbitApiService;
import autostock.taesung.com.autostock.exchange.upbit.dto.*;
import autostock.taesung.com.autostock.repository.CandleDataRepository;
import autostock.taesung.com.autostock.repository.TickerDataRepository;
import autostock.taesung.com.autostock.repository.TradeHistoryRepository;
import autostock.taesung.com.autostock.repository.UserRepository;
import autostock.taesung.com.autostock.service.UserStrategyService;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 사용자별 자동매매 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAutoTradingService {

    private final UserUpbitApiService upbitApiService;
    private final List<TradingStrategy> allStrategies;  // 모든 전략
    private final TradeHistoryRepository tradeHistoryRepository;
    private final UserRepository userRepository;
    private final UserStrategyService userStrategyService;
    private final CandleDataRepository candleDataRepository;
    private final TickerDataRepository tickerDataRepository;

    private static final double UPBIT_FEE_RATE = 0.0005;
    private static final int PERIOD = 100;
    // 최소 거래량 설정 (KRW 기준)
    private static final double MIN_TRADE_VOLUME_KRW = 200_000_000;  // 5억원 이상

    @Value("${trading.target-market:KRW-BTC}")
    private String defaultTargetMarket;

    @Value("${trading.target-markets:}")
    private String targetMarketsStr;

    @Value("${trading.excluded-markets:}")
    private String excludedMarketsStr;

    @Value("${trading.multi-market-enabled:false}")
    private boolean multiMarketEnabled;

    @Value("${trading.auto-select-top:0}")
    private int autoSelectTop;

    @Value("${trading.investment-ratio:0.1}")
    private double investmentRatio;

    @Value("${trading.min-order-amount:5000}")
    private double minOrderAmount;

    private final int minuteInterval = 1;
    private final int candleCount = 100;

    /**
     * 특정 사용자의 자동매매 실행
     */
    public void executeAutoTradingForUser(User user) {
        if (user.getUpbitAccessKey() == null || user.getUpbitSecretKey() == null) {
            log.warn("[{}] Upbit API 키가 없습니다.", user.getUsername());
            return;
        }

        List<String> excludedMarkets = parseExcludedMarkets();
        List<String> markets = getTopKrwMarkets(200, excludedMarkets);

        if (markets.isEmpty()) {
            log.warn("[{}] 거래 가능한 마켓이 없습니다.", user.getUsername());
            return;
        }

        log.info("========== [{}] 자동매매 시작 ==========", user.getUsername());
        log.info("대상 마켓 {}개: {}", markets.size(), markets);

        for (String market : markets) {
            try {
                executeAutoTradingForMarket(user, market, excludedMarkets);
                Thread.sleep(250);
            } catch (Exception e) {
                log.error("[{}][{}] 자동매매 실행 중 오류: {}", user.getUsername(), market, e.getMessage());
            }
        }

        log.info("========== [{}] 자동매매 종료 ==========\n", user.getUsername());
    }

    private List<String> parseExcludedMarkets() {
        List<String> excludedMarkets = new ArrayList<>();
        if (excludedMarketsStr != null && !excludedMarketsStr.trim().isEmpty()) {
            excludedMarkets = Arrays.stream(excludedMarketsStr.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());
        }
        return excludedMarkets;
    }

    private boolean isMarketAllowed(String market, List<String> excludedMarkets) {
        return !excludedMarkets.contains(market.toUpperCase());
    }

    private List<String> getActiveMarkets(List<String> excludedMarkets) {
        if (!multiMarketEnabled) {
            if (isMarketAllowed(defaultTargetMarket, excludedMarkets)) {
                return List.of(defaultTargetMarket);
            }
            return List.of();
        }

        if (autoSelectTop > 0) {
            try {
                List<Market> markets = upbitApiService.getMarkets();
                return markets.stream()
                        .filter(m -> m.getMarket().startsWith("KRW-"))
                        .filter(m -> !"CAUTION".equals(m.getMarketWarning()))
                        .map(Market::getMarket)
                        .filter(m -> isMarketAllowed(m, excludedMarkets))
                        .limit(autoSelectTop)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("마켓 목록 조회 실패: {}", e.getMessage());
            }
        }

        if (targetMarketsStr != null && !targetMarketsStr.trim().isEmpty()) {
            return Arrays.stream(targetMarketsStr.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .filter(m -> isMarketAllowed(m, excludedMarkets))
                    .collect(Collectors.toList());
        }

        return isMarketAllowed(defaultTargetMarket, excludedMarkets)
                ? List.of(defaultTargetMarket)
                : List.of();
    }

    /**
     * KRW 마켓 상위 코인 목록 조회
     */
    public List<String> getTopKrwMarkets(int limit, List<String> excludedMarkets) {
        try {
            List<Market> markets = upbitApiService.getMarkets();
            return markets.stream()
                    .filter(m -> m.getMarket().startsWith("KRW-"))
                    .filter(m -> !"CAUTION".equals(m.getMarketWarning()))  // 유의 종목 제외
                    .map(Market::getMarket)
                    .filter(m -> isMarketAllowed(m, excludedMarkets))
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("마켓 목록 조회 실패: {}", e.getMessage());
            // 기본 마켓 반환
            return List.of("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE");
        }
    }

    private void executeAutoTradingForMarket(User user, String market, List<String> excludedMarkets) {
        if (!isMarketAllowed(market, excludedMarkets)) {
            return;
        }

        //log.info("----- [{}][{}] 분석 시작 -----", user.getUsername(), market);

        try {
            // DB에 데이터가 충분한지 확인하여 API 호출 갯수 조절
            int fetchCount = candleCount;
            Optional<CandleData> lastCandle = candleDataRepository.findFirstByMarketAndUnitOrderByCandleDateTimeKstDesc(market, minuteInterval);
            if (lastCandle.isEmpty()) {
                // 데이터가 전혀 없으면 200개 가져옴
                fetchCount = candleCount;
            } else {
                // 이미 데이터가 있다면 최근 1~2개만 가져와서 업데이트해도 됨
                // 하지만 전략 분석 로직이 List<Candle>을 200개 기대하므로,
                // 분석용으로는 200개를 유지하되 DB 저장 로직에서 중복을 효율적으로 스킵함
                // (만약 API 호출 자체를 줄이고 싶다면 분석 로직을 DB 기반으로 바꿔야 함)
                fetchCount = candleCount;
            }

            // 1. 캔들 데이터 조회
            List<Candle> candles = upbitApiService.getMinuteCandles(market, minuteInterval, fetchCount);
            /*if (candles == null || candles.size() < PERIOD) {
                log.warn("[{}][{}] 캔들 데이터 부족", user.getUsername(), market);
                return;
            }
            double totalTradeVolume = candles.subList(0, PERIOD).stream()
                    .mapToDouble(c -> c.getCandleAccTradePrice().doubleValue())
                    .sum();
                    */

            // 캔들 데이터 DB 저장 (최신 데이터 위주로 저장, 중복 제외)
            saveCandlesToDb(candles);
            // 최소거래량 설정. (3 동안 거래 평균액 )
            List<Ticker> tickers = upbitApiService.getTicker(market);
            double currentPrice = tickers.get(0).getTradePrice().doubleValue();

            // 현재가 DB 저장
            saveTickerToDb(tickers.get(0));
            //log.info("[{}][{}] 현재가: {}", user.getUsername(), market, String.format("%.4f", currentPrice));

            // 사용자가 선택한 전략만 가져오기
            List<TradingStrategy> userStrategies = userStrategyService.getEnabledTradingStrategies(user.getId());

            if (userStrategies.isEmpty()) {
                log.info("[{}][{}] 활성화된 전략이 없습니다.", user.getUsername(), market);
                return;
            }

            int buySignals = 0;
            int sellSignals = 0;
            List<String> buyStrategies = new ArrayList<>();
            List<String> sellStrategies = new ArrayList<>();
            Double targetPrice = null;

            for (TradingStrategy strategy : userStrategies) {
                try {
                    int signal = strategy.analyze(market, candles);
                    if (signal == 1) {
                        buySignals++;
                        buyStrategies.add(strategy.getStrategyName());
                        Double strategyTarget = strategy.getTargetPrice(market);
                        if (strategyTarget != null && targetPrice == null) {
                            targetPrice = strategyTarget;
                        }
                    } else if (signal == -1) {
                        sellSignals++;
                        sellStrategies.add(strategy.getStrategyName());
                    }
                } catch (Exception e) {
                    // 분석 실패 무시
                }
            }

            log.info("[{}][{}] 전략 분석 - 매수: {}/{}, 매도: {}/{}",
                    user.getUsername(), market, buySignals, userStrategies.size(), sellSignals, userStrategies.size());

            // 과반수 이상 동의 시 매매 실행
            int threshold = (userStrategies.size() / 2) + 1;

            if (buySignals >= threshold) {
                log.info("[{}][{}] 매수 신호! 동의 전략: {}", user.getUsername(), market, buyStrategies);
                executeBuyForMarket(user, market, currentPrice, String.join(", ", buyStrategies), targetPrice);
            } else if (sellSignals >= threshold) {
                log.info("[{}][{}] 매도 신호! 동의 전략: {}", user.getUsername(), market, sellStrategies);
                executeSellForMarket(user, market, currentPrice, String.join(", ", sellStrategies));
            } else {
                //log.info("[{}][{}] 관망 - 매매 조건 미충족", user.getUsername(), market);
            }

        } catch (Exception e) {
            log.error("[{}][{}] 분석 중 오류: {}", user.getUsername(), market, e.getMessage());
        }
    }

    private void executeBuyForMarket(User user, String market, double currentPrice,
                                      String strategyName, Double targetPrice) {
        try {
            double krwBalance = upbitApiService.getKrwBalance(user);

            List<String> activeMarkets = getActiveMarkets(parseExcludedMarkets());
            /*double marketRatio = multiMarketEnabled && activeMarkets.size() > 1
                    ? investmentRatio / activeMarkets.size()
                    : investmentRatio;*/
            double marketRatio = investmentRatio;

            double orderAmount = krwBalance * marketRatio;

            log.info("[{}][{}] KRW 잔고: {}, 주문 금액: {}",
                    user.getUsername(), market,
                    String.format("%.0f", krwBalance),
                    String.format("%.0f", orderAmount));

            if (orderAmount < minOrderAmount) {
                log.warn("[{}][{}] 주문 금액이 최소 주문 금액({})보다 작습니다.",
                        user.getUsername(), market, minOrderAmount);
                orderAmount = minOrderAmount;
            }

            OrderResponse order = upbitApiService.buyMarketOrder(user, market, orderAmount);
            log.info("[{}][{}] 매수 주문 완료! UUID: {}", user.getUsername(), market, order.getUuid());

            saveTradeHistory(user, market, TradeType.BUY, orderAmount, currentPrice,
                    order.getUuid(), strategyName, targetPrice);

        } catch (Exception e) {
            log.error("[{}][{}] 매수 실행 실패: {}", user.getUsername(), market, e.getMessage());
        }
    }

    private void executeSellForMarket(User user, String market, double currentPrice, String strategyName) {
        try {
            String currency = market.split("-")[1];
            double coinBalance = upbitApiService.getCoinBalance(user, currency);

            log.info("[{}][{}] {} 보유량: {}", user.getUsername(), market, currency, coinBalance);

            if (coinBalance <= 0) {
                log.warn("[{}][{}] 매도할 코인이 없습니다.", user.getUsername(), market);
                return;
            }

            OrderResponse order = upbitApiService.sellMarketOrder(user, market, coinBalance);
            log.info("[{}][{}] 매도 주문 완료! UUID: {}", user.getUsername(), market, order.getUuid());

            double sellAmount = coinBalance * currentPrice;
            saveTradeHistory(user, market, TradeType.SELL, sellAmount, currentPrice,
                    order.getUuid(), strategyName, null);

            // 전략별 포지션 청산
            for (TradingStrategy strategy : allStrategies) {
                strategy.clearPosition(market);
            }

        } catch (Exception e) {
            log.error("[{}][{}] 매도 실행 실패: {}", user.getUsername(), market, e.getMessage());
        }
    }

    private void saveTradeHistory(User user, String market, TradeType tradeType, double amount,
                                   double price, String orderUuid, String strategyName, Double targetPrice) {
        try {
            double volume = amount / price;
            double fee = amount * UPBIT_FEE_RATE;

            TradeHistory history = TradeHistory.builder()
                    .userId(user.getId())
                    .market(market)
                    .tradeMethod("MARKET")
                    .tradeDate(LocalDate.now())
                    .tradeTime(LocalTime.now())
                    .tradeType(tradeType)
                    .amount(BigDecimal.valueOf(amount))
                    .volume(BigDecimal.valueOf(volume))
                    .price(BigDecimal.valueOf(price))
                    .fee(BigDecimal.valueOf(fee))
                    .orderUuid(orderUuid)
                    .strategyName(strategyName)
                    .targetPrice(targetPrice != null ? BigDecimal.valueOf(targetPrice) : null)
                    .highestPrice(tradeType == TradeType.BUY ? BigDecimal.valueOf(price) : null)  // 매수 시 최고가 초기화
                    .build();

            tradeHistoryRepository.save(history);
            log.info("[{}][{}] 거래 내역 저장 완료 - {}, 금액: {}, 수수료: {}",
                    user.getUsername(), market, tradeType,
                    String.format("%.0f", amount), String.format("%.0f", fee));

        } catch (Exception e) {
            log.error("[{}][{}] 거래 내역 저장 실패: {}", user.getUsername(), market, e.getMessage());
        }
    }

    /**
     * 캔들 데이터 DB 저장 (중복 제외)
     * 최신 데이터부터 확인하여 중복이 발견되면 중단을 고려할 수 있으나,
     * API 응답 순서가 최신순이므로 효율적으로 처리
     */
    private void saveCandlesToDb(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return;
        }

        try {
            // 업비트 API는 최신 캔들이 리스트의 0번에 위치함
            int savedCount = 0;
            for (int i = 0; i < candles.size(); i++) {
                Candle candle = candles.get(i);

                // 이미 존재하는 캔들인지 확인 (시장가와 KST 시간 기준)
                // 리스트의 0번부터 확인하므로, 이미 존재하는 데이터를 만나면 그 이후(과거 데이터)는 이미 저장되어 있을 확률이 높음
                if (candleDataRepository.findByMarketAndCandleDateTimeKst(candle.getMarket(), candle.getCandleDateTimeKst()).isPresent()) {
                    // 1분봉 데이터 적재 시, 연속된 데이터라면 중복 발견 시 중단하여 성능 최적화
                    // 단, 200개를 가져오는데 그 중 중간에 비어있을 가능성이 아주 낮으므로 break 허용
                    break;
                }

                CandleData candleData = CandleData.builder()
                        .market(candle.getMarket())
                        .candleDateTimeUtc(candle.getCandleDateTimeUtc())
                        .candleDateTimeKst(candle.getCandleDateTimeKst())
                        .openingPrice(candle.getOpeningPrice())
                        .highPrice(candle.getHighPrice())
                        .lowPrice(candle.getLowPrice())
                        .tradePrice(candle.getTradePrice())
                        .timestamp(candle.getTimestamp())
                        .candleAccTradePrice(candle.getCandleAccTradePrice())
                        .candleAccTradeVolume(candle.getCandleAccTradeVolume())
                        .unit(candle.getUnit())
                        .build();

                candleDataRepository.save(candleData);
                savedCount++;
            }
            if (savedCount > 0) {
                log.info("[{}] 신규 캔들 데이터 {}건 저장 완료", candles.get(0).getMarket(), savedCount);
            }
        } catch (Exception e) {
            log.error("캔들 데이터 DB 저장 중 오류: {}", e.getMessage());
        }
    }

    /**
     * Ticker 데이터 DB 저장
     */
    private void saveTickerToDb(Ticker ticker) {
        try {
            TickerData tickerData = TickerData.builder()
                    .market(ticker.getMarket())
                    .tradeDate(ticker.getTradeDate())
                    .tradeTime(ticker.getTradeTime())
                    .tradeDateKst(ticker.getTradeDateKst())
                    .tradeTimeKst(ticker.getTradeTimeKst())
                    .tradeTimestamp(ticker.getTradeTimestamp())
                    .openingPrice(ticker.getOpeningPrice())
                    .highPrice(ticker.getHighPrice())
                    .lowPrice(ticker.getLowPrice())
                    .tradePrice(ticker.getTradePrice())
                    .prevClosingPrice(ticker.getPrevClosingPrice())
                    .change(ticker.getChange())
                    .changePrice(ticker.getChangePrice())
                    .changeRate(ticker.getChangeRate())
                    .timestamp(ticker.getTimestamp())
                    .build();

            tickerDataRepository.save(tickerData);
        } catch (Exception e) {
            log.error("[{}] Ticker 데이터 DB 저장 중 오류: {}", ticker.getMarket(), e.getMessage());
        }
    }


    /**
     * 사용자 보유 현황 조회
     */
    public void printAccountStatus(User user) {
        log.info("========== [{}] 보유 현황 ==========", user.getUsername());
        try {
            List<Account> accounts = upbitApiService.getAccounts(user);
            for (Account account : accounts) {
                if (Double.parseDouble(account.getBalance()) > 0) {
                    log.info("{}: {} (평균 매수가: {})",
                            account.getCurrency(),
                            account.getBalance(),
                            account.getAvgBuyPrice());
                }
            }
        } catch (Exception e) {
            log.error("[{}] 보유 현황 조회 실패: {}", user.getUsername(), e.getMessage());
        }
        log.info("==============================\n");
    }

}