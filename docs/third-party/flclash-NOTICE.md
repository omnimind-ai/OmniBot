# FlClash Android navigation and motion

The Flutter components below contain code adapted from
[chen08209/FlClash](https://github.com/chen08209/FlClash), pinned to commit
`7fd417fb9a9a277c6a9339a8ac87f907df7c25f7` (retrieved 2026-10-28).
Copyright belongs to the respective FlClash authors. The copied portions are
licensed under GNU GPL version 3; the complete upstream license is preserved in
[flclash-LICENSE.txt](flclash-LICENSE.txt). These portions are not covered by
OmnibotApp's separate commercial license.

| OmnibotApp component | Upstream source |
| --- | --- |
| `ui/lib/widgets/flclash/navigation_dock.dart` | `lib/ui/navigation_dock.dart`, with surface/shape/type tokens adapted to Flutter Material and OmniPalette |
| `ui/lib/widgets/flclash/tab_view.dart` | `lib/pages/home.dart` (`_HomePageView` slide branch, adjacent-page warp, kept-page activity gating) |
| `ui/lib/widgets/flclash/page_activity.dart` | Activity boundary from `lib/ui/inherited.dart` (`PageActivityScope`), `lib/widgets/active_polling.dart`, and `lib/pages/home.dart`; settle-aware read scheduling is an OmnibotApp adaptation |
| `ui/lib/widgets/navigation/page_route.dart` | `lib/ui/navigator.dart` (`CommonRoute`), adapted to the existing GoRouter `Page` and imperative `PageRoute` entry points |
| `ui/lib/widgets/navigation/page_transition.dart` | `lib/ui/page_transition.dart` (`SharedXPageRouteMixin`, `SharedXPageTransition` and the paint-only fade); OmniPalette supplies the surface |
| `ui/lib/widgets/navigation/open_container.dart` | `lib/ui/open_container.dart` (`ListItem.open` on Android) |
| `ui/lib/widgets/navigation/drag_back.dart` | `lib/ui/drag_back.dart` |
| `ui/lib/widgets/navigation/deferred_push.dart` | `lib/ui/deferred_push.dart` |
| `ui/lib/widgets/navigation/keyboard_inset_hold.dart` | `lib/ui/keyboard_inset_hold.dart` |
| Settings row transition decoration in `ui/lib/features/home/pages/settings/settings_page.dart` | `lib/ui/list.dart` (`ListItem.open`) and `lib/ui/list_selected.dart` (`DecorationListItem`); group corners and surface adapted to the existing OmniSettingsGroup |
| `ui/lib/widgets/flclash/search_header.dart` and `search_surface.dart` | Proxy page `lib/ui/scaffold.dart`, `lib/ui/search_field.dart`, `lib/ui/button.dart`, `lib/ui/motion.dart`, and the solid/liquid-glass surface and press portions of `lib/ui/goo.dart` and `lib/ui/navigation_dock.dart` |
| `ui/lib/widgets/flclash/liquid_glass.dart`, `liquid_glass_capture.dart`, and `ui/shaders/liquid_glass.frag` | `lib/ui/liquid_glass.dart`, `lib/ui/liquid_glass_capture.dart`, and `shaders/liquid_glass.frag`; Flutter Material/HCT/reduced-motion adapters replace the upstream app helpers |
| `ui/lib/widgets/flclash/popup.dart` and `popup_geometry.dart`, shared by `glass_popup.dart` and `omni_menu.dart` | `lib/ui/popup.dart` (flat popup, source-control morph and liquid-glass platter), `lib/ui/goo.dart`, `lib/ui/motion.dart`, and the solid `menuShadows` constants from `lib/ui/liquid_glass.dart` |
| `ui/lib/features/home/widgets/conversation_context_menu.dart` | Profile long-press entry from `lib/ui/context_menu.dart` and the independent no-source route in `lib/ui/popup.dart`; the existing OmnibotApp action-panel position is retained |
| `ui/lib/widgets/flclash/elastic_press.dart` and `menu_interaction.dart` | `ElasticPress` and its spring/paint transform from `lib/ui/navigation_dock.dart`; row press, carried press and painted hit testing from `CommonPopupMenu` in `lib/ui/popup.dart` |
| `ui/lib/widgets/flclash/common_switch.dart`, exposed by `OmniSettingsSwitch` | `lib/ui/switch.dart` (`CommonSwitch` track/thumb geometry, lifted glass lens, toggle/press timing, drag and focus behavior); controlled-value, cancellation and reduced-motion handling adapted to OmnibotApp |
| `OmniSettingsSearchField` in `ui/lib/widgets/settings_ui.dart` | `lib/ui/search_field.dart` (44 dp capsule, 20 dp search icon, 41 dp text inset, 49 dp clear slot and keyboard-only focus ring), using the shared glass surface |
| `ui/lib/widgets/omni_sheet_motion.dart` | `lib/ui/snap_sheet.dart` (0.55 rubber-band coefficient, 100 dp extent, mass 3/stiffness 1000 critically damped return and scroll handoff), adapted to existing Flutter modal routes |
| Glass alert/sheet adapters in `omni_glass.dart` and `omni_glass_routes.dart` | Alert rim, hairline, shadow and veil composition in `lib/ui/dialog.dart` and `lib/ui/liquid_glass.dart`; default alert/sheet scrim values in `lib/ui/color.dart`. Flutter routes and alert layout retain focus, results, keyboard insets and back guards; the shared sheet motion adapter owns gesture handoff and requests dismissal through the route. |

The navigation dock retains the upstream floating-navigation layout, spring
simulations, drag selection, lens deformation and per-icon selection effects.
OmnibotApp enables the optional glass path at ultra quality by default; the
appearance preference can disable it. The optional glass
shader path now uses the shared upstream capture host described below.
Desktop-only integration is not included. Application-specific
icons, labels, colors, providers and destinations are supplied by OmnibotApp.

Tab motion uses FlClash's optional slide branch: the PageView moves horizontally
over `kTabScrollDuration` (300 ms) with `easeOut`, retaining the adjacent-page
warp. FlClash's upstream default remains the separate 220 ms fade branch.
OmnibotApp adds reduced-motion support and explicit kept-tab focus/semantics
gating. To avoid moving visible pages when clicks interrupt a warp, it reverses
within the current page pair from the existing offset; a third destination is
queued until landing, and only the latest requested destination continues.
The selected cold destination gets one layout frame before its slide starts,
adapting the upstream `DeferredPushRouteMixin` preparation mechanism to the
PageView's adjacent cache slot. Unrequested slots remain placeholders, and
visited pages retain their state. Static settings rows are present throughout
the slide. The application's UI reads and result publication use the actual
tab activity/landing signal instead of a timer: identical pending reads merge,
and reactivation invalidates an already-issued old snapshot and schedules one
fresh read. This scheduling concerns page UI only; ACP events and lifecycle
ownership remain with the existing runtime and history services.
The settings transition follows the actual `ToolsView -> ListItem.open ->
OpenContainer` mobile path. As upstream, the container is a page-colored
rectangle, with no `closedShape` override; the closed row carries its own
group-position corners and card surface when reparented. It uses the upstream
paint-only geometry, 300 ms `fastOutSlowIn` (flipped on completed-route return),
and page fade windows of 0.2–0.4 on entry and 0.6–0.8 on return. GoRouter owns
the route, and measurements use that destination's navigator even if the source
row is inside a nested navigator.
Only Flutter pointer drags enter the ported `DragBackRouteMixin`: linear
full-page travel, then the upstream 350 ms `fastEaseInToSlowEaseOut` settle.
As in upstream, Android system back has no additional progress observer and
commits through the official Router/Navigator pop, preserving the 300 ms
contraction into the source row. Cancellation leaves the page in place.
Pointer entry no longer depends on the retired predictive-back setting;
reduced-motion mode skips programmatic transforms.
Ordinary pages now also retain the upstream `CommonRoute` motion: 450 ms,
quarter-width horizontal travel, emphasized easing and paint-only fade windows.
Home-to-chat, other GoRouter destinations and imperative pushes share this route.
The Page adapter reads updated settings so changing a conversation retains its
existing ChatPage owner. These adapters and the moved navigation helpers retain
the upstream license despite their application-specific names.
Gesture admission also observes Navigator's departing next-route animation when
container routes do not participate in `secondaryAnimation`, so an uncovered
page cannot start dragging beneath an overlay that is still exiting.
Deferred push retains the upstream one-frame preparation before opening.
Keyboard inset holding uses the application's `TickerMode` instead of the
upstream `PageActivityScope`. Settings-page UI loading also waits for its actual
route transition to settle through a shared route gate; ACP work and business
lifecycle ownership remain unchanged.

Home conversation search and the Agents contacts list share the upstream proxy
toolbar's grouped search button, spring-driven expansion/return, button-slot
collapse, field/cancel separation, and joining-surface geometry in both solid
and liquid-glass modes. They keep
their existing query/filter owners and add reduced-motion and inactive-tab focus
handling. Search uses the upstream 44 dp buttons, 72 dp toolbar and 16 dp edge
insets; it intentionally replaces the previous home-specific search animation.
The home search and menu share one 88 by 44 dp capsule, which is captured and
restored as a complete source even though only the 44 dp menu segment opens it.
The Agent switcher uses the same toolbar capsule and optional glass material. The menu retains
upstream pointer-down admission, source snapshots, the 450 ms opening spring
and 500 ms return morph. Popup geometry, blur and corner interpolation use the
upstream solid or liquid-glass path; application menu actions remain owned by
HomeDrawer. Plugin refresh updates the visible menu without delaying its opening. The flat menu also retains the opening press across rows, including
pressed-row feedback, slide-to-select, release and cancel. Submenus remain
application-owned. The same opening and return renderer also
retains the upstream liquid-glass branch when the shared scope enables it.

The same FlClash popup renderer now presents button-opened lists through
`showOmniMenu`, `showGlassPopup` and `showOverlayGlassPopup`. A shared source
wrapper owns the snapshot, temporary hiding and restoration of each complete
control. Route and OverlayEntry ownership remain distinct: input menus preserve
the existing editor focus, keyboard, cancellation and Android back registration.
Each owner retains its safe-area/keyboard-aware placement; long-press menus,
information cards and editors without a source use the upstream no-source
return path. Interrupted closes retain the full return clock, and reduced
motion skips transforms. There is one Flutter spring/geometry renderer rather
than a second approximation for the overlay path.

The native HTML preview's more menu uses
`app/src/main/java/cn/com/omnimind/bot/ui/components/FlClashPopupMenu.kt`, adapted
from the same upstream popup, motion and goo geometry. It retains the 450/500 ms
clocks, springs, source snapshot, platter/droplet geometry and smooth-min outline.
Android PopupWindow and Canvas own its presentation lifecycle; no Flutter engine
is created. The native outline uses rounded rectangles. Android 12 (API 31) and
later use RenderEffect for content blur; Android 10/11 (API 29/30) retain geometry
and opacity without that platform effect. This is a native motion adaptation,
not a claim of pixel-identical Flutter rendering.

The liquid-glass engine retains the upstream refraction/dispersion fragment
shader, regular/menu/pill/lens optical styles, glow/rim/goo uniforms, standard
and ultra quality modes, one-host capture atlas, frost-dependent downsampling,
32-pixel atlas bands, retained captures, and explicit image/shader disposal.
`LiquidGlass.warmUp()` primes the same cached program used by painting; a failed
load is cached and does not block the first screen or retry every frame. The
unsupported-engine fallback is upstream's captured frost/tint path, not a
replacement BackdropFilter implementation. Reduced motion settles press glow
immediately while preserving the static glass material.

The shared popup renderer now restores the upstream glass platter and droplet
branches, including held capture bounds and the return-to-source optical blend.
OmnibotApp keeps the same pane through the stable menu state and marks child
surfaces with `LiquidGlassSurfaceScope`, so generic Route and Overlay menus do
not cover the glass with an opaque card or create a second capture. Source
snapshots use `LiquidGlass.withoutPanes`, as upstream, to capture glyphs without
baking a backdrop into the returning control.
Each source supplies its visible shape, material and capture bounds. Circular
buttons capture their painted surface independently of the larger touch target;
input fields include floating labels without moving their supporting text.
Returning snapshots and restored sources hand off in the same frame, with
source alpha compensation to avoid doubled fills during the final blend.

The home new-conversation, remote MCP add and run-log action controls retain their original
FloatingActionButton, Hero, tooltip, dimensions and action owners. A shared
presentation wrapper supplies the upstream regular glass style in their original
shape with theme-aware foregrounds, observing the existing pointer sequence for
press glow. Glass mode removes the FAB's separate material shadow; disabling
glass restores the original solid appearance and elevation.
The chat message-anchor button uses the same regular glass surface and
glass-aware fade while retaining its tap, long-press and fan interaction.

Home conversation long presses follow the profile `ContextMenuRegion` menu path:
the independent menu opens from the actual press point over 450 ms and shrinks
to 0.8 while fading over 200 ms on close. OmnibotApp additionally retains its
selected-conversation lift, scale and mild veil: an inert preview temporarily
replaces the source row visually, and the original row is restored on the same
frame that the closing preview disappears.
The menu uses the shared popup renderer and action rows; its final x/y retain
the previous conversation action-panel position, including bottom-edge
avoidance, as requested. The original long-press pointer is not carried into
the menu, matching upstream. A new press on the menu can drag between actions.
Business actions still wait for the closing overlay to be removed.

The shared menu rows use the upstream 24 dp superellipse panel, 8 dp inset,
16 dp row corners, 12 dp row padding, 20 dp icons and 15 sp/20 dp labels.
Menus share the upstream elastic press: spring-driven lift, bounded pull
beyond the pane edges, axis stretch and spring return, with hit testing mapped
to the painted bounds. Ordinary scrolling retains its gesture owner; menu row
press tracking yields to scrolling and excludes embedded editors and controls.
Glass and content transform together on one surface. Reduced motion retains
selection behavior without the elastic transforms.

Standalone round controls and glass FABs share the same `ElasticPress` spring
and bounded drag transform from `navigation_dock.dart`. Their original hit
targets, keyboard activation, Hero and action callbacks remain in place; press
feedback follows the upstream `ElasticButton` no-ink style. The chat-anchor
control retains its existing long-press selection gesture while its material
and icon stretch together. Glass-off and reduced-motion modes disable this
presentation transform.

Existing modal surfaces use a single glass owner with the upstream alert rim
and shadows. Their route barriers separate the uniform veil from the capture,
as FlClash does, instead of sampling a painted dim layer into the pane. Explicit
caller barrier colors remain supported. The chat composer resolves its capsule
and expanded corners against its actual size on the analytic corner shader
path; this avoids profiling a very wide stadium outline. Shared segmented
controls use the upstream lens style and elastic lift, with OmnibotApp retaining
its existing track layout and controlled selection callbacks.

Settings toggles use the upstream 52 by 32 dp track and 24 dp resting thumb.
Pressing lifts the thumb into a glass lens; dragging follows the finger and
settles back to the owner-provided value. Keyboard activation, screen-reader
semantics, cancellation and reduced motion remain supported. Settings search
fields use the same capsule geometry, icon insets and keyboard-only focus ring,
while refresh and other round actions share the existing elastic glass button.

Modal sheet motion uses the upstream snap-sheet rubber-band and critically
damped spring. A deliberate pull stretches the single pane; ordinary list
scrolling and text selection retain their gesture owners. Existing custom
extent controls report only their unconsumed overdrag to the same elastic
surface, preserving their height and navigation policy. Compact environment
editing opts into content-fitting height instead of reserving half the screen.
