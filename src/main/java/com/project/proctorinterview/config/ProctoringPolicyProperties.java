package com.project.proctorinterview.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.project.proctorinterview.common.Enums.ProctorEventType;

/**
 * How proctoring observations may affect a result ({@code app.report.proctoring}).
 *
 * <p>The rule this encodes is deliberate and narrow: observations can <b>cap</b> a
 * RECOMMENDED result at FURTHER_REVIEW so a human looks at it. They can never
 * lower a score, and they can never produce NOT_RECOMMENDED.
 *
 * <p>The reason is that these detections are unreliable and ambiguous. A phone
 * on the desk is not proof of anything, and the system should not be able to
 * reject a candidate on that basis. Escalating to a human is the strongest
 * action it is entitled to take.
 */
@ConfigurationProperties(prefix = "app.report.proctoring")
public record ProctoringPolicyProperties(
        boolean forceReviewEnabled,
        List<ProctorEventType> reviewTriggerTypes) {

    public ProctoringPolicyProperties {
        reviewTriggerTypes = reviewTriggerTypes == null ? List.of() : List.copyOf(reviewTriggerTypes);
    }
}
