package com.example.agentflow.toolexecution.domain;

/**
 * The output of the {@link ReportFormatter} (S08-06): the rendered report and the metadata the
 * executor needs to classify and audit it.
 *
 * <p>It is an immutable value object with value equality.
 *
 * @param text the rendered report, in the requested {@link ReportFormat} and HTML-escaped so it
 *     carries no executable markup (see {@link ReportFormatter})
 * @param format the format the report was actually rendered into (the request format, or {@link
 *     ReportFormat#MARKDOWN} when the request format was {@code null})
 * @param charCount the character length of {@link #text()}; an audit/observability hint, never a
 *     metric label
 * @param truncated {@code true} when the input had more facts than fit the formatter's result
 *     length cap, so the facts were included whole and the rest were dropped
 */
public record ReportResult(String text, ReportFormat format, int charCount, boolean truncated) {}
