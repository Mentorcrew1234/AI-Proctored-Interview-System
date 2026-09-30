package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;

/**
 * The interview language, as the AI layer sees it.
 *
 * <p>The rule being pinned here is honesty rather than capability. The enum
 * currently holds one value, and this project does not claim to interview in
 * any other language. What matters is that the architecture <em>carries</em>
 * the request, and that a language it cannot deliver degrades to English
 * loudly rather than silently producing an interview in the wrong one.
 */
class QuestionLanguageTest {

    private static QuestionRequest request(InterviewLanguage language) {
        return new QuestionRequest("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 5, language);
    }

    @Test
    void englishIsSupportedAndPassesStraightThrough() {
        QuestionRequest r = request(InterviewLanguage.ENGLISH);

        assertThat(r.language()).isEqualTo(InterviewLanguage.ENGLISH);
        assertThat(r.resolvedLanguage()).isEqualTo(InterviewLanguage.ENGLISH);
        assertThat(r.languageFellBack()).isFalse();
    }

    /**
     * A null is not a fallback, it is an absence - nothing was asked for, so
     * nothing was overridden and there is nothing to warn about.
     */
    @Test
    void aMissingLanguageResolvesToEnglishWithoutBeingReportedAsAFallback() {
        QuestionRequest r = request(null);

        assertThat(r.resolvedLanguage()).isEqualTo(InterviewLanguage.ENGLISH);
        assertThat(r.languageFellBack()).isFalse();
    }

    /**
     * The behaviour that matters for the future: today every enum value is
     * supported, so this asserts the <em>rule</em> rather than a hypothetical
     * value. When a second language is added to the enum, it will be
     * unsupported by the prompt until someone adds it to SUPPORTED too - and
     * this test documents that the interview keeps working in the meantime.
     */
    @Test
    void everyCurrentlySchedulableLanguageIsOneTheGeneratorCanActuallyWrite() {
        for (InterviewLanguage language : InterviewLanguage.values()) {
            QuestionRequest r = request(language);

            // Whatever happens, generation is always possible in some language.
            assertThat(r.resolvedLanguage()).isNotNull();

            if (r.languageFellBack()) {
                // An unsupported value must degrade to English, never fail and
                // never pass an unsupported language to the model.
                assertThat(r.resolvedLanguage()).isEqualTo(InterviewLanguage.ENGLISH);
            } else {
                assertThat(r.resolvedLanguage()).isEqualTo(language);
            }
        }
    }

    /** The prototype supports exactly one interview language. Stated, not implied. */
    @Test
    void onlyEnglishIsClaimed() {
        assertThat(InterviewLanguage.values()).containsExactly(InterviewLanguage.ENGLISH);
    }
}
