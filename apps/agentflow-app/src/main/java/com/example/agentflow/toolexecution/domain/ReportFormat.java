package com.example.agentflow.toolexecution.domain;

/**
 * The output format a {@link ReportFormatter} renders a report into (S08-06).
 *
 * <p>The report formatter is the concrete {@code report.format} tool: it turns structured facts and
 * citations into a report the run can return or render. It supports two output shapes — {@link
 * #MARKDOWN} for a human-readable document and {@link #JSON} for a machine-readable one — and
 * renders <em>both</em> without emitting executable HTML: every dynamic value (fact text and
 * citation) is HTML-escaped before it is written, so a hostile or prompt-injected fact can never
 * turn the report into live markup (invariant: documents and tool outputs are untrusted content).
 */
public enum ReportFormat {

  /** A Markdown document: a header plus one bullet per fact, with citation sources inline. */
  MARKDOWN,

  /** A JSON object: {@code {"truncated", "factCount", "facts":[{ "text", "citations" }]}}. */
  JSON
}
