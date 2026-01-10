package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 전략 파라미터 동적 조정 엔티티
 * 사용자별, 전략별로 파라미터를 커스터마이즈 가능
 */
@Entity
@Table(name = "strategy_parameters",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "strategy_name", "param_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 사용자 ID (null이면 글로벌 기본값)
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 전략 이름
     */
    @Column(name = "strategy_name", nullable = false, length = 100)
    private String strategyName;

    /**
     * 파라미터 키
     */
    @Column(name = "param_key", nullable = false, length = 100)
    private String paramKey;

    /**
     * 파라미터 값 (문자열로 저장, 타입에 맞게 변환)
     */
    @Column(name = "param_value", nullable = false, length = 500)
    private String paramValue;

    /**
     * 파라미터 타입 (DOUBLE, INTEGER, BOOLEAN, STRING)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "param_type", nullable = false)
    @Builder.Default
    private ParamType paramType = ParamType.DOUBLE;

    /**
     * 파라미터 설명
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 최소값 (숫자 타입 검증용)
     */
    @Column(name = "min_value")
    private Double minValue;

    /**
     * 최대값 (숫자 타입 검증용)
     */
    @Column(name = "max_value")
    private Double maxValue;

    /**
     * 기본값
     */
    @Column(name = "default_value", length = 500)
    private String defaultValue;

    /**
     * 활성화 여부
     */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 생성 일시
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 수정 일시
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ParamType {
        DOUBLE, INTEGER, BOOLEAN, STRING
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Double 값으로 변환
     */
    public Double getAsDouble() {
        try {
            return Double.parseDouble(paramValue);
        } catch (Exception e) {
            return defaultValue != null ? Double.parseDouble(defaultValue) : 0.0;
        }
    }

    /**
     * Integer 값으로 변환
     */
    public Integer getAsInteger() {
        try {
            return Integer.parseInt(paramValue);
        } catch (Exception e) {
            return defaultValue != null ? Integer.parseInt(defaultValue) : 0;
        }
    }

    /**
     * Boolean 값으로 변환
     */
    public Boolean getAsBoolean() {
        try {
            return Boolean.parseBoolean(paramValue);
        } catch (Exception e) {
            return defaultValue != null && Boolean.parseBoolean(defaultValue);
        }
    }
}