import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/flclash/search_header.dart';

import '../../../../../models/chat_message_model.dart';
import '../../../../../services/app_background_service.dart';
import '../chat_page_models.dart';
import '../utils/agent_run_timeline.dart';

import '../../command_overlay/widgets/message_bubble.dart';
import '../../command_overlay/widgets/chat_input_area.dart';
import 'agent_run_group_message.dart';
import 'chat_empty_greeting.dart';
import 'chat_message_viewport.dart';

part 'chat_app_bar.dart';
part 'chat_input_wrapper.dart';
part 'chat_message_list.dart';
