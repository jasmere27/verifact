package com.ai.agent.verifact.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduled jobs, e.g. deleting expired ResearchFact workspaces. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
