package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.intelligence.billing.BillingProvider;
import com.ai.agent.verifact.intelligence.monitoring.SourceConnector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Which integrations this deployment has configured; the UI disables what isn't available instead of failing later. */
@Component
public class CapabilitiesProbe {

    private final List<SourceConnector> connectors;
    private final BillingProvider billing;
    private final boolean scheduler;
    private final boolean cron;

    public CapabilitiesProbe(List<SourceConnector> connectors, BillingProvider billing,
                             @Value("${app.monitoring.scheduler-enabled:true}") boolean scheduler,
                             @Value("${app.monitoring.cron-secret:}") String cronSecret) {
        this.connectors = connectors;
        this.billing = billing;
        this.scheduler = scheduler;
        this.cron = cronSecret != null && !cronSecret.isBlank();
    }

    public Map<String, Object> describe() {
        Map<String, Boolean> sources = new LinkedHashMap<>();
        connectors.forEach(c -> sources.put(c.id(), c.available()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sources", sources);
        out.put("onlinePayment", billing.configured());
        out.put("billingProvider", billing.name());
        out.put("scheduler", scheduler);
        out.put("externalCron", cron);
        out.put("emailNotifications", false);
        return out;
    }
}
