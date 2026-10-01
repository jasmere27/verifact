package com.ai.agent.verifact.news;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface NewsReviewRepository extends JpaRepository<NewsReviewRecord, UUID> {

    @Modifying
    @Transactional
    @Query("delete from NewsReviewRecord r where r.updatedAt < :cutoff")
    int deleteUpdatedBefore(OffsetDateTime cutoff);
}
