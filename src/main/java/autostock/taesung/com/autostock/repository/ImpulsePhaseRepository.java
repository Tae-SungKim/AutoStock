package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.ImpulsePhaseState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImpulsePhaseRepository
        extends JpaRepository<ImpulsePhaseState, Long> {

    /**
     * 마켓별 Phase 상태 조회
     */
    Optional<ImpulsePhaseState> findByMarket(String market);

    /**
     * 마켓별 Phase 상태 존재 여부
     */
    boolean existsByMarket(String market);

    /**
     * 마켓별 Phase 상태 삭제 (필요 시 초기화용)
     */
    void deleteByMarket(String market);
}