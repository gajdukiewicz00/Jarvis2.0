package org.jarvis.security.repository;

import org.jarvis.security.model.RevokedToken;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RevokedTokenRepository extends JpaRepository<RevokedToken, UUID> {

    List<RevokedToken> findAllByOrderByRevokedAtDesc(Pageable pageable);
}
