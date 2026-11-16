# DroidHands design system

The Android app follows one small design system so every screen looks and behaves the same. It
borrows from apps known for calm, trustworthy UX:

- **iOS Settings, 1Password, Revolut, Monzo**: inset grouped lists, colored icon tiles, one hero card per screen.
- **Linear, Stripe**: a restrained palette with one brand accent, and red only for danger.
- **Tailscale**: the status comes first, and on/off is a single big switch.

Tokens live in `ui/theme` and components in `ui/components`. Screens use only these components.
They don't style Material widgets themselves.

## Tokens

| Token | Value | Use |
|---|---|---|
| Brand | Indigo (`#4F46E5` light, `#818CF8` dark) | Primary buttons, links, selected states, focus |
| Background | `#F5F6F8` light, `#0B0C10` dark | Behind everything. Top bar and sticky bars share it |
| Card | White (light), `#16171D` (dark) via `surfaceContainerLowest` | Every card and list group |
| Fill | `surfaceContainerHighest` | Code blocks, URL fields, the pairing code |
| Text | `onSurface` for content, `onSurfaceVariant` for secondary text, subtitles and footers | |
| Status | `statusColors()`: `ok` green, `warning` amber, `error` red, `pending` brand, `off` gray | Text, pills, badges, progress |
| Accents | `Accents.*` (saturated) | Backgrounds of `IconTile` (white glyph). For status tiles use `StatusLevel.accent()` |
| Tint | Status or brand color at `TINT_ALPHA` (12%) | Pill, badge and secondary-button backgrounds |

There is no dynamic (wallpaper) color, so every phone sees the same tested colors. Never hard-code
a color in a screen.

Shapes: 8 (small chips), 10 (icon tiles), 12 (`medium`: fields, notices, code), 16 (`large`: cards,
list groups), 24 (dialogs), and pills (`CircleShape`) for buttons, chips and badges.

Type: `headlineMedium` for hero numbers and states, `titleLarge` for page titles, `titleMedium`
for card titles, `bodyLarge` for row titles, `bodyMedium` for subtitles, `bodySmall` for footers
and meta lines, and `titleSmall` in `onSurfaceVariant` for section titles. Don't change font sizes
or weights inline.

Spacing: 16dp screen gutter (`padded()`), 16dp inside cards (`CardPadding`), 20dp between top-level
blocks on a screen, 12dp inside a card, and 8dp between buttons.

## Components

| Need | Component |
|---|---|
| Screen | `ScreenScaffold`. Tabs pass `large = true` (big collapsing title); pushed pages pass `onBack` |
| Group of settings or items | `ListSection(title, footer, action)` holding `ListRow`s separated by `ListDivider()` (`ListDivider(IconRowInset)` after rows with a leading tile) |
| Row | `ListRow(title, subtitle, leading, trailing, onClick)`. It shows a chevron by itself when it navigates. `SwitchRow` for toggles, `RowValue` for a trailing value |
| Row icon | `IconTile(icon, Accents.X)`. Same meaning, same color: Indigo for DroidHands and connections, Green for allowed/running, Orange for limits/warnings, Red for danger, Gray for info/neutral, Blue/Teal/Purple/Pink to tell list entries apart |
| App or agent without an icon | `Monogram(name)` |
| Free-standing card | `AppCard` (white, borderless, 16dp corners, optional `onClick`) |
| Hero status / empty state icon | `IconBadge(icon, color)` (tinted circle) |
| Badge | `StatusPill(text, color, dot)`. It is the only badge style |
| Filter / segmented choice | `ChoiceChip`. `DropdownSelector` uses the same look |
| Callout | `Notice(kind = INFO / WARNING / DANGER, title, action)` |
| Empty list | `EmptyState(title, message, icon, action)` |
| Code, URL, token | `CodeBlock` / `CopyableCode` |
| Sticky main action | `BottomActionBar` with one `PrimaryButton(fullWidth = true, size = LARGE)`, or Tertiary + Primary |
| Confirmation | `ConfirmDialog` |
| Title above free-standing cards | `SectionHeader` (same look as a `ListSection` title) |

Never use `OutlinedCard`, `ElevatedCard`, `Card`, `OutlinedButton`, `FilledTonalButton`, raw
`Button`/`TextButton`, `FilterChip`, `ExtendedFloatingActionButton` or `HorizontalDivider` in a
screen. The components above wrap them.

## Buttons

| Style | Looks | Use |
|---|---|---|
| `PrimaryButton` | Filled indigo pill | The one main action of a screen or card: Connect, Allow, Save, Done, Get started |
| `SecondaryButton` | Tinted indigo pill | Other real actions: Copy, Deny on approval cards, Open settings, Grant |
| `TertiaryButton` | Text only | Cancel, Not now, Back, See all, Show/Hide, Share |
| `destructive = true` | Red | Revoke, Delete, Revoke all. Primary only inside a confirmation; elsewhere Secondary or Tertiary |

Rules:
- **One primary per view.** A card may have its own primary only if it is a self-contained task
  (approval, pairing, update).
- **Button order:** dismissive on the left, affirmative on the right (Cancel · Save, Deny · Allow).
  Approval cards split the width evenly between Deny and Allow.
- `size = LARGE` + `fullWidth` for the call to action of a screen (Home's "Connect an AI agent",
  wizard steps, editors). The default size is for inline use.
- **Adding to a collection:** in the empty state, a primary button. With items, a "+ Add …" row in
  the list (`ListRow` with an indigo `IconTile(Icons.Filled.Add)` and primary-colored title), as
  in iOS "Add Account". No floating action buttons.

## Patterns

- **Lists over cards.** Anything that is a list of things (agents, apps, permissions, settings,
  activity) is a `ListSection`. Use cards for one-off blocks (hero, approval, setup hint).
- **Explain below, not inside.** Long explanations go in a `ListSection` footer or a `Notice`.
  Row subtitles stay to one or two lines.
- **Status = tile color + pill.** A running thing shows a green tile and a `StatusPill("Live", ok, dot = true)`.
  A problem shows an amber or red tile with the reason in the subtitle.
- **Destructive actions** sit last, in their own `ListSection`, as a red-titled `ListRow`
  (`titleColor = colorScheme.error`). They always open a `ConfirmDialog(destructive = true)`.
- **Text fields:** `OutlinedTextField` with `shape = MaterialTheme.shapes.medium`, full width,
  supporting text for hints and errors.
