# Spec: Visual notebook creation and settings

## Product framing

- Target user: a phone or tablet owner who wants to start writing immediately without learning abstract cover, paper, and orientation terminology.
- Main job: understand what will be created before tapping Create and understand what each stored default changes.
- Primary action: choose a recognizable notebook template, optionally customize it, and create it.
- Current obstacle: text chips expose implementation labels but do not show the resulting cover or page. Settings are accurate but read as one long preference list.

## Chosen pattern

Use a visual configurator, not a generic form and not independent decorative cards. Four illustrated templates set defensible starting values, while a live preview remains the source of truth when individual options change.

Templates:

| Template | Cover | Paper | Orientation | Intended use |
| --- | --- | --- | --- | --- |
| Ruled notes | Periwinkle / Solid | Ruled | Portrait | Classes, meetings, continuous notes |
| Grid notebook | Sage / Grid | Grid | Portrait | Diagrams, calculations, technical notes |
| Dotted journal | Sand / Corners | Dot | Portrait | Flexible writing, planning, bullet journal |
| Blank sketchbook | Salmon / Band | Blank | Landscape | Drawing, mind maps, wide visual work |

Choosing a template updates the real cover, pattern, paper, and orientation controls. Any later manual change marks the configuration as Custom without losing the selected values.

## New notebook layout

### Tablet

- Use a custom modal surface up to 920 dp wide and 88% of available height.
- Left pane: large live notebook illustration with cover, binding, tab, title label, paper sample, and a compact summary.
- Right pane: horizontally scrollable template cards, then Cover, Paper, and Layout controls.
- Bottom action row stays visible: Cancel and Create notebook.

### Phone

- Use one vertically scrolling column.
- Show a compact live preview first.
- Show the four templates in a horizontal row with at least 152 dp cards.
- Continue with visual cover swatches, paper thumbnails, and portrait/landscape thumbnails.
- Keep the action row outside the scroll area.

## Settings layout

Replace the seven equal-weight accordion rows with four intentional groups:

1. Notebook defaults — expanded first, with the same live preview, template cards, paper, orientation, and finger-drawing default.
2. Drawing — pen/pencil/highlighter choice plus live stroke samples for both width sliders.
3. Interface & export — theme previews and page-transition control, followed by the factual PDF export summary.
4. App & privacy — local-recognition disclosure, storage usage, version, privacy/source links, and the existing ScanIt-matched support button.

The screen begins with a short heading and description, not a card dashboard. Group headers include one-line summaries so collapsed content is still understandable.

## Visual language

- Mood: quiet stationery catalog; illustrative but restrained.
- Typography: Android system type. Titles use semibold; explanations use body-medium and `onSurfaceVariant`.
- Color: existing warm paper workspace and curated cover colors. Cobalt indicates selection only.
- Radius: 10 dp controls and template cards; paper remains 2–4 dp.
- Elevation: one subtle shadow on the live notebook only. Template cards use borders, not stacked shadows.
- Icons: no decorative icon library. Compose-drawn notebook, binding, page lines, grid, and dots carry meaning.

## Component behavior

### NotebookPreview

- Accepts cover color, pattern, paper, orientation, title, and compact/large mode.
- Renders from the same enums persisted by Room; no separate screenshot assets can drift from behavior.
- Updates immediately when any choice changes.

### TemplateCard

- Shows a cover miniature, paper sample, template name, and one-line use case.
- Selected state uses a 2 dp primary border and check semantics.
- Disabled state is not needed because every template works on Android 10+.

### PaperOption

- Shows the actual blank, ruled, grid, or dot pattern in a small white page.
- The selected paper name remains visible for accessibility and translation safety.

### DrawingWidthPreview

- Shows a real graphite or translucent yellow stroke above the slider.
- Slider writes to DataStore only on value-change completion.

## Accessibility and motion

- Every illustrated choice has a readable label and selected state in semantics.
- Touch targets remain at least 48 dp.
- Text contrast remains WCAG AA.
- Keyboard focus order follows preview, templates, customization, then actions.
- Live preview changes do not animate when page transitions are disabled or system animator scale is zero.

## Data and compatibility

- No Room schema change.
- Add a pure `NotebookTemplate` mapping to existing `CreateNotebookRequest` fields.
- DataStore remains the source for defaults.
- Existing notebooks are unchanged.
- English remains the only interface language.
- API 29 and API 37 remain required acceptance targets.

## Verification

- Pure tests verify every template mapping and Custom detection.
- Compose tests verify template selection updates visible paper/orientation labels and Create emits the mapped request.
- Settings flow verifies Notebook defaults and drawing previews are reachable and support/version remain present.
- Visual QA captures phone and tablet creation/settings screens.
- Existing 24-test API 29 and API 37 suites must remain green before release resumes.

## Final cleanup

After GitHub/Google Play work is complete, stop only Péřko emulators and locally started Péřko HTTP/browser-test services. Remove regenerable project build directories and Péřko AVD data only after release artifacts, screenshots, source, signing material, and repository state are verified. Do not touch physical devices or unrelated emulators.
