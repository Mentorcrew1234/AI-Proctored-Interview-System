package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long interview data is expected to be kept.
 *
 * <p>Note what this does <b>not</b> do: nothing deletes anything because of this
 * value. It drives a report - "this much data is older than the window you said
 * you would keep it for" - and a person decides what to do about it.
 *
 * <p>That is deliberate. A scheduled purge is the kind of feature that works
 * perfectly right up until the morning of a demonstration, and its failure mode
 * is unrecoverable destruction. For a prototype the honest arrangement is to
 * make the situation visible and leave the destruction manual and attributable.
 */
@ConfigurationProperties(prefix = "app.retention")
public record RetentionProperties(Integer months) {

    public RetentionProperties {
        // Falls back rather than failing startup, matching the interview mode.
        if (months == null || months < 1 || months > 120) {
            months = 24;
        }
    }
}
