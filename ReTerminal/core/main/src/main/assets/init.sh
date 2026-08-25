set -e  # Exit immediately on Failure

# 容器内新建文件默认 group 可写：配合宿主侧 workspace 的 setgid（group=app uid），
# 让 chroot 容器内 root 写出的文件天然 App 可写，避免 owner=root 时 App 落 other 无写权限
# （Agent 用 terminal 写 workspace 文件后 Flutter 编辑器保存报 Permission denied 的根因）。
# 容器内 root 写 /root、/etc 等非 workspace 位置时 group 位多 rw 无实际风险（App 进不去这些目录）。
umask 002

export PATH=/root/.npm-global/bin:/root/.local/bin:/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root
HEADLESS_MODE="${OMNIBOT_HEADLESS:-0}"
TERMINAL_DISTRIBUTION="${OMNIBOT_TERMINAL_DISTRIBUTION:-alpine}"
case "$TERMINAL_DISTRIBUTION" in
    ubuntu)
        INTERACTIVE_SHELL=/bin/bash
        PACKAGE_MANAGER_HINT=apt
        ;;
    *)
        TERMINAL_DISTRIBUTION=alpine
        INTERACTIVE_SHELL=/bin/ash
        PACKAGE_MANAGER_HINT=apk
        ;;
esac
[ -x "$INTERACTIVE_SHELL" ] || INTERACTIVE_SHELL=/bin/sh

if [ -n "$OMNIBOT_USER_ENV_FILE" ] && [ -r "$OMNIBOT_USER_ENV_FILE" ]; then
    . "$OMNIBOT_USER_ENV_FILE"
fi

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi

configure_apk_repositories() {
    if [ -z "$OMNIBOT_ALPINE_APK_REPOSITORY_BASE" ]; then
        return 0
    fi

    branch="$OMNIBOT_ALPINE_APK_BRANCH"
    if [ -z "$branch" ] && [ -r /etc/alpine-release ]; then
        branch="v$(cut -d. -f1,2 /etc/alpine-release)"
    fi
    if [ -z "$branch" ]; then
        branch="v3.21"
    fi

    mkdir -p /etc/apk
    printf '%s/%s/main\n%s/%s/community\n' \
        "$OMNIBOT_ALPINE_APK_REPOSITORY_BASE" "$branch" \
        "$OMNIBOT_ALPINE_APK_REPOSITORY_BASE" "$branch" \
        > /etc/apk/repositories
}

configure_apt_repositories() {
    if [ -z "$OMNIBOT_UBUNTU_APT_REPOSITORY_BASE" ]; then
        return 0
    fi

    codename="$(sed -n 's/^VERSION_CODENAME=//p' /etc/os-release 2>/dev/null | tr -d '"' | head -n 1)"
    [ -n "$codename" ] || codename=noble
    mkdir -p /etc/apt/sources.list.d
    printf 'Types: deb\nURIs: %s\nSuites: %s %s-updates %s-backports\nComponents: main universe restricted multiverse\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n\nTypes: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports\nSuites: %s-security\nComponents: main universe restricted multiverse\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n' \
        "$OMNIBOT_UBUNTU_APT_REPOSITORY_BASE" \
        "$codename" "$codename" "$codename" "$codename" \
        > /etc/apt/sources.list.d/ubuntu.sources
}

if [ "$TERMINAL_DISTRIBUTION" = "alpine" ]; then
    configure_apk_repositories || true
elif [ "$TERMINAL_DISTRIBUTION" = "ubuntu" ]; then
    configure_apt_repositories || true
fi

suppress_ubuntu_sudo_group_probe() {
    [ "$TERMINAL_DISTRIBUTION" = "ubuntu" ] || return 0

    # Ubuntu's /etc/bash.bashrc calls `groups` to decide whether to show a sudo
    # hint. PRoot keeps Android supplementary GIDs, which Ubuntu cannot resolve
    # through its own /etc/group, so that harmless probe pollutes every new
    # interactive session with "cannot find name for group ID" warnings.
    # Keep the Android groups intact because they grant host network/storage
    # access, and use Ubuntu's supported opt-out for the unnecessary sudo hint.
    mkdir -p "$HOME"
    [ -e "$HOME/.hushlogin" ] || : > "$HOME/.hushlogin"
}

suppress_ubuntu_sudo_group_probe

if [ "$HEADLESS_MODE" = "1" ]; then
    export PS1=""
    export PS2=""
    unset PROMPT_COMMAND
    export PAGER=cat
    export GIT_PAGER=cat
else
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
fi
export PIP_BREAK_SYSTEM_PACKAGES=1

if [ "$HEADLESS_MODE" != "1" ] && [ "$#" -eq 0 ]; then
    if [ "$TERMINAL_DISTRIBUTION" = "ubuntu" ]; then
        missing_packages=""
        for pkg in bash ca-certificates nano; do
            if ! dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q 'install ok installed'; then
                missing_packages="$missing_packages $pkg"
            fi
        done
        if [ -n "$missing_packages" ]; then
            printf '\033[34;1m[*]\033[0m Installing important packages\n'
            if apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $missing_packages; then
                printf '\033[32;1m[+]\033[0m Successfully installed\n'
            else
                printf '\033[31;1m[!]\033[0m Failed to install important packages automatically\n'
            fi
            printf '\033[34;1m[*]\033[0m Use \033[32m%s\033[0m to install new packages\n' "$PACKAGE_MANAGER_HINT"
        fi
    else
        required_packages="bash gcompat glib nano"
        missing_packages=""
        for pkg in $required_packages; do
            if ! apk info -e "$pkg" >/dev/null 2>&1; then
                missing_packages="$missing_packages $pkg"
            fi
        done
        if [ -n "$missing_packages" ]; then
            printf '\033[34;1m[*]\033[0m Installing important packages\n'
            if apk --wait 300 add --no-cache $missing_packages; then
                printf '\033[32;1m[+]\033[0m Successfully installed\n'
            else
                printf '\033[31;1m[!]\033[0m Failed to install important packages automatically\n'
            fi
            printf '\033[34;1m[*]\033[0m Use \033[32m%s\033[0m to install new packages\n' "$PACKAGE_MANAGER_HINT"
        fi
    fi
fi

#fix linker warning
if [ ! -f /linkerconfig/ld.config.txt ]; then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

if [ "$#" -eq 0 ]; then
    if [ "$HEADLESS_MODE" = "1" ]; then
        stty -echo -echoctl 2>/dev/null || true
        cd "${OMNIBOT_SESSION_CWD:-$HOME}"
        exec "$INTERACTIVE_SHELL"
    fi
    [ -r /etc/profile ] && . /etc/profile
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
    cd $HOME
    exec "$INTERACTIVE_SHELL"
else
    exec "$@"
fi
