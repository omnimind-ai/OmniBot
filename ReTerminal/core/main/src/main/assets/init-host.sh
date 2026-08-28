TERMINAL_DISTRIBUTION=${OMNIBOT_TERMINAL_DISTRIBUTION:-alpine}
case "$TERMINAL_DISTRIBUTION" in
  ubuntu) ;;
  *) TERMINAL_DISTRIBUTION=alpine ;;
esac

ROOTFS_DIR=$PREFIX/local/$TERMINAL_DISTRIBUTION
ROOTFS_ARCHIVE=$PREFIX/files/$TERMINAL_DISTRIBUTION.tar.gz
ROOTFS_READY_MARKER=$ROOTFS_DIR/.omnibot-rootfs-ready
ACTIVE_CHILD_PID=

[ ! -f "$ROOTFS_ARCHIVE" ] && ROOTFS_ARCHIVE=$PREFIX/files/$TERMINAL_DISTRIBUTION.tar

mkdir -p "$ROOTFS_DIR"

terminate_active_child() {
    if [ -n "$ACTIVE_CHILD_PID" ]; then
        kill "$ACTIVE_CHILD_PID" 2>/dev/null || true
        wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
        ACTIVE_CHILD_PID=
    fi
}

handle_termination() {
    terminate_active_child
    exit 130
}

run_child() {
    "$@" &
    ACTIVE_CHILD_PID=$!
    wait "$ACTIVE_CHILD_PID"
    child_status=$?
    ACTIVE_CHILD_PID=
    return "$child_status"
}

rootfs_entry_exists() {
    [ -e "$1" ] || [ -L "$1" ]
}

rootfs_has_minimum_layout() {
    rootfs_entry_exists "$ROOTFS_DIR/bin/sh" &&
        rootfs_entry_exists "$ROOTFS_DIR/etc/os-release"
}

rootfs_has_legacy_layout() {
    rootfs_has_minimum_layout || return 1
    rootfs_entry_exists "$ROOTFS_DIR/usr/bin/env" || return 1
    case "$TERMINAL_DISTRIBUTION" in
        ubuntu)
            rootfs_entry_exists "$ROOTFS_DIR/usr/bin/apt-get" &&
                [ -f "$ROOTFS_DIR/var/lib/dpkg/status" ]
            ;;
        *)
            rootfs_entry_exists "$ROOTFS_DIR/sbin/apk" &&
                [ -f "$ROOTFS_DIR/lib/apk/db/installed" ] &&
                [ -f "$ROOTFS_DIR/etc/alpine-release" ]
            ;;
    esac
}

clear_incomplete_rootfs() {
    clear_status=0
    for entry in "$ROOTFS_DIR"/* "$ROOTFS_DIR"/.[!.]* "$ROOTFS_DIR"/..?*; do
        [ -e "$entry" ] || [ -L "$entry" ] || continue
        name=${entry##*/}
        case "$name" in
            root|tmp) ;;
            *) rm -rf "$entry" || clear_status=1 ;;
        esac
    done
    return "$clear_status"
}

mark_rootfs_ready() {
    marker_tmp="$ROOTFS_READY_MARKER.$$"
    rm -f "$marker_tmp"
    printf '%s\n' "$TERMINAL_DISTRIBUTION" > "$marker_tmp" &&
        mv -f "$marker_tmp" "$ROOTFS_READY_MARKER"
}

trap handle_termination HUP INT TERM

if [ -f "$ROOTFS_READY_MARKER" ]; then
    if ! rootfs_has_minimum_layout; then
        if ! clear_incomplete_rootfs; then
            echo "Failed to reset incomplete $TERMINAL_DISTRIBUTION rootfs." >&2
            exit 1
        fi
    fi
elif rootfs_has_legacy_layout; then
    if ! mark_rootfs_ready; then
        echo "Failed to record completed $TERMINAL_DISTRIBUTION rootfs installation." >&2
        exit 1
    fi
else
    if ! clear_incomplete_rootfs; then
        echo "Failed to reset incomplete $TERMINAL_DISTRIBUTION rootfs." >&2
        exit 1
    fi
fi

if [ ! -f "$ROOTFS_READY_MARKER" ]; then
    if [ ! -f "$ROOTFS_ARCHIVE" ]; then
        echo "Missing $TERMINAL_DISTRIBUTION rootfs archive: $ROOTFS_ARCHIVE" >&2
        exit 1
    fi
    if ! run_child tar -xf "$ROOTFS_ARCHIVE" -C "$ROOTFS_DIR"; then
        echo "Failed to extract $TERMINAL_DISTRIBUTION rootfs." >&2
        exit 1
    fi
    if ! rootfs_has_legacy_layout; then
        clear_incomplete_rootfs
        echo "Extracted $TERMINAL_DISTRIBUTION rootfs is incomplete." >&2
        exit 1
    fi
    if ! mark_rootfs_ready; then
        echo "Failed to record completed $TERMINAL_DISTRIBUTION rootfs installation." >&2
        exit 1
    fi
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

install_runtime_file() {
    src="$1"
    dest="$2"
    mode="$3"
    [ -e "$src" ] || return 0
    tmp="${dest}.$$"
    rm -f "$tmp"
    cp "$src" "$tmp" && chmod "$mode" "$tmp" && mv -f "$tmp" "$dest"
}

install_runtime_file "$PREFIX/files/proot" "$PREFIX/local/bin/proot" 755

for sofile in "$PREFIX/files/"*.so.2; do
    [ -e "$sofile" ] || continue
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    install_runtime_file "$sofile" "$dest" 644
done


ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"
ARGS="$ARGS -b $FIPS_COMPAT_FILE:/proc/.sysctl_crypto_fips_enabled"

if [ -n "$OMNIBOT_HOST_WORKSPACE" ]; then
  ARGS="$ARGS -b $OMNIBOT_HOST_WORKSPACE:/workspace"
fi

if [ -n "$OMNIBOT_MT_STORAGE_HOST" ] && [ -d "$OMNIBOT_MT_STORAGE_HOST" ]; then
  ARGS="$ARGS -b $OMNIBOT_MT_STORAGE_HOST:/mnt/mt"
  ARGS="$ARGS -b $OMNIBOT_MT_STORAGE_HOST:/mt"
fi

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi


ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

if [ ! -d "$ROOTFS_DIR/tmp" ]; then
 mkdir -p "$ROOTFS_DIR/tmp"
 chmod 1777 "$ROOTFS_DIR/tmp"
fi
ARGS="$ARGS -b $ROOTFS_DIR/tmp:/dev/shm"

ARGS="$ARGS -r $ROOTFS_DIR"
ARGS="$ARGS -0"
# PRoot's hardlink-to-symlink emulation is useful for the interactive shell,
# but it breaks atomic writers used by official ACP runtimes.  Those writers
# create a temporary file and then link/rename it into place; under this mode
# the final path can point at a temporary name that no longer exists.  Keep
# the historical default for ordinary terminal sessions and let ACP launchers
# opt out explicitly.
if [ "${OMNIBOT_DISABLE_PROOT_LINK2SYMLINK:-0}" != "1" ]; then
  ARGS="$ARGS --link2symlink"
fi
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# The final runtime must stay attached to the caller's stdio.  In a
# non-interactive shell an asynchronously executed command gets /dev/null as
# stdin, which makes stdio services such as ACP observe EOF and exit before
# initialization.  Replacing the host shell also lets Process.destroy() target
# proot directly; --kill-on-exit remains responsible for its descendants.
trap - HUP INT TERM
exec "$LINKER" "$PREFIX/local/bin/proot" $ARGS /bin/sh "$PREFIX/local/bin/init" "$@"
