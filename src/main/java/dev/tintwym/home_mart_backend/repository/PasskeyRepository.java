package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.Passkey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasskeyRepository extends JpaRepository<Passkey, Long> {

    List<Passkey> findByAuthenticatableId(String authenticatableId);

    Optional<Passkey> findByCredentialId(String credentialId);

    void deleteByAuthenticatableIdAndId(String authenticatableId, Long id);
}
