package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "impulse_phase_state",
        indexes = @Index(name = "idx_phase_market", columnList = "market", unique = true))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImpulsePhaseState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Phase phase;

    private double peakZ;
    private double peakPrice;

    private LocalDateTime impulseTime;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Phase {
        IDLE,
        IMPULSE,
        CONFIRMED,
        PULLBACK
    }
}