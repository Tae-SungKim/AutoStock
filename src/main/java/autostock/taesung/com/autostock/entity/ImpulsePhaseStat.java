package autostock.taesung.com.autostock.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "impulse_phase_stat")
@Getter
@Setter
public class ImpulsePhaseStat {

    @Id
    @GeneratedValue
    private Long id;

    private String phase;
    private int tradeCount;
    private double avgProfit;
    private double winRate;
}