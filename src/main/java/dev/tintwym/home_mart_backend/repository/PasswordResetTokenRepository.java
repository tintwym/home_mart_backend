package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.PasswordResetToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    Optional<PasswordResetToken> findByEmail(String email);

    Optional<PasswordResetToken> findByEmailAndToken(String email, String token);

    void deleteByEmail(String email);
}
