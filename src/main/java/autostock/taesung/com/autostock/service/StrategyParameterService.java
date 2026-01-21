package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.StrategyParameter;
import autostock.taesung.com.autostock.repository.StrategyParameterRepository;
import autostock.taesung.com.autostock.strategy.TradingStrategy;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 전략 파라미터 동적 조정 서비스
 */
@Slf4j
@Service
public class StrategyParameterService {

    private final StrategyParameterRepository parameterRepository;
    private final List<TradingStrategy> strategies;

    public StrategyParameterService(StrategyParameterRepository parameterRepository, @Lazy List<TradingStrategy> strategies) {
        this.parameterRepository = parameterRepository;
        this.strategies = strategies;
    }

    /**
     * 파라미터 정의
     */
    @Data
    @Builder
    public static class ParameterDefinition {
        private String key;
        private String name;
        private String description;
        private StrategyParameter.ParamType type;
        private String defaultValue;
        private Double minValue;
        private Double maxValue;
    }

    /**
     * 파라미터 값 DTO
     */
    @Data
    @Builder
    public static class ParameterValue {
        private String key;
        private String value;
        private StrategyParameter.ParamType type;
        private String description;
        private Double minValue;
        private Double maxValue;
        private String defaultValue;
        private boolean isCustom;  // 사용자 커스텀 여부
    }

    /**
     * 전략별 파라미터 정의 (하드코딩된 기본 정의)
     */
    private static final Map<String, List<ParameterDefinition>> STRATEGY_PARAMS = new HashMap<>();

    static {
        // BollingerBandStrategy 파라미터
        STRATEGY_PARAMS.put("BollingerBandStrategy", Arrays.asList(
                // ===== 기본 볼린저밴드 설정 =====
                ParameterDefinition.builder()
                        .key("bollinger.period").name("볼린저 기간").description("볼린저 밴드 계산 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("20")
                        .minValue(5.0).maxValue(100.0).build(),
                ParameterDefinition.builder()
                        .key("bollinger.multiplier").name("표준편차 배수").description("볼린저 밴드 폭 계수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("2.0")
                        .minValue(1.0).maxValue(4.0).build(),

                // ===== RSI 설정 =====
                ParameterDefinition.builder()
                        .key("rsi.period").name("RSI 기간").description("RSI 계산 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("14")
                        .minValue(5.0).maxValue(50.0).build(),
                ParameterDefinition.builder()
                        .key("rsi.oversold").name("RSI 매수").description("매수 신호 기준 RSI 값")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("30")
                        .minValue(10.0).maxValue(50.0).build(),
                ParameterDefinition.builder()
                        .key("rsi.overbought").name("RSI 매도").description("매도 신호 기준 RSI 값")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("70")
                        .minValue(50.0).maxValue(90.0).build(),

                // ===== 거래량 설정 =====
                ParameterDefinition.builder()
                        .key("volume.threshold").name("거래량 증가율").description("거래량 증가 기준 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("120")
                        .minValue(50.0).maxValue(500.0).build(),

                // ===== 손절/익절 기본 설정 =====
                ParameterDefinition.builder()
                        .key("stopLoss.rate").name("손절률").description("손절 기준 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("-2.5")
                        .minValue(-10.0).maxValue(-0.5).build(),
                ParameterDefinition.builder()
                        .key("takeProfit.rate").name("익절률").description("익절 기준 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("2.0")
                        .minValue(1.0).maxValue(20.0).build(),
                ParameterDefinition.builder()
                        .key("trailingStop.rate").name("트레일링 스탑").description("고점 대비 하락 매도 기준 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("1.5")
                        .minValue(0.5).maxValue(10.0).build(),

                // ===== 캔들 기반 설정 =====
                ParameterDefinition.builder()
                        .key("stopLoss.cooldownCandles").name("손절 쿨다운").description("손절 후 재진입 대기 캔들 수")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("5")
                        .minValue(1.0).maxValue(20.0).build(),
                ParameterDefinition.builder()
                        .key("minHold.candles").name("최소 보유 캔들").description("최소 보유 캔들 수")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("3")
                        .minValue(1.0).maxValue(20.0).build(),

                // ===== ATR 기반 손익 설정 =====
                ParameterDefinition.builder()
                        .key("stopLoss.atrMult").name("ATR 손절 배수").description("ATR 기반 손절 배수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("2.0")
                        .minValue(0.5).maxValue(5.0).build(),
                ParameterDefinition.builder()
                        .key("takeProfit.atrMult").name("ATR 익절 배수").description("ATR 기반 익절 배수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("2.5")
                        .minValue(1.0).maxValue(10.0).build(),
                ParameterDefinition.builder()
                        .key("trailingStop.atrMult").name("트레일링 ATR 배수").description("트레일링 스탑 ATR 배수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("1.5")
                        .minValue(0.5).maxValue(5.0).build(),
                ParameterDefinition.builder()
                        .key("maxStopLoss.rate").name("최대 손절률").description("ATR 손절 최대 제한 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.03")
                        .minValue(0.01).maxValue(0.1).build(),

                // ===== 슬리피지 및 수수료 =====
                ParameterDefinition.builder()
                        .key("slippage.rate").name("슬리피지").description("슬리피지 비율")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.0015")
                        .minValue(0.0).maxValue(0.01).build(),
                ParameterDefinition.builder()
                        .key("fee.rate").name("수수료").description("거래 수수료 비율")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.0005")
                        .minValue(0.0).maxValue(0.01).build(),
                ParameterDefinition.builder()
                        .key("total.cost").name("총 비용").description("슬리피지 + 수수료 합계")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.002")
                        .minValue(0.0).maxValue(0.02).build(),
                ParameterDefinition.builder()
                        .key("minProfit.rate").name("최소 수익률").description("익절 시 최소 수익률")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.006")
                        .minValue(0.0).maxValue(0.05).build(),

                // ===== 호가창 검증 설정 =====
                ParameterDefinition.builder()
                        .key("orderbook.maxSpreadRate").name("최대 스프레드").description("진입 허용 최대 스프레드 비율")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.003")
                        .minValue(0.001).maxValue(0.01).build(),
                ParameterDefinition.builder()
                        .key("orderbook.minBidImbalance").name("최소 매수세").description("최소 매수세 불균형 비율")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.55")
                        .minValue(0.4).maxValue(0.8).build(),
                ParameterDefinition.builder()
                        .key("orderbook.maxPriceDiffRate").name("최대 가격 괴리").description("진입 허용 최대 가격 괴리")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.005")
                        .minValue(0.001).maxValue(0.02).build(),

                // ===== Fast Breakout 설정 =====
                ParameterDefinition.builder()
                        .key("fastBreakout.upperMult").name("FB 상단밴드 배수").description("Fast Breakout 상단밴드 돌파 배수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("1.002")
                        .minValue(1.0).maxValue(1.02).build(),
                ParameterDefinition.builder()
                        .key("fastBreakout.volumeMult").name("FB 거래량 배수").description("Fast Breakout 평균 거래대금 배수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("2.5")
                        .minValue(1.5).maxValue(5.0).build(),
                ParameterDefinition.builder()
                        .key("fastBreakout.rsiMin").name("FB RSI 최소").description("Fast Breakout RSI 최소값")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("55.0")
                        .minValue(40.0).maxValue(70.0).build(),

                // ===== 급등 차단 및 추격 매수 방지 =====
                ParameterDefinition.builder()
                        .key("highVolume.threshold").name("고거래량 기준").description("급등 차단 예외 거래량 배수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("2.0")
                        .minValue(1.5).maxValue(5.0).build(),
                ParameterDefinition.builder()
                        .key("chasePrevention.rate").name("추격 매수 방지").description("하단밴드 대비 이탈 제한 비율")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.035")
                        .minValue(0.01).maxValue(0.1).build(),

                // ===== 밴드폭 및 ATR 필터 =====
                ParameterDefinition.builder()
                        .key("bandWidth.minPercent").name("최소 밴드폭").description("진입 허용 최소 밴드폭 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.8")
                        .minValue(0.3).maxValue(3.0).build(),
                ParameterDefinition.builder()
                        .key("atr.candleMoveMult").name("ATR 캔들 이동 배수").description("급등 차단 ATR 대비 캔들 이동 배수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("0.8")
                        .minValue(0.5).maxValue(2.0).build()
        ));

        // RSIStrategy 파라미터
        STRATEGY_PARAMS.put("RSIStrategy", Arrays.asList(
                ParameterDefinition.builder()
                        .key("rsi.period").name("RSI 기간").description("RSI 계산 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("14")
                        .minValue(5.0).maxValue(50.0).build(),
                ParameterDefinition.builder()
                        .key("rsi.buyThreshold").name("매수 RSI").description("매수 신호 RSI 기준")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("30")
                        .minValue(10.0).maxValue(50.0).build(),
                ParameterDefinition.builder()
                        .key("rsi.sellThreshold").name("매도 RSI").description("매도 신호 RSI 기준")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("70")
                        .minValue(50.0).maxValue(90.0).build()
        ));

        // MACDStrategy 파라미터
        STRATEGY_PARAMS.put("MACDStrategy", Arrays.asList(
                ParameterDefinition.builder()
                        .key("macd.fastPeriod").name("빠른 EMA").description("빠른 지수이동평균 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("12")
                        .minValue(5.0).maxValue(30.0).build(),
                ParameterDefinition.builder()
                        .key("macd.slowPeriod").name("느린 EMA").description("느린 지수이동평균 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("26")
                        .minValue(15.0).maxValue(50.0).build(),
                ParameterDefinition.builder()
                        .key("macd.signalPeriod").name("시그널 기간").description("시그널 라인 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("9")
                        .minValue(3.0).maxValue(20.0).build()
        ));

        // MovingAverageStrategy 파라미터
        STRATEGY_PARAMS.put("MovingAverageStrategy", Arrays.asList(
                ParameterDefinition.builder()
                        .key("ma.shortPeriod").name("단기 이평").description("단기 이동평균 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("5")
                        .minValue(3.0).maxValue(20.0).build(),
                ParameterDefinition.builder()
                        .key("ma.longPeriod").name("장기 이평").description("장기 이동평균 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("20")
                        .minValue(10.0).maxValue(100.0).build()
        ));

        // DataDrivenStrategy 파라미터
        STRATEGY_PARAMS.put("DataDrivenStrategy", Arrays.asList(
                ParameterDefinition.builder()
                        .key("bollinger.period").name("볼린저 기간").description("볼린저 밴드 계산 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("20")
                        .minValue(5.0).maxValue(100.0).build(),
                ParameterDefinition.builder()
                        .key("bollinger.multiplier").name("표준편차 배수").description("볼린저 밴드 폭 계수")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("2.0")
                        .minValue(1.0).maxValue(4.0).build(),
                ParameterDefinition.builder()
                        .key("rsi.period").name("RSI 기간").description("RSI 계산 기간")
                        .type(StrategyParameter.ParamType.INTEGER).defaultValue("14")
                        .minValue(5.0).maxValue(50.0).build(),
                ParameterDefinition.builder()
                        .key("rsi.oversold").name("RSI 매수").description("매수 신호 기준 RSI 값")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("30")
                        .minValue(10.0).maxValue(50.0).build(),
                ParameterDefinition.builder()
                        .key("rsi.overbought").name("RSI 매도").description("매도 신호 기준 RSI 값")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("70")
                        .minValue(50.0).maxValue(90.0).build(),
                ParameterDefinition.builder()
                        .key("volume.threshold").name("거래량 증가율").description("거래량 증가 기준 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("100")
                        .minValue(50.0).maxValue(500.0).build(),
                ParameterDefinition.builder()
                        .key("stopLoss.rate").name("손절률").description("손절 기준 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("-2.5")
                        .minValue(-10.0).maxValue(-0.5).build(),
                ParameterDefinition.builder()
                        .key("takeProfit.rate").name("익절률").description("익절 기준 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("3.0")
                        .minValue(1.0).maxValue(20.0).build(),
                ParameterDefinition.builder()
                        .key("trailingStop.rate").name("트레일링 스탑").description("고점 대비 하락 매도 기준 (%)")
                        .type(StrategyParameter.ParamType.DOUBLE).defaultValue("1.5")
                        .minValue(0.5).maxValue(10.0).build()
        ));

    }

    /**
     * 초기화 - 글로벌 기본 파라미터 생성
     */
    @PostConstruct
    public void initializeDefaultParameters() {
        log.info("전략 파라미터 초기화 시작...");

        for (Map.Entry<String, List<ParameterDefinition>> entry : STRATEGY_PARAMS.entrySet()) {
            String strategyName = entry.getKey();

            for (ParameterDefinition def : entry.getValue()) {
                Optional<StrategyParameter> existing = parameterRepository
                        .findByUserIdIsNullAndStrategyNameAndParamKey(strategyName, def.getKey());

                if (existing.isEmpty()) {
                    StrategyParameter param = StrategyParameter.builder()
                            .userId(null)  // 글로벌
                            .strategyName(strategyName)
                            .paramKey(def.getKey())
                            .paramValue(def.getDefaultValue())
                            .paramType(def.getType())
                            .description(def.getDescription())
                            .minValue(def.getMinValue())
                            .maxValue(def.getMaxValue())
                            .defaultValue(def.getDefaultValue())
                            .enabled(true)
                            .build();

                    parameterRepository.save(param);
                    log.debug("파라미터 생성: {}.{}", strategyName, def.getKey());
                }
            }
        }

        log.info("전략 파라미터 초기화 완료");
    }

    /**
     * 전략별 파라미터 조회 (글로벌 + 사용자 오버라이드 병합)
     */
    public Map<String, Object> getEffectiveParameters(String strategyName, Long userId) {
        Map<String, Object> params = new HashMap<>();

        // 글로벌 파라미터 로드
        List<StrategyParameter> globalParams = parameterRepository
                .findByUserIdIsNullAndStrategyName(strategyName);
        for (StrategyParameter p : globalParams) {
            params.put(p.getParamKey(), convertValue(p));
        }

        // 사용자 오버라이드 적용
        if (userId != null) {
            List<StrategyParameter> userParams = parameterRepository
                    .findByUserIdAndStrategyNameAndEnabledTrue(userId, strategyName);
            for (StrategyParameter p : userParams) {
                params.put(p.getParamKey(), convertValue(p));
            }
        }

        return params;
    }

    /**
     * 파라미터 상세 목록 조회 (UI용)
     */
    public List<ParameterValue> getParameterDetails(String strategyName, Long userId) {
        List<ParameterValue> result = new ArrayList<>();

        // 글로벌 파라미터
        List<StrategyParameter> globalParams = parameterRepository
                .findByUserIdIsNullAndStrategyName(strategyName);
        Map<String, StrategyParameter> globalMap = globalParams.stream()
                .collect(Collectors.toMap(StrategyParameter::getParamKey, p -> p));

        // 사용자 파라미터
        Map<String, StrategyParameter> userMap = new HashMap<>();
        if (userId != null) {
            List<StrategyParameter> userParams = parameterRepository
                    .findByUserIdAndStrategyName(userId, strategyName);
            userMap = userParams.stream()
                    .collect(Collectors.toMap(StrategyParameter::getParamKey, p -> p));
        }

        // 병합
        for (StrategyParameter global : globalParams) {
            StrategyParameter userOverride = userMap.get(global.getParamKey());
            StrategyParameter effective = userOverride != null ? userOverride : global;

            result.add(ParameterValue.builder()
                    .key(effective.getParamKey())
                    .value(effective.getParamValue())
                    .type(effective.getParamType())
                    .description(effective.getDescription())
                    .minValue(effective.getMinValue())
                    .maxValue(effective.getMaxValue())
                    .defaultValue(global.getParamValue())
                    .isCustom(userOverride != null)
                    .build());
        }

        return result;
    }

    /**
     * 사용자 파라미터 설정
     */
    @Transactional
    public StrategyParameter setUserParameter(Long userId, String strategyName,
                                               String paramKey, String paramValue) {
        // 값 검증
        Optional<StrategyParameter> globalParam = parameterRepository
                .findByUserIdIsNullAndStrategyNameAndParamKey(strategyName, paramKey);

        if (globalParam.isEmpty()) {
            throw new IllegalArgumentException("존재하지 않는 파라미터: " + strategyName + "." + paramKey);
        }

        StrategyParameter template = globalParam.get();
        validateValue(template, paramValue);

        // 기존 사용자 파라미터 조회 또는 생성
        Optional<StrategyParameter> existing = parameterRepository
                .findByUserIdAndStrategyNameAndParamKey(userId, strategyName, paramKey);

        StrategyParameter userParam;
        if (existing.isPresent()) {
            userParam = existing.get();
            userParam.setParamValue(paramValue);
        } else {
            userParam = StrategyParameter.builder()
                    .userId(userId)
                    .strategyName(strategyName)
                    .paramKey(paramKey)
                    .paramValue(paramValue)
                    .paramType(template.getParamType())
                    .description(template.getDescription())
                    .minValue(template.getMinValue())
                    .maxValue(template.getMaxValue())
                    .defaultValue(template.getDefaultValue())
                    .enabled(true)
                    .build();
        }

        log.info("사용자 파라미터 설정: user={}, {}.{} = {}",
                userId, strategyName, paramKey, paramValue);

        return parameterRepository.save(userParam);
    }

    /**
     * 사용자 파라미터 일괄 설정
     */
    @Transactional
    public void setUserParameters(Long userId, String strategyName, Map<String, String> params) {
        for (Map.Entry<String, String> entry : params.entrySet()) {
            setUserParameter(userId, strategyName, entry.getKey(), entry.getValue());
        }
    }

    /**
     * 사용자 파라미터 초기화 (기본값으로)
     */
    @Transactional
    public void resetUserParameters(Long userId, String strategyName) {
        parameterRepository.deleteByUserIdAndStrategyName(userId, strategyName);
        log.info("사용자 파라미터 초기화: user={}, strategy={}", userId, strategyName);
    }

    /**
     * 특정 사용자 파라미터 삭제
     */
    @Transactional
    public void deleteUserParameter(Long userId, String strategyName, String paramKey) {
        parameterRepository.deleteByUserIdAndStrategyNameAndParamKey(userId, strategyName, paramKey);
        log.info("사용자 파라미터 삭제: user={}, {}.{}", userId, strategyName, paramKey);
    }

    /**
     * 사용 가능한 전략 목록
     */
    public List<String> getAvailableStrategies() {
        return strategies.stream()
                .map(TradingStrategy::getStrategyName)
                .collect(Collectors.toList());
    }

    /**
     * 전략별 기본 파라미터 정의 조회
     */
    public List<ParameterDefinition> getParameterDefinitions(String strategyName) {
        return STRATEGY_PARAMS.getOrDefault(strategyName, Collections.emptyList());
    }

    /**
     * 특정 파라미터 값 조회 (Double)
     */
    public double getDoubleParam(String strategyName, Long userId, String paramKey, double defaultValue) {
        Map<String, Object> params = getEffectiveParameters(strategyName, userId);
        Object value = params.get(paramKey);
        if (value instanceof Double) {
            return (Double) value;
        } else if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    /**
     * 특정 파라미터 값 조회 (Integer)
     */
    public int getIntParam(String strategyName, Long userId, String paramKey, int defaultValue) {
        Map<String, Object> params = getEffectiveParameters(strategyName, userId);
        Object value = params.get(paramKey);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private Object convertValue(StrategyParameter param) {
        switch (param.getParamType()) {
            case DOUBLE:
                return param.getAsDouble();
            case INTEGER:
                return param.getAsInteger();
            case BOOLEAN:
                return param.getAsBoolean();
            default:
                return param.getParamValue();
        }
    }

    private void validateValue(StrategyParameter template, String value) {
        if (template.getParamType() == StrategyParameter.ParamType.DOUBLE ||
            template.getParamType() == StrategyParameter.ParamType.INTEGER) {

            double numValue;
            try {
                numValue = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("숫자 형식이 아닙니다: " + value);
            }

            if (template.getMinValue() != null && numValue < template.getMinValue()) {
                throw new IllegalArgumentException(
                        "최소값(" + template.getMinValue() + ")보다 작습니다: " + value);
            }
            if (template.getMaxValue() != null && numValue > template.getMaxValue()) {
                throw new IllegalArgumentException(
                        "최대값(" + template.getMaxValue() + ")보다 큽니다: " + value);
            }
        }
    }
}