import 'dart:math' as math;

import 'package:flutter/foundation.dart';

enum ChatComposerKeyboardPhase { hidden, opening, visible, closing }

extension ChatComposerKeyboardPhaseBehavior on ChatComposerKeyboardPhase {
  bool get expandsEmptyTextField {
    return switch (this) {
      ChatComposerKeyboardPhase.opening ||
      ChatComposerKeyboardPhase.visible => true,
      ChatComposerKeyboardPhase.hidden ||
      ChatComposerKeyboardPhase.closing => false,
    };
  }
}

enum ChatComposerPopup { legacyActions, agentRunSettings, agentPermission }

enum ChatComposerPrimaryAction { cancel, send, addAttachment, disabled }

@immutable
class ChatComposerState {
  const ChatComposerState({
    required this.hasText,
    required this.hasFocus,
    required this.keyboardPhase,
    this.isHovered = false,
    this.openPopups = const <ChatComposerPopup>{},
    this.openingPopups = const <ChatComposerPopup>{},
  });

  final bool hasText;
  final bool hasFocus;
  final ChatComposerKeyboardPhase keyboardPhase;
  final bool isHovered;
  final Set<ChatComposerPopup> openPopups;
  final Set<ChatComposerPopup> openingPopups;

  bool get expandsTextField =>
      hasText || (hasFocus && keyboardPhase.expandsEmptyTextField);

  bool isPopupOpen(ChatComposerPopup popup) => openPopups.contains(popup);

  bool isPopupOpening(ChatComposerPopup popup) => openingPopups.contains(popup);

  bool isPopupActive(ChatComposerPopup popup) =>
      isPopupOpen(popup) || isPopupOpening(popup);

  bool hasPayload({
    required bool hasAttachments,
    required bool hasExternalPayload,
    bool? hasTextOverride,
  }) => (hasTextOverride ?? hasText) || hasAttachments || hasExternalPayload;

  ChatComposerPrimaryAction primaryAction({
    required bool isProcessing,
    required bool hasAttachments,
    required bool hasExternalPayload,
    bool supportsAttachmentFallback = false,
    bool? hasTextOverride,
  }) {
    if (isProcessing) {
      return ChatComposerPrimaryAction.cancel;
    }
    if (hasPayload(
      hasAttachments: hasAttachments,
      hasExternalPayload: hasExternalPayload,
      hasTextOverride: hasTextOverride,
    )) {
      return ChatComposerPrimaryAction.send;
    }
    return supportsAttachmentFallback
        ? ChatComposerPrimaryAction.addAttachment
        : ChatComposerPrimaryAction.disabled;
  }

  ChatComposerState copyWith({
    bool? hasText,
    bool? hasFocus,
    ChatComposerKeyboardPhase? keyboardPhase,
    bool? isHovered,
    Set<ChatComposerPopup>? openPopups,
    Set<ChatComposerPopup>? openingPopups,
  }) {
    return ChatComposerState(
      hasText: hasText ?? this.hasText,
      hasFocus: hasFocus ?? this.hasFocus,
      keyboardPhase: keyboardPhase ?? this.keyboardPhase,
      isHovered: isHovered ?? this.isHovered,
      openPopups: openPopups ?? this.openPopups,
      openingPopups: openingPopups ?? this.openingPopups,
    );
  }

  @override
  bool operator ==(Object other) {
    return other is ChatComposerState &&
        other.hasText == hasText &&
        other.hasFocus == hasFocus &&
        other.keyboardPhase == keyboardPhase &&
        other.isHovered == isHovered &&
        setEquals(other.openPopups, openPopups) &&
        setEquals(other.openingPopups, openingPopups);
  }

  @override
  int get hashCode => Object.hash(
    hasText,
    hasFocus,
    keyboardPhase,
    isHovered,
    Object.hashAllUnordered(openPopups),
    Object.hashAllUnordered(openingPopups),
  );
}

/// Explicit transition owner for focus, keyboard, hover, payload and popup
/// state used by the shared chat composer.
class ChatComposerStateMachine extends ChangeNotifier
    implements ValueListenable<ChatComposerState> {
  ChatComposerStateMachine({required bool hasText, required bool hasFocus})
    : _value = ChatComposerState(
        hasText: hasText,
        hasFocus: hasFocus,
        keyboardPhase: ChatComposerKeyboardPhase.hidden,
      );

  static const double keyboardVisibleInsetThreshold = 0.5;
  static const double keyboardMotionEpsilon = 1.0;

  ChatComposerState _value;
  double _lastKeyboardInset = 0;

  @override
  ChatComposerState get value => _value;

  void textChanged(bool hasText) =>
      _transition(value.copyWith(hasText: hasText));

  void focusChanged(bool hasFocus) =>
      _transition(value.copyWith(hasFocus: hasFocus));

  void hoverChanged(bool isHovered) =>
      _transition(value.copyWith(isHovered: isHovered));

  ChatComposerKeyboardPhase keyboardInsetChanged(double bottomInset) {
    final normalizedInset = bottomInset.isFinite
        ? math.max(0.0, bottomInset)
        : 0.0;
    final previousInset = _lastKeyboardInset;
    _lastKeyboardInset = normalizedInset;

    final phase = switch (normalizedInset) {
      <= keyboardVisibleInsetThreshold => ChatComposerKeyboardPhase.hidden,
      _
          when previousInset <= keyboardVisibleInsetThreshold ||
              normalizedInset > previousInset + keyboardMotionEpsilon =>
        ChatComposerKeyboardPhase.opening,
      _ when normalizedInset < previousInset - keyboardMotionEpsilon =>
        ChatComposerKeyboardPhase.closing,
      _ => switch (value.keyboardPhase) {
        ChatComposerKeyboardPhase.hidden ||
        ChatComposerKeyboardPhase.opening ||
        ChatComposerKeyboardPhase.visible => ChatComposerKeyboardPhase.visible,
        ChatComposerKeyboardPhase.closing => ChatComposerKeyboardPhase.closing,
      },
    };
    _transition(value.copyWith(keyboardPhase: phase));
    return phase;
  }

  bool beginPopupOpening(ChatComposerPopup popup) {
    if (value.isPopupActive(popup)) {
      return false;
    }
    _transition(
      value.copyWith(openingPopups: _withPopup(value.openingPopups, popup)),
    );
    return true;
  }

  void popupOpened(ChatComposerPopup popup) {
    _transition(
      value.copyWith(
        openPopups: _withPopup(value.openPopups, popup),
        openingPopups: _withoutPopup(value.openingPopups, popup),
      ),
    );
  }

  void popupClosed(ChatComposerPopup popup) {
    _transition(
      value.copyWith(
        openPopups: _withoutPopup(value.openPopups, popup),
        openingPopups: _withoutPopup(value.openingPopups, popup),
      ),
    );
  }

  Set<ChatComposerPopup> _withPopup(
    Set<ChatComposerPopup> current,
    ChatComposerPopup popup,
  ) => Set<ChatComposerPopup>.unmodifiable(<ChatComposerPopup>{
    ...current,
    popup,
  });

  Set<ChatComposerPopup> _withoutPopup(
    Set<ChatComposerPopup> current,
    ChatComposerPopup popup,
  ) => Set<ChatComposerPopup>.unmodifiable(
    current.where((item) => item != popup),
  );

  void _transition(ChatComposerState next) {
    if (next == _value) return;
    _value = next;
    notifyListeners();
  }
}
