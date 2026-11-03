part of 'chat_input_area.dart';

/// Compact chat layout shares the established composer actions and editor.
extension _ChatInputCompactComposer on _ChatInputAreaComposerMixin {
  /// One persistent editor moves from the compact row to the upper text row.
  /// Only padding and the space below it animate; IME composition, selection
  /// and the controller remain owned by the same TextField element.
  Widget _buildMinimalComposer() {
    return ValueListenableBuilder<ChatComposerState>(
      valueListenable: _composerStateMachine,
      builder: (context, state, _) {
        final expanded = state.hasFocus || _compactExpansionHeld;
        final duration = _compactAnimationDuration;
        final surface = ChatComposerSurface(
          key: const ValueKey('chat-minimal-composer'),
          expanded: expanded,
          duration: duration,
          child: Padding(
            key: _compactComposerSurfaceKey,
            padding: const EdgeInsets.all(7),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if ((widget.selectedModelOverrideId ?? '').trim().isNotEmpty)
                  IgnorePointer(
                    ignoring: !expanded,
                    child: ExcludeFocus(
                      excluding: !expanded,
                      child: ExcludeSemantics(
                        excluding: !expanded,
                        child: ClipRect(
                          child: AnimatedAlign(
                            key: const ValueKey(
                              'chat-input-model-override-visibility',
                            ),
                            duration: duration,
                            curve: Curves.easeOutCubic,
                            alignment: Alignment.topLeft,
                            heightFactor: expanded ? 1 : 0,
                            child: Padding(
                              padding: const EdgeInsets.only(bottom: 8),
                              child: _buildSelectedModelOverrideChip(),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                LayoutBuilder(
                  builder: (context, constraints) {
                    final hasModel =
                        widget.runtimeConfigButton != null ||
                        _shouldShowModelPicker;
                    final modelWidth = hasModel
                        ? math.min(112.0, constraints.maxWidth * 0.34)
                        : 0.0;
                    return Stack(
                      alignment: Alignment.bottomCenter,
                      children: [
                        Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            AnimatedPadding(
                              key: const ValueKey('chat-input-editor-layout'),
                              duration: duration,
                              curve: Curves.easeOutCubic,
                              padding: EdgeInsets.only(
                                left: expanded ? 9 : 38,
                                right: expanded ? 9 : 44,
                                top: expanded ? 5 : 0,
                              ),
                              child: _buildCompactTextEditor(
                                expanded: expanded,
                                duration: duration,
                              ),
                            ),
                            AnimatedContainer(
                              key: const ValueKey('chat-input-tools-spacing'),
                              duration: duration,
                              curve: Curves.easeOutCubic,
                              height: expanded ? 42 : 0,
                            ),
                          ],
                        ),
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.center,
                          children: [
                            SizedBox(
                              width: 36,
                              height: 36,
                              child: _buildCompactAddButton(),
                            ),
                            const Spacer(),
                            if (hasModel)
                              IgnorePointer(
                                ignoring: !expanded,
                                child: ExcludeFocus(
                                  excluding: !expanded,
                                  child: ExcludeSemantics(
                                    excluding: !expanded,
                                    child: ClipRect(
                                      child: AnimatedAlign(
                                        key: const ValueKey(
                                          'chat-input-model-visibility',
                                        ),
                                        duration: duration,
                                        curve: Curves.easeOutCubic,
                                        alignment: Alignment.centerRight,
                                        widthFactor: expanded ? 1 : 0,
                                        child: Padding(
                                          padding: const EdgeInsets.only(
                                            right: 4,
                                          ),
                                          child: SizedBox(
                                            width: modelWidth,
                                            height: 36,
                                            child:
                                                widget.runtimeConfigButton ??
                                                _buildModelPickerButton(
                                                  compact: false,
                                                ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            SizedBox(
                              width: 36,
                              height: 36,
                              child: _buildLargeSendOrStopButton(),
                            ),
                          ],
                        ),
                      ],
                    );
                  },
                ),
              ],
            ),
          ),
        );
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (widget.attachments.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 7),
                child: _buildAttachmentPreview(),
              ),
              const SizedBox(height: 8),
            ],
            surface,
          ],
        );
      },
    );
  }

  Widget _buildCompactTextEditor({
    required bool expanded,
    required Duration duration,
  }) {
    final editor = ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 36),
      child: Align(
        alignment: Alignment.centerLeft,
        heightFactor: 1,
        child: _buildTextField(multiline: true, expanded: expanded),
      ),
    );
    // AnimatedSize's zero-duration controller can mark itself dirty during
    // layout. Skip that wrapper for reduced motion; the TextField's GlobalKey
    // retains the same editing element when the accessibility setting changes.
    if (duration == Duration.zero) return editor;
    return AnimatedSize(
      duration: duration,
      curve: Curves.easeOutCubic,
      alignment: Alignment.topLeft,
      child: editor,
    );
  }
}
