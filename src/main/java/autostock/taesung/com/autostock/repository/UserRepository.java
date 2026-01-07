package autostock.taesung.com.autostock.repository;

import autostock.taesung.com.autostock.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 사용자명으로 조회
     */
    Optional<User> findByUsername(String username);

    /**
     * 이메일로 조회
     */
    Optional<User> findByEmail(String email);

    /**
     * 사용자명 존재 여부
     */
    boolean existsByUsername(String username);

    /**
     * 이메일 존재 여부
     */
    boolean existsByEmail(String email);

    /**
     * 자동매매 활성화된 사용자 목록
     */
    List<User> findByAutoTradingEnabledTrue();

    /**
     * 활성화된 사용자 중 자동매매 활성화된 사용자
     */
    List<User> findByEnabledTrueAndAutoTradingEnabledTrue();
}