package autostock.taesung.com.autostock.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 사용자 엔티티
 * 로그인 정보 및 Upbit API 키 저장
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 사용자 아이디
     */
    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    /**
     * 비밀번호 (암호화 저장)
     */
    @Column(name = "password", nullable = false, length = 255)
    private String password;

    /**
     * 이메일
     */
    @Column(name = "email", length = 100)
    private String email;

    /**
     * Upbit Access Key
     */
    @Column(name = "upbit_access_key", length = 255)
    private String upbitAccessKey;

    /**
     * Upbit Secret Key
     */
    @Column(name = "upbit_secret_key", length = 255)
    private String upbitSecretKey;

    /**
     * 자동매매 활성화 여부
     */
    @Column(name = "auto_trading_enabled", nullable = false)
    @Builder.Default
    private Boolean autoTradingEnabled = false;

    /**
     * 계정 활성화 여부
     */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 사용자 역할 (USER, ADMIN)
     */
    @Column(name = "role", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Role role = Role.USER;

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

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 사용자 역할 enum
     */
    public enum Role {
        USER,
        ADMIN
    }

    // UserDetails 구현
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}