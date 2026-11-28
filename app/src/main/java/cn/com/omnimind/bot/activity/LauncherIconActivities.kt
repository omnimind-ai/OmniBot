package cn.com.omnimind.bot.activity

/**
 * Concrete launcher activity subclasses for alternate app icon variants.
 *
 * Each subclass is declared in AndroidManifest.xml with its own adaptive icon
 * and Android 12+ splash screen theme so cold starts display the matching
 * animated splash vector before application code runs.
 */
class LauncherDotActivity : LauncherActivity()

class LauncherCursorActivity : LauncherActivity()

class LauncherWaveActivity : LauncherActivity()

class LauncherOpenActivity : LauncherActivity()

class LauncherClassicActivity : LauncherActivity()
