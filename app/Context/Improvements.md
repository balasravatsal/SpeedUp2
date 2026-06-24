# Speed Up — Analysis & Improvement Roadmap

_Last updated: June 2026_

This document summarizes fixes applied in this iteration and recommends next steps for the matching engine, autofill pipeline, and overall architecture.

---

## What Was Fixed (Latest — JD-First Profile Matcher)

### Root cause of the Crunchyroll bug
When the app could not read the JD properly, it **fell back to showing your profile skills as "matched"** (Java, React, etc.) with a fake 50% score and stale job title from your profile/experience history (Anblicks Inc / Software Engineer).

### New approach: JD requirements → Profile comparison

```
Read live screen text (Chrome foreground window)
        ↓
JdExtractor — parse title, company, location, requirements from JD
        ↓
For EACH requirement found in the JD:
    Does user's profile satisfy it?
        YES → skillsMatched
        WEAK → skillsPartial
        NO  → skillsMissing
        ↓
Score = matched / total JD requirements (not profile skills)
```

**Profile skills are never shown as matched unless they appear in the JD.**

For the [Crunchyroll SVP Accounting role](https://job-boards.greenhouse.io/crunchyroll/jobs/7818180), you should now see:
- **Title:** Senior Vice President, Accounting
- **Company:** Crunchyroll
- **You have:** Leadership, Communication (if in your profile)
- **You lack:** CPA, GAAP, IFRS, ERP, 15+ years experience, Master's degree, etc.
- **Score:** Low (Poor/Weak Match) — not 50% "Strong Match"

### Previous "profile-centric" approach (reverted)
The earlier fix ignored JD skills outside your profile. That was wrong for a gap-analysis tool. The correct model is always **JD requirements as the source of truth**.

### 2. Semantic skill matching

Layered matching in `SkillMatcher`:
1. Exact / normalized string match
2. Alias map (50+ tech skills with variants)
3. Fuzzy (Jaro-Winkler)
4. ONNX MiniLM embeddings via `SemanticMatcher` when model is loaded

This handles `Node.js` ↔ `NodeJS`, `Spring Boot` ↔ `Spring`, `ML` ↔ `Machine Learning`, etc.

### 3. Auto-fill for browsers & job platforms

**WindowScanner improvements:**
- Scans `TYPE_APPLICATION` **and** `TYPE_WINDOW` (needed for Chrome overlays).
- Recognizes browser packages (Chrome, Firefox, Edge, Samsung Internet, etc.).
- Separate targets: content window for JD reading, form window for autofill.

**FormFieldDetector improvements:**
- Detects WebView/HTML inputs via focusable + `ACTION_SET_TEXT`/`ACTION_PASTE`.
- Broader class name patterns (`textfield`, `textarea`, `input`).

**FieldMapper improvements:**
- Chrome/Firefox/Edge HTML `name`/`id` overrides (`first-name`, `email`, `tel`, etc.).
- Partial ID matching for Greenhouse/Workday-style field names.

**AutofillExecutor improvements:**
- Select-all + paste fallback for WebView fields that reject `ACTION_SET_TEXT`.

---

## Architecture Overview

```
AccessibilityService
       │
       ├─► WindowScanner ──► Content window (JD text) ──► JobFitAnalyzer
       │                                              └──► SkillMatcher
       │
       └─► WindowScanner ──► Form window (fields) ──► FieldMapper (6 layers)
                                                    └──► ProfileValueResolver
                                                    └──► AutofillExecutor
```

### Field mapping layers (per `SymenticMatchPlan.md`)

| Layer | Method | Status |
|-------|--------|--------|
| 1 | `inputType` flags | ✅ Implemented |
| 2 | App-specific overrides (LinkedIn, Chrome) | ✅ Expanded |
| 3 | Keyword alias map | ✅ Implemented |
| 4 | Fuzzy (Jaro-Winkler) | ✅ Implemented |
| 5 | ONNX semantic embeddings | ✅ Implemented (needs model in assets) |
| 6 | User correction feedback (Room DB) | ❌ Not yet |

---

## Model & Approach Recommendations

### Keep the current on-device ONNX approach ✅

**Why:**
- Privacy — form labels and JD text never leave the device
- Works offline on planes / poor connectivity
- No API cost per field or per job scan
- MiniLM-L6 (~23MB quantized) is sufficient for field labels and skill synonyms

**When to add cloud AI (Claude/Gemini):**
- Ambiguous multi-field context ("Legal name (as on passport)" on a visa form)
- Cover letter generation (v2 per PRD)
- Parsing uploaded PDF resumes (v2)

Do **not** replace the on-device pipeline — use cloud as Layer 7 fallback only.

### Model improvements (short term)

| Improvement | Effort | Impact |
|-------------|--------|--------|
| Cache embeddings for seen field labels | Low | Faster repeat visits to same ATS |
| Pre-embed full alias list at init (already done for fields) | Done | — |
| Add skill-pair training examples to alias map | Low | Better than raising embedding threshold |
| Lower semantic threshold for browsers (0.65) | Low | More web form matches |
| Bundle `app_overrides.json` for top 20 ATS apps | Medium | Near-perfect accuracy on Workday/Greenhouse/Lever |

### Model improvements (medium term)

| Improvement | Effort | Impact |
|-------------|--------|--------|
| Fine-tune MiniLM on job-form field pairs | High | Best ROI for autofill accuracy |
| Switch to `multilingual-e5-small` | Medium | i18n forms (Naukri Hindi labels, EU sites) |
| NER model for JD skill extraction | Medium | Catch skills not in static catalog |
| Room DB for user corrections (Layer 6) | Medium | App learns per-package field IDs |

### Architecture changes to consider

#### A. Split "read" vs "act" services
Keep one AccessibilityService but separate:
- `JobContentReader` — scroll-aware JD extraction (many JDs are below fold)
- `FormFiller` — only runs on explicit user tap

#### B. Pagination / multi-step forms
Current autofill fills visible page only. Add:
- Detect "Next" / "Continue" buttons
- Fill → tap Next → fill next page (with user confirmation per PRD)

#### C. Dropdown / date pickers
Spinners and date wheels need `ACTION_CLICK` + option scan — not yet implemented. High priority for Workday.

#### D. Resume upload
Requires `Storage Access Framework` navigation — complex but high value. Consider prompting user once to pin resume folder.

---

## Known Limitations

1. **Chrome WebView variability** — Some sites use shadow DOM or custom components with poor accessibility trees. Semantic matching helps but cannot fix entirely inaccessible forms.
2. **JD below the fold** — Only visible accessibility nodes are scanned. User may need to scroll JD into view before tapping the widget.
3. **FLAG_SECURE apps** — Banking apps block accessibility entirely.
4. **Model asset** — `model_quantized.onnx` must be present in `assets/`. If missing, Layers 1–4 still work; Layer 5 (semantic) is skipped.
5. **Partial skill matches** — React vs React Native scoring is heuristic; fine-tuning would improve this.

---

## Suggested Build Order (Next Sprints)

1. **Room correction DB** — store `(package, viewId) → CanonicalField` after user fixes a wrong mapping
2. **ATS override JSON** — Greenhouse, Workday, Lever, Ashby, iCIMS field ID maps
3. **Dropdown handler** — Spinner / `<select>` option matching
4. **Scroll-aware JD scan** — auto-scroll or prompt "scroll to read full JD"
5. **Multi-step form continuation** — detect and tap "Next"
6. **Cover letter template** — use matched skills from `JobFitAnalyzer` output

---

## Scoring Formula (Current)

| Component | Max Pts | Logic |
|-----------|---------|-------|
| Skills match | 40 | `matched / (matched + partial)` — only in-scope skills |
| Experience | 25 | User yrs vs JD requirement |
| Skill depth | 20 | % of matched skills with yrs of experience in profile |
| Profile completeness | 15 | Bonus for 3+ / 5+ skills in profile |

**Interpretation:**
- 80–100 → Strong match (apply with confidence)
- 50–79 → Partial (review gaps)
- 0–49 → Low overlap with your profile

---

## Should You Change the Approach?

**No wholesale change recommended.** The layered pipeline in `SymenticMatchPlan.md` is industry-standard (same pattern as browser autofill, 1Password, Bitwarden).

**Do augment, not replace:**
- Static aliases + fuzzy → fast, deterministic, debuggable
- ONNX embeddings → safety net for unknown labels
- Per-app overrides → perfection on top platforms
- User corrections → long-term moat

The biggest gap today is **not the model** — it is **ATS-specific overrides** and **multi-step form handling**. Invest engineering time there before swapping models.
