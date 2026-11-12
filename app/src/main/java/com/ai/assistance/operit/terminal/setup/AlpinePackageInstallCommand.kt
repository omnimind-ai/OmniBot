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

      omnibot_alpine_branch="${'$'}(if [ -r /etc/alpine-release ]; then cut -d. -f1,2 /etc/alpine-release | sed 's/^/v/'; else printf '%s' 'v3.21'; fi)"
      for omnibot_mirror in 'https://mirrors.aliyun.com/alpine' 'https://dl-cdn.alpinelinux.org/alpine' 'https://mirrors.tuna.tsinghua.edu.cn/alpine'; do
        printf '[!] selected Alpine mirror failed; retrying with %s.\n' "${'$'}omnibot_mirror" >&2
        mkdir -p /etc/apk
        printf '%s/%s/main\n%s/%s/community\n' \
          "${'$'}omnibot_mirror" "${'$'}omnibot_alpine_branch" \
          "${'$'}omnibot_mirror" "${'$'}omnibot_alpine_branch" > /etc/apk/repositories
        if apk update && apk add --no-cache "${'$'}@"; then
          return 0
        fi
      done

      printf '[!] Alpine certificate validation failed; retrying bootstrap with certificate checks disabled.\n' >&2
      for omnibot_mirror in 'https://mirrors.aliyun.com/alpine' 'https://dl-cdn.alpinelinux.org/alpine' 'https://mirrors.tuna.tsinghua.edu.cn/alpine'; do
        mkdir -p /etc/apk
        printf '%s/%s/main\n%s/%s/community\n' \
          "${'$'}omnibot_mirror" "${'$'}omnibot_alpine_branch" \
          "${'$'}omnibot_mirror" "${'$'}omnibot_alpine_branch" > /etc/apk/repositories
        if apk --no-check-certificate update &&
          apk --no-check-certificate add --no-cache "${'$'}@"; then
          return 0
        fi
      done
      return 1
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
