package autostock.taesung.com.autostock.realtrading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 실거래 설정
 * - 모든 수치는 외부 설정으로 관리
 * - 백테스트와 실거래가 동일한 설정 사용
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "realtrading")
public class RealTradingConfig {

    // ==================== 분할 진입 설정 ====================
    /** 1차 진입 비율 (30%) */
    private double entryRatio1 = 0.30;
    /** 2차 진입 비율 (30%) */
    private double entryRatio2 = 0.30;
    /** 3차 진입 비율 (40%) */
    private double entryRatio3 = 0.40;
    /** 2차 진입 조건: 1차 대비 하락률 */
    private double entry2DropPercent = -0.015;  // -1.5%
    /** 3차 진입 조건: 1차 대비 하락률 */
    private double entry3DropPercent = -0.025;  // -2.5%
    /** 2차 진입 조건: 하락 임계값 (양수) */
    private double entry2DropThreshold = 0.015;  // 1.5%
    /** 3차 진입 조건: 하락 임계값 (양수) */
    private double entry3DropThreshold = 0.025;  // 2.5%
    /** 진입 간 최소 대기 시간 (초) */
    private int entryMinIntervalSeconds = 60;
    /** 진입 가격 프리미엄 (체결률 향상용) */
    private double entryPricePremiumPercent = 0.001;  // 0.1%
    /** 최소 신호 강도 */
    private int minSignalStrength = 50;
    /** 기본 전략 이름 */
    private String defaultStrategyName = "ScaledEntryStrategy";

    // ==================== 분할 청산 설정 ====================
    /** 1차 익절 목표 수익률 */
    private double partialTakeProfitRate = 0.025;  // 2.5%
    /** 1차 익절 시 청산 비율 */
    private double partialExitRatio = 0.50;  // 50%
    /** ATR 기반 목표가 배수 */
    private double takeProfitAtrMultiplier = 1.5;
    /** 트레일링 스탑 활성화 수익률 임계값 */
    private double trailingActivationThreshold = 0.03;  // 3%
    /** 트레일링 스탑 활성화 수익률 */
    private double trailingActivationRate = 0.02;  // 2%
    /** ATR 기반 트레일링 배수 */
    private double trailingAtrMultiplier = 2.0;
    /** 고정 트레일링 스탑률 (ATR 없을 때) */
    private double trailingStopRate = 0.015;  // 1.5%

    // ==================== 손절 설정 ====================
    /** ATR 기반 손절 배수 */
    private double stopLossAtrMultiplier = 1.5;
    /** 최대 손절률 (ATR 손절이 이보다 크면 제한) */
    private double maxStopLossRate = -0.03;  // -3%
    /** 최소 손절률 (ATR 손절이 이보다 작으면 제한) */
    private double minStopLossRate = -0.01;  // -1%

    // ==================== 체결 설정 ====================
    /** 슬리피지 허용 한계 (%) */
    private double maxSlippagePercent = 0.003;  // 0.3%
    /** 주문 체결 대기 시간 (초) */
    private int orderTimeoutSeconds = 30;
    /** 주문 상태 폴링 간격 (초) */
    private int orderPollIntervalSeconds = 2;
    /** 체결 실패 시 재시도 횟수 */
    private int orderRetryCount = 2;
    /** 최대 주문 재시도 횟수 */
    private int maxOrderRetries = 2;
    /** 재시도 간 대기 시간 (초) */
    private int orderRetryDelaySeconds = 5;
    /** 재시도 대기 시간 (밀리초) */
    private int retryDelayMs = 5000;
    /** 재시도 시 가격 조정률 */
    private double retryPriceAdjustmentPercent = 0.001;  // 0.1%
    /** 지정가 주문 가격 오프셋 (매수: +, 매도: -) */
    private double limitOrderOffset = 0.001;  // 0.1%
    /** 부분 체결 허용 여부 */
    private boolean acceptPartialFill = true;
    /** 급한 청산 시 가격 할인율 */
    private double urgentExitDiscountPercent = 0.002;  // 0.2%
    /** 일반 청산 시 가격 할인율 */
    private double normalExitDiscountPercent = 0.001;  // 0.1%

    // ==================== 리스크 관리 ====================
    /** 포지션당 최대 손실률 (계좌 대비) */
    private double maxPositionLossRate = 0.01;  // 1%
    /** 일일 최대 손실률 (계좌 대비) */
    private double maxDailyLossRate = 0.03;  // 3%
    /** 연속 손실 허용 횟수 */
    private int maxConsecutiveLosses = 3;
    /** 연속 손실 후 거래 중단 시간 (분) */
    private int cooldownMinutes = 60;
    /** 최대 동시 포지션 수 */
    private int maxConcurrentPositions = 5;
    /** 포지션당 최대 투자 비율 */
    private double maxPositionSizeRate = 0.20;  // 20%

    // ==================== 유동성 체크 ====================
    /** 최소 거래대금 (KRW) */
    private long minTradingVolume = 500_000_000L;  // 5억
    /** 호가 스프레드 최대 허용치 (%) */
    private double maxSpreadPercent = 0.005;  // 0.5%
    /** 주문 수량이 호가 물량의 최대 비율 */
    private double maxOrderToBookRatio = 0.10;  // 10%

    // ==================== ATR 설정 ====================
    /** ATR 계산 기간 */
    private int atrPeriod = 14;
    /** ATR 계산용 캔들 타입 (분) */
    private int atrCandleMinutes = 15;

    // ==================== 캔들 설정 ====================
    /** 분석용 캔들 개수 */
    private int candleCount = 100;
    /** 분석용 캔들 타입 (분) */
    private int candleMinutes = 5;

    /**
     * 분할 진입 비율 검증
     */
    public boolean isValidEntryRatios() {
        double total = entryRatio1 + entryRatio2 + entryRatio3;
        return Math.abs(total - 1.0) < 0.001;
    }

    /**
     * N차 진입 비율 반환
     */
    public double getEntryRatio(int phase) {
        return switch (phase) {
            case 1 -> entryRatio1;
            case 2 -> entryRatio2;
            case 3 -> entryRatio3;
            default -> 0;
        };
    }

    /**
     * N차 진입 조건 하락률 반환
     */
    public double getEntryDropPercent(int phase) {
        return switch (phase) {
            case 2 -> entry2DropPercent;
            case 3 -> entry3DropPercent;
            default -> 0;
        };
    }
}