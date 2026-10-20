import 'package:ui/features/home/pages/chat/utils/jackery_chat_policy.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/features/jackery/jackery_device_widgets.dart';

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_glass.dart';

import '../../../../../models/chat_message_model.dart';
import '../../../../../services/app_background_service.dart';
import '../../../../../widgets/app_background_widgets.dart';
import '../chat_page_models.dart';
import '../utils/agent_run_timeline.dart';
import '../utils/na_chat_presentation.dart';
import 'na_message_entrance.dart';

import 'package:ui/services/agent_message_kinds.dart';

import '../../command_overlay/widgets/message_bubble.dart';
import '../../command_overlay/widgets/chat_input_area.dart';
import 'agent_run_group_message.dart';
import 'chat_empty_greeting.dart';

part 'chat_app_bar.dart';
part 'chat_input_wrapper.dart';
part 'chat_message_list.dart';
part 'chat_mode_slider.dart';
