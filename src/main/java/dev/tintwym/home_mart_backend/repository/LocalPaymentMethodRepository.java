package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.LocalPaymentMethod;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalPaymentMethodRepository extends JpaRepository<LocalPaymentMethod, String> {

    List<LocalPaymentMethod> findByUserId(String userId);

    List<LocalPaymentMethod> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<LocalPaymentMethod> findByUserIdAndIsDefaultTrue(String userId);

    void deleteByUserId(String userId);
}
