UI Spec (v0.1)
==============

Purpose
-------
This document defines the UI rules and implementation constraints for bringing the app to a professional, consistent, Material 3 based interface without changing business logic. It is the single source of truth for UI refactors and future redesign iterations.

Scope
-----
In scope:
- Material 3 theming (colors, typography, shapes) as a global foundation.
- Reusable UI wrappers for consistent layout and components.
- Screen-by-screen UI refactor (visual structure, hierarchy, spacing, component choice).
- Accessibility and ergonomic defaults (tap targets, contrast, semantics).
- Separation of Debug/Diagnostics from user-facing flows.

Out of scope for v0.1:
- Any changes to business logic, RFID/QR/UHF workflows, sync logic, repositories, ViewModels, navigation architecture.
- New product features.
- Large UX redesign of flows (may come later, but not required to adopt this spec).

Success definition
------------------
A screen is considered done when:
- It uses Material 3 theme tokens (MaterialTheme.colorScheme, typography, shapes).
- It uses standardized layout patterns (Scaffold + TopAppBar, content padding, Section cards).
- It has a clear action hierarchy (one primary action, secondary actions are visually secondary, destructive actions are safe).
- It supports basic states: idle, loading, success, error, empty.
- It is accessible: contrast is acceptable, tap targets are ergonomic, content descriptions exist for non-text UI.

Design principles
-----------------
- Clarity over decoration: visual hierarchy must be obvious at a glance.
- Consistency over creativity: same component means same behavior everywhere.
- Fewer, stronger actions: one primary action per screen.
- Safe destructive actions: confirm, differentiate color, place away from primary.
- Debug belongs to Diagnostics: user screens must not expose raw debug panels by default.
- Density tuned for productivity: avoid oversized cards and excessive padding on data-heavy screens.
- Feedback always visible: show progress, status, and next steps.

Definitions
-----------
- Primary action: the single most important action on a screen (FilledButton).
- Secondary action: important but not the primary (FilledTonalButton or OutlinedButton).
- Tertiary action: rarely used action (TextButton, icon button, or overflow menu).
- Destructive action: deletes/clears/writes irreversible data (requires confirmation and safe placement).
- Section: a card-like block with title and content.
- Diagnostics: a separate area for debug data, tests, toggles.

Information architecture
------------------------
Navigation:
- Bottom Navigation stays as is (Find, Lookup, Verify Write, Batch, Settings).
- Each screen uses TopAppBar with a clear title.
- Screen content uses a single scrolling container (LazyColumn) unless there is a strong reason.

Screen layout baseline
----------------------
All screens:
- Use Scaffold (Material 3) with TopAppBar.
- Content container is LazyColumn with:
  - contentPadding: 16dp horizontal, 12dp vertical
  - item spacing: 12dp
- Use SectionCard for grouping related controls.
- Avoid custom tinted background panels behind everything.
- Avoid large empty areas. Prefer compact sections.

Theme and tokens
----------------
Colors:
- Use Material 3 ColorScheme generated from a single seed (brand green).
- Dynamic color is optional and off by default (can be enabled later explicitly).
- Semantic colors:
  - success: use colorScheme.tertiary or a dedicated success color mapped to ColorScheme where possible
  - warning: use colorScheme.secondary
  - error/destructive: use colorScheme.error

Typography:
- Use Material 3 typography scale. Do not introduce many custom sizes.
- Titles:
  - Screen title: TopAppBar default
  - Section title: titleLarge or titleMedium
- Body:
  - Primary text: bodyLarge
  - Supporting text: bodyMedium or bodySmall
- Numbers that matter (RSSI/score/counters): use a strong style (headlineMedium) but keep within hierarchy.

Shapes and elevation:
- Use Material 3 shapes.
- SectionCard uses ElevatedCard (preferred) or Card if elevation is undesired.
- Keep corner radii consistent across the app.

Component rules
---------------
Buttons:
- Primary: FilledButton
- Secondary: FilledTonalButton (preferred) or OutlinedButton (choose one and be consistent)
- Tertiary: TextButton or IconButton
- Destructive:
  - Never be the primary action by default
  - Requires confirmation (Dialog) or a long-press pattern
  - Visually distinct via error color or clear label

Inputs:
- Use OutlinedTextField for editable text.
- Show helper text for format instructions (EPC, QR).
- Provide trailing icons for paste/clear if useful.

Chips:
- Summary counters use AssistChip with short labels (e.g. "Total: 9").
- Avoid large pill panels for counters.

Segmented control:
- Use SingleChoiceSegmentedButtonRow for mode switching.

Lists:
- Use ListItem for rows in a list (search results, batch queue items).
- Use consistent leading icon, headline, supporting text, trailing actions.

Feedback:
- Snackbars for transient messages.
- Inline status text inside sections for ongoing processes.
- Progress indicator when scanning/syncing runs.

States
------
Each functional section must define:
- Idle: what the user sees before action
- Running: show that it is active, provide Stop/Cancel if needed
- Success: visible confirmation
- Error: readable message and next step
- Empty: clear guidance

Diagnostics separation
----------------------
- Debug toggles and raw values must live in Diagnostics, not in Find/Lookup/Verify/Batch.
- User screens may show a short friendly status, not raw internal counters.
- If a user needs advanced info, provide a "Open Diagnostics" entry from Settings.

Reusable UI wrappers (required)
-------------------------------
The following wrappers must exist and be used by migrated screens:
- AppScaffold(title, navigationIcon, actions, content)
- SectionCard(title optional, content)
- PrimaryButton(text, onClick, fullWidth default true)
- SecondaryButton(text, onClick, fullWidth default true)
- StatChip(label)
Optional helpers:
- SectionTitle(text)
- AppTextField(...) if repeated often

Screen guidelines
-----------------

Find
----
Goal: Fast "target EPC" setup and a clear proximity feedback loop.

Structure:
- Section: Target
  - OutlinedTextField for EPC input
  - Supporting text: "Paste or type the tag EPC."
  - Tertiary actions:
    - "Use last scanned" as small TextButton or inline action
    - Paste/Clear as trailing icons if helpful
- Section: Proximity
  - Clear status line: Idle / Running / Signal detected
  - Primary action: Start (when idle), Stop (when running)
  - Main metric: proximity score or value (large but not overwhelming)
  - Visual meter: linear or box meter, but aligned to Material 3 (no custom massive blocks)
  - Supporting: target short form (e.g. prefix...suffix)

Rules:
- Do not show raw debug lists (Any tags seen, Matched RSSI, etc.) in the main screen.
- Offer "Copy last seen EPC" only if it is a normal user need. Otherwise move to Diagnostics.

Lookup
------
Goal: Quick identify a tag and see a compact card from the local database.

Structure:
- Section: Scan
  - Two actions: Scan RFID, Scan QR (secondary actions if both are equal)
  - Show last scanned EPC as supporting text
  - If not found: show a single helpful message with next step
- Section: Result
  - If found: show a compact card with Name, EPC, Status, Location
  - If multiple: show a list (ListItem)
- Section: Sync (optional)
  - Show last sync time and status
  - Provide one clear action "Sync library" (secondary unless it is the main purpose of the screen)

Rules:
- Do not mix sync diagnostics with lookup results.
- If "Sync may be needed", provide a direct CTA button to sync.

Verify Write
-----------
Goal: Verify scanned EPC matches expected. If not, allow safe write of expected EPC.

Structure:
- Section: Scan tag
  - Actions: Scan RFID, Scan QR
  - Show scanning status as supporting text
- Section: Status
  - Expected EPC (from selected record)
  - Scanned EPC (from scan)
  - Match state: Matched / Not matched / Not scanned
  - Primary action appears only when it is safe and meaningful
- Action rules:
  - If scanned matches expected: show success state, no write action
  - If mismatch: allow "Write expected EPC" but require confirmation dialog or long-press
  - If scanned is empty: do not show write action as primary, prompt user to scan first

Rules:
- Writing is destructive/irreversible. Always confirm.
- Avoid a permanently disabled huge button. Prefer conditional visibility with explanation.

Batch
-----
Goal: Work through a batch queue with clear progress summary and a simple scan mode.

Structure:
- Top actions:
  - Import and Export should be secondary or top app bar actions, not oversized panels
  - Clear batch is destructive and should be in overflow or separated with confirmation
- Section: Summary
  - Counters as AssistChips: Total, Found, Not found, Unknown
- Section: Mode
  - Segmented control: Inventory sweep / Manual scan
- Section: Manual scan (or Inventory sweep)
  - Controls and list should be compact and readable

Rules:
- Avoid large colored background panels for everything.
- Destructive clear requires confirmation.

Settings
--------
Goal: Manage sync and app settings in a standard settings layout.

Structure:
- List-like layout using ListItem within SectionCard blocks
- Diagnostics entry:
  - "Open diagnostics" as a ListItem with icon
- Sync section:
  - last sync time, status
  - action: Sync library
- Save settings:
  - Only if settings are not auto-saved. If auto-save, replace with "Saved" feedback.

Rules:
- Settings is not a dashboard. Keep it clean and list-like.

Accessibility and ergonomics
----------------------------
- Tap targets: at least 48dp height for main buttons and interactive rows.
- Contrast: meet basic readability. Avoid low-contrast gray on tinted backgrounds.
- Content descriptions for icons and non-text UI.
- Support large font sizes without clipping.
- Avoid relying only on color to convey status.

Implementation constraints
--------------------------
- No hard-coded colors in screens. Use MaterialTheme.colorScheme.
- No ad-hoc padding. Use standard spacing values (4, 8, 12, 16, 24).
- Prefer UI wrappers for repeated patterns.
- Keep each PR small:
  - PR-UI-01: theme only
  - PR-UI-02: wrappers + doc
  - Next PRs: one screen at a time

PR strategy
-----------
PR-UI-01:
- Introduce Material 3 theme files and wire AppTheme at root.
- No screen redesign.

PR-UI-02:
- Add reusable wrappers and a small preview.
- No screen redesign.

PR-UI-03+:
- Migrate screens in order: Lookup, Verify Write, Batch, Find, Settings
- One screen per PR, UI-only.

Quality gates
-------------
For each PR:
- Build: ./gradlew :app:assembleDebug
- App launches (cold start)
- Provide before/after screenshots for the changed screen (when screen work starts)
- No logic changes and no behavioral regressions

Open questions (non-blocking for v0.1)
--------------------------------------
- Whether to enable dynamic color by default (recommended: no).
- Whether Settings should use explicit Save or auto-save (recommended: auto-save where safe).
- Confirmation style for destructive write (dialog vs long-press) (recommended: dialog first).
