# OmniBot chroot 后端 root 段（运行在 su 提权后的 root shell 内）
# 职责：私有挂载命名空间 → 解包 rootfs（属主 root）→ mount --bind 少量路径 → exec chroot
# 重构要点（借鉴 Eta，2026-08-25）：
#   - rootfs 解包在 su 进程内完成，tar 以 root 运行 → 属主天然 root:root，
#     apt/dpkg 可直接写，无需任何 chown 自愈补丁（旧版 7 个补丁全部删除）。
#   - 只对 rootfs 顶层 + 关键子目录 chmod 755（非递归），让 App 进程能进入
#     判定完整性（EmbeddedRuntimeInstaller.isRootfsInstalled）；内部文件仍 root 属主。
#   - 无全递归 chown → 不再遍历 bind 进来的 /data、/proc、/sys → 不再卡死。
# 为什么 unshare -m：su 会话落在 root 守护进程的命名空间，直接挂载会污染
# 全局挂载表且永不回收；unshare -m 后本进程退出，全部 bind 挂载随命名空间
# 销毁自动回收，无需 umount 管理。
# 为什么二次 exec 进本脚本：unshare 是外部命，只能让子进程进新命名空间，
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

# 分发类型：host 段 launcher 只导出 OMNIBOT_TERMINAL_DISTRIBUTION（非裸变量），
# 这里自己推导，不依赖 host 段是否导出裸 TERMINAL_DISTRIBUTION。
TERMINAL_DISTRIBUTION=${OMNIBOT_TERMINAL_DISTRIBUTION:-alpine}
case "$TERMINAL_DISTRIBUTION" in
  ubuntu) ;;
  *) TERMINAL_DISTRIBUTION=alpine ;;
esac

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

# ---- rootfs 解包（属主 root，无 chown 补丁）----
# 在 su 进程内解包，tar 以 root 运行 → 所有文件属主 root:root，apt/dpkg 可直接写。
# 原子解压：先解到临时目录，校验后 rm -rf 旧 + mv 新（借鉴 Eta AlpineEnvironmentInstaller）。
ROOTFS_ARCHIVE=$PREFIX/files/$TERMINAL_DISTRIBUTION.tar.gz
[ ! -f "$ROOTFS_ARCHIVE" ] && ROOTFS_ARCHIVE=$PREFIX/files/$TERMINAL_DISTRIBUTION.tar

rootfs_has_minimum_layout() {
    [ -e "$ROOTFS_DIR/bin/sh" ] && [ -e "$ROOTFS_DIR/etc/os-release" ]
}

if [ ! -d "$ROOTFS_DIR" ] || ! rootfs_has_minimum_layout; then
    if [ ! -f "$ROOTFS_ARCHIVE" ]; then
        echo "init-host-chroot-root: missing rootfs archive: $ROOTFS_ARCHIVE" >&2
        exit 1
    fi
    echo "init-host-chroot-root: extracting $ROOTFS_ARCHIVE (owner=root)" >&2
    TMP_ROOTFS="$ROOTFS_DIR.installing"
    rm -rf "$TMP_ROOTFS"
    mkdir -p "$TMP_ROOTFS"
    # toybox tar 对绝对路径符号链接和 hard link 都会拒绝并返回非 0，
    # 但这些错误对 ubuntu/alpine rootfs 实际运行无关键影响；用 || true 吞掉，
    # 改由下面的 rootfs_has_minimum_layout 判定完整性。
    tar -xf "$ROOTFS_ARCHIVE" -C "$TMP_ROOTFS" 2>/dev/null || true
    if ! [ -e "$TMP_ROOTFS/bin/sh" ] || ! [ -e "$TMP_ROOTFS/etc/os-release" ]; then
        echo "init-host-chroot-root: extracted rootfs incomplete" >&2
        rm -rf "$TMP_ROOTFS"
        exit 1
    fi
    rm -rf "$ROOTFS_DIR"
    mv "$TMP_ROOTFS" "$ROOTFS_DIR"
fi

# rootfs 顶层 + 关键子目录对 App 开放进入（chmod 755，非递归）：
# App 进程需要 ls/stat rootfs 判定完整性（EmbeddedRuntimeInstaller.isRootfsInstalled）。
# 只开放进入权，内部文件仍 root 属主（apt/dpkg 需要）。不做全递归 chown。
chmod 755 "$ROOTFS_DIR" 2>/dev/null || true
for d in bin sbin usr etc lib lib64 var; do
    [ -d "$ROOTFS_DIR/$d" ] && chmod 755 "$ROOTFS_DIR/$d" 2>/dev/null || true
done
# /root 必须允许 App(uid=app_uid) 进入（否则 agent 读 /root/.npm-global 等 EACCES）。
# 只开放进入权，具体文件属主仍 root（apt/dpkg 需要）。
chmod 755 "$ROOTFS_DIR/root" 2>/dev/null || true

# 容器内 App 需要的目录（workspace/mnt/mt/tmp）由 root 段创建（App 无写权限）
mkdir -p "$ROOTFS_DIR/tmp" "$ROOTFS_DIR/workspace" "$ROOTFS_DIR/mnt/mt" "$ROOTFS_DIR/mt"
chmod 1777 "$ROOTFS_DIR/tmp" 2>/dev/null || true

# $1 = host 源路径，$2 = 容器内绝对路径（以 / 开头）
# 目录用 rbind（递归复制子挂载）：普通 bind 会漏掉 /apex 下的 APEX、/dev/pts、/storage/emulated
# 等子挂载，导致容器内看到不完整视图。单个挂载失败只告警不中断。
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

# Android 系统分区（realpath 解析符号链接后再绑定）
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

# /dev/shm 落到 rootfs 的 tmp（Android 默认无 /dev/shm）
bind_one "$ROOTFS_DIR/tmp" /dev/shm

# 共享工作区：容器内 /workspace 与 App 侧同一目录
# setgid + group rwx：配合 init.sh 的 umask 002，让容器内 root 写出的文件
# 天然 group=app uid 且 group 可写，App（uid=appUid）无需事后 chown 即可编辑。
# workspace 属主本就是 app_uid（App 创建），无需 chown。
if [ -n "$OMNIBOT_HOST_WORKSPACE" ]; then
    mkdir -p "$OMNIBOT_HOST_WORKSPACE"
    chmod 2770 "$OMNIBOT_HOST_WORKSPACE" 2>/dev/null || true
    bind_one "$OMNIBOT_HOST_WORKSPACE" /workspace
fi

# MT 管理器共享存储
if [ -n "$OMNIBOT_MT_STORAGE_HOST" ] && [ -d "$OMNIBOT_MT_STORAGE_HOST" ]; then
    bind_one "$OMNIBOT_MT_STORAGE_HOST" /mnt/mt
    bind_one "$OMNIBOT_MT_STORAGE_HOST" /mt
fi

# 进程组回收：仅 headless（Agent 链路）需要 setsid 让 chroot 进程成为新会话 leader（pid=pgid），
# 并把 pgid 写入 pid 文件，App 侧停止/超时/关闭时经 su kill -KILL -pgid 整组回收。
# 交互（OMNIBOT_HEADLESS 非 1）不走该链路：必须继承宿主会话与控制终端。
# EXECUTOR_KEY 必须与 App 侧 ChrootProcessReaper.sanitizeExecutorKey 同一套规则。
EXECUTOR_KEY=$(printf '%s' "${OMNIBOT_EXECUTOR_KEY:-default}" | tr -c 'A-Za-z0-9_.-' '_')
RUN_DIR="$PREFIX/local/run"
mkdir -p "$RUN_DIR"
# run 目录对 App 开放读（App 要读 pid 文件做 killpg）；pid 文件 root 写 644，App 可读
chmod 755 "$RUN_DIR" 2>/dev/null || true
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
# 因此 guest bash 保持 TTY=pts/N S+，作业控制正常；该链路不写有效 pid。
rm -f "$PID_FILE"
exec chroot "$ROOTFS_DIR" /bin/sh "$PREFIX/local/bin/init" "$@"
