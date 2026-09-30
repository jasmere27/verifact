package com.ai.agent.verifact.legal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdviceLanguageTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "You have a strong case.", "You'll likely win this.", "You should sue your employer.",
            "You are entitled to back pay.", "Firing someone for complaining is illegal.",
            "The landlord violated the law.", "This is retaliation.", "We guarantee results.",
            "Shows whether Labor Code timing rules were violated.", "Needed to know if deadlines were met.",
            "The claim may be time-barred.", "Needed to calculate damages.", "Photos strengthen factual claims.",
            "The person appears to have grounds for a claim.", "Whether the employer violated overtime rules.",
            "Needed to estimate potential damages.", "Written messages affect the strength of claims.",
            "A reviewer could assess whether the statements meet legal definitions of harassment."})
    void adviceIsRecognised(String sentence) {
        assertThat(AdviceLanguage.isAdvice(sentence)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The person says they were fired on March 20.", "Retaliation protections for workplace complaints",
            "Describes when final wages must be paid.", "A professional may want to review the lease.",
            "The person says they had a claim denied by their insurer.", "The tenant has a case number from small claims court."})
    void neutralWordingIsKept(String sentence) {
        assertThat(AdviceLanguage.isAdvice(sentence)).isFalse();
    }

    @Test
    void onlyTheAdviceSentencesAreRemoved() {
        assertThat(AdviceLanguage.strip("The person was fired in March. You should sue. They were not paid."))
                .isEqualTo("The person was fired in March. They were not paid.");
        assertThat(AdviceLanguage.strip("You have a case.")).isEmpty();
    }
}
