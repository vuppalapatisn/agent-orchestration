#!/usr/bin/env node
/**
 * Gemini Code Review PR Agent
 *
 * Reads the pull request diff from the GitHub API, asks Google Gemini to review
 * it, and publishes the findings back to the PR as an inline review.
 *
 * Runs on plain Node 20+ (global fetch, node:crypto). No npm install needed.
 *
 * Required env:
 *   GEMINI_API_KEY     Google AI Studio API key
 *   GITHUB_TOKEN       token with pull-requests: write
 *   GITHUB_REPOSITORY  "owner/repo"
 *   PR_NUMBER          pull request number
 *
 * Optional env (defaults in CONFIG below):
 *   GEMINI_MODEL, MAX_DIFF_CHARS, MAX_FINDINGS, MAX_FILES,
 *   GUIDELINES_FILE, EXCLUDE_GLOBS, FAIL_ON_SEVERITY, DRY_RUN
 */

import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { appendFile } from 'node:fs/promises';

const CONFIG = {
  model: process.env.GEMINI_MODEL || 'gemini-2.5-pro',
  maxDiffChars: intEnv('MAX_DIFF_CHARS', 180_000),
  maxFindings: intEnv('MAX_FINDINGS', 20),
  maxFiles: intEnv('MAX_FILES', 60),
  guidelinesFile: process.env.GUIDELINES_FILE || '.github/gemini-review-guidelines.md',
  excludeGlobs: (process.env.EXCLUDE_GLOBS || defaultExcludes()).split(',').map((s) => s.trim()).filter(Boolean),
  failOnSeverity: (process.env.FAIL_ON_SEVERITY || 'none').toLowerCase(),
  dryRun: /^(1|true|yes)$/i.test(process.env.DRY_RUN || ''),
};

const SEVERITY_RANK = { blocker: 4, major: 3, minor: 2, nit: 1 };
const SEVERITY_ICON = { blocker: '🛑', major: '⚠️', minor: '🔎', nit: '💬' };
const MARKER_PREFIX = 'gemini-review';

function defaultExcludes() {
  return [
    'target/**', 'build/**', 'dist/**', 'out/**', 'node_modules/**',
    '**/*.lock', '**/package-lock.json', '**/yarn.lock', '**/pnpm-lock.yaml',
    '**/*.min.js', '**/*.min.css', '**/*.map',
    '**/*.png', '**/*.jpg', '**/*.jpeg', '**/*.gif', '**/*.svg', '**/*.ico',
    '**/*.pdf', '**/*.jar', '**/*.war', '**/*.zip', '**/*.class',
  ].join(',');
}

function intEnv(name, fallback) {
  const raw = process.env[name];
  const n = raw ? Number.parseInt(raw, 10) : Number.NaN;
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

function requireEnv(name) {
  const v = process.env[name];
  if (!v) {
    console.error(`::error::Missing required environment variable ${name}`);
    process.exit(1);
  }
  return v;
}

/* ------------------------------------------------------------------ *
 * GitHub REST helpers
 * ------------------------------------------------------------------ */

const GITHUB_API = process.env.GITHUB_API_URL || 'https://api.github.com';

async function gh(path, { method = 'GET', body, token } = {}) {
  const res = await fetch(`${GITHUB_API}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'Content-Type': 'application/json',
      'User-Agent': 'gemini-code-review-agent',
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`GitHub ${method} ${path} -> ${res.status}: ${text.slice(0, 800)}`);
  }
  return text ? JSON.parse(text) : null;
}

async function ghPaginate(path, token, cap = 10) {
  const out = [];
  for (let page = 1; page <= cap; page += 1) {
    const sep = path.includes('?') ? '&' : '?';
    const batch = await gh(`${path}${sep}per_page=100&page=${page}`, { token });
    if (!Array.isArray(batch) || batch.length === 0) break;
    out.push(...batch);
    if (batch.length < 100) break;
  }
  return out;
}

/* ------------------------------------------------------------------ *
 * Diff handling
 * ------------------------------------------------------------------ */

/** Minimal glob matcher supporting `**`, `*` and `?`. */
function globToRegExp(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i += 1) {
    const c = glob[i];
    if (c === '*') {
      if (glob[i + 1] === '*') {
        re += '.*';
        i += 1;
        if (glob[i + 1] === '/') i += 1; // `**/` also matches zero directories
      } else {
        re += '[^/]*';
      }
    } else if (c === '?') {
      re += '[^/]';
    } else {
      re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
    }
  }
  return new RegExp(`^${re}$`);
}

const EXCLUDE_RES = CONFIG.excludeGlobs.map(globToRegExp);

function isExcluded(path) {
  return EXCLUDE_RES.some((re) => re.test(path));
}

/**
 * Parse a unified diff patch into hunks, tracking the new-file line number of
 * every line the GitHub review API will accept a RIGHT-side comment on.
 */
function parsePatch(patch) {
  const hunks = [];
  const commentableLines = new Set();
  let newLine = 0;
  let current = null;

  for (const raw of patch.split('\n')) {
    const header = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(raw);
    if (header) {
      newLine = Number.parseInt(header[1], 10);
      current = { header: raw, lines: [] };
      hunks.push(current);
      continue;
    }
    if (!current) continue;

    if (raw.startsWith('+')) {
      current.lines.push({ kind: 'add', newLine, text: raw.slice(1) });
      commentableLines.add(newLine);
      newLine += 1;
    } else if (raw.startsWith('-')) {
      current.lines.push({ kind: 'del', newLine: null, text: raw.slice(1) });
    } else if (raw.startsWith('\\')) {
      // "\ No newline at end of file" — carries no line number
    } else {
      const text = raw.startsWith(' ') ? raw.slice(1) : raw;
      current.lines.push({ kind: 'ctx', newLine, text });
      commentableLines.add(newLine);
      newLine += 1;
    }
  }
  return { hunks, commentableLines };
}

/** Render a patch with explicit new-file line numbers so Gemini can cite them. */
function renderAnnotatedPatch(file) {
  const out = [`=== FILE: ${file.filename} (${file.status}, +${file.additions}/-${file.deletions}) ===`];
  if (file.previous_filename) out.push(`--- renamed from: ${file.previous_filename}`);
  for (const hunk of file.parsed.hunks) {
    out.push(hunk.header);
    for (const line of hunk.lines) {
      if (line.kind === 'add') out.push(`+ ${String(line.newLine).padStart(5)} | ${line.text}`);
      else if (line.kind === 'del') out.push(`-       | ${line.text}`);
      else out.push(`  ${String(line.newLine).padStart(5)} | ${line.text}`);
    }
  }
  return out.join('\n');
}

/** Prefer files with real code churn when the diff must be trimmed. */
function priority(file) {
  return (file.additions || 0) + (file.deletions || 0);
}

function buildDiffBundle(files) {
  const ordered = [...files].sort((a, b) => priority(b) - priority(a));
  const included = [];
  const skipped = [];
  let total = 0;

  for (const file of ordered) {
    if (included.length >= CONFIG.maxFiles) {
      skipped.push({ filename: file.filename, reason: 'file cap reached' });
      continue;
    }
    const rendered = renderAnnotatedPatch(file);
    if (total + rendered.length > CONFIG.maxDiffChars) {
      skipped.push({ filename: file.filename, reason: 'diff size cap reached' });
      continue;
    }
    total += rendered.length;
    included.push({ file, rendered });
  }

  // Restore original (path-sorted) order for a stable, readable prompt.
  included.sort((a, b) => a.file.filename.localeCompare(b.file.filename));
  return { text: included.map((i) => i.rendered).join('\n\n'), included, skipped };
}

/* ------------------------------------------------------------------ *
 * Gemini
 * ------------------------------------------------------------------ */

const RESPONSE_SCHEMA = {
  type: 'object',
  properties: {
    summary: {
      type: 'string',
      description: 'Two to five sentences: what this PR changes and the overall risk level.',
    },
    verdict: {
      type: 'string',
      enum: ['approve', 'comment', 'request_changes'],
      description: 'approve = no material issues; request_changes = at least one blocker.',
    },
    findings: {
      type: 'array',
      description: 'Concrete, actionable issues in the changed lines. Empty if the diff is clean.',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string', description: 'Exact path as shown in the FILE header.' },
          line: { type: 'integer', description: 'New-file line number exactly as printed in the annotated diff.' },
          severity: { type: 'string', enum: ['blocker', 'major', 'minor', 'nit'] },
          category: {
            type: 'string',
            enum: ['correctness', 'security', 'concurrency', 'performance', 'error-handling',
                   'api-design', 'testing', 'maintainability', 'style', 'docs'],
          },
          title: { type: 'string', description: 'Under 80 characters.' },
          detail: { type: 'string', description: 'What is wrong and the concrete scenario in which it breaks.' },
          suggestion: { type: 'string', description: 'Replacement code for that line/block, or empty string.' },
        },
        required: ['file', 'line', 'severity', 'category', 'title', 'detail'],
      },
    },
  },
  required: ['summary', 'verdict', 'findings'],
};

const SYSTEM_INSTRUCTION = `You are a senior staff engineer reviewing a pull request in a production Java 21 / Spring Boot 3 service that orchestrates AI agents (Spring AI, JPA, Spring Security, Actuator, Docker, Kubernetes).

Review ONLY the changed lines shown in the annotated diff. Judge them in the context of the surrounding code you can see.

What to report, in priority order:
1. Correctness bugs — wrong logic, off-by-one, null dereference, unhandled empty Optional, inverted conditions, resource leaks, wrong equals/hashCode, incorrect exception handling.
2. Security — hardcoded credentials or API keys, disabled CSRF/auth on state-changing endpoints, permitAll on sensitive paths, SQL/prompt injection, secrets or PII in logs, unsafe deserialization, overly broad CORS.
3. Concurrency — shared mutable state, non-thread-safe fields on singleton beans, unbounded thread pools, blocking calls without timeouts, missing @Transactional or wrong propagation.
4. Performance — N+1 JPA queries, work inside loops that should be batched, unbounded collections or caches, missing pagination.
5. Error handling and observability — swallowed exceptions, catch(Exception) without context, missing timeouts/retries on outbound calls, log level misuse.
6. API and data contracts — breaking DTO changes, missing validation annotations, inconsistent HTTP status codes.
7. Testing gaps — new branching logic with no corresponding test.
8. Maintainability — duplicated logic that already exists elsewhere in the diff, dead code, misleading names.

Hard rules:
- Every finding MUST use a "line" value that literally appears in the annotated diff for that file, and MUST use the exact file path from the FILE header.
- Prefer few high-confidence findings over many speculative ones. If you are not reasonably sure the code is wrong, do not report it.
- Do not report formatting, import ordering, or preferences a linter would own, unless the repository guidelines ask for it.
- Do not restate what the diff obviously does, and do not praise.
- "suggestion" must be compilable replacement code for the cited lines only, with no diff markers, no line numbers, and no prose. Use an empty string when a code fix is not appropriate.
- Set verdict to "request_changes" only when at least one blocker exists; "approve" only when there are no findings above "nit".`;

async function callGemini(prompt) {
  const apiKey = requireEnv('GEMINI_API_KEY');
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${CONFIG.model}:generateContent`;
  const body = {
    systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTION }] },
    contents: [{ role: 'user', parts: [{ text: prompt }] }],
    generationConfig: {
      temperature: 0.15,
      topP: 0.95,
      maxOutputTokens: 16_384,
      responseMimeType: 'application/json',
      responseSchema: RESPONSE_SCHEMA,
    },
  };

  const maxAttempts = 4;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
      body: JSON.stringify(body),
    });

    if (res.ok) {
      const json = await res.json();
      const candidate = json.candidates?.[0];
      const text = candidate?.content?.parts?.map((p) => p.text).filter(Boolean).join('') || '';
      if (!text) {
        throw new Error(`Gemini returned no text (finishReason=${candidate?.finishReason}, promptFeedback=${JSON.stringify(json.promptFeedback || {})})`);
      }
      const usage = json.usageMetadata || {};
      console.log(`Gemini ok: prompt=${usage.promptTokenCount ?? '?'} output=${usage.candidatesTokenCount ?? '?'} tokens`);
      return JSON.parse(text);
    }

    const errText = await res.text();
    const retryable = res.status === 429 || res.status >= 500;
    if (!retryable || attempt === maxAttempts) {
      throw new Error(`Gemini ${res.status}: ${errText.slice(0, 800)}`);
    }
    const waitMs = 2000 * 2 ** (attempt - 1);
    console.log(`Gemini ${res.status}, retrying in ${waitMs}ms (attempt ${attempt}/${maxAttempts})`);
    await new Promise((r) => setTimeout(r, waitMs));
  }
  throw new Error('unreachable');
}

function buildPrompt({ pr, bundle, guidelines, skipped }) {
  const parts = [];
  parts.push(`## Pull request #${pr.number}: ${pr.title}`);
  parts.push(`Author: ${pr.user?.login || 'unknown'} | Base: ${pr.base?.ref} | Head: ${pr.head?.ref}`);
  parts.push(`Files changed: ${pr.changed_files} | +${pr.additions}/-${pr.deletions}`);
  parts.push('');
  parts.push('## PR description');
  parts.push((pr.body || '(no description provided)').slice(0, 4000));
  if (guidelines) {
    parts.push('');
    parts.push('## Repository review guidelines (these override your defaults)');
    parts.push(guidelines.slice(0, 12_000));
  }
  if (skipped.length) {
    parts.push('');
    parts.push(`## Files omitted from this diff (do not comment on them): ${skipped.map((s) => s.filename).join(', ')}`);
  }
  parts.push('');
  parts.push('## Annotated diff');
  parts.push('Format: `+ <newLine> | code` = added, `  <newLine> | code` = unchanged context, `-       | code` = removed.');
  parts.push('Cite only the numbers printed in the middle column.');
  parts.push('');
  parts.push(bundle.text);
  parts.push('');
  parts.push(`Return at most ${CONFIG.maxFindings} findings, most severe first.`);
  return parts.join('\n');
}

/* ------------------------------------------------------------------ *
 * Publishing
 * ------------------------------------------------------------------ */

/** Stable id for a finding, used to avoid re-posting the same comment. */
function hashFor(finding) {
  const key = `${finding.file}:${finding.line}:${finding.title}`;
  return createHash('sha256').update(key).digest('hex').slice(0, 16);
}

function markerFor(finding) {
  return `<!-- ${MARKER_PREFIX}:${hashFor(finding)} -->`;
}

function normaliseFindings(raw, fileIndex) {
  const kept = [];
  const dropped = [];

  for (const finding of Array.isArray(raw) ? raw : []) {
    const file = fileIndex.get(finding.file);
    if (!file) {
      dropped.push({ finding, reason: `path "${finding.file}" is not part of the reviewed diff` });
      continue;
    }
    const line = Number.parseInt(finding.line, 10);
    if (!Number.isFinite(line) || !file.parsed.commentableLines.has(line)) {
      dropped.push({ finding, reason: `line ${finding.line} is not in the diff for ${finding.file}` });
      continue;
    }
    kept.push({
      ...finding,
      line,
      severity: SEVERITY_RANK[finding.severity] ? finding.severity : 'minor',
    });
  }

  kept.sort((a, b) => SEVERITY_RANK[b.severity] - SEVERITY_RANK[a.severity]);
  const capped = kept.slice(0, CONFIG.maxFindings);
  for (const extra of kept.slice(CONFIG.maxFindings)) {
    dropped.push({ finding: extra, reason: 'over MAX_FINDINGS cap' });
  }
  return { findings: capped, dropped };
}

function commentBody(finding) {
  const icon = SEVERITY_ICON[finding.severity] || '💬';
  const lines = [
    `${icon} **${finding.severity.toUpperCase()} · ${finding.category}** — ${finding.title}`,
    '',
    finding.detail,
  ];
  if (finding.suggestion && finding.suggestion.trim()) {
    lines.push('', '```suggestion', finding.suggestion.replace(/\n+$/, ''), '```');
  }
  lines.push('', `<sub>🤖 Reviewed by Google ${CONFIG.model}. Suggestions are advisory — verify before applying.</sub>`, markerFor(finding));
  return lines.join('\n');
}

function summaryBody({ result, findings, dropped, bundle, existingSkipped }) {
  const counts = findings.reduce((acc, f) => {
    acc[f.severity] = (acc[f.severity] || 0) + 1;
    return acc;
  }, {});
  const order = ['blocker', 'major', 'minor', 'nit'];
  const tally = order.filter((s) => counts[s]).map((s) => `${SEVERITY_ICON[s]} ${counts[s]} ${s}`).join(' · ') || 'no issues found';

  const lines = [
    '## 🤖 Gemini Code Review',
    '',
    result.summary || '_No summary returned._',
    '',
    `**Findings:** ${tally}`,
    `**Verdict:** \`${result.verdict || 'comment'}\``,
    `**Reviewed:** ${bundle.included.length} file(s), ${bundle.text.length.toLocaleString('en-US')} diff chars, model \`${CONFIG.model}\``,
  ];

  if (bundle.skipped.length) {
    lines.push('', '<details><summary>Files not reviewed (size/type limits)</summary>', '');
    for (const s of bundle.skipped) lines.push(`- \`${s.filename}\` — ${s.reason}`);
    lines.push('</details>');
  }
  if (existingSkipped > 0) {
    lines.push('', `_${existingSkipped} finding(s) already posted on an earlier run were skipped._`);
  }
  if (dropped.length) {
    lines.push('', '<details><summary>Findings dropped by validation</summary>', '');
    for (const d of dropped) {
      lines.push(`- **${d.finding.title || 'untitled'}** (\`${d.finding.file}:${d.finding.line}\`) — ${d.reason}`);
      if (d.finding.detail) lines.push(`  - ${d.finding.detail}`);
    }
    lines.push('</details>');
  }
  lines.push('', '<sub>Comment `/gemini-review` on this PR to run the agent again.</sub>');
  return lines.join('\n');
}

async function stepSummary(text) {
  const path = process.env.GITHUB_STEP_SUMMARY;
  if (!path) return;
  try {
    await appendFile(path, `${text}\n`);
  } catch (err) {
    console.log(`Could not write step summary: ${err.message}`);
  }
}

/* ------------------------------------------------------------------ *
 * Main
 * ------------------------------------------------------------------ */

async function main() {
  const token = requireEnv('GITHUB_TOKEN');
  const [owner, repo] = requireEnv('GITHUB_REPOSITORY').split('/');
  const prNumber = requireEnv('PR_NUMBER');

  const pr = await gh(`/repos/${owner}/${repo}/pulls/${prNumber}`, { token });
  if (pr.state !== 'open') {
    console.log(`PR #${prNumber} is ${pr.state}; nothing to review.`);
    return;
  }

  const allFiles = await ghPaginate(`/repos/${owner}/${repo}/pulls/${prNumber}/files`, token);
  const reviewable = allFiles.filter((f) => {
    if (f.status === 'removed') return false;
    if (!f.patch) return false; // binary or too large for the API to return
    if (isExcluded(f.filename)) return false;
    return true;
  });

  console.log(`PR #${prNumber}: ${allFiles.length} changed file(s), ${reviewable.length} reviewable.`);
  if (reviewable.length === 0) {
    const msg = 'No reviewable text changes in this pull request (all files are binary, excluded, or deletions).';
    console.log(msg);
    await stepSummary(`## 🤖 Gemini Code Review\n\n${msg}`);
    return;
  }

  for (const file of reviewable) file.parsed = parsePatch(file.patch);
  const fileIndex = new Map(reviewable.map((f) => [f.filename, f]));

  const guidelines = await readFile(CONFIG.guidelinesFile, 'utf8').catch(() => '');
  const bundle = buildDiffBundle(reviewable);
  const prompt = buildPrompt({ pr, bundle, guidelines, skipped: bundle.skipped });
  console.log(`Prompt: ${prompt.length.toLocaleString('en-US')} chars over ${bundle.included.length} file(s).`);

  const result = await callGemini(prompt);
  const { findings, dropped } = normaliseFindings(result.findings, fileIndex);
  console.log(`Gemini returned ${result.findings?.length || 0} finding(s); ${findings.length} validated, ${dropped.length} dropped.`);

  // Skip anything an earlier run already posted (re-runs on new commits).
  const existing = await ghPaginate(`/repos/${owner}/${repo}/pulls/${prNumber}/comments`, token).catch(() => []);
  const seen = new Set();
  for (const c of existing) {
    for (const m of (c.body || '').matchAll(new RegExp(`<!-- ${MARKER_PREFIX}:([0-9a-f]+) -->`, 'g'))) seen.add(m[1]);
  }
  const fresh = findings.filter((f) => !seen.has(hashFor(f)));
  const alreadyPosted = findings.length - fresh.length;

  const body = summaryBody({ result, findings, dropped, bundle, existingSkipped: alreadyPosted });
  const comments = fresh.map((f) => ({ path: f.file, line: f.line, side: 'RIGHT', body: commentBody(f) }));

  await stepSummary(body);

  if (CONFIG.dryRun) {
    console.log('DRY_RUN=true — not posting to GitHub.\n');
    console.log(body);
    for (const c of comments) console.log(`\n--- ${c.path}:${c.line} ---\n${c.body}`);
  } else {
    // event: COMMENT keeps the agent advisory; it never blocks merge on its own.
    await gh(`/repos/${owner}/${repo}/pulls/${prNumber}/reviews`, {
      method: 'POST',
      token,
      body: { commit_id: pr.head.sha, event: 'COMMENT', body, comments },
    });
    console.log(`Posted review with ${comments.length} inline comment(s).`);
  }

  const threshold = SEVERITY_RANK[CONFIG.failOnSeverity];
  if (threshold) {
    const blocking = findings.filter((f) => SEVERITY_RANK[f.severity] >= threshold);
    if (blocking.length) {
      console.error(`::error::${blocking.length} finding(s) at or above "${CONFIG.failOnSeverity}" severity.`);
      process.exitCode = 1;
    }
  }
}

main().catch(async (err) => {
  console.error(`::error::Gemini code review failed: ${err.message}`);
  await stepSummary(`## 🤖 Gemini Code Review\n\n❌ The review agent failed: \`${err.message}\``);
  process.exit(1);
});
