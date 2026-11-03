import 'package:flutter/material.dart';
import 'package:ui/models/chat_message_model.dart';

import 'cards/artifact_card.dart';

/// Complete history records use the existing file actions without loading a
/// potentially huge JSON file into the text editor just to inspect its metadata.
class HistoryPayloadAttachment extends StatelessWidget {
  const HistoryPayloadAttachment({super.key, required this.attachment});

  final Map<String, dynamic> attachment;

  @override
  Widget build(BuildContext context) => ArtifactCard(
    artifact: {
      ...attachment,
      'title':
          attachment['title'] ?? attachment['name'] ?? attachment['fileName'],
      'androidPath': attachment['androidPath'] ?? attachment['path'],
      'previewKind': 'file',
    },
  );
}

class HistoryPayloadAttachments extends StatelessWidget {
  const HistoryPayloadAttachments({super.key, required this.message});

  final ChatMessageModel message;

  @override
  Widget build(BuildContext context) {
    final attachments = message.historyPayloadAttachments;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final attachment in attachments)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: HistoryPayloadAttachment(attachment: attachment),
          ),
      ],
    );
  }
}
