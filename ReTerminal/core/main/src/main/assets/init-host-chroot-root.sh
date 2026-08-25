# OmniBot chroot 后端 root 段（运行在 su 提权后的 root shell 内）
# 职责：私有挂载命名空间 → mount --bind 对齐 proot -b 清单 → exec chroot
# 为什么 unshare -m：su 会话落在 root 守护进程的命名空间，直接挂载会污染
# 全局挂载表且永不回收；unshare -m 后本进程退出，全部 bind 挂载随命名空间
# 销毁自动回收，无需 umount 管理。
# 为什么二次 exec 进本脚本：unshare 是外部命令，只能让子进程进新命名空间，
# 所以用环境变量标记位让自己在私有命名空间里重入一次。

if [ -z "$OMNIBOT_CHROOT_NS" ]; then
    if ! command -v unshare >/dev/null 2>&1; then
        echo "init-host-chroot-root: 'unshare' not found; cannot isolate mount namespace" >&2
        exit 1
    fi
    OMNIBOT_CHROOT_NS=1
    export OMNIBOT_CHROOT_NS
    # 先跳到 init 的挂载命名空间再 unshare：App 的命名空间带 Android 应用数据隔离
    # （/data/data 只能看到本应用 + 极少数条目，真 root 也绕不开，真机实测 3 条 vs 575 条；
    # 且跨命名空间的 /proc/1/root/data 无法直接 bind）。从 init 视图派生私有命名空间，
    # 下面 bind_one /data 才能拿到完整视图。nsenter 不可用时退回 App 视图（旧行为）。
    if command -v nsenter >/dev/null 2>&1; then
        exec nsenter -t 1 -m -- unshare -m sh "$0" "$@"
    fi
    exec unshare -m sh "$0" "$@"
fi

PATH=/system/bin:/system/xbin:/sbin:/sbin/bin:$PATH
export PATH

if [ -z "$PREFIX" ] || [ -z "$ROOTFS_DIR" ]; then
    echo "init-host-chroot-root: PREFIX/ROOTFS_DIR not set" >&2
    exit 1
fi

# 隔离挂载传播：KernelSU/Magisk 环境下 / 常处于 shared peer group，
# 仅靠 unshare -m 不够——shared 挂载上的 bind 会传播回全局命名空间（真机实测泄漏数百条）。
# toybox mount 的兼容性陷阱（真机 strace 实测）：
#   --make-rprivate 长选项被拒；remount 形式假成功（内核 remount 路径忽略传播位）；
#   单参数形式走 /etc/fstab 查找失败。两参数形式可用，源仅占位（传播操作内核忽略源）。
# 另注意 toybox 把 rprivate 映射为 MS_SLAVE|MS_REC——slave 同样阻断向外传播，满足隔离需求。
mount --make-rprivate / 2>/dev/null || \
mount -o rprivate dummy / 2>/dev/null || \
mount -o rslave dummy / 2>/dev/null
if grep -q "shared:" /proc/self/mountinfo; then
    echo "init-host-chroot-root: FATAL: shared mount propagation survives; aborting to protect host mount table" >&2
    exit 1
fi

# $1 = host 源路径，$2 = 容器内绝对路径（以 / 开头）
# 目录用 rbind（递归复制子挂载）：普通 bind 会漏掉 /apex 下的 APEX、/dev/pts、/storage/emulated
# 等子挂载，导致容器内看到不完整视图（开发者审查指出，对齐 proot -b 的全树可见语义）。
# 单个挂载失败只告警不中断，与 proot 跳过不存在源的容错行为一致。
bind_one() {
    src="$1"
    dst="$ROOTFS_DIR$2"
    [ -e "$src" ] || return 0
    if [ -d "$src" ]; then
        mkdir -p "$dst"
        mount --rbind "$src" "$dst" 2>/dev/null || {
            echo "init-host-chroot-root: rbind failed: $src -> $2" >&2
            return 1
        }
    else
        mkdir -p "$(dirname "$dst")"
        [ -e "$dst" ] || touch "$dst"
        mount --bind "$src" "$dst" 2>/dev/null || {
            echo "init-host-chroot-root: bind failed: $src -> $2" >&2
            return 1
        }
    fi
}

# rootfs 属主修正：rootfs 由宿主 App 进程（uid=app_uid）解包，tar 以 app_uid
# 运行导致所有文件/目录属主为 app_uid，而 apt/dpkg 等需要 root:root 属主
# （否则 apt 降权到 _apt 访问 /var/lib/apt/lists/partial 时 Permission denied）。
# 这里必须在 mount 任何宿主路径之前执行，避免 chown 递归污染 bind mount 的
# 宿主真实数据（/data、/workspace 等）。仅在 rootfs 属主非 root 时执行一次，
# 后续 root 属主保持不变，避免每次启动全量 chown 拖慢冷启动。
if [ "$(stat -c '%u' "$ROOTFS_DIR")" != "0" ]; then
    echo "init-host-chroot-root: rootfs owner not root, chown -R root:root $ROOTFS_DIR" >&2
    chown -R root:root "$ROOTFS_DIR" 2>/dev/null
fi
# rootfs 顶层目录必须允许 App(uid=app_uid) 进入：宿主段 init-host-chroot.sh 以 App 进程
# 运行需要 ls/mkdir $ROOTFS_DIR/{workspace,mnt,mt,tmp}。rootfs 归 root 后顶层是 700，
# App 进不去会 Permission denied。chmod 755 只开放进入权，内部文件仍 root 属主（apt 需要）。
chmod 755 "$ROOTFS_DIR" 2>/dev/null || true
# /root 必须允许 App(uid=app_uid) 进入（否则 agent 读 /root/.npm-global 等 EACCES）。
# 每次启动确保：/root 对 others 开放进入权（r-x），具体文件属主由下方 user_dir 循环归 app_uid。
chmod 755 "$ROOTFS_DIR/root" 2>/dev/null || true

# Android 系统分区（realpath 解析符号链接后再绑定，与 init-host.sh 一致）
for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do
    if [ -e "$system_mnt" ]; then
        system_mnt=$(realpath "$system_mnt")
        bind_one "$system_mnt" "$system_mnt"
    fi
done
unset system_mnt

bind_one /sdcard /sdcard
bind_one /storage /storage
bind_one /dev /dev
# /data 完整视图依赖上文 nsenter 跳进 init 命名空间：App 命名空间受 Android
# 应用数据隔离限制，直接绑只能拿到 3 个条目；init 视图下正常 bind 即为全部（575 条）
bind_one /data /data
# 宿主视图补齐：/cache、/metadata 是独立挂载点，不在清单里容器内就看不到
# （bind_one 对不存在的源静默跳过，无此分区的设备不受影响）
bind_one /cache /cache
bind_one /metadata /metadata
# /proc 必须先于伪文件挂载：伪文件要 bind 到 /proc 视图内的路径上
# /proc 与 $PREFIX 是容器正确性的地基：挂载失败宁可响亮退出，不放进错误视图
bind_one /proc /proc || exit 1
bind_one /sys /sys

# App 私有目录在容器内保持同路径可见（容器内 init 位于 $PREFIX/local/bin/init）
bind_one "$PREFIX" "$PREFIX" || exit 1

# 伪 /proc 文件（proot 版同款：为容器内程序提供裁剪过的 stat 视图）
bind_one "$PREFIX/local/stat" /proc/stat
bind_one "$PREFIX/local/vmstat" /proc/vmstat
# 注意：proot 版的 FIPS 伪文件与 urandom→random 两条 bind 在真 chroot 下去掉了：
# 前者的目标落在真实 procfs 里，procfs 不允许创建文件，原理上不可能 bind；
# 后者被 SELinux 拒绝且 Android 7+ 内核的 /dev/random 本就不再阻塞，无必要。

# /dev/shm 落到 rootfs 的 tmp（proot 版同款；Android 默认无 /dev/shm）
bind_one "$ROOTFS_DIR/tmp" /dev/shm

# 共享工作区：容器内 /workspace 与 App 侧同一目录
if [ -n "$OMNIBOT_HOST_WORKSPACE" ]; then
    # 设 setgid + group rwx：配合 init.sh 的 umask 002，让容器内 root 写出的文件
    # 天然 group=app uid 且 group 可写，App（uid=appUid）无需事后 chown 即可编辑。
    # 否则 root 写文件 owner=root、0644，App 落 other 无写权限 → Flutter 编辑器保存报
    # Permission denied（Agent 用 terminal 写 workspace 的根因）。root 权限无限制，每次启动自愈。
    chmod 2770 "$OMNIBOT_HOST_WORKSPACE" 2>/dev/null || true
    bind_one "$OMNIBOT_HOST_WORKSPACE" /workspace
fi

# MT 管理器共享存储
if [ -n "$OMNIBOT_MT_STORAGE_HOST" ] && [ -d "$OMNIBOT_MT_STORAGE_HOST" ]; then
    bind_one "$OMNIBOT_MT_STORAGE_HOST" /mnt/mt
    bind_one "$OMNIBOT_MT_STORAGE_HOST" /mt
fi

# 进程组回收：仅 headless（Agent 链路）需要 setsid 让 chroot 进程成为新会话 leader（pid=pgid），
# 并把 pgid 写入 pid 文件，App 侧停止/超时/关闭时经 su kill -KILL -pgid 整组回收，
# 避免 root 命令子进程在工具报告停止后残留（开发者审查 P1：destroyForcibly 只杀外层 sh/su 客户端）。
# 交互（OMNIBOT_HEADLESS 非 1）不走该链路：必须继承宿主会话与控制终端，否则 guest bash 无 tty → no job control。
# EXECUTOR_KEY 必须与 App 侧 ChrootProcessReaper.sanitizeExecutorKey 同一套规则：
# 无头会话的 sessionId 原样透传，可能含空格等字符，不归一化会导致 App 按消毒后的
# 文件名找 pid 文件而这里按原样写，killpg 永远找不到（双向约定，单侧改即破）
EXECUTOR_KEY=$(printf '%s' "${OMNIBOT_EXECUTOR_KEY:-default}" | tr -c 'A-Za-z0-9_.-' '_')
RUN_DIR="$PREFIX/local/run"
mkdir -p "$RUN_DIR"
PID_FILE="$RUN_DIR/chroot-${EXECUTOR_KEY}.pid"
export OMNIBOT_CHROOT_ROOT_PID_FILE="$PID_FILE"
# 先占位：App 读到空文件会重试，避免「文件还不存在」被当成没有 chroot 进程
: > "$PID_FILE"

if [ "$OMNIBOT_HEADLESS" = "1" ]; then
    # 用 sh -c 包一层：$$ 为 setsid 新会话的 leader pid（随后 exec chroot 保持同 pid），
    # 先写 pid 文件再 exec，保证 App 读到的一定是最终 chroot 进程的进程组
    HOST_SH=/system/bin/sh
    setsid "$HOST_SH" -c 'echo $$ > "$OMNIBOT_CHROOT_ROOT_PID_FILE"; exec chroot "$ROOTFS_DIR" /bin/sh "$PREFIX/local/bin/init" "$@"' sh "$@"
    rc=$?
    rm -f "$PID_FILE"
    exit $rc
fi

# 交互会话：不 setsid，直接 exec chroot 继承宿主会话/控制终端（与 proot 段一致），
# 因此 guest bash 保持 TTY=pts/N S+，作业控制正常；该链路不写有效 pid（回收由 chroot 退出自然回收）
rm -f "$PID_FILE"
exec chroot "$ROOTFS_DIR" /bin/sh "$PREFIX/local/bin/init" "$@"
