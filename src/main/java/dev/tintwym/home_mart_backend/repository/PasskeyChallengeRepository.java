package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.PasskeyChallenge;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasskeyChallengeRepository extends JpaRepository<PasskeyChallenge, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PasskeyChallenge c WHERE c.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
