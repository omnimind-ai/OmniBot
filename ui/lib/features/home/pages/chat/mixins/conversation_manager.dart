import 'package:flutter/material.dart';
import '../../../../../models/conversation_model.dart';
import '../../../../../models/conversation_thread_target.dart';
import '../../../../../models/chat_message_model.dart';
import '../../../../../services/conversation_service.dart';
import '../../../../../services/conversation_history_service.dart';
import '../utils/deep_thinking_persistence.dart';

/// 对话管理 Mixin
/// 负责对话的创建、加载、保存、切换等功能
mixin ConversationManager<T extends StatefulWidget> on State<T> {
  bool _hasSavedConversation = false;

  Future<void> _persistDeepThinkingCardsForConversation(
    int conversationId,
    List<ChatMessageModel> snapshotMessages,
    ConversationMode mode,
  ) async {
    final candidates = snapshotMessages.where((message) {
      final cardData = message.cardData;
      return message.type == 2 && cardData?['type'] == 'deep_thinking';
    });
    for (final message in candidates) {
      final cardData = message.cardData;
      if (cardData == null) continue;
      await ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: message.id,
        cardData: buildPersistentDeepThinkingCardData(
          Map<String, dynamic>.from(cardData),
        ),
        createdAtMillis: message.createAt.millisecondsSinceEpoch,
        mode: mode,
      );
    }
  }

  // ===================== 抽象属性/方法（需要在主类中实现）=====================

  List<ChatMessageModel> get messages;
  int? get currentConversationId;
  set currentConversationId(int? value);
  ConversationModel? get currentConversation;
  set currentConversation(ConversationModel? value);
  ConversationThreadTarget? get routeThreadTarget;
  ConversationMode get activeConversationModeValue;
  String? get agentIdForNewConversation => null;
  bool get hasMoreMessages;
  set hasMoreMessages(bool value);
  bool get isLoadingMore;
  set isLoadingMore(bool value);
  int get messageOffset;
  set messageOffset(int value);
  int captureConversationLifecycleToken();
  bool isConversationLifecycleTokenCurrent(int token);
  void invalidateConversationLifecycle();
  List<ChatMessageModel>? getInMemoryMessagesForConversation(
    int conversationId,
    ConversationMode mode,
  );
  ConversationModel? getInMemoryConversationForConversation(
    int conversationId,
    ConversationMode mode,
  );
  void onConversationReset(ConversationMode mode) {}
  void onConversationLoaded(
    ConversationMode mode,
    int conversationId,
    ConversationModel? conversation,
    List<ChatMessageModel> messages,
  ) {}
  void onConversationMissing(ConversationMode mode, int conversationId) {}
  void onConversationPersisted(
    ConversationMode mode,
    int conversationId,
    ConversationModel conversation,
    List<ChatMessageModel> messages,
  ) {}
  bool isEphemeralConversation(int conversationId, ConversationMode mode) =>
      false;

  bool _isConversationOperationCurrent(int token) =>
      mounted && isConversationLifecycleTokenCurrent(token);

  // ===================== 对话初始化 =====================

  /// 初始化对话
  Future<void> initializeConversation({int? lifecycleToken}) async {
    final token = lifecycleToken ?? captureConversationLifecycleToken();
    if (!_isConversationOperationCurrent(token)) {
      return;
    }
    final target = routeThreadTarget;
    final operationMode = target?.mode ?? activeConversationModeValue;

    // 多引擎/跨隔离场景下，仅在原生路由携带 conversationId 时刷新缓存
    if (target?.fromNativeRoute == true) {
      await ConversationHistoryService.reloadLocalCache();
      if (!_isConversationOperationCurrent(token)) {
        return;
      }
    }

    if (target != null) {
      if (target.isNewConversation) {
        await ConversationService.setCurrentConversationTarget(target);
        await ConversationHistoryService.saveCurrentConversationTarget(
          target,
          mode: target.mode,
        );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        setState(() {
          messages.clear();
          currentConversationId = null;
          currentConversation = null;
          _hasSavedConversation = false;
          hasMoreMessages = false;
          messageOffset = 0;
          isLoadingMore = false;
        });
        onConversationReset(target.mode);
        return;
      }

      final conversationId = target.conversationId;
      if (conversationId != null) {
        await loadConversation(
          conversationId,
          mode: target.mode,
          lifecycleToken: token,
        );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        await ConversationService.setCurrentConversationTarget(target);
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        await ConversationHistoryService.saveCurrentConversationTarget(
          target,
          mode: target.mode,
        );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        verifyConversationExists(lifecycleToken: token);
        return;
      }
    }

    // 如果没有传入conversationId，尝试恢复上次的对话
    final savedTarget =
        await ConversationHistoryService.getCurrentConversationTarget(
          mode: operationMode,
        );
    if (!_isConversationOperationCurrent(token)) {
      return;
    }
    if (savedTarget != null) {
      if (savedTarget.isNewConversation || savedTarget.conversationId == null) {
        await ConversationService.setCurrentConversationTarget(savedTarget);
        await ConversationHistoryService.saveCurrentConversationTarget(
          savedTarget,
          mode: operationMode,
        );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        setState(() {
          messages.clear();
          currentConversationId = null;
          currentConversation = null;
          _hasSavedConversation = false;
          hasMoreMessages = false;
          messageOffset = 0;
          isLoadingMore = false;
        });
        onConversationReset(activeConversationModeValue);
        return;
      }

      await loadConversation(
        savedTarget.conversationId!,
        mode: savedTarget.mode,
        lifecycleToken: token,
      );
      if (!_isConversationOperationCurrent(token)) {
        return;
      }
      await ConversationService.setCurrentConversationTarget(savedTarget);
      if (!_isConversationOperationCurrent(token)) {
        return;
      }
      await ConversationHistoryService.saveCurrentConversationTarget(
        savedTarget,
        mode: operationMode,
      );
      if (!_isConversationOperationCurrent(token)) {
        return;
      }

      // 恢复对话后，再次验证对话是否仍然存在
      verifyConversationExists(lifecycleToken: token);
      return;
    }

    await _restoreLatestConversationOrReset(lifecycleToken: token);
  }

  Future<void> _restoreLatestConversationOrReset({int? lifecycleToken}) async {
    final token = lifecycleToken ?? captureConversationLifecycleToken();
    final operationMode = activeConversationModeValue;
    try {
      final latestConversation =
          await ConversationService.getLatestConversation(mode: operationMode);
      if (!_isConversationOperationCurrent(token)) {
        return;
      }
      if (latestConversation != null) {
        await loadConversation(
          latestConversation.id,
          mode: latestConversation.mode,
          lifecycleToken: token,
        );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        await ConversationService.setCurrentConversationId(
          latestConversation.id,
          mode: latestConversation.mode,
        );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        await ConversationHistoryService.saveCurrentConversationId(
          latestConversation.id,
          mode: latestConversation.mode,
        );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        verifyConversationExists(lifecycleToken: token);
        return;
      }
    } catch (e) {
      debugPrint('恢复最近对话失败: $e');
    }

    if (!_isConversationOperationCurrent(token)) {
      return;
    }

    if (mounted) {
      setState(() {
        messages.clear();
        currentConversationId = null;
        currentConversation = null;
        _hasSavedConversation = false;
        hasMoreMessages = false;
        messageOffset = 0;
        isLoadingMore = false;
      });
    } else {
      messages.clear();
      currentConversationId = null;
      currentConversation = null;
      _hasSavedConversation = false;
      hasMoreMessages = false;
      messageOffset = 0;
      isLoadingMore = false;
    }

    onConversationReset(activeConversationModeValue);
    final blankTarget = ConversationThreadTarget.newConversation(
      mode: operationMode,
    );
    await ConversationService.setCurrentConversationTarget(blankTarget);
    if (!_isConversationOperationCurrent(token)) {
      return;
    }
    await ConversationHistoryService.saveCurrentConversationTarget(
      blankTarget,
      mode: operationMode,
    );
  }

  /// 加载对话
  Future<void> loadConversation(
    int conversationId, {
    ConversationMode? mode,
    bool preferInMemory = true,
    int? lifecycleToken,
  }) async {
    final token = lifecycleToken ?? captureConversationLifecycleToken();
    if (!_isConversationOperationCurrent(token)) {
      return;
    }
    final operationMode = mode ?? activeConversationModeValue;
    try {
      final inMemoryConversation = preferInMemory
          ? getInMemoryConversationForConversation(
              conversationId,
              operationMode,
            )
          : null;
      final inMemoryMessages = preferInMemory
          ? getInMemoryMessagesForConversation(conversationId, operationMode)
          : null;
      final conversations = await ConversationService.getAllConversations(
        includeArchived: true,
      );
      if (!_isConversationOperationCurrent(token)) {
        return;
      }
      ConversationModel? conversation;
      try {
        conversation = conversations.firstWhere(
          (c) => c.id == conversationId && c.mode == operationMode,
        );
      } catch (_) {
        conversation = null;
      }
      final resolvedConversation = inMemoryConversation ?? conversation;

      if (resolvedConversation != null) {
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        setState(() {
          currentConversationId = resolvedConversation.id;
          currentConversation = resolvedConversation;
          _hasSavedConversation = false;
        });
      } else {
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        setState(() {
          currentConversationId = conversationId;
          currentConversation = null;
          _hasSavedConversation = false;
        });
      }

      List<ChatMessageModel> savedMessages;
      if (inMemoryMessages != null) {
        savedMessages = List<ChatMessageModel>.from(inMemoryMessages);
        setState(() {
          hasMoreMessages = false;
          messageOffset = savedMessages.length;
          // getInMemoryMessagesForConversation copied these entries from the
          // active runtime itself. Reinstalling them would dispose every row
          // notifier and interrupt an AgentRunGroupMessage that is currently
          // animating from running to completed.
        });
      } else {
        final pagedResult =
            await ConversationHistoryService.getConversationMessagesPaged(
              conversationId,
              mode: operationMode,
              limit: 50,
              offset: 0,
              expectedMessageCount: resolvedConversation?.messageCount,
            );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        savedMessages = pagedResult.messages;
        setState(() {
          hasMoreMessages = pagedResult.hasMore;
          messageOffset = 50;
          messages.clear();
          messages.addAll(savedMessages);
        });
      }
      onConversationLoaded(
        operationMode,
        conversationId,
        resolvedConversation,
        // `savedMessages` is the snapshot actually selected for this load.
        // When a live runtime already owns the conversation, the page-level
        // list may still be empty after a refresh/rebuild. Passing `messages`
        // here used to turn that transient empty list into an authoritative
        // runtime snapshot and could erase the visible session.
        List<ChatMessageModel>.from(savedMessages),
      );
    } catch (e) {
      debugPrint('加载对话失败: $e');
    }
  }

  // ===================== 分页加载更多 =====================

  /// 加载更多历史消息
  Future<void> loadMoreMessages({int? lifecycleToken}) async {
    final token = lifecycleToken ?? captureConversationLifecycleToken();
    if (!hasMoreMessages || isLoadingMore) return;
    if (!_isConversationOperationCurrent(token)) {
      return;
    }
    final operationMode = activeConversationModeValue;
    final conversationId = currentConversationId;
    if (conversationId == null) return;
    if (isEphemeralConversation(conversationId, operationMode)) return;

    setState(() {
      isLoadingMore = true;
    });

    try {
      final pagedResult =
          await ConversationHistoryService.getConversationMessagesPaged(
            conversationId,
            mode: operationMode,
            limit: 50,
            offset: messageOffset,
            expectedMessageCount: currentConversation?.messageCount,
          );
      if (_isConversationOperationCurrent(token)) {
        setState(() {
          messages.addAll(pagedResult.messages);
          hasMoreMessages = pagedResult.hasMore;
          messageOffset = messageOffset + pagedResult.messages.length;
          isLoadingMore = false;
        });
      }
    } catch (e) {
      debugPrint('加载更多消息失败: $e');
      if (_isConversationOperationCurrent(token)) {
        setState(() {
          isLoadingMore = false;
        });
      }
    }
  }

  // ===================== 对话存在性检查 =====================

  /// 检查当前对话是否还存在，如果不存在则切换到新对话
  Future<void> checkConversationExists({int? lifecycleToken}) async {
    final token = lifecycleToken ?? captureConversationLifecycleToken();
    final operationMode = activeConversationModeValue;
    final activeConversationId = currentConversationId;
    if (activeConversationId == null) return;
    if (isEphemeralConversation(activeConversationId, operationMode)) return;

    try {
      final allConversations = await ConversationService.getAllConversations(
        includeArchived: true,
      );
      if (!_isConversationOperationCurrent(token)) {
        return;
      }
      final exists = allConversations.any(
        (conversation) =>
            conversation.id == activeConversationId &&
            conversation.mode == operationMode,
      );

      if (!exists) {
        final missingConversationId = activeConversationId;
        final restored = await _restoreConversationFromMessages(
          missingConversationId,
          mode: operationMode,
          lifecycleToken: token,
        );
        if (!_isConversationOperationCurrent(token)) {
          return;
        }
        if (restored) {
          debugPrint('当前对话不在列表中，已从消息记录恢复: $currentConversationId');
          return;
        }
        invalidateConversationLifecycle();
        final transitionToken = captureConversationLifecycleToken();
        onConversationMissing(operationMode, missingConversationId);

        final sameModeConversations = allConversations
            .where((conversation) => conversation.mode == operationMode)
            .toList();
        if (sameModeConversations.isNotEmpty) {
          final fallbackConversation = sameModeConversations.first;
          await loadConversation(
            fallbackConversation.id,
            mode: fallbackConversation.mode,
            lifecycleToken: transitionToken,
          );
          if (!_isConversationOperationCurrent(transitionToken)) {
            return;
          }
          final fallbackTarget = ConversationThreadTarget.existing(
            conversationId: fallbackConversation.id,
            mode: fallbackConversation.mode,
          );
          await ConversationService.setCurrentConversationTarget(
            fallbackTarget,
          );
          if (!_isConversationOperationCurrent(transitionToken)) {
            return;
          }
          await ConversationHistoryService.saveCurrentConversationTarget(
            fallbackTarget,
            mode: fallbackConversation.mode,
          );
          debugPrint('当前对话已失效，已切换到最近对话: ${fallbackConversation.id}');
          return;
        }

        if (_isConversationOperationCurrent(transitionToken)) {
          // 对话已被删除，切换到新对话
          setState(() {
            messages.clear();
            currentConversationId = null;
            currentConversation = null;
            hasMoreMessages = false;
            messageOffset = 0;
            isLoadingMore = false;
          });
        } else {
          return;
        }
        onConversationReset(operationMode);
        final blankTarget = ConversationThreadTarget.newConversation(
          mode: operationMode,
        );
        await ConversationService.setCurrentConversationTarget(blankTarget);
        if (!_isConversationOperationCurrent(transitionToken)) {
          return;
        }
        await ConversationHistoryService.saveCurrentConversationTarget(
          blankTarget,
          mode: operationMode,
        );
        debugPrint('当前对话已被删除，已切换到新对话');
      }
    } catch (e) {
      debugPrint('检查对话存在性失败: $e');
    }
  }

  Future<bool> _restoreConversationFromMessages(
    int conversationId, {
    required ConversationMode mode,
    int? lifecycleToken,
  }) async {
    final token = lifecycleToken ?? captureConversationLifecycleToken();
    try {
      final savedMessages =
          await ConversationHistoryService.getConversationMessages(
            conversationId,
            mode: mode,
            expectedMessageCount: currentConversation?.messageCount,
          );
      if (!_isConversationOperationCurrent(token)) {
        return false;
      }
      if (savedMessages.isEmpty) return false;

      final firstUserMessage = savedMessages.firstWhere(
        (m) => m.user == 1 && (m.text ?? '').isNotEmpty,
        orElse: () => ChatMessageModel.userMessage("新对话"),
      );
      final titleText = firstUserMessage.text ?? '新对话';
      final title = titleText.length > 20
          ? '${titleText.substring(0, 20)}...'
          : titleText;

      final newest = savedMessages.isNotEmpty ? savedMessages.first : null;
      final oldest = savedMessages.isNotEmpty ? savedMessages.last : null;
      final lastText = newest?.text ?? '';
      final createdAt =
          oldest?.createAt.millisecondsSinceEpoch ??
          DateTime.now().millisecondsSinceEpoch;
      final updatedAt = newest?.createAt.millisecondsSinceEpoch ?? createdAt;

      final recovered = ConversationModel(
        id: conversationId,
        mode: mode,
        title: title,
        summary: currentConversation?.summary,
        status: currentConversation?.status ?? 0,
        lastMessage: lastText,
        messageCount: savedMessages.length,
        createdAt: createdAt,
        updatedAt: updatedAt,
      );

      await ConversationService.updateConversation(
        recovered,
        preserveLatestMetadata: true,
      );
      if (!_isConversationOperationCurrent(token)) {
        return false;
      }
      final restoredTarget = ConversationThreadTarget.existing(
        conversationId: conversationId,
        mode: mode,
      );
      await ConversationService.setCurrentConversationTarget(restoredTarget);
      if (!_isConversationOperationCurrent(token)) {
        return false;
      }
      await ConversationHistoryService.saveCurrentConversationTarget(
        restoredTarget,
        mode: mode,
      );
      if (!_isConversationOperationCurrent(token)) {
        return false;
      }

      if (_isConversationOperationCurrent(token)) {
        setState(() {
          currentConversation = recovered;
          if (messages.isEmpty) {
            messages
              ..clear()
              ..addAll(savedMessages);
          }
        });
      }
      return true;
    } catch (e) {
      debugPrint('从消息记录恢复对话失败: $e');
      return false;
    }
  }

  /// 验证对话是否存在（延迟执行，避免阻塞初始化）
  void verifyConversationExists({int? lifecycleToken}) {
    final token = lifecycleToken ?? captureConversationLifecycleToken();
    // 延迟检查，给 UI 渲染留出时间
    Future.delayed(const Duration(milliseconds: 100), () async {
      if (!_isConversationOperationCurrent(token)) return;
      await checkConversationExists(lifecycleToken: token);
    });
  }

  /// 检查并处理已删除的对话（用于 drawer 关闭时）
  void checkAndHandleDeletedConversation({int? lifecycleToken}) {
    final token = lifecycleToken ?? captureConversationLifecycleToken();
    if (currentConversationId == null) return;

    // 延迟检查，确保 drawer 关闭动画完成且路由导航完成
    Future.delayed(const Duration(milliseconds: 300), () async {
      if (!_isConversationOperationCurrent(token)) return;
      await checkConversationExists(lifecycleToken: token);
    });
  }

  // ===================== 对话保存 =====================

  /// 构建对话历史文本
  String buildConversationHistoryText(List<ChatMessageModel> msgs) {
    final buffer = StringBuffer();
    for (final message in msgs) {
      if (message.user == 1) {
        final text = message.content?['text'] as String? ?? '';
        if (text.isNotEmpty) {
          buffer.write('用户: $text\n');
        }
      }
      // else if (message.user == 2) {
      //   final text = message.content?['text'] as String? ?? '';
      //   if (text.isNotEmpty) {
      //     buffer.write('助手: $text\n');
      //   }
      // }
    }
    return buffer.toString().trim();
  }

  /// 持久化对话快照
  Future<void> persistConversationSnapshot({
    bool generateSummary = false,
    bool markComplete = false,
    int? lifecycleToken,
    bool rethrowOnFailure = false,
  }) async {
    if (messages.isEmpty) return;
    final token = lifecycleToken ?? captureConversationLifecycleToken();

    // 立即捕获状态，防止异步操作期间上下文切换导致的脏读
    final snapshotMessages = List<ChatMessageModel>.from(messages);
    final snapshotConversationId = currentConversationId;
    final snapshotConversation = currentConversation;
    final snapshotMode = activeConversationModeValue;
    if (snapshotConversationId != null &&
        isEphemeralConversation(snapshotConversationId, snapshotMode)) {
      return;
    }

    try {
      debugPrint(
        "[conversation manager] 对话持久化 generateSummary: $generateSummary markComplete: $markComplete",
      );
      final now = DateTime.now().millisecondsSinceEpoch;
      final lastMessage = snapshotMessages.isNotEmpty
          ? (snapshotMessages[0].text ?? '')
          : '';
      final messageCount = snapshotMessages.length;

      final firstUserMessage = snapshotMessages.firstWhere(
        (m) => m.user == 1,
        orElse: () => ChatMessageModel.userMessage("新对话"),
      );
      final userText = firstUserMessage.text ?? '新对话';
      final title = userText.length > 20
          ? '${userText.substring(0, 20)}...'
          : userText;

      String? summary;
      if (generateSummary) {
        final conversationHistory = buildConversationHistoryText(
          snapshotMessages,
        );
        summary = conversationHistory.isEmpty
            ? null
            : await ConversationService.generateConversationSummary(
                conversationHistory: conversationHistory,
              );
      }

      int? targetId = snapshotConversationId;

      if (targetId == null) {
        final newConversationId = await ConversationService.createConversation(
          title: title,
          summary: summary,
          mode: snapshotMode,
          agentId: snapshotMode == ConversationMode.agent
              ? agentIdForNewConversation
              : null,
          rethrowOnError: rethrowOnFailure,
        );

        if (newConversationId != null) {
          targetId = newConversationId;

          if (_isConversationOperationCurrent(token) &&
              currentConversationId == snapshotConversationId) {
            setState(() {
              currentConversationId = newConversationId;
              currentConversation = ConversationModel(
                id: newConversationId,
                mode: snapshotMode,
                title: title,
                summary: summary,
                status: 0,
                lastMessage: lastMessage,
                messageCount: messageCount,
                createdAt: now,
                updatedAt: now,
              );
            });
          }

          // After setState above, currentConversationId is now newConversationId.
          // Use targetId for subsequent guard checks so that service calls are
          // not accidentally skipped after a successful create.
          if (_isConversationOperationCurrent(token) &&
              currentConversationId == targetId) {
            await ConversationService.setCurrentConversationId(
              newConversationId,
              mode: snapshotMode,
            );
            await ConversationHistoryService.saveCurrentConversationId(
              newConversationId,
              mode: snapshotMode,
            );
          }
        }
      }

      if (targetId == null) {
        throw StateError('Conversation creation returned no id.');
      }

      if (targetId != null) {
        // 只有当前上下文仍然是该对话时，才更新全局当前对话ID
        if (_isConversationOperationCurrent(token) &&
            currentConversationId == targetId) {
          await ConversationService.setCurrentConversationId(
            targetId,
            mode: snapshotMode,
          );
          await ConversationHistoryService.saveCurrentConversationId(
            targetId,
            mode: snapshotMode,
          );
        }

        final baseConversation =
            snapshotConversation ??
            ConversationModel(
              id: targetId,
              mode: snapshotMode,
              title: title,
              summary: summary,
              status: 0,
              lastMessage: lastMessage,
              messageCount: messageCount,
              createdAt: now,
              updatedAt: now,
            );

        final updatedConversation = baseConversation.copyWith(
          summary: summary ?? baseConversation.summary,
          lastMessage: lastMessage,
          messageCount: messageCount,
          updatedAt: now,
        );

        await ConversationService.updateConversation(
          updatedConversation,
          preserveLatestMetadata: true,
        );

        await _persistDeepThinkingCardsForConversation(
          targetId,
          snapshotMessages,
          snapshotMode,
        );

        if (_isConversationOperationCurrent(token) &&
            currentConversationId == targetId) {
          setState(() {
            currentConversation = updatedConversation;
          });
        }

        if (_isConversationOperationCurrent(token)) {
          onConversationPersisted(
            snapshotMode,
            targetId,
            updatedConversation,
            List<ChatMessageModel>.from(snapshotMessages),
          );
        }

        if (markComplete) {
          await ConversationService.completeConversation(
            targetId,
            mode: snapshotMode,
          );
        }
      }
    } catch (e) {
      debugPrint('保存对话失败: $e');
      if (rethrowOnFailure) rethrow;
    }
  }

  /// 保存对话且总结
  Future<void> saveConversationWithSummary() async {
    if (_hasSavedConversation) {
      await persistConversationSnapshot(
        generateSummary: true,
        markComplete: true,
      );
    }
  }

  /// 保存对话
  Future<void> saveConversation() async {
    _hasSavedConversation = true;
    await persistConversationSnapshot(
      generateSummary: false,
      markComplete: true,
    );
  }

  /// 创建新对话
  Future<void> createNewConversation() async {
    invalidateConversationLifecycle();
    final token = captureConversationLifecycleToken();
    if (!_isConversationOperationCurrent(token)) {
      return;
    }
    setState(() {
      messages.clear();
      currentConversationId = null;
      currentConversation = null;
      _hasSavedConversation = false;
      hasMoreMessages = false;
      messageOffset = 0;
      isLoadingMore = false;
    });
    onConversationReset(activeConversationModeValue);

    final blankTarget = ConversationThreadTarget.newConversation(
      mode: activeConversationModeValue,
    );
    await ConversationService.setCurrentConversationTarget(blankTarget);
    if (!_isConversationOperationCurrent(token)) {
      return;
    }
    await ConversationHistoryService.saveCurrentConversationTarget(
      blankTarget,
      mode: activeConversationModeValue,
    );
  }
}
