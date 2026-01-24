package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.ImpulseHourParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ImpulseHourParamRepository extends JpaRepository<ImpulseHourParam, Long> {

    Optional<ImpulseHourParam> findByHour(Integer hour);

    Optional<ImpulseHourParam> findByHourAndEnabledTrue(Integer hour);
}