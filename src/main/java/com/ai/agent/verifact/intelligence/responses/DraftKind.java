package com.ai.agent.verifact.intelligence.responses;

/** Response Studio draft types, each with the guidance given to the model. */
public enum DraftKind {
    INTERNAL_BRIEFING("Internal incident briefing", "For the internal team: what is being claimed, where it appeared, what the evidence shows, what is unresolved, recommended actions. Frank and specific."),
    EXECUTIVE_SUMMARY("Executive summary", "Five to eight sentences for leadership: the claim, current evidence position, risk, decision needed. No jargon."),
    HOLDING_STATEMENT("Holding statement", "A short public holding statement (2-4 sentences): acknowledges awareness, commits to looking into it, avoids confirming or denying anything the evidence doesn't settle."),
    CUSTOMER_COMMUNICATION("Customer communication", "A plain, calm message to customers: what they may have seen, what is confirmed, what is not, what they can do, where to get updates."),
    PUBLIC_RESPONSE("Public response draft", "A public statement responding to the claim, stating only what the cited evidence establishes and acknowledging what is still open."),
    CORRECTIVE_INFORMATION("Corrective information", "A factual correction: the claim as circulated, what the cited sources actually say, links. Only for points the evidence contradicts or puts in context."),
    FAQ("Frequently asked questions", "Five to eight Q&A pairs that customers or journalists may ask, answered only from the evidence; unresolved points answered honestly as open."),
    ESCALATION_CHECKLIST("Escalation checklist", "A checklist for the response team: who to inform (roles, not names), what to verify, which primary sources to contact, decision points, monitoring follow-ups.");

    private final String label;
    private final String guidance;

    DraftKind(String label, String guidance) {
        this.label = label;
        this.guidance = guidance;
    }

    public String label() {
        return label;
    }

    String guidance() {
        return guidance;
    }
}
