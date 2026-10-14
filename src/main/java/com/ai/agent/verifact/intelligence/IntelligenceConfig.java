package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.intelligence.billing.PlanCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the plan catalog ({@code app.billing.*}). */
@Configuration
@EnableConfigurationProperties(PlanCatalog.class)
public class IntelligenceConfig {
}
