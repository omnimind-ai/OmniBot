// Adapted from FlClash's ProxiesView -> CommonScaffold bar search,
// lib/ui/scaffold.dart, search_field.dart, button.dart, and motion.dart at
// 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7.
// Retains the upstream spring clock, staggered blur/fades, rectangle morph,
// group-slot collapse and solid GooSurface. App icons/palette and externally
// owned query state are the integration boundary. See docs/third-party/flclash-NOTICE.md.
// SPDX-License-Identifier: GPL-3.0-only
// ignore_for_file: prefer_initializing_formals

import 'dart:math' as math;
import 'dart:ui' show ImageFilter, lerpDouble;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

part 'search_surface.dart';

const _buttonSize = 44.0;
const _iconSize = 24.0;
const _toolbarHeight = 72.0;
const _edgeInset = 16.0;
const _barButtonGap = 8.0;
const _fullRadius = BorderRadius.all(Radius.circular(1000));
const _fullShape = RoundedSuperellipseBorder(borderRadius: _fullRadius);

/// FlClash's mobile toolbar search, including the search/action button group.
/// The caller owns the query and whether search is open. [onClose] should clear
/// that query and unfocus its field, as CommonScaffold.handleExitSearching does.
class FlClashSearchHeader extends StatefulWidget
    implements PreferredSizeWidget {
  static const toolbarHeight = _toolbarHeight;

  /// A popup takes over a held press when it opens, as CommonPopupBox does in
  /// FlClash. The shared capsule can then settle while its menu animates.
  static void releasePressOf(BuildContext context) {
    context.findAncestorStateOfType<_ElasticPressState>()?.release();
  }

  const FlClashSearchHeader({
    super.key,
    required this.title,
    required this.searching,
    this.onSearch,
    this.onClose,
    this.enableSearch = true,
    this.searchField,
    this.controller,
    this.focusNode,
    this.onChanged,
    this.hintText,
    this.actions = const [],
    this.searchActions = const [],
    this.leading,
    this.primary = false,
    this.searchTooltip,
    this.clearTooltip,
    this.searchButtonKey,
    this.closeButtonKey,
    this.fieldKey,
    this.searchFieldKey,
    this.clearButtonKey,
    this.trailingSearchWidget,
  }) : assert(!enableSearch || (onSearch != null && onClose != null));

  final Widget title;
  final bool searching;
  final VoidCallback? onSearch;
  final VoidCallback? onClose;
  final bool enableSearch;
  final Widget? searchField;
  final TextEditingController? controller;
  final FocusNode? focusNode;
  final ValueChanged<String>? onChanged;
  final String? hintText;
  final List<Widget> actions;
  final List<Widget> searchActions;
  final Widget? leading;
  final bool primary;
  final String? searchTooltip;
  final String? clearTooltip;
  final Key? searchButtonKey;
  final Key? closeButtonKey;
  final Key? fieldKey;
  final Key? searchFieldKey;
  final Key? clearButtonKey;
  final Widget? trailingSearchWidget;

  @override
  Size get preferredSize => const Size.fromHeight(_toolbarHeight);

  @override
  State<FlClashSearchHeader> createState() => _FlClashSearchHeaderState();
}

class _FlClashSearchHeaderState extends State<FlClashSearchHeader>
    with SingleTickerProviderStateMixin {
  late final _motion = _SearchMotion(this);
  late final _titleOpacity = _motion.drive((frame) => frame.titleOpacity);
  late final _actionsOpacity = _motion.drive((frame) => frame.actionsOpacity);
  late final _leadOpacity = _motion.drive((frame) => frame.leadOpacity);
  late final _leadingOpacity = _motion.drive((frame) => frame.leadingOpacity);
  late final _fieldOpacity = _motion.drive((frame) => frame.fieldOpacity);
  final _origin = ValueNotifier<_SearchOrigin?>(null);
  final _barKey = GlobalKey();
  final _searchGroupKey = GlobalKey();
  final _searchLeadKey = GlobalKey();
  final _leadingKey = GlobalKey();
  final _fieldGeometryKey = GlobalKey();
  TextEditingController? _ownedController;
  FocusNode? _ownedFocus;
  bool _reduceMotion = false;

  TextEditingController get _controller =>
      widget.controller ?? (_ownedController ??= TextEditingController());
  FocusNode get _focus => widget.focusNode ?? (_ownedFocus ??= FocusNode());

  @override
  void initState() {
    super.initState();
    if (widget.searching) {
      _motion.jumpTo(open: true);
      _focusAfterFrame();
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _reduceMotion = MediaQuery.disableAnimationsOf(context);
    if (_reduceMotion) _motion.jumpTo(open: widget.searching);
  }

  Rect? _measure(GlobalKey key) {
    final bar = _barKey.currentContext?.findRenderObject();
    final box = key.currentContext?.findRenderObject();
    if (box is! RenderBox || bar is! RenderBox || !box.hasSize) return null;
    return box.localToGlobal(Offset.zero, ancestor: bar) & box.size;
  }

  _SearchOrigin? _measureOrigin() {
    final group = _measure(_searchGroupKey);
    return group == null
        ? null
        : _SearchOrigin.of(
            group,
            lead: widget.actions.isEmpty ? null : _measure(_searchLeadKey),
            leading: _measure(_leadingKey),
          );
  }

  void _focusAfterFrame() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && widget.searching && widget.searchField == null) {
        _focus.requestFocus();
      }
    });
  }

  @override
  void didUpdateWidget(covariant FlClashSearchHeader oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.searching == widget.searching) return;
    if (widget.searching) {
      _origin.value = _measureOrigin();
      _focusAfterFrame();
    } else {
      final field = _measure(_fieldGeometryKey);
      _origin.value = _origin.value?.withField(field);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && !widget.searching) {
          _origin.value = _measureOrigin()?.withField(field) ?? _origin.value;
        }
      });
    }
    if (_reduceMotion) {
      _motion.jumpTo(open: widget.searching);
    } else {
      final origin = _origin.value;
      _motion.moveTo(
        open: widget.searching,
        returns: origin == null || !origin.lone
            ? _SearchReturn.group
            : origin.leading == null
            ? _SearchReturn.droplet
            : _SearchReturn.pinch,
      );
    }
  }

  @override
  void dispose() {
    _motion.dispose();
    _origin.dispose();
    _ownedController?.dispose();
    _ownedFocus?.dispose();
    super.dispose();
  }

  Widget _under(Widget child, Animation<double> opacity) => _UnderBarSearch(
    opacity: opacity,
    searching: widget.searching,
    child: child,
  );

  Widget _button(Widget child) => _ElasticPress(
    child: SizedBox.square(dimension: _buttonSize, child: child),
  );

  Widget _closedActions(BuildContext context) {
    if (!widget.enableSearch) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var index = 0; index < widget.actions.length; index++) ...[
            if (index > 0) const SizedBox(width: _barButtonGap),
            _button(widget.actions[index]),
          ],
        ],
      );
    }
    final grouped = widget.actions.isNotEmpty;
    final search = IconButton(
      key: widget.searchButtonKey,
      tooltip:
          widget.searchTooltip ??
          (Localizations.localeOf(context).languageCode == 'en'
              ? 'Search'
              : '搜索'),
      onPressed: widget.onSearch,
      icon: grouped
          ? const Icon(LucideIcons.search)
          : _SearchGlyph(
              motion: _motion,
              child: const Icon(LucideIcons.search),
            ),
    );
    final group = grouped
        ? FlClashToolbarButtonGroup(
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _SearchLead(
                  key: _searchLeadKey,
                  motion: _motion,
                  child: SizedBox.square(dimension: _buttonSize, child: search),
                ),
                SizedBox.square(
                  dimension: _buttonSize,
                  child: widget.actions.first,
                ),
              ],
            ),
          )
        : _button(search);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _under(
          KeyedSubtree(
            key: _searchGroupKey,
            child: _SearchSwell(
              motion: _motion,
              width: (grouped ? 2 : 1) * _buttonSize,
              slotWidth: grouped ? _buttonSize : 0,
              droplet: grouped ? null : _origin,
              liftOf: (frame) => frame.glassLift + frame.groupSwell,
              child: group,
            ),
          ),
          _leadOpacity,
        ),
        for (final action in widget.actions.skip(grouped ? 1 : 0)) ...[
          const SizedBox(width: _barButtonGap),
          _under(_button(action), _actionsOpacity),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final top = widget.primary ? MediaQuery.paddingOf(context).top : 0.0;
    final media = MediaQuery.paddingOf(context);
    final field = !widget.enableSearch
        ? null
        : widget.searchField ??
              _SearchField(
                fieldKey: widget.searchFieldKey ?? widget.fieldKey,
                controller: _controller,
                focusNode: _focus,
                onChanged: widget.onChanged,
                hintText: widget.hintText,
                clearButtonKey: widget.clearButtonKey,
                clearTooltip: widget.clearTooltip,
                trailing: widget.trailingSearchWidget,
              );
    return Padding(
      padding: EdgeInsets.only(top: top),
      child: SizedBox(
        key: _barKey,
        height: _toolbarHeight,
        child: RepaintBoundary(
          child: _SearchButtonTheme(
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Positioned.fill(
                  child: Padding(
                    padding: EdgeInsets.only(
                      left: _edgeInset + media.left,
                      right: _edgeInset + media.right,
                    ),
                    child: Row(
                      children: [
                        if (widget.leading != null) ...[
                          _under(
                            KeyedSubtree(
                              key: _leadingKey,
                              child: _SearchSwell(
                                motion: _motion,
                                width: _buttonSize,
                                liftOf: (frame) => frame.glassLift,
                                child: _button(widget.leading!),
                              ),
                            ),
                            _leadingOpacity,
                          ),
                          const SizedBox(width: _edgeInset),
                        ],
                        Expanded(
                          child: _under(
                            DefaultTextStyle.merge(
                              style: TextStyle(
                                fontSize: 24,
                                height: 30 / 24,
                                fontWeight: FontWeight.w600,
                                color: context.omniPalette.textPrimary,
                              ),
                              child: widget.title,
                            ),
                            _titleOpacity,
                          ),
                        ),
                        if (widget.enableSearch || widget.actions.isNotEmpty)
                          const SizedBox(width: _barButtonGap),
                        _closedActions(context),
                      ],
                    ),
                  ),
                ),
                if (widget.enableSearch)
                  Positioned.fill(
                    child: _BarSearch(
                      motion: _motion,
                      opacity: _fieldOpacity,
                      origin: _origin,
                      fieldKey: _fieldGeometryKey,
                      searching: widget.searching,
                      actions: [
                        for (final action in widget.searchActions)
                          _button(action),
                      ],
                      actionsWidth:
                          widget.searchActions.length * _buttonSize +
                          math.max(0, widget.searchActions.length - 1) *
                              _barButtonGap,
                      cancel: _button(
                        IconButton(
                          key: widget.closeButtonKey,
                          tooltip: MaterialLocalizations.of(context)
                              .closeButtonTooltip,
                          onPressed: widget.onClose,
                          icon: const Icon(LucideIcons.x),
                        ),
                      ),
                      color: context.omniPalette.surfacePrimary,
                      field: field!,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A wide toolbar control sharing the search/action group's solid surface and
/// elastic press. Its child supplies the label; the complete capsule is one
/// interaction target and can be captured by [FlClashPopupBox].
class FlClashToolbarCapsule extends StatelessWidget {
  const FlClashToolbarCapsule({
    super.key,
    required this.child,
    required this.onPressed,
    this.tooltip,
  });

  final Widget child;
  final VoidCallback? onPressed;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    final button = _ElasticPress(
      child: TextButton(
        onPressed: onPressed,
        style:
            (LiquidGlassScope.of(context)
                    ? LiquidGlass.buttonStyle(
                        (_) => LiquidGlassStyle.regular(
                          Theme.of(context).colorScheme,
                        ),
                      )
                    : const ButtonStyle())
                .merge(
                  TextButton.styleFrom(
                    backgroundColor: context.omniPalette.surfacePrimary,
                    foregroundColor: context.omniPalette.textPrimary,
                    minimumSize: const Size(_buttonSize, _buttonSize),
                    maximumSize: const Size(double.infinity, _buttonSize),
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    shape: _fullShape,
                    visualDensity: VisualDensity.standard,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    splashFactory: NoSplash.splashFactory,
                    overlayColor: Colors.transparent,
                  ),
                ),
        child: child,
      ),
    );
    return tooltip == null ? button : Tooltip(message: tooltip!, child: button);
  }
}

/// Adjacent toolbar buttons share the home search/menu capsule and press motion.
/// Supply plain [IconButton] children; a popup opened from inside the group
/// captures the complete capsule as its source.
class FlClashToolbarButtonGroup extends StatelessWidget {
  const FlClashToolbarButtonGroup({
    super.key,
    required this.child,
    this.buttonSize = _buttonSize,
    this.iconSize = _iconSize,
  });

  final Widget child;
  final double buttonSize;
  final double iconSize;

  @override
  Widget build(BuildContext context) => FlClashPopupSource(
    shape: _fullShape,
    builder: (_) => _ElasticPress(
      child: _ToolbarGroupSurface(
        child: _SearchButtonTheme(
          grouped: true,
          buttonSize: buttonSize,
          iconSize: iconSize,
          child: child,
        ),
      ),
    ),
  );
}

class _SearchButtonTheme extends StatelessWidget {
  const _SearchButtonTheme({
    this.grouped = false,
    this.buttonSize = _buttonSize,
    this.iconSize = _iconSize,
    required this.child,
  });
  final bool grouped;
  final double buttonSize;
  final double iconSize;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final goo = !grouped && _GooSurface.of(context);
    final glass = !grouped && !goo && LiquidGlassScope.of(context);
    final fill = grouped || goo ? Colors.transparent : palette.surfacePrimary;
    return IconButtonTheme(
      data: IconButtonThemeData(
        style:
            (goo
                    ? _GooSurface.buttonStyle
                    : glass
                    ? LiquidGlass.buttonStyle(
                        (_) => LiquidGlassStyle.regular(
                          Theme.of(context).colorScheme,
                        ),
                      )
                    : const ButtonStyle())
                .merge(
                  IconButton.styleFrom(
                    backgroundColor: fill,
                    foregroundColor: palette.textPrimary,
                    disabledForegroundColor: palette.textPrimary.withValues(
                      alpha: .38,
                    ),
                    fixedSize: Size.square(buttonSize),
                    minimumSize: Size.square(buttonSize),
                    padding: EdgeInsets.zero,
                    iconSize: iconSize,
                    shape: _fullShape,
                    visualDensity: VisualDensity.standard,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    splashFactory: NoSplash.splashFactory,
                    overlayColor: Colors.transparent,
                  ),
                ),
      ),
      child: child,
    );
  }
}

/// The two toolbar segments share one glass pane, including press lighting.
class _ToolbarGroupSurface extends StatefulWidget {
  const _ToolbarGroupSurface({required this.child});
  final Widget child;

  @override
  State<_ToolbarGroupSurface> createState() => _ToolbarGroupSurfaceState();
}

class _ToolbarGroupSurfaceState extends State<_ToolbarGroupSurface> {
  int? _pointer;

  void _end(PointerEvent event) {
    if (event.pointer == _pointer) setState(() => _pointer = null);
  }

  @override
  Widget build(BuildContext context) {
    if (!LiquidGlassScope.of(context)) {
      return DecoratedBox(
        decoration: ShapeDecoration(
          color: context.omniPalette.surfacePrimary,
          shape: _fullShape,
        ),
        child: widget.child,
      );
    }
    return Listener(
      onPointerDown: (event) => setState(() => _pointer = event.pointer),
      onPointerUp: _end,
      onPointerCancel: _end,
      child: LiquidGlass(
        style: LiquidGlassStyle.regular(Theme.of(context).colorScheme),
        pressed: _pointer != null,
        child: widget.child,
      ),
    );
  }
}

class _SearchField extends StatelessWidget {
  const _SearchField({
    required this.controller,
    required this.focusNode,
    this.fieldKey,
    this.onChanged,
    this.hintText,
    this.clearButtonKey,
    this.clearTooltip,
    this.trailing,
  });
  final TextEditingController controller;
  final FocusNode focusNode;
  final Key? fieldKey;
  final ValueChanged<String>? onChanged;
  final String? hintText;
  final Key? clearButtonKey;
  final String? clearTooltip;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final palette = context.omniPalette;
    final pill = OutlineInputBorder(
      borderRadius: _fullRadius,
      borderSide: BorderSide.none,
    );
    return ListenableBuilder(
      listenable: controller,
      builder: (_, _) {
        final empty = controller.text.isEmpty;
        return TextField(
          key: fieldKey,
          controller: controller,
          focusNode: focusNode,
          maxLines: 1,
          textAlignVertical: TextAlignVertical.center,
          textInputAction: TextInputAction.search,
          style: TextStyle(
            fontSize: 17,
            height: 22 / 17,
            color: palette.textPrimary,
          ),
          onChanged: onChanged,
          decoration: InputDecoration(
            // FlClash's field is explicitly not dense: the application theme's
            // dense decorator otherwise ignores the 44 dp minimum height.
            isDense: false,
            constraints: const BoxConstraints.tightFor(height: _buttonSize),
            floatingLabelBehavior: FloatingLabelBehavior.never,
            filled: false,
            hintText: hintText ?? (english ? 'Search' : '搜索'),
            hintStyle: TextStyle(color: palette.textTertiary),
            border: pill,
            enabledBorder: pill,
            focusedBorder: pill,
            contentPadding: EdgeInsetsDirectional.only(
              end: empty && trailing == null ? 12 : 0,
            ),
            prefixIcon: const Padding(
              padding: EdgeInsetsDirectional.only(start: 12),
              child: Icon(LucideIcons.search, size: 20),
            ),
            prefixIconConstraints: const BoxConstraints.tightFor(width: 41),
            suffixIcon: empty && trailing == null
                ? null
                : Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (trailing != null)
                        Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: trailing!,
                        ),
                      if (!empty)
                        SizedBox(
                          width: 49,
                          child: IconButton(
                            key: clearButtonKey,
                            tooltip:
                                clearTooltip ??
                                (english ? 'Clear search' : '清空搜索'),
                            onPressed: () {
                              controller.clear();
                              onChanged?.call('');
                            },
                            style: IconButton.styleFrom(
                              backgroundColor: Colors.transparent,
                              foregroundColor: palette.textPrimary,
                              overlayColor: Colors.transparent,
                              splashFactory: NoSplash.splashFactory,
                            ),
                            icon: const Icon(LucideIcons.circleX, size: 24),
                          ),
                        )
                      else
                        const SizedBox(width: 8),
                    ],
                  ),
            suffixIconConstraints: const BoxConstraints(),
          ),
        );
      },
    );
  }
}

// Upstream motion.dart helpers.
final dropletSpring = SpringDescription.withDampingRatio(
  mass: 1,
  stiffness: 165,
  ratio: 0.8,
);
double riseAndFall(double time, double peak, double sharpness) {
  if (time <= 0) return 0;
  final x = time / peak;
  return math.pow(x, sharpness) * math.exp(sharpness * (1 - x));
}

// iOS 27's bar search, as the simulator shows it. Opening, its button group
// gives up the search button and the field grows out of what is left, about
// 3% over at its peak 0.24 s on; closing, it shrinks back in 0.15 s, and the
// group takes the search button back with a swell. A back button takes the
// field back, the group its cancel button; the glass swells through the change
// as under a press. Each part starts as the last slows, where iOS pauses.
// A lone button takes a field at the bar's end in on a softer spring, then
// swells; past one across the bar it re-forms out of a droplet that starts a
// quarter of the way to the field at a quarter of its size and overshoots,
// or, while a back button takes the field, pinches off the field's end.
final _searchSpring = SpringDescription.withDampingRatio(
  mass: 1,
  stiffness: 390,
  ratio: 0.75,
);
final _searchCloseSpring = SpringDescription.withDampingRatio(
  mass: 1,
  stiffness: 600,
  ratio: 0.75,
);
final _searchMorphSpring = SpringDescription.withDampingRatio(
  mass: 1,
  stiffness: 241,
  ratio: 0.77,
);
final _droplet = SpringSimulation(dropletSpring, 0, 1, 0);
const double _dropletSize = 0.25;
const double _searchFieldGap = 12;
const double _regularSearchWidth = 280;
const double _searchBlur = 6;

Rect _lifted(Rect rect, double lift) {
  final scale = 1 + lift * _ElasticPress.growthOf(rect.longestSide);
  return Rect.fromCenter(
    center: rect.center,
    width: rect.width * scale,
    height: rect.height * scale,
  );
}

Widget _blurredIn(double opacity, Widget? child) {
  final sigma = _searchBlur * (1 - opacity);
  return Opacity(
    opacity: opacity,
    child: ImageFiltered(
      enabled: sigma > 0,
      imageFilter: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma),
      child: child,
    ),
  );
}

class _SearchFrame {
  const _SearchFrame({
    required this.fieldGrowth,
    required this.slotCollapse,
    required this.fieldOpacity,
    required this.titleOpacity,
    required this.actionsOpacity,
    required this.flankOpacity,
    required this.contentOpacity,
    required this.glyphOpacity,
    required this.groupSwell,
    required this.leadingOpacity,
    required this.towardLeading,
    required this.glassLift,
    required this.leadOpacity,
    required this.fieldGlyphOpacity,
    required this.fieldSwell,
    required this.dropletOffset,
    required this.dropletSize,
    required this.flankScale,
  });

  static const closed = _SearchFrame(
    fieldGrowth: 0,
    slotCollapse: 0,
    fieldOpacity: 0,
    titleOpacity: 1,
    actionsOpacity: 1,
    flankOpacity: 0,
    contentOpacity: 0,
    glyphOpacity: 1,
    groupSwell: 0,
    leadingOpacity: 1,
    towardLeading: 0,
    glassLift: 0,
    leadOpacity: 1,
    fieldGlyphOpacity: 0,
    fieldSwell: 0,
    dropletOffset: 0,
    dropletSize: 1,
    flankScale: 1,
  );

  static const open = _SearchFrame(
    fieldGrowth: 1,
    slotCollapse: 1,
    fieldOpacity: 1,
    titleOpacity: 0,
    actionsOpacity: 0,
    flankOpacity: 1,
    contentOpacity: 1,
    glyphOpacity: 0,
    groupSwell: 0,
    leadingOpacity: 0,
    towardLeading: 0,
    glassLift: 0,
    leadOpacity: 0,
    fieldGlyphOpacity: 0,
    fieldSwell: 0,
    dropletOffset: 0,
    dropletSize: 1,
    flankScale: 1,
  );

  final double fieldGrowth;
  final double slotCollapse;
  final double fieldOpacity;
  final double titleOpacity;
  final double actionsOpacity;
  final double flankOpacity;
  final double contentOpacity;
  final double glyphOpacity;
  final double groupSwell;
  final double leadingOpacity;
  final double towardLeading;
  final double glassLift;
  final double leadOpacity;
  final double fieldGlyphOpacity;
  final double fieldSwell;
  final double dropletOffset;
  final double dropletSize;
  final double flankScale;
}

/// How a closing bar's search gives its button back.
enum _SearchReturn { group, morph, droplet, pinch }

/// One opening or closing of a bar's search, in seconds from its start as
/// iOS 27 times each part.
class _SearchMove {
  _SearchMove({
    required this.opens,
    required this.returns,
    required this.from,
    required Simulation this.growth,
    required this.delay,
  });

  _SearchMove.settled({required this.opens})
    : returns = _SearchReturn.group,
      from = opens ? _SearchFrame.open : _SearchFrame.closed,
      growth = null,
      delay = 0;

  final bool opens;
  final _SearchReturn returns;
  final _SearchFrame from;
  final Simulation? growth;
  final double delay;

  double growthAt(double time) =>
      growth?.x(math.max(0, time - delay)) ?? (opens ? 1 : 0);

  double growthVelocityAt(double time) =>
      time < delay ? 0 : growth?.dx(time - delay) ?? 0;

  bool growthSettledAt(double time) =>
      growth?.isDone(math.max(0, time - delay)) ?? true;

  bool isDoneAt(double time) =>
      time >=
          (opens
              ? 0.29
              : switch (returns) {
                  _SearchReturn.group => 0.55,
                  _SearchReturn.morph => 0.75,
                  _SearchReturn.droplet => 0.8,
                  _SearchReturn.pinch => 0.7,
                }) &&
      growthSettledAt(time);

  _SearchFrame at(double time) {
    if (isDoneAt(time)) {
      return opens ? _SearchFrame.open : _SearchFrame.closed;
    }
    double to(
      double from,
      double target,
      double start,
      double end, [
      Curve curve = Curves.linear,
    ]) {
      final t = ((time - start) / (end - start)).clamp(0.0, 1.0);
      return lerpDouble(from, target, curve.transform(t))!;
    }

    final from = this.from;
    if (opens) {
      final actionsOpacity = to(from.actionsOpacity, 0, 0.02, 0.08);
      return _SearchFrame(
        fieldGrowth: growthAt(time),
        slotCollapse: to(from.slotCollapse, 1, 0, 0.08, Curves.easeOut),
        fieldOpacity: to(from.fieldOpacity, 1, 0, 0.05),
        titleOpacity: to(from.titleOpacity, 0, 0, 0.08),
        actionsOpacity: actionsOpacity,
        flankOpacity: to(from.flankOpacity, 1, 0.08, 0.2),
        contentOpacity: to(from.contentOpacity, 1, 0.07, 0.22),
        glyphOpacity: to(from.glyphOpacity, 0, 0, 0.03),
        groupSwell: to(from.groupSwell, 0, 0, 0.1),
        leadingOpacity: to(from.leadingOpacity, 0, 0, 0.02),
        towardLeading: to(from.towardLeading, 0, 0, 0.08),
        glassLift: to(
          to(from.glassLift, 1, 0.02, 0.12, Curves.easeOut),
          0,
          0.16,
          0.29,
          Curves.easeInOut,
        ),
        leadOpacity: to(from.leadOpacity, 0, 0.02, 0.08),
        fieldGlyphOpacity: to(from.fieldGlyphOpacity, 0, 0, 0.03),
        fieldSwell: to(from.fieldSwell, 0, 0, 0.1),
        dropletOffset: to(from.dropletOffset, 0, 0, 0.05),
        dropletSize: to(from.dropletSize, 1, 0, 0.05),
        flankScale: to(from.flankScale, 1, 0, 0.05),
      );
    }
    final returns = this.returns;
    final grouped = returns == _SearchReturn.group;
    final morphs = returns == _SearchReturn.morph;
    final drips = returns == _SearchReturn.droplet;
    final pinches = returns == _SearchReturn.pinch;
    final actionsOpacity = to(from.actionsOpacity, 1, 0.18, 0.26);
    final swell = grouped
        ? to(
            to(from.groupSwell, 1, 0.27, 0.36, Curves.easeOut),
            0,
            0.36,
            0.55,
            Curves.easeInOut,
          )
        : to(from.groupSwell, 0, 0, 0.1) +
              (morphs ? 0.8 * riseAndFall(time - 0.25, 0.26, 18) : 0);
    final droplet = _droplet.x(time);
    return _SearchFrame(
      fieldGrowth: growthAt(time),
      slotCollapse: to(from.slotCollapse, 0, 0.17, 0.33, Curves.easeInOut),
      fieldOpacity: switch (returns) {
        _SearchReturn.group ||
        _SearchReturn.pinch => to(from.fieldOpacity, 0, 0.19, 0.29),
        _SearchReturn.morph => to(from.fieldOpacity, 0, 0.62, 0.7),
        _SearchReturn.droplet => to(from.fieldOpacity, 0, 0.02, 0.12),
      },
      titleOpacity: to(from.titleOpacity, 1, 0.08, 0.19),
      actionsOpacity: actionsOpacity,
      flankOpacity: drips || pinches
          ? to(from.flankOpacity, 0, 0, 0.03)
          : to(from.flankOpacity, 0, 0.11, 0.2),
      contentOpacity: to(from.contentOpacity, 0, 0, 0.04),
      glyphOpacity: switch (returns) {
        _SearchReturn.group => to(from.glyphOpacity, 1, 0.3, 0.46),
        _SearchReturn.morph => to(from.glyphOpacity, 1, 0, 0.02),
        _SearchReturn.droplet => to(from.glyphOpacity, 1, 0.3, 0.5),
        _SearchReturn.pinch => to(from.glyphOpacity, 1, 0.14, 0.3),
      },
      groupSwell: swell,
      leadingOpacity: to(from.leadingOpacity, 1, 0.16, 0.19),
      towardLeading: to(to(from.towardLeading, 1, 0, 0.08), 0, 0.4, 0.41),
      glassLift: grouped || pinches
          ? to(
              to(from.glassLift, 1, 0.06, 0.16, Curves.easeOut),
              0,
              0.21,
              0.32,
              Curves.easeInOut,
            )
          : to(from.glassLift, 0, 0, 0.1),
      leadOpacity: switch (returns) {
        _SearchReturn.group => actionsOpacity,
        _SearchReturn.morph => to(from.leadOpacity, 1, 0.61, 0.62),
        _SearchReturn.droplet ||
        _SearchReturn.pinch => to(from.leadOpacity, 1, 0, 0.02),
      },
      fieldGlyphOpacity: morphs
          ? to(from.fieldGlyphOpacity, 1, 0.33, 0.45)
          : to(from.fieldGlyphOpacity, 0, 0, 0.03),
      fieldSwell: morphs ? swell : to(from.fieldSwell, 0, 0, 0.1),
      dropletOffset: switch (returns) {
        _SearchReturn.droplet =>
          _dropletSize * (1 - droplet - 0.71 * riseAndFall(time, 0.19, 5.7)),
        _SearchReturn.pinch => 0.46 * (1 - droplet),
        _ => to(from.dropletOffset, 0, 0, 0.05),
      },
      dropletSize: switch (returns) {
        _SearchReturn.droplet => lerpDouble(
          _dropletSize,
          1,
          _droplet.x(math.max(0, time - 0.31)),
        )!,
        _SearchReturn.pinch => droplet,
        _ => to(from.dropletSize, 1, 0, 0.05),
      },
      flankScale: drips || pinches
          ? to(from.flankScale, 0, 0, 0.05)
          : to(from.flankScale, 1, 0, 0.05),
    );
  }
}

class _SearchClock extends Simulation {
  _SearchClock(this.move);

  final _SearchMove move;

  @override
  double x(double time) => time;

  @override
  double dx(double time) => 1;

  @override
  bool isDone(double time) => move.isDoneAt(time);
}

class _SearchChannel extends Animatable<double> {
  const _SearchChannel(this.motion, this.of);

  final _SearchMotion motion;
  final double Function(_SearchFrame frame) of;

  @override
  double transform(double t) => of(motion.frame);
}

class _SearchMotion {
  _SearchMotion(TickerProvider vsync)
    : _clock = AnimationController.unbounded(vsync: vsync);

  final AnimationController _clock;
  var _move = _SearchMove.settled(opens: false);
  ({_SearchMove move, double time, _SearchFrame frame})? _last;

  Listenable get listenable => _clock;

  bool get isAnimating => _clock.isAnimating;

  _SearchFrame get frame {
    final time = _clock.value;
    final last = _last;
    if (last != null && identical(last.move, _move) && last.time == time) {
      return last.frame;
    }
    final frame = _move.at(time);
    _last = (move: _move, time: time, frame: frame);
    return frame;
  }

  Animation<double> drive(double Function(_SearchFrame frame) of) =>
      _clock.drive(_SearchChannel(this, of));

  void addStatusListener(AnimationStatusListener listener) =>
      _clock.addStatusListener(listener);

  void moveTo({
    required bool open,
    _SearchReturn returns = _SearchReturn.group,
  }) {
    final time = _clock.value;
    final last = _move;
    _move = _SearchMove(
      opens: open,
      returns: returns,
      from: last.at(time),
      growth: SpringSimulation(
        open
            ? _searchSpring
            : switch (returns) {
                _SearchReturn.group => _searchCloseSpring,
                _SearchReturn.morph => _searchMorphSpring,
                _SearchReturn.droplet => dropletSpring,
                _SearchReturn.pinch => _searchCloseSpring,
              },
        last.growthAt(time),
        open ? 1 : 0,
        last.growthVelocityAt(time),
        snapToEnd: true,
      ),
      // A field already moving turns back at once.
      delay: !last.growthSettledAt(time)
          ? 0
          : open
          ? 0.05
          : returns == _SearchReturn.group || returns == _SearchReturn.pinch
          ? 0.04
          : 0,
    );
    _clock.animateWith(_SearchClock(_move));
  }

  void jumpTo({required bool open}) {
    _move = _SearchMove.settled(opens: open);
    _clock.value = 0;
  }

  void dispose() => _clock.dispose();
}

class _SearchOrigin {
  const _SearchOrigin(
    this.group,
    this.withoutSearch,
    this.leading, {
    this.lone = false,
    this.field,
  });

  factory _SearchOrigin.of(
    Rect group, {
    required Rect? lead,
    required Rect? leading,
  }) {
    final button = _buttonSize;
    final back = leading == null
        ? null
        : Rect.fromCenter(
            center: leading.center,
            width: button,
            height: button,
          );
    if (lead == null) {
      return _SearchOrigin(group, group, back, lone: true);
    }
    return lead.center.dx < group.center.dx
        ? _SearchOrigin(
            Rect.fromLTRB(
              lead.right - button,
              group.top,
              group.right,
              group.bottom,
            ),
            Rect.fromLTRB(lead.right, group.top, group.right, group.bottom),
            back,
          )
        : _SearchOrigin(
            Rect.fromLTRB(
              group.left,
              group.top,
              lead.left + button,
              group.bottom,
            ),
            Rect.fromLTRB(group.left, group.top, lead.left, group.bottom),
            back,
          );
  }

  final Rect group;
  final Rect withoutSearch;
  final Rect? leading;
  final bool lone;
  final Rect? field;

  double get toField => field == null ? 0 : field!.center.dx - group.center.dx;

  _SearchOrigin withField(Rect? field) =>
      _SearchOrigin(group, withoutSearch, leading, lone: lone, field: field);

  Rect at(double slotCollapse) =>
      Rect.lerp(group, withoutSearch, slotCollapse)!;
}

/// The search button of a bar's button group, which gives its place up while
/// the search is open.
class _SearchLead extends StatelessWidget {
  const _SearchLead({super.key, required this.motion, required this.child});

  final _SearchMotion motion;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: motion.listenable,
      builder: (_, child) {
        final frame = motion.frame;
        return ClipRect(
          clipBehavior: frame.slotCollapse > 0 ? Clip.hardEdge : Clip.none,
          child: Align(
            widthFactor: 1 - frame.slotCollapse,
            heightFactor: 1,
            child: _blurredIn(frame.glyphOpacity, child),
          ),
        );
      },
      child: child,
    );
  }
}

/// A button a bar's search grows from or into, swelling as a press does.
class _SearchSwell extends StatelessWidget {
  const _SearchSwell({
    required this.motion,
    required this.width,
    this.slotWidth = 0,
    this.droplet,
    required this.liftOf,
    required this.child,
  });

  final _SearchMotion motion;
  final double width;
  final double slotWidth;

  /// Where a lone button re-forms out of a droplet from.
  final ValueListenable<_SearchOrigin?>? droplet;
  final double Function(_SearchFrame frame) liftOf;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final droplet = this.droplet;
    return AnimatedBuilder(
      animation: Listenable.merge([motion.listenable, ?droplet]),
      builder: (_, child) {
        final frame = motion.frame;
        final width = this.width - slotWidth * frame.slotCollapse;
        final origin = droplet?.value;
        final scale =
            (1 + liftOf(frame) * _ElasticPress.growthOf(width)) *
            (origin == null ? 1 : frame.dropletSize);
        final shift = origin == null
            ? 0.0
            : origin.toField * frame.dropletOffset;
        return Transform(
          alignment: Alignment.center,
          transform: Matrix4.translationValues(shift, 0, 0)
            ..scaleByDouble(scale, scale, 1, 1),
          child: child,
        );
      },
      child: child,
    );
  }
}

/// The glyph of a lone search button, which fades back in as it re-forms.
class _SearchGlyph extends StatelessWidget {
  const _SearchGlyph({required this.motion, required this.child});

  final _SearchMotion motion;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: motion.listenable,
      builder: (_, child) => _blurredIn(motion.frame.glyphOpacity, child),
      child: child,
    );
  }
}

/// What a bar's search covers: faded out as the field opens over it, and out
/// of reach while it is open.
class _UnderBarSearch extends StatelessWidget {
  const _UnderBarSearch({
    required this.opacity,
    required this.searching,
    required this.child,
  });

  final Animation<double> opacity;
  final bool searching;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LiquidGlassFade(
      opacity: opacity,
      child: ExcludeFocus(
        excluding: searching,
        child: ExcludeSemantics(
          excluding: searching,
          child: IgnorePointer(ignoring: searching, child: child),
        ),
      ),
    );
  }
}

/// The field a bar's search button opens into, grown out of that button on
/// [motion]: across the bar to a [cancel] button where there is one, as iOS
/// 27 opens one over a page above a tab bar, else at the bar's end, as iPadOS
/// does. The [actions] that still apply while searching lead the field. The
/// buttons beside it grow out of its ends and flow into it under a press, as
/// iOS's do.
class _BarSearch extends StatelessWidget {
  const _BarSearch({
    required this.motion,
    required this.opacity,
    required this.origin,
    required this.fieldKey,
    required this.searching,
    required this.actions,
    required this.actionsWidth,
    required this.cancel,
    required this.color,
    required this.field,
  });

  final _SearchMotion motion;
  final Animation<double> opacity;
  final ValueListenable<_SearchOrigin?> origin;
  final GlobalKey fieldKey;
  final bool searching;
  final List<Widget> actions;
  final double actionsWidth;
  final Widget? cancel;
  final Color color;
  final Widget field;

  @override
  Widget build(BuildContext context) {
    final padding = MediaQuery.paddingOf(context);
    final cancel = this.cancel;
    final inset = _edgeInset;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final height = _buttonSize;
    final glyph = Icon(
      LucideIcons.search,
      size: _iconSize,
      color: Theme.of(context).colorScheme.onSurface,
    );
    return ExcludeSemantics(
      excluding: !searching,
      child: ExcludeFocus(
        excluding: !searching,
        child: IgnorePointer(
          ignoring: !searching,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final width = constraints.maxWidth;
              final top = constraints.maxHeight - (_toolbarHeight + height) / 2;
              final start = (rtl ? padding.right : padding.left) + inset;
              final end = width - (rtl ? padding.left : padding.right) - inset;
              final actionsRoom = actionsWidth > 0
                  ? actionsWidth + _searchFieldGap
                  : 0.0;
              final fieldEnd = cancel == null
                  ? end
                  : end - height - _searchFieldGap;
              final fieldStart = cancel == null
                  ? math.max(start + actionsRoom, end - _regularSearchWidth)
                  : start + actionsRoom;
              final actionsStart = fieldStart - actionsRoom;
              Rect place(double from, double to) => Rect.fromLTRB(
                rtl ? width - to : from,
                top,
                rtl ? width - from : to,
                top + height,
              );
              final slot = place(fieldStart, math.max(fieldStart, fieldEnd));
              final actionsRect = place(
                actionsStart,
                actionsStart + actionsWidth,
              );
              final search = AnimatedBuilder(
                animation: Listenable.merge([motion.listenable, origin]),
                child: OverflowBox(
                  alignment: AlignmentDirectional.centerStart,
                  minWidth: slot.width,
                  maxWidth: slot.width,
                  child: field,
                ),
                builder: (_, content) {
                  if (!searching && !motion.isAnimating) {
                    return const SizedBox.shrink();
                  }
                  final frame = motion.frame;
                  final growth = frame.fieldGrowth;
                  final grown = math.max(0.0, growth);
                  final measured = origin.value;
                  final group =
                      measured?.at(frame.slotCollapse) ??
                      place(fieldEnd - height, fieldEnd);
                  final leading = cancel == null ? null : measured?.leading;
                  final from = leading == null
                      ? group
                      : Rect.lerp(group, leading, frame.towardLeading)!;
                  final fieldRect = _lifted(
                    Rect.lerp(from, slot, growth)!,
                    frame.glassLift + frame.fieldSwell,
                  );
                  Widget grow(
                    Rect rest,
                    Widget child, {
                    required bool leads,
                    Rect? home,
                  }) {
                    final facesRight = leads != rtl;
                    final reach = lerpDouble(
                      height / 2,
                      -_searchFieldGap,
                      grown,
                    )!;
                    final anchor = facesRight
                        ? fieldRect.left + reach
                        : fieldRect.right - reach;
                    final size = rest.size * grown;
                    var rect = Rect.fromLTWH(
                      facesRight ? anchor - size.width : anchor,
                      rest.center.dy - size.height / 2,
                      size.width,
                      size.height,
                    );
                    if (home != null) {
                      rect = Rect.lerp(
                        rect,
                        Rect.lerp(home, rest, grown),
                        frame.towardLeading,
                      )!;
                    }
                    rect = _lifted(rect, frame.glassLift);
                    rect = Rect.fromCenter(
                      center: rect.center,
                      width: rect.width * frame.flankScale,
                      height: rect.height * frame.flankScale,
                    );
                    final scale = rect.width / rest.width;
                    return Positioned.fromRect(
                      rect: rest,
                      child: Transform(
                        transform: Matrix4.translationValues(
                          rect.left - rest.left,
                          rect.top - rest.top,
                          0,
                        )..scaleByDouble(scale, scale, 1, 1),
                        child: _blurredIn(frame.flankOpacity, child),
                      ),
                    );
                  }

                  return TextFieldTapRegion(
                    child: Stack(
                      children: [
                        Positioned.fromRect(
                          rect: fieldRect,
                          child: _ElasticPress(
                            key: fieldKey,
                            child: _GooPane(
                              child: ClipRSuperellipse(
                                borderRadius: _fullRadius,
                                child: Stack(
                                  alignment: Alignment.center,
                                  children: [
                                    _blurredIn(frame.contentOpacity, content),
                                    if (frame.fieldGlyphOpacity > 0)
                                      _blurredIn(
                                        frame.fieldGlyphOpacity,
                                        glyph,
                                      ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ),
                        if (actions.isNotEmpty)
                          grow(
                            actionsRect,
                            Row(
                              children: [
                                for (final (index, action)
                                    in actions.indexed) ...[
                                  if (index > 0)
                                    const SizedBox(width: _barButtonGap),
                                  action,
                                ],
                              ],
                            ),
                            leads: true,
                          ),
                        if (cancel != null)
                          grow(
                            place(end - height, end),
                            cancel,
                            leads: false,
                            home: leading == null
                                ? null
                                : measured?.withoutSearch,
                          ),
                      ],
                    ),
                  );
                },
              );
              return LiquidGlassFade(
                opacity: opacity,
                child: _GooSurface(
                  color: color,
                  child: _SearchButtonTheme(child: search),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
