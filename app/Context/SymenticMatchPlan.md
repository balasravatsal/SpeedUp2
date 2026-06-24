Great problem — this is essentially **semantic field normalization**, and it's a well-known challenge in form autofill systems (even browsers struggle with it). Let me give you a solid roadmap.

## The Core Problem

You need to map **arbitrary field labels/hints/ids** → **canonical user data fields** (name, email, phone, etc.)

---

## Roadmap

### Phase 1 — Data Collection & Signal Extraction

Before you can match a field, you need to extract as many signals as possible from it.

**Signals to collect from each form field (via AccessibilityService):**

```
viewIdResourceName  → "com.linkedin.android:id/first_name_field"
hint                → "Enter your first name"
contentDescription  → "First name"
text                → (current value if any)
labelFor            → associated TextView label
className           → EditText / AutoCompleteTextView
inputType           → TYPE_TEXT_VARIATION_PERSON_NAME, TYPE_TEXT_VARIATION_EMAIL_ADDRESS, etc.
```

The `inputType` flag is a **free signal** — Android already classifies many field types for you. Use it as a strong prior.

---

### Phase 2 — Canonical Field Schema

Define your own internal schema of "slots":

```kotlin
enum class CanonicalField {
    FIRST_NAME,
    LAST_NAME,
    FULL_NAME,
    EMAIL,
    PHONE,
    LINKEDIN_URL,
    PORTFOLIO_URL,
    CITY,
    STATE,
    COUNTRY,
    YEARS_OF_EXPERIENCE,
    CURRENT_COMPANY,
    CURRENT_TITLE,
    COVER_LETTER,
    // ...
}
```

Every field on any form maps to exactly one of these (or `UNKNOWN`).

---

### Phase 3 — Matching Pipeline (Layered)

Run these layers **in order**, stopping at the first confident match:

```
Signal Extraction
      │
      ▼
Layer 1: inputType flags          (free, reliable)
      │
      ▼
Layer 2: Exact keyword lookup     (fast, deterministic)
      │
      ▼
Layer 3: Fuzzy / alias matching   (handles typos, abbreviations)
      │
      ▼
Layer 4: Embedding similarity     (semantic: "given name" = "first name")
      │
      ▼
Layer 5: Context window           (look at surrounding fields)
      │
      ▼
Layer 6: User correction feedback (learn from the user)
```

---

#### Layer 1 — Android `inputType` flags

```kotlin
when {
    inputType has TYPE_TEXT_VARIATION_EMAIL_ADDRESS    -> EMAIL
    inputType has TYPE_TEXT_VARIATION_PERSON_NAME     -> NAME (narrow further with other signals)
    inputType has TYPE_TEXT_VARIATION_PHONE           -> PHONE
    inputType has TYPE_TEXT_VARIATION_POSTAL_ADDRESS  -> ADDRESS
    inputType has TYPE_NUMBER_VARIATION_PASSWORD      -> skip
}
```

#### Layer 2 — Keyword Lookup Table

Build a flat alias map (JSON/hardcoded):

```json
{
  "FIRST_NAME":  ["first name", "first_name", "firstname", "given name", "givenname", "fname", "forename", "prénom"],
  "LAST_NAME":   ["last name", "last_name", "lastname", "surname", "family name", "lname"],
  "FULL_NAME":   ["full name", "fullname", "name", "your name", "applicant name"],
  "EMAIL":       ["email", "e-mail", "email address", "work email"],
  "PHONE":       ["phone", "mobile", "cell", "contact number", "phone number"],
  "LINKEDIN_URL":["linkedin", "linkedin url", "linkedin profile"],
  ...
}
```

Normalize all signals to lowercase, strip punctuation, then scan the alias map. **O(1) lookup.**

#### Layer 3 — Fuzzy Matching

For typos/abbreviations that miss Layer 2, use **Levenshtein distance** or **Jaro-Winkler**. A pure-Kotlin implementation is tiny — no library needed for simple cases.

```kotlin
if (jaroWinkler(normalizedSignal, alias) > 0.92) → match
```

#### Layer 4 — Embedding Similarity (Semantic Core)

This is where "given name", "prénom", "nombre" all collapse to `FIRST_NAME`. Two options:

| Option | How | Tradeoff |
|---|---|---|
| **On-device (recommended)** | MiniLM / `all-MiniLM-L6-v2` via ONNX Runtime for Android | ~23MB, works offline, private |
| **API call** | Send label text to an embedding API | Latency + requires internet |

For a field autofill app, **on-device is strongly preferred** — users don't want their form field labels sent to a server.

You only embed the field label once per unique label seen, then cosine-similarity vs your pre-embedded canonical field names.

```
embed("given name")  · embed("first name")  = 0.94  ✅
embed("given name")  · embed("last name")   = 0.61  ❌
```

#### Layer 5 — Context Window

Look at **neighboring fields** in the same form section. If you see:

```
[FIRST_NAME resolved] → [??? "name"] → [LAST_NAME resolved]
```

The middle field is almost certainly `MIDDLE_NAME`. Positional order matters too — most forms go `first → last`, not `last → first`.

#### Layer 6 — User Correction Loop

When confidence is low, show the user: *"We think this is 'Last Name' — correct?"*

Store corrections in a local DB:
```
(app_package, field_id, label_hash) → CanonicalField
```

Next time the same app's field appears, you return the corrected mapping instantly. This is how your app gets smarter over time per-app.

---

### Phase 4 — Confidence Scoring & Fallback UI

```kotlin
data class FieldMatch(
    val canonical: CanonicalField,
    val confidence: Float,   // 0.0 - 1.0
    val matchedLayer: Int
)

when {
    confidence > 0.90 -> autofill silently
    confidence > 0.65 -> autofill + show unobtrusive chip "Filled as: First Name ✎"
    else              -> show picker: "What is this field for?"
}
```

---

### Phase 5 — App-Specific Overrides

Popular apps (LinkedIn, Workday, Greenhouse, Lever, Indeed) have **stable resource IDs**. Build a small lookup table:

```json
{
  "com.linkedin.android": {
    "id/first_name": "FIRST_NAME",
    "id/last_name":  "LAST_NAME"
  },
  "com.greenhouse.jobapp": { ... }
}
```

This gives you **perfect accuracy** for the top 10 job apps with zero ML needed.

---

## Tech Stack Summary

| Component | Tool |
|---|---|
| Field signal extraction | `AccessibilityService` |
| Keyword/alias matching | Hardcoded Kotlin map |
| Fuzzy matching | Kotlin (Jaro-Winkler, 50 lines) |
| Semantic embeddings | ONNX Runtime + MiniLM-L6 |
| Local correction DB | Room (SQLite) |
| App-specific overrides | JSON bundled in assets |

---

## Suggested Build Order

1. **Step 1** — Canonical schema + keyword alias map (covers ~70% of fields)
2. **Step 2** — inputType flags + app-specific overrides for LinkedIn/Indeed
3. **Step 3** — Fuzzy matching layer
4. **Step 4** — ONNX + MiniLM integration (semantic layer)
5. **Step 5** — User correction feedback loop + Room DB

The alias map alone will handle the vast majority of real-world cases. The embedding layer is your safety net for edge cases and internationalization.