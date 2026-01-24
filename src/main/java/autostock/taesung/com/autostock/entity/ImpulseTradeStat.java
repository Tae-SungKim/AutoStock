package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Impulse 전략 거래 통계 엔티티
 *
 * [목적]
 * - Impulse 전략의 모든 진입/청산 결과를 DB에 저장
 * - 시간대별 승률, 평균 수익률 분석의 기초 데이터
 * - 자동 파라미터 튜닝의 학습 데이터로 활용
 *
 * [데이터 흐름]
 * 1. 진입 시: recordEntry()에서 entry 관련 필드 저장
 * 2. 청산 시: recordExit()에서 exit 관련 필드 + profitRate + success 업데이트
 *
 * [분석 활용]
 * - 시간대(hour)별 승률 분석 → 파라미터 자동 튜닝
 * - exitReason별 통계 → 전략 개선 포인트 파악
 * - entryZScore, entryExecutionStrength 상관관계 → 진입 조건 최적화
 */
@Entity
@Table(name = "impulse_trade_stat", indexes = {
        @Index(name = "idx_impulse_market", columnList = "market"),
        @Index(name = "idx_impulse_entry_time", columnList = "entry_time"),
        @Index(name = "idx_impulse_hour", columnList = "entry_hour")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImpulseTradeStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 마켓 코드 (예: KRW-XRP, KRW-DOGE)
     * - BTC, ETH 제외한 KRW 알트코인만 대상
     */
    @Column(name = "market", nullable = false, length = 20)
    private String market;

    /**
     * 진입 시각
     * - Impulse 매수 신호 발생 시점
     */
    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    /**
     * 청산 시각
     * - 매도 완료 시점
     * - NULL이면 아직 보유 중 (미청산 포지션)
     */
    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    /**
     * 진입 가격
     * - Impulse 신호 발생 시점의 현재가
     */
    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    /**
     * 청산 가격
     * - 실제 매도된 가격
     */
    @Column(name = "exit_price", precision = 20, scale = 8)
    private BigDecimal exitPrice;

    /**
     * 수익률
     * - 계산식: (exitPrice - entryPrice) / entryPrice
     * - 양수: 수익, 음수: 손실
     * - 예: 0.015 = +1.5% 수익
     */
    @Column(name = "profit_rate", precision = 10, scale = 6)
    private BigDecimal profitRate;

    /**
     * 진입 시 거래량 Z-score
     * - 현재 거래량이 평균 대비 몇 표준편차인지
     * - 높을수록 거래량 급증 (2.0 이상이면 강한 신호)
     */
    @Column(name = "entry_z_score", nullable = false, precision = 10, scale = 4)
    private BigDecimal entryZScore;

    /**
     * 진입 시 Z-score 변화량 (Delta Z)
     * - 현재 Z-score - 직전 Z-score
     * - 양수: Z-score 증가 중 (모멘텀 상승)
     */
    @Column(name = "entry_delta_z", nullable = false, precision = 10, scale = 4)
    private BigDecimal entryDeltaZ;

    /**
     * 진입 시 체결강도
     * - 최근 30초 기준: 매수체결량 / 전체체결량 × 100
     * - 65% 이상이면 매수세 우위
     */
    @Column(name = "entry_execution_strength", nullable = false, precision = 10, scale = 2)
    private BigDecimal entryExecutionStrength;

    /**
     * 진입 시 거래량
     * - 해당 1분봉의 누적 거래량
     */
    @Column(name = "entry_volume", precision = 20, scale = 4)
    private BigDecimal entryVolume;

    /**
     * 진입 시각의 시간대 (0~23)
     * - 시간대별 파라미터 튜닝에 활용
     * - 예: 9시~12시는 거래량 많음 → 조건 완화 가능
     */
    @Column(name = "entry_hour", nullable = false)
    private Integer entryHour;

    /**
     * 성공 여부
     * - true: 수익 (profitRate > 0)
     * - false: 손실 (profitRate <= 0)
     * - 승률 계산에 사용
     */
    @Column(name = "success")
    private Boolean success;

    /**
     * 청산 사유
     * - SUCCESS: 목표가 도달 또는 정상 익절
     * - STOP_LOSS: 손절
     * - TRAILING_STOP: 트레일링 스탑
     * - FAKE_IMPULSE: 30초 내 가짜 임펄스 판정
     */
    @Column(name = "exit_reason", length = 50)
    private String exitReason;

    /**
     * 레코드 생성 시각
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        // 진입 시각에서 시간대(hour) 자동 추출
        if (this.entryTime != null) {
            this.entryHour = this.entryTime.getHour();
        }
    }
}