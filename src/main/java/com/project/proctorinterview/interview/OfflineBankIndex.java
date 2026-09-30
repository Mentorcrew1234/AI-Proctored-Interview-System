package com.project.proctorinterview.interview;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.json.JsonMapper;

/**
 * Which technical domains the offline question bank actually covers.
 *
 * <p>Exists so the scheduling form can tell a recruiter the truth: any domain
 * may be typed, and the AI generator will happily write questions for it, but
 * if the AI is unavailable the interview falls back to the bank - and the bank
 * only has curated questions for the domains listed here. Everything else gets
 * a general engineering set.
 *
 * <p><b>Read from {@code question-bank.json} rather than duplicated in Java.</b>
 * A hand-maintained second list would drift the first time somebody added a
 * domain to the bank and forgot, and the form would then warn about a domain
 * that is in fact covered - or worse, stay silent about one that is not.
 *
 * <p>Loaded once, statically. The bank is a packaged resource that cannot change
 * while the application runs, and {@code FallbackLlmClient} reads the same file
 * for the questions themselves.
 */
final class OfflineBankIndex {

    private static final Logger log = LoggerFactory.getLogger(OfflineBankIndex.class);
    private static final List<String> TECHNICAL_DOMAINS = load();

    private OfflineBankIndex() {
    }

    static List<String> technicalDomains() {
        return TECHNICAL_DOMAINS;
    }

    @SuppressWarnings("unchecked")
    private static List<String> load() {
        try (InputStream in = new ClassPathResource("question-bank.json").getInputStream()) {
            Map<String, Object> root = JsonMapper.builder().build().readValue(in, Map.class);

            Object technical = root.get("TECHNICAL");
            if (!(technical instanceof Map<?, ?> domains)) {
                return List.of();
            }

            List<String> named = new ArrayList<>();
            for (Object key : domains.keySet()) {
                String domain = String.valueOf(key);
                // Keys beginning with "_" are structural - "_default" is the
                // general set an unknown domain falls back to, not a domain
                // anyone would choose.
                if (!domain.startsWith("_")) {
                    named.add(domain);
                }
            }
            named.sort(String::compareToIgnoreCase);
            return List.copyOf(named);
        } catch (Exception e) {
            // Not fatal: the form simply stops distinguishing covered domains
            // from uncovered ones, which is a worse hint, not a broken page.
            log.warn("Could not read question-bank.json to list covered domains: {}", e.getMessage());
            return List.of();
        }
    }
}
