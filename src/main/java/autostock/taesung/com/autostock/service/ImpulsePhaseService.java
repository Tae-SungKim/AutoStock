package autostock.taesung.com.autostock.service;

import autostock.taesung.com.autostock.entity.ImpulsePhaseState;
import autostock.taesung.com.autostock.repository.ImpulsePhaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImpulsePhaseService {

    private final ImpulsePhaseRepository repo;

    public ImpulsePhaseState get(String market) {
        return repo.findByMarket(market)
                .orElseGet(() -> repo.save(
                        ImpulsePhaseState.builder()
                                .market(market)
                                .phase(ImpulsePhaseState.Phase.IDLE)
                                .build()
                ));
    }

    public void update(ImpulsePhaseState state) {
        repo.save(state);
    }

    public void reset(String market) {
        repo.findByMarket(market).ifPresent(s -> {
            s.setPhase(ImpulsePhaseState.Phase.IDLE);
            repo.save(s);
        });
    }
}
