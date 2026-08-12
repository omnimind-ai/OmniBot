import 'package:flutter/material.dart';
import '../../../../../models/chat_message_model.dart';
import '../../command_overlay/utils/deep_thinking_parser.dart';
import '../../command_overlay/constants/messages.dart';

/// Dispatch 流式处理 Mixin
/// 负责处理 dispatch 任务的流式响应、思考卡片等功能
mixin DispatchStreamHandler<T extends StatefulWidget> on State<T> {
  // ===================== 抽象属性/方法（需要在主类中实现）=====================

  List<ChatMessageModel> get messages;
  bool get isAiResponding;
  set isAiResponding(bool value);

  String get deepThinkingContent;
  set deepThinkingContent(String value);
  bool get isDeepThinking;
  set isDeepThinking(bool value);
  String? get currentDispatchTaskId;
  set currentDispatchTaskId(String? value);
  int get currentThinkingStage;
  set currentThinkingStage(int value);

  // ===================== Dispatch 流式处理 =====================

  /// 处理 dispatch 流式数据
  void handleDispatchStreamData(
    String taskID,
    String data,
    String fullContent,
  ) {
    if (currentDispatchTaskId != taskID) return;

    final result = DeepThinkingParser.extractDeepThinking(fullContent);

    if (result.hasAnyContent) {
      deepThinkingContent = result.toDisplayText();

      if (result.isDeepThinkingComplete && isDeepThinking) {
        isDeepThinking = false;
      }

      updateThinkingCard(taskID);
    }
  }

  /// 创建思考卡片
  void createThinkingCard(String taskID) {
    final loadingIndex = messages.indexWhere((msg) => msg.id == taskID);
    final thinkingCardId = '$taskID-thinking';
    if (loadingIndex != -1) {
      setState(() => messages.removeAt(loadingIndex));
    }

    final startTime = DateTime.now().millisecondsSinceEpoch;
    final cardData = {
      'type': 'deep_thinking',
      'isLoading': isDeepThinking,
      'thinkingContent': deepThinkingContent,
      'stage': currentThinkingStage,
      'taskID': taskID,
      'cardId': thinkingCardId,
      'startTime': startTime,
      'endTime': null,
    };

    setState(() {
      messages.removeWhere((msg) => msg.id == thinkingCardId);
      messages.insert(
        0,
        ChatMessageModel(
          id: thinkingCardId,
          type: 2,
          user: 3,
          content: {'cardData': cardData, 'id': thinkingCardId},
        ),
      );
    });
  }

  /// 更新思考卡片
  void updateThinkingCard(String taskID) {
    final thinkingCardId = '$taskID-thinking';
    final index = messages.indexWhere((msg) => msg.id == thinkingCardId);
    if (index != -1) {
      setState(() {
        final existing = messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? {});
        final cardData = Map<String, dynamic>.from(content['cardData'] ?? {});

        final currentStage = cardData['stage'] as int? ?? 1;
        final newStage = currentStage == 4 ? 4 : currentThinkingStage;

        final startTime = cardData['startTime'] as int?;
        int? endTime = cardData['endTime'] as int?;
        if (newStage == 4 && endTime == null) {
          endTime = DateTime.now().millisecondsSinceEpoch;
        }

        cardData['thinkingContent'] = deepThinkingContent;
        cardData['isLoading'] = isDeepThinking;
        cardData['stage'] = newStage;
        cardData['taskID'] = taskID;
        cardData['cardId'] = thinkingCardId;
        cardData['startTime'] = startTime;
        cardData['endTime'] = endTime;

        content['cardData'] = cardData;
        messages[index] = existing.copyWith(content: content);
      });
    }
  }

  /// 处理 dispatch 流式结束
  void handleDispatchStreamEnd(String taskID, String fullContent) async {
    if (currentDispatchTaskId != taskID) return;

    isDeepThinking = false;
    updateThinkingCard(taskID);

    await callDispatchPostProcess(taskID, fullContent);
  }

  /// 处理 dispatch 流式错误
  void handleDispatchStreamError(
    String taskID,
    String error,
    String fullContent,
    bool isRateLimited,
  ) {
    if (currentDispatchTaskId != taskID) return;

    isDeepThinking = false;
    updateThinkingCard(taskID);

    if (isRateLimited) {
      handleRateLimitError(taskID);
      resetDispatchState();
      return;
    }

    if (fullContent.isNotEmpty) {
      callDispatchPostProcess(taskID, fullContent);
    } else {
      handleDispatchError(taskID, error);
    }
  }

  /// 调用 dispatch 后处理
  Future<void> callDispatchPostProcess(
    String taskID,
    String llmRawMessage,
  ) async {
    fallbackToChat(taskID);
    resetDispatchState();
  }

  /// 处理 dispatch 结果
  void processDispatchResult(String taskID, Map<String, dynamic> result) {
    currentThinkingStage = 4;
    updateThinkingCard(taskID);

    final bool isRateLimited = result['is_rate_limited'] as bool? ?? false;

    if (isRateLimited) {
      handleRateLimitError(taskID);
      return;
    }

    fallbackToChat(taskID);
  }

  /// 处理 dispatch 错误
  void handleDispatchError(String taskID, String error) {
    setState(() {
      isAiResponding = false;
      messages.removeWhere((msg) => msg.id == taskID && msg.isLoading);
      messages.insert(
        0,
        ChatMessageModel(
          id: taskID,
          type: 1,
          user: 2,
          content: {'text': '小万忙不过来了，等会儿再试试吧', 'id': taskID},
          isError: true,
        ),
      );
    });
    resetDispatchState();
  }

  /// 处理验证错误
  void handleValidationError(String taskID, String debugMessage) {
    isDeepThinking = false;
    currentThinkingStage = 4;
    updateThinkingCard(taskID);

    setState(() {
      isAiResponding = false;
      messages.removeWhere((msg) => msg.id == taskID && msg.isLoading);
      messages.insert(
        0,
        ChatMessageModel(
          id: taskID,
          type: 1,
          user: 2,
          content: {'text': kNetworkErrorMessage, 'id': taskID},
          isError: true,
        ),
      );
    });
    resetDispatchState();
  }

  /// 处理限流错误
  void handleRateLimitError(String taskID) {
    currentThinkingStage = 4;
    updateThinkingCard(taskID);

    setState(() {
      isAiResponding = false;
      messages.insert(
        0,
        ChatMessageModel(
          id: '$taskID-ratelimit',
          type: 1,
          user: 2,
          content: {'text': kRateLimitErrorMessage, 'id': '$taskID-ratelimit'},
          isError: true,
        ),
      );
    });
  }

  /// 旧分发链路兜底（开源版不再回退普通聊天）
  void fallbackToChat(String taskID) {
    currentThinkingStage = 4;
    isDeepThinking = false;
    updateThinkingCard(taskID);
    setState(() {
      isAiResponding = false;
      messages.removeWhere((msg) => msg.id == taskID && msg.isLoading);
      messages.insert(
        0,
        ChatMessageModel(
          id: '$taskID-disabled',
          type: 1,
          user: 2,
          content: {
            'text': '统一 Agent 已启用，旧聊天分发链路已移除，请检查模型配置后重试。',
            'id': '$taskID-disabled',
          },
          isError: true,
        ),
      );
    });
    resetDispatchState();
  }

  /// 移除思考卡片
  void removeThinkingCard(String taskID) {
    final thinkingCardId = '$taskID-thinking';
    setState(() {
      messages.removeWhere((msg) => msg.id == thinkingCardId);
    });
  }

  /// 重置 dispatch 状态
  void resetDispatchState() {
    currentDispatchTaskId = null;
    deepThinkingContent = '';
    isDeepThinking = false;
  }
}
