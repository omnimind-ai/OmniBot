// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Portuguese (`pt`).
class AppLocalizationsPt extends AppLocalizations {
  AppLocalizationsPt([String locale = 'pt']) : super(locale);

  @override
  String get memoryShortRetention =>
      'As memórias de curto prazo permanecem neste dispositivo sem expiração automática. Pressione e segure um item para excluí-lo ou selecione vários para excluir.';

  @override
  String get memoryShortDeleteConfirm => 'Excluir memórias de curto prazo?';

  @override
  String get memoryShortDeleteScope =>
      'Exclui permanentemente apenas os itens de curto prazo selecionados e seu índice de busca. O histórico de conversas, notas rápidas originais, memórias de longo prazo extraídas e o contexto da conversa atual são preservados.';

  @override
  String get memoryShortDeleteFailed =>
      'A exclusão não foi concluída. A lista foi atualizada; selecione os itens novamente e tente outra vez.';

  @override
  String get memoryShortDeleted => 'Memórias de curto prazo excluídas';

  @override
  String get appName => 'Omnibot';

  @override
  String get brandName => 'Omnibot';

  @override
  String get brandNameEnglish => 'Omnibot';

  @override
  String get commonLoading => 'Carregando';

  @override
  String get homeDrawerSearchHint => 'Pesquisar';

  @override
  String get homeDrawerClearSearch => 'Limpar pesquisa';

  @override
  String get themeModeTitle => 'Modo de tema';

  @override
  String get themeModeSubtitle =>
      'Alternar entre aparência clara, escura ou do sistema';

  @override
  String get themeModeLight => 'Claro';

  @override
  String get themeModeDark => 'Escuro';

  @override
  String get themeModeSystem => 'Sistema';

  @override
  String get languageTitle => 'Idioma';

  @override
  String get languageSubtitle =>
      'Escolha o idioma de exibição da interface, prompts do Agent e textos de ferramentas';

  @override
  String get languageFollowSystem => 'Sistema';

  @override
  String get languageZhHans => '简体中文';

  @override
  String get languageEnglish => 'English';

  @override
  String get languagePortugueseBr => 'Português (Brasil)';

  @override
  String get settingsTitle => 'Configurações';

  @override
  String get settingsSectionModelMemory => 'Modelos e memória';

  @override
  String get settingsSectionServiceEnvironment => 'Serviços e ambiente';

  @override
  String get settingsSectionExperienceAppearance => 'Experiência e aparência';

  @override
  String get settingsSectionPermissionInfo => 'Permissões e informações';

  @override
  String get settingsModelProviderTitle => 'Provedores de modelos';

  @override
  String get settingsModelProviderSubtitle =>
      'Configurar endpoints de modelos, chaves de API e listas de modelos';

  @override
  String get settingsSceneModelTitle => 'Configuração de modelos por cena';

  @override
  String get settingsSceneModelSubtitle =>
      'Vincular modelos por cena e usar o modelo padrão para cenas não vinculadas';

  @override
  String get settingsWorkspaceMemoryTitle => 'Memória do Workspace';

  @override
  String get settingsWorkspaceMemoryLoading => 'Carregando...';

  @override
  String get settingsWorkspaceMemoryEnabled =>
      'Memória do workspace ativada (busca por embeddings disponível)';

  @override
  String get settingsWorkspaceMemoryLexical =>
      'Usar memória do workspace (atualmente busca lexical)';

  @override
  String get settingsMcpToolsTitle => 'Ferramentas MCP';

  @override
  String get settingsMcpToolsSubtitle =>
      'Adicionar, ativar e gerenciar serviços MCP remotos';

  @override
  String get settingsLocalServiceTitle => 'Serviço local';

  @override
  String get settingsLocalServiceSubtitle =>
      'Acessar o MCP e o webchat do Omnibot na sua rede local';

  @override
  String get settingsAlpineTitle => 'Ambiente de Terminal';

  @override
  String get settingsAlpineSubtitle =>
      'Escolher e gerenciar o sistema de terminal integrado Alpine ou Ubuntu';

  @override
  String get settingsHideRecentsTitle => 'Ocultar dos recentes';

  @override
  String get settingsHideRecentsSubtitle =>
      'Ocultar o aplicativo da lista de tarefas recentes quando ativado';

  @override
  String get settingsRecentConversationsOnlyTitle =>
      'Mostrar apenas conversas dos últimos 7 dias';

  @override
  String get settingsRecentConversationsOnlySubtitle =>
      'Arquivar automaticamente conversas sem atualização há mais de 7 dias para acelerar a barra lateral';

  @override
  String get settingsAlarmTitle => 'Configurações de alarme';

  @override
  String get settingsAlarmSubtitle =>
      'Configurar toque padrão, arquivo MP3 local ou URL de MP3';

  @override
  String get settingsAppearanceTitle => 'Aparência';

  @override
  String get settingsAppearanceSubtitle =>
      'Configurar modo de tema, idioma, plano de fundo compartilhado, tamanho da fonte e cor do texto';

  @override
  String get settingsVibrationTitle => 'Resposta tátil';

  @override
  String get settingsVibrationSubtitle =>
      'Usar vibração para sinalizar o progresso das tarefas em execução';

  @override
  String get settingsIndependentSendButtonTitle =>
      'Botão de envio independente';

  @override
  String get settingsIndependentSendButtonSubtitle =>
      'Quando ativado, Enter cria uma nova linha; quando desativado, Enter envia a mensagem diretamente';

  @override
  String get settingsHabitualHandTitle => 'Mão dominante';

  @override
  String get settingsHabitualHandSubtitle =>
      'Altera a direção do gesto de deslizar nos menus do histórico';

  @override
  String get settingsHabitualHandLeft => 'Esquerda';

  @override
  String get settingsHabitualHandRight => 'Direita';

  @override
  String get settingsAboutTitle => 'Sobre o Omnibot';

  @override
  String get settingsHideRecentsFailed =>
      'Falha ao atualizar ocultar dos recentes';

  @override
  String get settingsSaveFailed => 'Falha ao salvar configurações';

  @override
  String settingsMcpEnabledToast(Object endpoint) {
    return 'MCP ativado: $endpoint';
  }

  @override
  String get settingsMcpDisabledToast => 'MCP desativado';

  @override
  String get settingsMcpToggleFailed => 'Falha ao alternar o MCP';

  @override
  String get settingsCopiedAddress => 'Endereço copiado';

  @override
  String get settingsCopiedToken => 'Token copiado';

  @override
  String get settingsTokenRefreshed => 'Token atualizado';

  @override
  String get settingsTokenRefreshFailed => 'Falha ao atualizar o token';

  @override
  String get settingsMcpLocalService => 'Serviço local';

  @override
  String get settingsMcpAddress => 'Endereço';

  @override
  String get settingsMcpToken => 'Token';

  @override
  String get settingsNotGenerated => 'Não gerado';

  @override
  String get settingsCopyAddress => 'Copiar endereço';

  @override
  String get settingsCopyToken => 'Copiar token';

  @override
  String get settingsRefreshToken => 'Atualizar token';

  @override
  String get settingsMcpSecurityNotice =>
      'Use o serviço MCP local na mesma rede local com Authorization: Bearer <Token> e evite expor o endereço ou o token na internet pública.';

  @override
  String get settingsInstalledAppsPermissionFailed =>
      'Falha ao solicitar permissão de acesso aos aplicativos instalados';

  @override
  String get appearanceTitle => 'Aparência';

  @override
  String get appearanceAutoSaving => 'Salvando alterações…';

  @override
  String get appearanceAutosaveHint =>
      'As alterações são salvas automaticamente';

  @override
  String get appearanceBackgroundSource => 'Origem do plano de fundo';

  @override
  String get appearancePreview => 'Pré-visualização';

  @override
  String get appearanceAdjustments => 'Ajustes';

  @override
  String get appearancePreviewChat => 'Chat';

  @override
  String get appearancePreviewWorkspace => 'Workspace';

  @override
  String get appearanceEnableBackground => 'Ativar imagem de fundo';

  @override
  String get appearanceEnableBackgroundSubtitle =>
      'Aplicar às páginas de Chat e Workspace e salvar automaticamente';

  @override
  String get appearanceSourceLocal => 'Imagem local';

  @override
  String get appearanceSourceRemote => 'URL da imagem';

  @override
  String get appearanceNoLocalImage => 'Nenhuma imagem local selecionada';

  @override
  String get appearancePickImage => 'Escolher imagem';

  @override
  String get appearanceRepickImage => 'Escolher novamente';

  @override
  String get appearanceRemoteImageUrl => 'URL da imagem';

  @override
  String get appearanceRemoteImageUrlHint =>
      'https://example.com/background.jpg';

  @override
  String get appearanceBackgroundBlur => 'Desfoque de fundo';

  @override
  String get appearanceBackgroundBlurSubtitle =>
      'Ajustar o desfoque da camada sobre a imagem';

  @override
  String get appearanceOverlayIntensity => 'Intensidade da sobreposição';

  @override
  String get appearanceOverlayIntensitySubtitle =>
      'Aumentar a sobreposição uniforme para deixar a interface mais limpa';

  @override
  String get appearanceOverlayBrightness => 'Brilho da sobreposição';

  @override
  String get appearanceOverlayBrightnessSubtitle =>
      'Clarear ou escurecer a sobreposição sem modificar a imagem original';

  @override
  String get appearanceChatTextSize => 'Tamanho do texto do chat';

  @override
  String get appearanceChatTextSizeSubtitle =>
      'Afeta apenas mensagens do usuário, respostas da IA e o painel de raciocínio';

  @override
  String get appearanceTextColorTitle => 'Cor do texto do chat';

  @override
  String get appearanceTextColorSubtitle =>
      'Adapta-se ao fundo por padrão, ou você pode fixar uma cor personalizada';

  @override
  String get appearanceTextColorAuto => 'Automático';

  @override
  String get appearanceCustomColorLabel => 'Cor personalizada';

  @override
  String get appearanceCustomColorHint => '#FFFFFF ou #FF112233';

  @override
  String get appearancePreviewTip =>
      'Você pode arrastar a imagem e fazer pinça para ampliar na pré-visualização acima. O resultado permanece próximo ao efeito real.';

  @override
  String get appearanceColorWhite => 'Branco';

  @override
  String get appearanceColorDarkGray => 'Cinza-escuro';

  @override
  String get appearanceColorLightBlue => 'Azul-claro';

  @override
  String get appearanceColorNavy => 'Azul-marinho';

  @override
  String get appearanceColorTeal => 'Verde-azulado';

  @override
  String get appearanceColorWarmYellow => 'Amarelo-quente';

  @override
  String get appearanceInvalidHttpUrl =>
      'Insira uma URL de imagem http(s) válida';

  @override
  String get appearanceInvalidHexColor => 'Insira #RRGGBB ou #AARRGGBB';

  @override
  String get appearanceInvalidHexColorFormat => 'Código de cor inválido';

  @override
  String appearancePickImageFailed(Object error) {
    return 'Falha ao selecionar imagem: $error';
  }

  @override
  String get appearancePickLocalImageFirst =>
      'Selecione primeiro uma imagem local';

  @override
  String get appearanceLocalImageMissing =>
      'A imagem local não existe mais. Escolha novamente';

  @override
  String appearanceAutosaveFailed(Object error) {
    return 'Falha no salvamento automático: $error';
  }

  @override
  String get chatToolCalling => 'Chamando ferramenta';

  @override
  String get chatFallbackReply =>
      'Não foi possível gerar uma resposta agora. Tente novamente.';

  @override
  String get chatPermissionRequired =>
      'As permissões precisam ser ativadas antes de executar tarefas';

  @override
  String chatPermissionRequiredWithNames(Object names) {
    return 'Ative estas permissões antes de executar tarefas: $names';
  }

  @override
  String get chatRecentTerminalOutputNotice =>
      '[Exibindo apenas a saída mais recente do terminal]\n';

  @override
  String chatUserPrefix(Object text) {
    return 'Usuário: $text\n';
  }

  @override
  String get permissionOverlay => 'Sobreposição de tela';

  @override
  String get permissionInstalledApps => 'Acesso aos apps instalados';

  @override
  String get permissionPublicStorage => 'Acesso ao armazenamento público';

  @override
  String get browserOverlayTitle => 'Navegador do Agent';

  @override
  String get browserOverlayClose => 'Fechar janela do navegador';

  @override
  String get browserOverlayUnsupported =>
      'A visualização do navegador ainda não é suportada nesta plataforma';

  @override
  String get networkErrorMessage =>
      'A conexão oscilou agora há pouco. Tente enviar novamente.';

  @override
  String get rateLimitErrorMessage =>
      'O Omnibot está ocupado no momento. Tente novamente em instantes.';

  @override
  String get chatHistoryArchivedTitle => 'Conversas arquivadas';

  @override
  String get chatHistoryTitle => 'Histórico de conversas';

  @override
  String get chatHistoryNoArchived => 'Nenhuma conversa arquivada';

  @override
  String get chatHistoryEmpty => 'Nenhuma conversa ainda';

  @override
  String get chatHistoryArchivedToast => 'Arquivada';

  @override
  String get chatHistoryUnarchivedToast => 'Removida do arquivo';

  @override
  String get chatHistoryArchiveFailed => 'Não foi possível arquivar a conversa';

  @override
  String get chatHistoryUnarchiveFailed =>
      'Não foi possível restaurar a conversa';

  @override
  String get chatHistoryArchiveHint =>
      'Deslize uma conversa para a esquerda para arquivá-la';

  @override
  String get homeDrawerArchive => 'Arquivo';

  @override
  String get homeDrawerNewChat => 'Nova conversa';

  @override
  String get webchatNoChats => 'Iniciar uma nova conversa';

  @override
  String get memoryCenterTitle => 'Central de memória';

  @override
  String get memoryShortTermTitle => 'Memória de curto prazo';

  @override
  String get memoryLongTermTitle => 'Memória de longo prazo';

  @override
  String get memoryNoShortTerm => 'Nenhuma memória de curto prazo ainda';

  @override
  String get memoryNoShortTermDesc =>
      'Informações de contexto das conversas ficam na memória de curto prazo e depois são organizadas na memória de longo prazo.';

  @override
  String get memoryFilteredNoShortTerm =>
      'Nenhuma memória de curto prazo no filtro atual';

  @override
  String get memoryFilteredNoShortTermDesc =>
      'Volte mais tarde; novas memórias de curto prazo aparecerão gradualmente.';

  @override
  String get memoryNoLongTerm =>
      'Memória de longo prazo ainda não inicializada';

  @override
  String get memoryNoLongTermDesc =>
      'Quando a capacidade de memória for ativada, suas memórias de longo prazo entre sessões serão acumuladas aqui.';

  @override
  String get memoryDeleteConfirmTitle => 'Tem certeza de que deseja excluir?';

  @override
  String get memoryDeleteWarning => 'Esta ação não pode ser desfeita';

  @override
  String get memoryEditDisabled =>
      'A edição da memória de curto prazo não é suportada';

  @override
  String get memoryDeleteDisabled =>
      'A exclusão da memória de curto prazo não é suportada';

  @override
  String get memoryGreeting => 'Olá,\nVamos guardar suas memórias juntos aqui.';

  @override
  String memorySelectedCount(Object n) {
    return '$n selecionada(s)';
  }

  @override
  String get memoryDeselectAll => 'Desmarcar tudo';

  @override
  String get memoryEditTitle => 'Editar memória';

  @override
  String get memoryIdLabel => 'ID da memória';

  @override
  String get memoryMatchScore => 'Pontuação de correspondência';

  @override
  String get memoryAdditionalInfo => 'Informações adicionais';

  @override
  String get memoryAddLongTerm => 'Adicionar memória de longo prazo';

  @override
  String get memorySaveToLongTerm => 'Salvar na memória de longo prazo';

  @override
  String get memoryLongTermAdded => 'Memória de longo prazo adicionada';

  @override
  String get memoryEditLongTerm => 'Editar memória de longo prazo';

  @override
  String get memorySaveChanges => 'Salvar alterações';

  @override
  String get memoryDeleteLongTermConfirm =>
      'Excluir esta memória de longo prazo?';

  @override
  String get memoryLongTermDeleted => 'Memória de longo prazo excluída';

  @override
  String memoryLongTermFailed(Object error) {
    return 'Falha na operação da memória de longo prazo: $error';
  }

  @override
  String get memoryNoMemories => 'Sem memórias';

  @override
  String get memoryNoMemoriesDesc =>
      'Comece a explorar e adicione conteúdos importantes';

  @override
  String get pluginMarketTitle => 'Loja de plugins';

  @override
  String get pluginMarketEmpty => 'Nenhum plugin disponível';

  @override
  String get pluginMarketEmptyDesc =>
      'Os plugins oficiais aparecerão aqui quando conectados';

  @override
  String get pluginInstall => 'Instalar';

  @override
  String get pluginUpdate => 'Atualizar';

  @override
  String get pluginUninstall => 'Desinstalar';

  @override
  String get pluginCancel => 'Cancelar';

  @override
  String get pluginNoDescription => 'Sem descrição';

  @override
  String get pluginIncompatible =>
      'Este plugin é incompatível com a versão atual';

  @override
  String get pluginLoadFailed => 'Falha ao carregar a loja de plugins';

  @override
  String get pluginInstallFailed => 'Falha ao instalar o plugin';

  @override
  String get pluginUpdateFailed => 'Falha ao atualizar o plugin';

  @override
  String get pluginToggleFailed => 'Falha ao alternar o plugin';

  @override
  String get pluginUninstallFailed => 'Falha ao desinstalar o plugin';

  @override
  String get pluginUninstallTitle => 'Desinstalar plugin';

  @override
  String pluginUninstallConfirmMsg(Object name) {
    return 'Desinstalar \"$name\"?';
  }

  @override
  String pluginInstalledMsg(Object name) {
    return '$name instalado';
  }

  @override
  String pluginUpdatedMsg(Object name) {
    return '$name atualizado';
  }

  @override
  String pluginEnabledMsg(Object name) {
    return '$name ativado';
  }

  @override
  String pluginDisabledMsg(Object name) {
    return '$name desativado';
  }

  @override
  String pluginUninstalledMsg(Object name) {
    return '$name desinstalado';
  }

  @override
  String get pluginKindBundledModule => 'Módulo integrado';

  @override
  String get pluginKindRuntimeBundle => 'Pacote de execução';

  @override
  String get pluginKindCompanionApp => 'Aplicativo complementar';

  @override
  String get pluginDetailTitle => 'Detalhes do plugin';

  @override
  String get pluginSearchHint => 'Pesquisar plugins, descrições ou capacidades';

  @override
  String get pluginSearchEmpty => 'Nenhum plugin correspondente';

  @override
  String get pluginAboutTitle => 'Sobre';

  @override
  String get pluginCapabilitiesTitle => 'Capacidades';

  @override
  String get pluginNoCapabilities =>
      'Este plugin não declara capacidades adicionais';

  @override
  String get pluginInformationTitle => 'Informações';

  @override
  String get pluginPublisherLabel => 'Desenvolvedor';

  @override
  String get pluginVersionLabel => 'Versão';

  @override
  String get pluginTypeLabel => 'Tipo';

  @override
  String get pluginDownloadSizeLabel => 'Tamanho do download';

  @override
  String get pluginInterfaceVersionLabel => 'Versão da interface';

  @override
  String get pluginStatusInstalled => 'Instalado';

  @override
  String get pluginStatusEnabled => 'Ativado';

  @override
  String get pluginStatusNotInstalled => 'Não instalado';

  @override
  String get pluginEnableTitle => 'Ativar plugin';

  @override
  String get pluginEnableDescription =>
      'Permitir que o Agent use as capacidades deste plugin';

  @override
  String get pluginRetry => 'Tentar novamente';

  @override
  String get skillStoreTitle => 'Loja de Skills';

  @override
  String get skillBuiltin => 'Integrada';

  @override
  String get skillOfficial => 'Oficial';

  @override
  String get skillUser => 'Usuário';

  @override
  String get skillInstalled => 'Instalada';

  @override
  String get skillNotInstalled => 'Não instalada';

  @override
  String get skillEnabled => 'Ativada';

  @override
  String get skillDisabled => 'Desativada';

  @override
  String get skillInstall => 'Instalar';

  @override
  String get skillDelete => 'Excluir';

  @override
  String get skillEmpty => 'Nenhuma skill disponível';

  @override
  String get skillNoDescription => 'Sem descrição';

  @override
  String get skillBuiltinRemovedDesc =>
      'Esta skill integrada foi removida do workspace. Você pode reinstalá-la a qualquer momento.';

  @override
  String get skillDeleteTitle => 'Excluir skill';

  @override
  String skillDeleteConfirmMsg(Object name) {
    return 'Excluir \"$name\"?';
  }

  @override
  String get skillDeleted => 'Excluída';

  @override
  String get skillDeleteFailed => 'Falha ao excluir';

  @override
  String skillInstalledMsg(Object name) {
    return '$name instalada';
  }

  @override
  String get skillInstallFailed => 'Falha ao instalar';

  @override
  String skillEnabledMsg(Object name) {
    return '$name ativada';
  }

  @override
  String skillDisabledMsg(Object name) {
    return '$name desativada';
  }

  @override
  String get skillToggleFailed => 'Falha ao alternar';

  @override
  String get skillSyncOfficialTooltip => 'Instalar/atualizar skills oficiais';

  @override
  String skillSyncOfficialSuccess(Object count) {
    return 'Skills oficiais sincronizadas ($count)';
  }

  @override
  String get skillSyncOfficialFailed => 'Falha ao sincronizar skills oficiais';

  @override
  String get skillLoadFailed => 'Falha ao carregar skills';

  @override
  String get modelProviderConfigTitle => 'Configuração do provedor';

  @override
  String get modelProviderConfigDesc =>
      'Adicione, alterne e gerencie nomes, endereços e chaves dos provedores de modelos.';

  @override
  String get modelProviderName => 'Nome do provedor';

  @override
  String get modelProviderNameHint => 'ex.: DeepSeek';

  @override
  String get modelProviderBaseUrlHint =>
      'Adicione # no final para desativar o preenchimento automático do caminho';

  @override
  String get modelProviderApiKeyHint =>
      'As requisições serão enviadas sem autenticação se a chave de API não for preenchida.';

  @override
  String get modelListTitle => 'Lista de modelos';

  @override
  String get modelListDesc =>
      'Permite adicionar modelos manualmente ou buscar a lista remota do provedor atual.';

  @override
  String modelListCount(Object count) {
    return '$count modelos no total';
  }

  @override
  String get modelAddPrompt => 'Adicione um modelo!';

  @override
  String get modelBuiltinProvider => 'Provedor integrado';

  @override
  String get modelIdEmpty =>
      'O ID do modelo não pode estar vazio nem começar com \'scene.\'';

  @override
  String get modelAlreadyExists => 'O modelo já existe';

  @override
  String get modelAdded => 'Modelo adicionado';

  @override
  String get modelDeleted => 'Modelo excluído';

  @override
  String get modelDeleteFailed => 'Falha ao excluir o modelo';

  @override
  String get modelIdHint => 'Insira o ID do modelo';

  @override
  String get modelAddProviderTitle => 'Adicionar provedor';

  @override
  String get modelAddButton => 'Adicionar';

  @override
  String get modelProviderAdded => 'Provedor adicionado';

  @override
  String modelProviderAddFailed(Object error) {
    return 'Falha ao adicionar provedor: $error';
  }

  @override
  String get modelDeleteProviderTitle => 'Excluir provedor';

  @override
  String modelDeleteProviderMsg(Object name) {
    return 'Excluir \"$name\"? Os vínculos de cena serão preservados, mas você precisará selecionar outro provedor disponível.';
  }

  @override
  String get modelProviderDeleted => 'Provedor excluído';

  @override
  String modelProviderDeleteFailed(Object error) {
    return 'Falha ao excluir provedor: $error';
  }

  @override
  String get modelProviderLoadFailed =>
      'Falha ao carregar configurações de provedores';

  @override
  String modelProviderSwitchFailed(Object error) {
    return 'Falha ao alternar provedor: $error';
  }

  @override
  String get modelProviderBaseUrlRequired => 'Insira primeiro uma Base URL';

  @override
  String get modelProviderInvalidBaseUrl =>
      'Insira uma Base URL http(s) válida';

  @override
  String modelProviderFetchedModels(Object count) {
    return '$count modelos carregados';
  }

  @override
  String modelProviderFetchFailed(Object error) {
    return 'Falha ao buscar a lista de modelos: $error';
  }

  @override
  String get sceneModelMapping => 'Mapeamento de cenas';

  @override
  String get sceneModelMappingDesc =>
      'Vincule provedores e modelos por cena. Cenas não vinculadas continuarão usando o modelo padrão.';

  @override
  String get sceneModelRefreshList => 'Atualizar lista de modelos';

  @override
  String get sceneModelSearchHint =>
      'Toque no botão à direita para pesquisar, recolher e selecionar modelos por provedor; a barra de busca superior permanece fixa.';

  @override
  String get sceneModelNoScenes => 'Nenhuma cena configurável';

  @override
  String get sceneModelLoadFailed =>
      'Falha ao carregar configurações de modelos por cena';

  @override
  String sceneModelPartialUpdateFailed(Object profiles) {
    return 'Alguns modelos foram atualizados, mas estes provedores falharam: $profiles';
  }

  @override
  String sceneModelUpdatedModels(Object count) {
    return '$count modelos atualizados';
  }

  @override
  String sceneModelRefreshFailed(Object error) {
    return 'Falha ao atualizar a lista de modelos: $error';
  }

  @override
  String get sceneModelInvalidModelId =>
      'O ID do modelo não pode começar com scene.';

  @override
  String sceneModelBoundToast(Object scene, Object model) {
    return '$scene agora está usando $model';
  }

  @override
  String sceneModelSaveFailed(Object scene, Object error) {
    return 'Falha ao salvar $scene: $error';
  }

  @override
  String sceneModelBindingCleared(Object scene) {
    return 'Vínculo removido de $scene';
  }

  @override
  String sceneModelDefaultRestored(Object scene) {
    return '$scene voltou para o modelo padrão';
  }

  @override
  String sceneModelClearFailed(Object scene, Object error) {
    return 'Falha ao limpar $scene: $error';
  }

  @override
  String get modelsNoAvailableModels => 'Nenhum modelo disponível';

  @override
  String get alarmSaved => 'Configurações de alarme salvas';

  @override
  String get alarmRingtoneSource => 'Origem do toque';

  @override
  String get alarmSystemDefault => 'Padrão do sistema';

  @override
  String get alarmSystemDefaultDesc =>
      'Sem configuração extra, melhor compatibilidade';

  @override
  String get alarmLocalMp3 => 'MP3 local';

  @override
  String get alarmLocalMp3Desc =>
      'Selecionar um arquivo MP3 no celular como toque do alarme';

  @override
  String get alarmMp3Url => 'URL de MP3';

  @override
  String get alarmMp3UrlDesc =>
      'Usar uma URL HTTP(S) para reproduzir um MP3 online';

  @override
  String get alarmAudioPermissionDenied =>
      'Permissão de leitura de áudio não concedida';

  @override
  String get alarmInvalidFilePath =>
      'Caminho de arquivo inválido, selecione novamente';

  @override
  String get alarmSelectLocalFirst => 'Selecione primeiro um arquivo MP3 local';

  @override
  String get alarmEnterHttpsUrl => 'Insira uma URL HTTP(S) de MP3';

  @override
  String get alarmLocalFile => 'Arquivo local';

  @override
  String get alarmSelectMp3 => 'Selecionar arquivo MP3';

  @override
  String get authorizePageTitle => 'Autorização de permissões';

  @override
  String get authorizeReceiveNotifications => 'Receber notificações';

  @override
  String get authorizeNotificationsDesc =>
      'Ative para acompanhar o progresso das tarefas em tempo real';

  @override
  String get storageUsageTitle => 'Uso de armazenamento';

  @override
  String get storageUsageSubtitle =>
      'Ver detalhes de uso de armazenamento e limpar por categoria';

  @override
  String get storageAnalyzeFailed =>
      'Falha na análise de armazenamento, tente novamente';

  @override
  String storageCategoryCleaned(Object name, Object size) {
    return '$name limpo, $size liberados';
  }

  @override
  String get storageCleanFailed =>
      'Falha na limpeza, tente novamente mais tarde';

  @override
  String storageCleanCategory(Object name) {
    return 'Limpar $name';
  }

  @override
  String get storageCleanConfirmMsg => 'Confirmar limpeza desta categoria?';

  @override
  String get storageCleanScope => 'Escopo da limpeza';

  @override
  String get storageCleanAll => 'Tudo';

  @override
  String get storageClean7Days => 'Mais de 7 dias';

  @override
  String get storageClean30Days => 'Mais de 30 dias';

  @override
  String storageStrategyName(Object name) {
    return 'Estratégia: $name';
  }

  @override
  String storageStrategyDone(Object size) {
    return 'Estratégia concluída, $size liberados';
  }

  @override
  String storageStrategyPartialDone(Object count, Object size) {
    return 'Estratégia concluída, $size liberados, $count itens parcialmente concluídos';
  }

  @override
  String get storageStrategyFailed =>
      'Falha na estratégia, tente novamente mais tarde';

  @override
  String get storageLoadFailed => 'Falha ao carregar';

  @override
  String get storageReanalyze => 'Reanalisar';

  @override
  String get storageTotalUsage => 'Uso total';

  @override
  String get storageAppSize => 'Tamanho do app';

  @override
  String get storageUserData => 'Dados do usuário';

  @override
  String get storageCleanable => 'Liberável';

  @override
  String storageStatsSource(Object source) {
    return 'Fonte das estatísticas: $source';
  }

  @override
  String storagePackageName(Object name) {
    return 'Pacote atual: $name';
  }

  @override
  String get storageTrendFirst =>
      'Esta é a primeira análise. As tendências de uso serão exibidas nas próximas análises.';

  @override
  String get storageSmartCleanup => 'Limpeza inteligente';

  @override
  String get storageExecute => 'Executar';

  @override
  String get storageUsageAnalysis => 'Análise de uso';

  @override
  String get storageClean => 'Limpar';

  @override
  String get storageRiskLow => 'Risco baixo';

  @override
  String get storageRiskCaution => 'Atenção';

  @override
  String get storageRiskHigh => 'Risco alto';

  @override
  String get storageReadOnly => 'Somente leitura';

  @override
  String get storageSystemStats =>
      'Estatísticas do sistema (mais próximo das configurações do Android)';

  @override
  String get storageDirectoryScan => 'Estimativa por varredura de diretórios';

  @override
  String get storageAdditionalInfo => 'Informações adicionais';

  @override
  String get storageCatAppBinary => 'Binário do aplicativo';

  @override
  String get storageCatAppBinaryDesc =>
      'Arquivos instalados do aplicativo (APK/AAB)';

  @override
  String get storageCatCache => 'Cache';

  @override
  String get storageCatCacheDesc =>
      'Arquivos temporários e cache de imagens, seguro para limpar';

  @override
  String get storageCatCacheHint =>
      'Será regenerado automaticamente durante o uso após a limpeza';

  @override
  String get storageCatConversation => 'Histórico de conversas';

  @override
  String get storageCatConversationDesc =>
      'Histórico de mensagens e execuções de ferramentas (estimado)';

  @override
  String get storageCatConversationHint =>
      'Excluirá registros históricos de mensagens sem possibilidade de recuperação';

  @override
  String get storageCatDatabaseOther => 'Outros bancos de dados';

  @override
  String get storageCatDatabaseOtherDesc => 'Índices e tabelas do sistema';

  @override
  String get storageCatWorkspaceBrowser =>
      'Artefatos do navegador no Workspace';

  @override
  String get storageCatWorkspaceBrowserDesc =>
      'Capturas de tela, downloads e arquivos intermediários do navegador';

  @override
  String get storageCatWorkspaceBrowserHint =>
      'Excluirá arquivos intermediários da ferramenta de navegador';

  @override
  String get storageCatWorkspaceOffloads => 'Saídas auxiliares do Workspace';

  @override
  String get storageCatWorkspaceOffloadsDesc =>
      'Saídas offline de ferramentas e arquivos temporários';

  @override
  String get storageCatWorkspaceOffloadsHint =>
      'Exclui apenas artefatos temporários, sem afetar funcionalidades principais';

  @override
  String get storageCatWorkspaceAttachments => 'Anexos do Workspace';

  @override
  String get storageCatWorkspaceAttachmentsDesc =>
      'Arquivos anexados usados em tarefas anteriores';

  @override
  String get storageCatWorkspaceAttachmentsHint =>
      'Pode afetar a visualização de anexos em conversas antigas';

  @override
  String get storageCatWorkspaceShared =>
      'Arquivos compartilhados do Workspace';

  @override
  String get storageCatWorkspaceSharedDesc =>
      'Arquivos do workspace compartilhados entre tarefas';

  @override
  String get storageCatWorkspaceSharedHint =>
      'Pode afetar tarefas futuras que reutilizem arquivos compartilhados';

  @override
  String get storageCatWorkspaceMemory => 'Dados de memória do Workspace';

  @override
  String get storageCatWorkspaceMemoryDesc =>
      'Memórias de curto/longo prazo e índices';

  @override
  String get storageCatWorkspaceUserFiles => 'Arquivos do usuário no Workspace';

  @override
  String get storageCatWorkspaceUserFilesDesc =>
      'Arquivos salvos manualmente pelo usuário no workspace';

  @override
  String get storageCatTerminalLocal => 'Ambiente de terminal (local)';

  @override
  String get storageCatTerminalLocalDesc =>
      'Diretório local de execução do terminal Alpine/Ubuntu';

  @override
  String get storageCatTerminalLocalHint =>
      'Excluirá o diretório local do terminal; será necessário reinicializar';

  @override
  String get storageCatTerminalBootstrap => 'Ambiente de terminal (bootstrap)';

  @override
  String get storageCatTerminalBootstrapDesc =>
      'Arquivos base proot/lib/rootfs';

  @override
  String get storageCatTerminalBootstrapHint =>
      'Excluirá arquivos de inicialização do terminal; será necessário reinicializar';

  @override
  String get storageCatSharedDrafts => 'Rascunhos compartilhados';

  @override
  String get storageCatSharedDraftsDesc =>
      'Cache de rascunhos importados via compartilhamento externo';

  @override
  String get storageCatSharedDraftsHint =>
      'Excluirá anexos de rascunhos não enviados';

  @override
  String get storageCatMcpInbox => 'Caixa de entrada MCP';

  @override
  String get storageCatMcpInboxDesc =>
      'Diretório de recebimento de arquivos via MCP';

  @override
  String get storageCatMcpInboxHint =>
      'Excluirá os arquivos recebidos na caixa de entrada MCP';

  @override
  String get storageCatLegacyWorkspace => 'Dados legados';

  @override
  String get storageCatLegacyWorkspaceDesc =>
      'Diretórios antigos de workspace possivelmente mantidos após atualização';

  @override
  String get storageCatLegacyWorkspaceHint =>
      'Confirme que não são mais necessários antes de limpar';

  @override
  String get storageCatOtherUserData => 'Outros dados';

  @override
  String get storageCatOtherUserDataDesc =>
      'Dados não correspondentes a nenhuma categoria específica';

  @override
  String get storageStrategySafeQuick => 'Limpeza rápida e segura';

  @override
  String get storageStrategySafeQuickDesc =>
      'Prioriza limpar caches de baixo risco e arquivos temporários';

  @override
  String get storageStrategyBalanceDeep => 'Limpeza profunda equilibrada';

  @override
  String get storageStrategyBalanceDeepDesc =>
      'Libera mais espaço preservando dados essenciais e arquivos do usuário';

  @override
  String get storageStrategyFree1gb => 'Meta de liberar 1 GB';

  @override
  String get storageStrategyFree1gbDesc =>
      'Limpa por ordem de maior impacto buscando liberar 1 GB';

  @override
  String get storageHintConversation =>
      'Se o histórico não liberar espaço imediatamente, abra a página novamente e toque em \"Reanalisar\"';

  @override
  String get storageHintTerminal =>
      'Após limpar o runtime do terminal, você pode reinicializá-lo na página Ambiente de Terminal';

  @override
  String get storageHintGeneral =>
      'Se a limpeza falhar, tente novamente mais tarde ou reinicie o aplicativo';

  @override
  String get storageHintNotCleanable =>
      'Esta categoria não pode ser limpa no momento';

  @override
  String get storageHintSkipped => 'Esta categoria foi ignorada (opcional)';

  @override
  String storageCleanPartialFailed(Object hint) {
    return 'Parte da limpeza falhou: $hint';
  }

  @override
  String get storageCleanPartialFailedGeneric =>
      'Alguns arquivos não puderam ser limpos; tente novamente mais tarde';

  @override
  String storageTrendVsLast(Object cleanable, Object total) {
    return 'Em relação à última análise: total $total, liberável $cleanable';
  }

  @override
  String storageLastAnalyzed(Object time) {
    return 'Última análise: $time';
  }

  @override
  String get aboutDescription =>
      'O Omnibot é um assistente de IA centrado em\nconversas inteligentes, combinando compreensão semântica\ne aprendizado contínuo para ajudar no processamento\nde informações, apoio à decisão e tarefas do dia a dia.';

  @override
  String get aboutBetaProgramTitle => 'Participar dos testes beta';

  @override
  String get aboutBetaProgramDescription =>
      'Receba atualizações beta com mais frequência.';

  @override
  String get aboutBetaProgramToggleFailed =>
      'Falha ao atualizar preferência do programa beta';

  @override
  String get aboutPreferencesSectionTitle => 'Atualizações e testes';

  @override
  String get aboutApkSourceTitle => 'Fonte de download do APK';

  @override
  String get aboutApkSourceDescription =>
      'Escolha a origem usada para instalar atualizações.';

  @override
  String get aboutApkSourceDisclaimer =>
      'Ao usar este aplicativo, você concorda com nossa Política de Privacidade e consente com a coleta anônima de informações de uso pelo backend do site de código aberto para aprimorar o software. Você é o único responsável por eventuais perdas ou consequências decorrentes do uso do aplicativo.';

  @override
  String get aboutApkSourceOptionCnb => 'Site do Omnibot';

  @override
  String get aboutApkSourceOptionCnbDescription =>
      'Distribuído pelo site do Omnibot';

  @override
  String get aboutApkSourceOptionGithub => 'GitHub';

  @override
  String get aboutApkSourceOptionGithubDescription =>
      'Fonte oficial de lançamentos';

  @override
  String get aboutApkSourceSwitchFailed =>
      'Falha ao alternar a fonte de download do APK';

  @override
  String get aboutUpdateHintDefault =>
      'Verifique se há atualizações para obter a versão mais recente';

  @override
  String get workspaceMemoryLoadFailed =>
      'Falha ao carregar a configuração de memória do workspace';

  @override
  String get agentSoulSaved => 'Configuração de personalidade do Agent salva';

  @override
  String get agentSoulSaveFailed => 'Falha ao salvar a personalidade do Agent';

  @override
  String get chatPromptSaved => 'Prompt de sistema do modo somente chat salvo';

  @override
  String get chatPromptSaveFailed =>
      'Falha ao salvar o prompt de sistema do modo somente chat';

  @override
  String get workspaceMemorySaved => 'MEMORY.md salvo';

  @override
  String get workspaceMemorySaveFailed => 'Falha ao salvar MEMORY.md';

  @override
  String get workspaceEmbeddingToggleFailed =>
      'Falha ao alternar embeddings de memória';

  @override
  String get workspaceRollupToggleFailed =>
      'Falha ao alternar consolidação noturna';

  @override
  String get workspaceRollupDone => 'Consolidação concluída';

  @override
  String get workspaceRollupFailed => 'Falha na consolidação';

  @override
  String get workspaceNone => 'Nenhum';

  @override
  String get workspaceMemoryTitle => 'Memória do Workspace';

  @override
  String get workspaceMemoryCapability => 'Capacidade de memória';

  @override
  String get workspaceEmbeddingReady =>
      'Configurado, busca vetorial disponível';

  @override
  String get workspaceEmbeddingNotReady =>
      'Não configurado, usará busca lexical como alternativa';

  @override
  String get workspaceGoToConfig =>
      'Ir para configuração de modelos por cena para definir o modelo de embedding';

  @override
  String get workspaceNightlyRollup =>
      'Consolidação noturna de memória (22:00)';

  @override
  String workspaceLastRun(Object time) {
    return 'Última execução: $time';
  }

  @override
  String workspaceNextRun(Object time) {
    return 'Próxima execução: $time';
  }

  @override
  String get workspaceRollupNow => 'Consolidar agora';

  @override
  String get workspaceSettingsAndMemory => 'Configurações e memória do Agent';

  @override
  String get agentSoulSetting => 'Personalidade do Agent';

  @override
  String get chatPromptSetting => 'Prompt do modo somente chat';

  @override
  String get workspaceMemoryMd => 'MEMORY.md (Memória de longo prazo)';

  @override
  String get alpineNodeJs => 'Ambiente de execução Node.js';

  @override
  String get alpineNpm => 'Gerenciador de pacotes do Node.js';

  @override
  String get alpineGit => 'Controle de versão Git';

  @override
  String get alpinePython => 'Interpretador Python';

  @override
  String get alpinePip => 'Projetos e pacotes Python';

  @override
  String get alpinePipInstall => 'Instalador de pacotes Python';

  @override
  String get alpineCodex => 'CLI OpenAI Codex para Agents ACP';

  @override
  String get alpineClaudeCode => 'CLI Anthropic Claude Code para Agents ACP';

  @override
  String get alpineOpenCode => 'CLI OpenCode com suporte ACP integrado';

  @override
  String get alpineKimiCode => 'CLI oficial do Kimi Code e interface Web local';

  @override
  String get alpinePi => 'Agent Pi com suporte a ferramentas MCP';

  @override
  String get alpineSshClient => 'Cliente SSH';

  @override
  String get alpineSshpass => 'Auxiliar de senha SSH';

  @override
  String get alpineOpenSshServer => 'Servidor OpenSSH';

  @override
  String get alpineDetectFailed => 'Falha ao detectar o ambiente de terminal';

  @override
  String get alpineBootTasksLoadFailed =>
      'Falha ao carregar tarefas de inicialização';

  @override
  String get alpineConfigOpenFailed =>
      'Falha ao abrir a configuração do ambiente de terminal';

  @override
  String get alpineBootTaskAdded => 'Tarefa de inicialização adicionada';

  @override
  String get alpineBootTaskUpdated => 'Tarefa de inicialização atualizada';

  @override
  String get alpineBootTaskSaveFailed =>
      'Falha ao salvar tarefa de inicialização';

  @override
  String get alpineBootEnabled => 'Início automático ativado ao abrir o app';

  @override
  String get alpineBootDisabled => 'Início automático desativado';

  @override
  String get alpineBootTaskUpdateFailed => 'Falha ao atualizar tarefa';

  @override
  String get alpineDeleteBootTask => 'Excluir tarefa de inicialização';

  @override
  String alpineDeleteBootTaskMsg(Object name) {
    return 'Excluir \"$name\"?';
  }

  @override
  String get alpineBootTaskDeleted => 'Tarefa de inicialização excluída';

  @override
  String get alpineBootTaskDeleteFailed => 'Falha ao excluir tarefa';

  @override
  String get alpineCommandSent => 'Comando de inicialização enviado';

  @override
  String get alpineStartFailed => 'Falha ao iniciar tarefa';

  @override
  String get alpineDetecting => 'Detectando ambiente';

  @override
  String alpineStartConfig(Object count) {
    return 'Iniciar configuração ($count itens)';
  }

  @override
  String get alpineAllReady => 'Tudo pronto';

  @override
  String get alpineDetectingDesc =>
      'Detectando informações de versão das ferramentas de desenvolvimento no sistema de terminal selecionado.';

  @override
  String alpineReadyCount(Object ready, Object total) {
    return '$ready/$total itens prontos no sistema de terminal selecionado. Marque os itens ausentes para configurar automaticamente no ReTerminal.';
  }

  @override
  String get alpineBootTasks => 'Tarefas de inicialização';

  @override
  String get alpineBootTasksDesc =>
      'Ao abrir o Omnibot, as tarefas ativadas são verificadas em segundo plano e iniciadas na sessão correspondente do ReTerminal. Ideal para serviços contínuos.';

  @override
  String get alpineAddTask => 'Adicionar tarefa';

  @override
  String get alpineOpenTerminal => 'Abrir terminal';

  @override
  String get alpineNoTasksDesc =>
      'Nenhuma tarefa. Você pode adicionar comandos persistentes como `python app.py`, `node server.js` ou `./start.sh`.';

  @override
  String get alpineBootOnAppOpen => 'Iniciar ao abrir o app';

  @override
  String get alpineNotEnabled => 'Desativado';

  @override
  String get alpineRunning => 'Em execução';

  @override
  String get alpineStartNow => 'Iniciar agora';

  @override
  String get alpineEdit => 'Editar';

  @override
  String get alpineVersionDetected => 'Versão detectada';

  @override
  String get alpineVersionNotFound => 'Não detectado';

  @override
  String get alpineTaskNameHint => 'Insira o nome da tarefa';

  @override
  String get alpineCommandHint => 'Insira o comando de inicialização';

  @override
  String get alpineEditBootTask => 'Editar tarefa de inicialização';

  @override
  String get alpineAddBootTask => 'Adicionar tarefa de inicialização';

  @override
  String get alpineTaskName => 'Nome da tarefa';

  @override
  String get alpineTaskNameExample => 'ex.: Serviço de API local';

  @override
  String get alpineStartCommand => 'Comando de inicialização';

  @override
  String get alpineCommandExample => 'ex.: python app.py ou pnpm start';

  @override
  String get alpineWorkDir => 'Diretório de trabalho';

  @override
  String get alpineBootAutoStart =>
      'Iniciar automaticamente ao abrir o Omnibot';

  @override
  String get alpineDevEnv => 'Ambiente de desenvolvimento';

  @override
  String get alpineAiAgent => 'Agent de IA';

  @override
  String get alpineEnvConfig => 'Configuração do ambiente';

  @override
  String alpineWorkDirValue(Object dir) {
    return 'Diretório de trabalho: $dir';
  }

  @override
  String get workspaceEmbeddingRetrieval => 'Busca por embeddings de memória';

  @override
  String get chatHistoryStartConversation => 'Iniciar uma conversa';

  @override
  String get homeDrawerSearching => 'Pesquisando conversas...';

  @override
  String get homeDrawerNoResults =>
      'Nenhuma conversa correspondente encontrada';

  @override
  String get homeDrawerSearchHint2 =>
      'Tente palavras-chave mais curtas ou reformule sua busca';

  @override
  String get homeDrawerSearchResults => 'Resultados da pesquisa';

  @override
  String get homeDrawerResultCount => 'resultados';

  @override
  String get homeDrawerScheduled => 'Agendadas';

  @override
  String get homeDrawerScheduledTasks => 'Tarefas agendadas';

  @override
  String get homeDrawerPinnedConversations => 'Conversas fixadas';

  @override
  String get homeDrawerAgentSection => 'Agent';

  @override
  String get homeDrawerOmniAiSection => 'OmniAi';

  @override
  String get homeDrawerChatOnlySection => 'Somente chat';

  @override
  String get homeDrawerAgentNoProject => 'Outros';

  @override
  String get homeDrawerGreeting => 'Olá!';

  @override
  String get homeDrawerWelcome => 'Bem-vindo ao Omnibot';

  @override
  String get homeDrawerDawnGreeting => 'Madrugada';

  @override
  String get homeDrawerDawnSub => 'Ainda acordado?';

  @override
  String get homeDrawerDawnGreeting2 => 'Antes do amanhecer';

  @override
  String get homeDrawerDawnSub2 => 'Quem cedo madruga, cuide-se!';

  @override
  String get homeDrawerDawnGreeting3 => 'Madrugada tranquila';

  @override
  String get homeDrawerDawnSub3 => 'Lembre-se de descansar um pouco.';

  @override
  String get homeDrawerMorningGreeting => 'Bom dia!';

  @override
  String get homeDrawerMorningSub => 'Comece o dia com energia';

  @override
  String get homeDrawerMorningGreeting2 => 'Bom dia!';

  @override
  String get homeDrawerMorningSub2 => 'Um novo dia começou';

  @override
  String get homeDrawerForenoonGreeting => 'Ótima manhã!';

  @override
  String get homeDrawerForenoonSub => 'Alongue um pouco os ombros';

  @override
  String get homeDrawerForenoonGreeting2 => 'Ótimo ritmo!';

  @override
  String get homeDrawerForenoonSub2 => 'Continue assim';

  @override
  String get homeDrawerLunchGreeting => 'Hora do almoço!';

  @override
  String get homeDrawerLunchSub => 'Faça uma boa refeição';

  @override
  String get homeDrawerLunchGreeting2 => 'Boa tarde~';

  @override
  String get homeDrawerLunchSub2 => 'Faça uma pequena pausa após o almoço';

  @override
  String get homeDrawerLunchGreeting3 => 'Sem ideia do que comer?';

  @override
  String get homeDrawerLunchSub3 => 'Deixe o Omnibot recomendar para você';

  @override
  String get homeDrawerAfternoonGreeting => 'Hora de uma pausa para o café';

  @override
  String get homeDrawerAfternoonSub => 'Você consegue!';

  @override
  String get homeDrawerAfternoonGreeting2 => 'Descanse a vista um pouco';

  @override
  String get homeDrawerAfternoonSub2 => 'Relaxe os olhos por um instante';

  @override
  String get homeDrawerEveningGreeting => 'Bom retorno para casa';

  @override
  String get homeDrawerEveningSub => 'Relaxe hoje à noite';

  @override
  String get homeDrawerEveningGreeting2 => 'Brisa do fim de tarde';

  @override
  String get homeDrawerEveningSub2 => 'Sensação agradável, não é?';

  @override
  String get homeDrawerEveningGreeting3 => 'Dia intenso hoje';

  @override
  String get homeDrawerEveningSub3 => 'Recompense-se com um bom jantar';

  @override
  String get homeDrawerNightGreeting => 'Boa noite!';

  @override
  String get homeDrawerNightSub => 'Aproveite seu tempo';

  @override
  String get homeDrawerNightGreeting2 => 'A noite está chegando';

  @override
  String get homeDrawerNightSub2 => 'Prepare-se para descansar mais cedo';

  @override
  String get homeDrawerNightGreeting3 => 'Hora de descansar';

  @override
  String get homeDrawerNightSub3 =>
      'Deixe o Omnibot definir um alarme para você';

  @override
  String get homeDrawerLateNightGreeting =>
      'Guarde o celular e durma mais cedo';

  @override
  String get homeDrawerLateNightSub => 'Recarregue as energias para amanhã';

  @override
  String get homeDrawerLateNightGreeting2 => 'Já está tarde';

  @override
  String get homeDrawerLateNightSub2 => 'Diga boa noite ao dia de hoje';

  @override
  String get dialogCancel => 'Cancelar';

  @override
  String get dialogConfirm => 'Confirmar';

  @override
  String get dialogOk => 'OK';

  @override
  String get dialogLoading => 'Carregando';

  @override
  String get dialogPleaseWait => 'Aguarde...';

  @override
  String get webviewDownloading => 'Baixando, aguarde...';

  @override
  String get webviewDownloadStarted => 'Download iniciado...';

  @override
  String get webviewImageSaved => 'Imagem salva na galeria';

  @override
  String get webviewFileSavedDownloads => 'Arquivo salvo em Downloads';

  @override
  String webviewFileSaved(Object fileName) {
    return 'Arquivo baixado: $fileName';
  }

  @override
  String get webviewDownloadFailed => 'Falha no download';

  @override
  String get webviewBrowse => 'Navegador Web';

  @override
  String get webviewRefresh => 'Atualizar';

  @override
  String get webviewLoadFailed => 'Falha ao carregar a página';

  @override
  String get webviewUnknownError => 'Erro desconhecido';

  @override
  String get webviewReload => 'Recarregar';

  @override
  String get openclawBaseUrlEmpty =>
      'A Base URL do OpenClaw está vazia; configure-a primeiro';

  @override
  String get openclawConnected =>
      'OpenClaw conectado com sucesso, protocolo Gateway OK';

  @override
  String get openclawConnectedNoChallenge =>
      'OpenClaw conectado, mas nenhum desafio foi recebido; verifique a versão do Gateway';

  @override
  String get openclawTimeout =>
      'Tempo esgotado na conexão com o OpenClaw; verifique o endereço e a rede';

  @override
  String openclawFailed(Object error) {
    return 'Falha na conexão com o OpenClaw: $error';
  }

  @override
  String get chatHistoryExpandAll => 'Expandir todas as datas';

  @override
  String get chatHistoryCollapseAll => 'Recolher todas as datas';

  @override
  String chatHistoryMessageCount(Object count) {
    return '$count mensagens';
  }

  @override
  String get chatHistoryUntitled => 'Conversa sem título';

  @override
  String authContinueTaskRequires(Object permissions) {
    return 'Continuar a tarefa requer apenas: $permissions';
  }

  @override
  String get authSetPermissions => 'Configurar permissões';

  @override
  String get authPermissionsSafe =>
      'Não se preocupe, você pode revogar essas permissões a qualquer momento';

  @override
  String get authCheckingPermissions => 'Verificando permissões...';

  @override
  String get authContinueTask => 'Continuar tarefa';

  @override
  String get permissionAccessibility => 'Permissão de acessibilidade';

  @override
  String get permissionAccessibilityAndroidGui =>
      'Permissão de acessibilidade GUI do Android';
}
