package autostock.taesung.com.autostock.strategy.replay;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ReplayResult {

    // =====================
    // 기본 메타 정보
    // =====================
    private LocalDateTime time;
    private String market;
    private double price;

    // =====================
    // 볼륨 / 통계 지표
    // =====================
    private double z;           // 현재 Z-score
    private double prevZ;       // 이전 Z-score
    private double volume;      // 현재 분 거래량
    private double avgVolume;   // 평균 거래량 (윈도우 기준)
    private double density;     // 캔들 밀도

    // =====================
    // 판단 결과
    // =====================
    private String action;      // IMPULSE / CONFIRM_ENTRY / REBREAK_ENTRY / RESET
    private String reason;      // 실패/판단 사유 (nullable)

    // =====================================================
    // 🔥 하위 호환 Getter (기존 코드 보호용)
    // =====================================================

    /** 기존 컨트롤러 / 로그용 */
    public String getDecision() {
        return action;
    }

    /** 기존 분석 코드용 */
    public double getZScore() {
        return z;
    }

    public double getPrevZScore() {
        return prevZ;
    }

    public double getCurrentVolume() {
        return volume;
    }

    public double getAvgVolume() {
        return avgVolume;
    }

    public double getDensity() {
        return density;
    }

    public String getReason() {
        return reason;
    }
}