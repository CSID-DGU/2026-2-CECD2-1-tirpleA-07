package com.capstone.triplea.advertisement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdPostRepository extends JpaRepository<AdPost, Long> {

    Optional<AdPost> findByEventId(Long eventId);
}
