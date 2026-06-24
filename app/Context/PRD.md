# Speed Up — Product Requirements Document

---

## 1. Product Overview

**App Name:** Speed Up
**Platform:** Android (Kotlin)
**Core Purpose:** Accelerate job applications by analyzing job fit in real-time and auto-filling application forms across any job platform — native apps or browser-based.

**Target User:** Active job seekers applying to multiple jobs daily across LinkedIn, Indeed, Naukri, Workday, Greenhouse, Lever, and similar platforms.

---

## 2. Problem Statement

Job seekers waste significant time on repetitive tasks:
- Reading full JDs to assess fit before deciding to apply
- Manually filling the same personal/professional details on every form
- Each platform uses different field nomenclature (Full Name vs First + Last vs Legal Name)
- Switching between apps/tabs to copy-paste resume details

**Speed Up eliminates this friction.**

---

## 3. Goals & Non-Goals

### Goals
- Let users build a profile once, reuse everywhere
- Detect job postings in real-time and score fit against user profile
- Auto-fill any job application form intelligently across different nomenclatures
- Provide a persistent, non-intrusive floating widget for quick access

### Non-Goals (v1)
- Auto-submitting applications without user confirmation
- Parsing uploaded PDF resumes (v2)
- iOS support
- Cover letter generation (v2)

---

## 4. User Personas

**Persona 1 — The Mass Applier**
Applying to 20-30 jobs/day. Needs speed. Doesn't want to type the same info repeatedly.

**Persona 2 — The Selective Applier**
Applies carefully. Needs fit analysis to decide whether a job is worth applying to before investing time.

---

## 5. Core Features

---

### Feature 1 — User Profile (Home Screen)

The single source of truth for all autofill data.

#### 5.1.1 Personal Information
```
- First Name
- Last Name  
- Full Name (auto-composed, editable)
- Email (primary + optional secondary)
- Phone (with country code)
- Location (City, State, Country, Pincode)
- LinkedIn URL
- Portfolio / GitHub URL
- Date of Birth (optional, some forms need it)
- Gender (optional)
- Nationality / Work Authorization Status
```

#### 5.1.2 Professional Summary
```
- Current Job Title
- Years of Experience (total)
- Short Bio / About (2-3 lines for text areas)
```

#### 5.1.3 Tech Stack / Skills
```
- Primary Skills (tags, e.g. React, TypeScript, Kotlin)
- Secondary Skills (familiar but not primary)
- Domain expertise (e.g. Frontend, Backend, ML, DevOps)
```

#### 5.1.4 Work Experience
Each entry:
```
- Company Name
- Job Title / Designation
- Start Date → End Date (or Present)
- Location (Remote / City)
- Description (bullet points)
- Tech used in this role
```

#### 5.1.5 Education
Each entry:
```
- Institution Name
- Degree (B.Tech, M.Sc, etc.)
- Field of Study
- Start Year → End Year
- CGPA / Percentage (optional)
```

#### 5.1.6 Resume
```
- Upload PDF resume (stored locally)
- File name, size, upload date shown
- Option to have multiple resumes (General, Frontend-specific, etc.)
- Active resume selector
```

#### 5.1.7 Certifications / Links (optional section)
```
- Certification name, issuer, year
- External links (project links, publications)
```

---

### Feature 2 — Floating Widget

A persistent overlay that lives on top of all other apps. The primary interaction surface for Speed Up while the user is on a job site.

#### 5.2.1 Widget States

```
State 1: Idle
└── Small circular icon (Speed Up logo) floating on screen edge

State 2: Job Detected
└── Icon pulses / changes color (green = good fit, yellow = partial, red = low fit)
└── Badge shows fit score (e.g. "82%")

State 3: Expanded Panel
└── Triggered by tapping the icon
└── Bottom sheet slides up as an overlay
```

#### 5.2.2 Expanded Panel Contents

```
┌─────────────────────────────────┐
│  ⚡ Speed Up                 ✕  │
├─────────────────────────────────┤
│  📋 Job Detected                │
│  "Senior Frontend Engineer"     │
│  Flipkart · Bangalore           │
├─────────────────────────────────┤
│  FIT SCORE          82%  🟢     │
│  ─────────────────────────────  │
│  ✅ React, TypeScript match     │
│  ✅ 2+ yrs experience match     │
│  ⚠️  Requires AWS (you: basic)  │
│  ❌ Requires ML experience      │
├─────────────────────────────────┤
│  [  View Full Analysis  ]       │
│  [  Auto Fill Form      ]  ←    │
│  [  Skip this job       ]       │
└─────────────────────────────────┘
```

#### 5.2.3 Widget Permissions
- Requires `SYSTEM_ALERT_WINDOW` permission
- User prompted on first launch with clear explanation
- Widget position saved (user can drag it anywhere on edge)
- Option to hide widget temporarily

---

### Feature 3 — Job Detection & Fit Analysis

When user opens a job posting in any app or browser.

#### 5.3.1 Detection Triggers
```
AccessibilityEvent: TYPE_WINDOW_STATE_CHANGED or TYPE_WINDOW_CONTENT_CHANGED
→ Service scans node tree for job-posting patterns
→ Looks for signals:
   - Keywords: "Job Description", "Responsibilities", "Requirements", 
               "Qualifications", "We are looking for", "About the role"
   - Structural: Long text blocks + skills list + apply button nearby
→ If detected: extract job content, run fit analysis
```

#### 5.3.2 Job Content Extraction
Extracts from node tree:
```
- Job Title
- Company Name
- Location / Remote flag
- Required Skills (parsed from bullets)
- Experience required (regex: "X+ years", "X-Y years")
- Education requirement
- Nice-to-have vs must-have (parsed from section headers)
- CTC / Salary (if visible)
```

#### 5.3.3 Fit Scoring Logic
```
Score breakdown (100 points total):

Skills Match         → 40 pts
  - Exact match:     +4 pts each (up to 10 skills)
  - Related match:   +2 pts each (e.g. React Native when React required)

Experience Match     → 25 pts
  - Meets required:  25 pts
  - Within 1 year:   15 pts
  - Underqualified:  5 pts

Domain Match         → 20 pts
  - Same domain:     20 pts
  - Adjacent:        10 pts

Education Match      → 10 pts
  - Exact degree:    10 pts
  - Equivalent:      7 pts

Location / Remote    → 5 pts
  - Match or remote: 5 pts
  - Mismatch:        0 pts
```

Score interpretation:
```
80-100 → 🟢 Strong Match — Apply with confidence
50-79  → 🟡 Partial Match — Review gaps before applying
0-49   → 🔴 Low Match — Significant skill gap
```

AI layer (Claude API) is used for semantic skill matching — so "Node.js" matches "Express", "React" matches "React.js / ReactJS", "ML" partially matches "TensorFlow".

---

### Feature 4 — Auto Fill Engine

The most complex feature. Fills forms correctly regardless of field nomenclature.

#### 5.4.1 Field Detection Strategy

The engine scans all `EditText`, `Spinner`, `CheckBox`, and web accessibility nodes and applies a multi-signal approach to identify what each field is asking for:

```
Signal Priority (checked in order):
1. viewIdResourceName   e.g. "id/first_name", "id/email_input"
2. hint                 e.g. "Enter your first name"
3. contentDescription   e.g. "First name field"
4. labelFor             Associated TextView label text
5. previousSiblingText  Label visually above the field
6. placeholderText      (WebView fields)
7. AI fallback          Send full context to Claude if still ambiguous
```

#### 5.4.2 Field Normalisation Map

This is the core intelligence — mapping hundreds of real-world field names to canonical profile fields:

```
CANONICAL: first_name
Matches: "first name", "firstname", "given name", "first_name", 
         "fname", "forename", "legal first name"

CANONICAL: last_name  
Matches: "last name", "lastname", "surname", "family name", 
         "last_name", "lname", "legal last name"

CANONICAL: full_name
Matches: "full name", "fullname", "your name", "name", 
         "legal name", "complete name", "applicant name"
→ Smart fill: if separate first/last detected nearby, fill both
→ Otherwise: fill "Vatsal Shah" as combined

CANONICAL: email
Matches: "email", "email address", "e-mail", "work email",
         "personal email", "contact email"

CANONICAL: phone
Matches: "phone", "mobile", "phone number", "mobile number",
         "contact number", "cell", "telephone"

CANONICAL: location_city
Matches: "city", "current city", "location", "city of residence"

CANONICAL: location_state
Matches: "state", "state/province", "province"

CANONICAL: location_country
Matches: "country", "nationality country", "country of residence"

CANONICAL: linkedin
Matches: "linkedin", "linkedin url", "linkedin profile",
         "linkedin.com/in/...", "social profile"

CANONICAL: portfolio
Matches: "portfolio", "website", "personal website", 
         "github", "github url", "portfolio url"

CANONICAL: current_title
Matches: "current role", "current position", "job title",
         "current title", "designation", "your role"

CANONICAL: total_experience
Matches: "years of experience", "total experience", 
         "work experience (years)", "experience"

CANONICAL: resume_upload
Matches: file input with accept=".pdf,.doc" or
         button text: "upload resume", "attach cv", "upload cv",
         "choose file", "browse", "upload document"

CANONICAL: cover_letter
Matches: "cover letter", "why do you want to join", 
         "why this role", "tell us about yourself" (long textarea)

CANONICAL: gender (optional field)
Matches: "gender", "sex", dropdown with Male/Female/Other options

CANONICAL: dob
Matches: "date of birth", "dob", "birth date", "birthday"

CANONICAL: work_authorization
Matches: "are you authorized to work", "work permit", 
         "visa status", "right to work"
→ Fill from user's pre-set authorization status
```

#### 5.4.3 Work Experience Section Detection

Many platforms have "Add Work Experience" repeating sections:

```
Detection:
- Button text: "Add experience", "Add work history", "+ Add position"
- Repeated section pattern: multiple identical field groups

Fill strategy:
1. Detect existing empty section OR click "Add" to create one
2. For each experience entry in user profile:
   - Fill company name
   - Fill job title
   - Fill start/end date (handle MM/YYYY, YYYY, date picker)
   - Fill description
3. Repeat for each entry
4. Confirm with user before proceeding to next section
```

#### 5.4.4 Date Field Handling

Date fields are notoriously varied:

```
Type 1: Plain text → "MM/YYYY" or "YYYY-MM-DD" → direct text input
Type 2: Separate dropdowns → Month dropdown + Year dropdown → select each
Type 3: Date picker dialog → navigate calendar via accessibility actions
Type 4: Two text fields → Start Month + Start Year separate fields
```

#### 5.4.5 Dropdown / Select Field Handling

```
For Spinner / DropdownMenu:
- Perform ACTION_CLICK to open
- Scan child nodes for matching option text
- Perform ACTION_CLICK on matching option

For Radio buttons:
- Scan all options
- Match to user's stored value
- ACTION_CLICK on correct option
```

#### 5.4.6 Resume Upload Handling

```
1. Detect upload field (file input or upload button)
2. Perform ACTION_CLICK
3. System file picker opens
4. Use AccessibilityService to navigate picker:
   - Find user's stored resume path
   - Click on it
5. Confirm selection
```

#### 5.4.7 Auto Fill User Flow

```
User taps "Auto Fill Form" in widget
        ↓
Engine scans all visible fields
        ↓
Shows preview in overlay:
┌─────────────────────────────────┐
│  Fields Detected (8)            │
│  ─────────────────────────────  │
│  ✅ First Name    → "Vatsal"    │
│  ✅ Last Name     → "Shah"      │
│  ✅ Email         → "v@..."     │
│  ✅ Phone         → "+91..."    │
│  ✅ LinkedIn      → "url"       │
│  ⚠️  Current CTC  → Not set    │
│  ✅ Resume        → resume.pdf  │
│  ❓ "Hobbies"     → Unknown     │
├─────────────────────────────────┤
│  [ Fill All ]  [ Fill Selected ]│
└─────────────────────────────────┘
        ↓
User taps "Fill All"
        ↓
Engine fills sequentially with 
small delays (human-like)
        ↓
Shows completion toast
```

---

## 6. App Screens

```
1. Onboarding (first launch)
   ├── What Speed Up does (3 slides)
   ├── Request Accessibility Service permission
   ├── Request Overlay permission
   └── Go to Profile Setup

2. Home / Profile Screen
   ├── Tab: Personal Info
   ├── Tab: Experience
   ├── Tab: Education  
   ├── Tab: Skills
   └── Tab: Resume

3. Settings Screen
   ├── Widget appearance (color, size, position)
   ├── Auto-fill behavior (ask before fill / fill directly)
   ├── Notification preferences
   ├── Manage permissions
   └── Clear data

4. Job Analysis Screen (opened from widget)
   ├── Full JD text
   ├── Extracted skills list
   ├── Fit score breakdown
   └── Apply / Skip

5. Fill Preview Screen (overlay)
   ├── Detected fields list
   ├── Mapped values
   ├── Edit individual values
   └── Confirm fill

6. History Screen (v2)
   └── Jobs applied, fit scores, dates
```

---

## 7. Permissions Required

| Permission | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Read screen, perform actions |
| `SYSTEM_ALERT_WINDOW` | Show floating widget |
| `READ_EXTERNAL_STORAGE` | Access resume PDF for upload |
| `INTERNET` | Claude API for fit analysis |
| `FOREGROUND_SERVICE` | Keep service alive |
| `RECEIVE_BOOT_COMPLETED` | Restart service after reboot |

---

## 8. Technical Architecture

```
app/
├── ui/
│   ├── onboarding/
│   ├── profile/
│   │   ├── PersonalInfoFragment
│   │   ├── ExperienceFragment
│   │   ├── EducationFragment
│   │   ├── SkillsFragment
│   │   └── ResumeFragment
│   ├── settings/
│   └── jobanalysis/
│
├── service/
│   ├── SpeedUpAccessibilityService.kt   ← core service
│   ├── FloatingWidgetService.kt          ← overlay manager
│   └── FieldDetectorService.kt           ← field mapping engine
│
├── engine/
│   ├── ScreenReader.kt                  ← node tree parser
│   ├── JobDetector.kt                   ← detects job postings
│   ├── FitAnalyzer.kt                   ← scoring logic
│   ├── FieldMapper.kt                   ← nomenclature resolution
│   └── ActionExecutor.kt                ← performs fills/clicks
│
├── ai/
│   └── ClaudeClient.kt                  ← fit analysis + ambiguous field resolution
│
├── data/
│   ├── local/
│   │   ├── UserProfileDao
│   │   ├── ExperienceDao
│   │   └── AppDatabase (Room)
│   └── model/
│       ├── UserProfile.kt
│       ├── WorkExperience.kt
│       ├── Education.kt
│       └── JobPosting.kt
│
└── utils/
    ├── NodeTreeUtils.kt
    ├── DateParser.kt
    └── FieldNormalizer.kt
```

---

## 9. Data Storage

All data stored **locally** on device (no backend required for v1):

```
Room Database:
- user_profile table
- work_experience table
- education table  
- skills table
- job_history table (v2)

File Storage:
- Resume PDF stored in app's private storage
- Multiple resumes supported

SharedPreferences:
- Widget position, size, color
- Auto-fill behavior settings
- Onboarding completion flag
```

---

## 10. Phased Rollout

### Phase 1 — Foundation
- Profile setup screens
- Floating widget (idle + expand states)
- Accessibility Service skeleton
- Basic field detection (name, email, phone)
- Fill on LinkedIn Easy Apply

### Phase 2 — Intelligence
- Job detection from node tree
- Fit scoring (rule-based first)
- Full field normalisation map
- Work experience section filling
- Chrome / browser support

### Phase 3 — AI Layer
- Claude API for fit analysis
- Ambiguous field resolution via AI
- Semantic skill matching
- Cover letter generation

### Phase 4 — Polish
- Fill history / job tracker
- Multiple resume profiles
- Smart resume selector (pick resume based on job)
- Notifications for high-fit jobs

---

## 11. Edge Cases to Handle

| Scenario | Handling |
|---|---|
| Field label is an icon only | Fall back to AI with screenshot context |
| Form is multi-step / paginated | Fill current page, detect "Next" button, continue |
| CAPTCHA blocks form | Alert user, pause auto-fill |
| App uses FLAG_SECURE | Notify user, disable fill for that screen |
| Date picker is a custom wheel | Use scroll accessibility actions |
| Field already filled | Skip or ask user before overwriting |
| Two email fields (email + confirm email) | Detect "confirm" pattern, fill same value |
| Phone with country code dropdown | Fill dropdown first, then number field |
| Required field not in user profile | Highlight in red in preview, ask user to fill manually |

---

This PRD covers everything needed to scope and build v1 through v3. Want me to start with the Kotlin project structure, the `AccessibilityService` skeleton, or the floating widget implementation?