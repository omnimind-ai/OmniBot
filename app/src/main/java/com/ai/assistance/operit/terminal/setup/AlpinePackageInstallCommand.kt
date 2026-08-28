package com.ai.assistance.operit.terminal.setup

internal val ALPINE_APK_INSTALL_WITH_REPAIR_FUNCTION = """
    omnibot_apk_add() {
      omnibot_apk_status=0
      apk add --no-cache "${'$'}@" || omnibot_apk_status=${'$'}?
      if [ "${'$'}omnibot_apk_status" -eq 0 ]; then
        return 0
      fi

      printf '[!] apk add failed (exit code %s); repairing interrupted packages and retrying once.\n' \
        "${'$'}omnibot_apk_status" >&2
      apk fix --no-cache || apk fix --no-cache --upgrade || true
      apk add --no-cache "${'$'}@" && return 0

      if grep -q 'mirrors.tuna.tsinghua.edu.cn/alpine' /etc/apk/repositories 2>/dev/null; then
        printf '[!] selected Alpine mirror failed; retrying with the official mirror.\n' >&2
        sed -i 's#https://mirrors.tuna.tsinghua.edu.cn/alpine#https://dl-cdn.alpinelinux.org/alpine#g' \
          /etc/apk/repositories
      fi

      apk update && apk add --no-cache "${'$'}@" && return 0
      printf '[!] Alpine certificate validation failed; retrying bootstrap with certificate checks disabled.\n' >&2
      apk --no-check-certificate update &&
        apk --no-check-certificate add --no-cache "${'$'}@"
    }
""".trimIndent()

internal fun buildAlpinePackageInstallCommand(packageNames: List<String>): String {
    require(packageNames.isNotEmpty()) { "At least one Alpine package is required." }
    val arguments = packageNames.joinToString(" ") { shellSingleQuote(it) }
    return "$ALPINE_APK_INSTALL_WITH_REPAIR_FUNCTION\nomnibot_apk_add $arguments"
}

private fun shellSingleQuote(value: String): String {
    return "'${value.replace("'", "'\"'\"'")}'"
}
