# OmniBot chroot 后端启动脚本（host 段，运行在 App 进程上下文）
# 与 init-host.sh 相同的 rootfs 解包逻辑；差异在启动方式：
# proot 用用户态 ptrace 模拟，本脚本经 su 提权后用真 chroot（需要 KernelSU）
# 为什么 argv 走 launcher 文件：su -c 只接受单个 shell 字符串，
# 直接拼接 "$@" 会被二次解析破坏（Agent 命令含空格引号），
# 因此把完整 argv 以正确 quoting 写进临时 launcher 再交给 su 执行。

TERMINAL_DISTRIBUTION=${OMNIBOT_TERMINAL_DISTRIBUTION:-alpine}
case "$TERMINAL_DISTRIBUTION" in
  ubuntu) ;;
  *) TERMINAL_DISTRIBUTION=alpine ;;
esac

ROOTFS_DIR=$PREFIX/local/$TERMINAL_DISTRIBUTION
ROOTFS_ARCHIVE=$PREFIX/files/$TERMINAL_DISTRIBUTION.tar.gz

[ ! -f "$ROOTFS_ARCHIVE" ] && ROOTFS_ARCHIVE=$PREFIX/files/$TERMINAL_DISTRIBUTION.tar

# rootfs 顶层目录必须允许 App(uid=app_uid) 进入：本脚本以 App 进程运行，需要
# ls/mkdir $ROOTFS_DIR/{workspace,mnt,mt,tmp}。rootfs 归 root 后顶层是 700，App 进不去
# 会 Permission denied。这里在访问 rootfs 前先经 su 把顶层 chmod 755（只开放进入权，
# 内部文件仍 root 属主，apt/dpkg 需要）。su 不可用则忽略，后续 root 段会处理。
su -c "chmod 755 '$ROOTFS_DIR' '$ROOTFS_DIR/mnt' '$ROOTFS_DIR/workspace' '$ROOTFS_DIR/tmp'" 2>/dev/null || true

mkdir -p "$ROOTFS_DIR"

# rootfs 已解包则跳过（root/tmp 是解包后固有目录，与 init-host.sh 判定一致）
if [ -z "$(ls -A "$ROOTFS_DIR" | grep -vE '^(root|tmp)$')" ]; then
    # toybox tar 对绝对路径符号链接和 hard link 都会拒绝并返回非 0，
    # 但这些错误对 ubuntu/alpine rootfs 实际运行无关键影响（perl 缺失别名、alternatives 缺等）；
    # 用 || true 吞掉非 0 退出码，改由 rootfs_has_minimum_layout / rootfs_has_legacy_layout 判定完整性
    tar -xf "$ROOTFS_ARCHIVE" -C "$ROOTFS_DIR" 2>/dev/null || true
fi

FIPS_COMPAT_FILE="$PREFIX/local/sysctl_crypto_fips_enabled"
[ ! -f "$FIPS_COMPAT_FILE" ] && {
    mkdir -p "$PREFIX/local"
    printf '0\n' > "$FIPS_COMPAT_FILE"
}

if [ -n "$OMNIBOT_HOST_WORKSPACE" ]; then
    mkdir -p "$OMNIBOT_HOST_WORKSPACE"
    mkdir -p "$ROOTFS_DIR/workspace"
fi

if [ -n "$OMNIBOT_MT_STORAGE_HOST" ] && [ -d "$OMNIBOT_MT_STORAGE_HOST" ]; then
    mkdir -p "$ROOTFS_DIR/mnt/mt" "$ROOTFS_DIR/mt"
fi

mkdir -p "$PREFIX/local/bin" "$PREFIX/local/lib"

ROOT_SCRIPT="$PREFIX/local/bin/init-host-chroot-root"
if [ ! -f "$ROOT_SCRIPT" ]; then
    echo "init-host-chroot: missing root script: $ROOT_SCRIPT" >&2
    echo "init-host-chroot: reopen the terminal, or switch container backend back to proot" >&2
    exit 1
fi
chmod 755 "$ROOT_SCRIPT" 2>/dev/null

if [ ! -d "$ROOTFS_DIR/tmp" ]; then
    mkdir -p "$ROOTFS_DIR/tmp"
fi
# apk_data_file 可能拒 sticky bit；失败只告警，tmp 仍可用
if ! chmod 1777 "$ROOTFS_DIR/tmp" 2>/dev/null; then
    echo "init-host-chroot: warning: cannot chmod 1777 $ROOTFS_DIR/tmp" >&2
fi

# su 可用性运行时探测：授权被撤销/KernelSU 限制时自动回退 proot（每次启动都检查，
# 不依赖设置页切换时刻的状态——开发者审查要求「每次启动重新检查、失败则回退」）
# 探针不能只信退出码：KernelSU App Profile 可保留 uid 0 但剥光 capabilities，
# su -c id 退出码仍为 0（开发者审查 P2#6）。必须验证 uid=0 且 CapEff 非全零。
su_root_capable() {
    probe_out=$(su -c 'id -u; sed -n "s/^CapEff:[[:space:]]*//p" /proc/self/status' 2>/dev/null) || return 1
    probe_uid=$(printf '%s\n' "$probe_out" | sed -n '1p')
    probe_cap=$(printf '%s\n' "$probe_out" | sed -n '2p')
    [ "$probe_uid" = "0" ] || return 1
    # CapEff 全零 = 有效能力被剥光，等同无 root；tr -d '0' 后为空即全零
    [ -n "$probe_cap" ] && [ -n "$(printf '%s' "$probe_cap" | tr -d '0')" ]
}

# fallback 必须通过 /system/bin/sh 包一层，避免 untrusted_app 域 execute_no_trans SELinux 拒绝
if ! command -v su >/dev/null 2>&1 || ! su_root_capable; then
    echo "init-host-chroot: su unavailable or not authorized; falling back to proot" >&2
    PROOT_HOST="$PREFIX/local/bin/init-host"
    if [ ! -f "$PROOT_HOST" ]; then
        echo "init-host-chroot: proot fallback script missing: $PROOT_HOST" >&2
        exit 1
    fi
    exec /system/bin/sh "$PROOT_HOST" "$@"
fi

# 把字符串转成 shell 单引号字面量：' → '\''（结束引号+转义引号+开始引号）
shell_quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# 历史 launcher 按年龄回收：崩溃残留不会被 trap 清到；只删 mtime 超过 2 天的，
# 并发会话刚创建的文件 mtime 是新的、不受影响（开发者审查 P2#5：禁止 glob 全删）
find "$PREFIX/local/bin" -maxdepth 1 -name '.chroot-launcher.*' -mtime +1 -delete 2>/dev/null || true

# $$ + executorKey + 秒级时间戳：并发会话 PID 不同；崩溃后 PID 复用也不会撞上未清文件
SAFE_KEY=$(printf '%s' "${OMNIBOT_EXECUTOR_KEY:-x}" | tr -c 'A-Za-z0-9_.-' '_')
LAUNCHER="$PREFIX/local/bin/.chroot-launcher.$$.${SAFE_KEY}.$(date +%s)"
cleanup_launcher() { rm -f "$LAUNCHER"; }
trap cleanup_launcher EXIT INT HUP TERM
{
    printf '#!/system/bin/sh\n'
    # 防御性显式导出：su 一般继承环境，但实现间有差异，关键变量不赌运气
    printf 'export PREFIX=%s\n' "$(shell_quote "$PREFIX")"
    printf 'export ROOTFS_DIR=%s\n' "$(shell_quote "$ROOTFS_DIR")"
    printf 'export OMNIBOT_TERMINAL_DISTRIBUTION=%s\n' "$(shell_quote "$TERMINAL_DISTRIBUTION")"
    printf 'export FIPS_COMPAT_FILE=%s\n' "$(shell_quote "$FIPS_COMPAT_FILE")"
    printf 'export OMNIBOT_HOST_WORKSPACE=%s\n' "$(shell_quote "$OMNIBOT_HOST_WORKSPACE")"
    printf 'export OMNIBOT_MT_STORAGE_HOST=%s\n' "$(shell_quote "$OMNIBOT_MT_STORAGE_HOST")"
    # init.sh 实际消费的变量一并转发：su 若清洗环境，Agent 链路会静默丢 headless/cwd
    # 注意 init.sh 读取的是 OMNIBOT_TERMINAL_DISTRIBUTION（不是裸 TERMINAL_DISTRIBUTION），
    # 上一版导出错名导致过滤环境的 su 下 Ubuntu 被按 Alpine 初始化（开发者审查指出）
    printf 'export OMNIBOT_HEADLESS=%s\n' "$(shell_quote "$OMNIBOT_HEADLESS")"
    printf 'export OMNIBOT_USER_ENV_FILE=%s\n' "$(shell_quote "$OMNIBOT_USER_ENV_FILE")"
    printf 'export OMNIBOT_ALPINE_APK_REPOSITORY_BASE=%s\n' "$(shell_quote "$OMNIBOT_ALPINE_APK_REPOSITORY_BASE")"
    printf 'export OMNIBOT_ALPINE_APK_BRANCH=%s\n' "$(shell_quote "$OMNIBOT_ALPINE_APK_BRANCH")"
    printf 'export OMNIBOT_UBUNTU_APT_REPOSITORY_BASE=%s\n' "$(shell_quote "$OMNIBOT_UBUNTU_APT_REPOSITORY_BASE")"
    printf 'export OMNIBOT_SESSION_CWD=%s\n' "$(shell_quote "$OMNIBOT_SESSION_CWD")"
    printf 'export TERM=%s\n' "$(shell_quote "$TERM")"
    printf 'export LANG=%s\n' "$(shell_quote "$LANG")"
    printf 'export COLORTERM=%s\n' "$(shell_quote "$COLORTERM")"
    printf 'export HOME=%s\n' "$(shell_quote "$HOME")"
} > "$LAUNCHER"

# ACP extraEnv（CODEX_HOME / DEEPSEEK_* / DSH_*）不是 OMNIBOT_ 前缀；
# su 若清洗环境，只转发 OMNIBOT_* 会让 Codex/Harness 丢配置。
# 危险键留给 su 自己的环境，其余原样写入 launcher。
is_unsafe_env_key() {
    case "$1" in
        PATH|LD_LIBRARY_PATH|LD_PRELOAD|LD_AUDIT|SHLVL|PWD|_|IFS|BASH_ENV|ENV|SHELLOPTS|BASHOPTS|PS1|PS2)
            return 0 ;;
        *) return 1 ;;
    esac
}
env | while IFS='=' read -r key val; do
    [ -n "$key" ] || continue
    is_unsafe_env_key "$key" && continue
    printf 'export %s=%s\n' "$key" "$(shell_quote "$val")" >> "$LAUNCHER"
done

# argv 追加：完整透传给 root 段（exec chroot 后原样到 init.sh）
{
    printf 'exec sh %s' "$(shell_quote "$ROOT_SCRIPT")"
    for arg in "$@"; do
        printf ' %s' "$(shell_quote "$arg")"
    done
    printf '\n'
} >> "$LAUNCHER"
chmod 700 "$LAUNCHER"

# 不用 exec：su 返回后由 trap 回收 launcher。只删自己的文件，不扫历史残留
su -c "exec sh $LAUNCHER"
exit $?
