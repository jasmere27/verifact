package com.ai.agent.verifact.news;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NewsReviewRepository extends JpaRepository<NewsReviewRecord, UUID> {
}
