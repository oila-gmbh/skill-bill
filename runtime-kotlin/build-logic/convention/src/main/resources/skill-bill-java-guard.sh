skill_bill_required_java_major=21

skill_bill_java_major() {
    skill_bill_probe_home=$1
    skill_bill_probe_version=""
    if [ -r "$skill_bill_probe_home/release" ] ; then
        skill_bill_probe_version=$( sed -n 's/^JAVA_VERSION="\([0-9][0-9]*\).*/\1/p' "$skill_bill_probe_home/release" 2>/dev/null | sed -n 1p )
    fi
    if [ -z "$skill_bill_probe_version" ] && [ -x "$skill_bill_probe_home/bin/java" ] ; then
        skill_bill_probe_version=$( "$skill_bill_probe_home/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | sed -n 1p )
    fi
    printf '%s' "$skill_bill_probe_version"
}

skill_bill_java_home_ok() {
    [ -n "$1" ] || return 1
    [ -x "$1/bin/java" ] || return 1
    skill_bill_home_major=$( skill_bill_java_major "$1" )
    [ -n "$skill_bill_home_major" ] || return 1
    [ "$skill_bill_home_major" -ge "$skill_bill_required_java_major" ] 2>/dev/null
}

skill_bill_path_java_ok() {
    command -v java >/dev/null 2>&1 || return 1
    skill_bill_path_major=$( java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | sed -n 1p )
    [ -n "$skill_bill_path_major" ] || return 1
    [ "$skill_bill_path_major" -ge "$skill_bill_required_java_major" ] 2>/dev/null
}

skill_bill_scan_java_home() {
    if [ -x /usr/libexec/java_home ] ; then
        skill_bill_mac_home=$( /usr/libexec/java_home -v "$skill_bill_required_java_major+" 2>/dev/null )
        if skill_bill_java_home_ok "$skill_bill_mac_home" ; then
            printf '%s' "$skill_bill_mac_home"
            return 0
        fi
    fi
    for skill_bill_candidate in \
            /usr/lib/jvm/* \
            /Library/Java/JavaVirtualMachines/*/Contents/Home \
            /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
            /usr/local/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
            "$HOME"/.sdkman/candidates/java/* \
            "$HOME"/.asdf/installs/java/* \
            "$HOME"/.gradle/jdks/* ; do
        if skill_bill_java_home_ok "$skill_bill_candidate" ; then
            printf '%s' "$skill_bill_candidate"
            return 0
        fi
    done
    return 1
}

if skill_bill_java_home_ok "${SKILL_BILL_JAVA_HOME:-}" ; then
    JAVA_HOME=$SKILL_BILL_JAVA_HOME
    export JAVA_HOME
elif skill_bill_java_home_ok "${JAVA_HOME:-}" ; then
    :
elif skill_bill_path_java_ok ; then
    unset JAVA_HOME
else
    skill_bill_scanned_home=$( skill_bill_scan_java_home )
    if [ -n "$skill_bill_scanned_home" ] ; then
        JAVA_HOME=$skill_bill_scanned_home
        export JAVA_HOME
    else
        echo "ERROR: no Java $skill_bill_required_java_major+ runtime found." >&2
        echo "The Skill Bill runtime is compiled for Java $skill_bill_required_java_major and cannot run on an older JVM." >&2
        echo "JAVA_HOME is currently: ${JAVA_HOME:-<unset>}" >&2
        echo "Set SKILL_BILL_JAVA_HOME to a Java $skill_bill_required_java_major+ installation and retry." >&2
        exit 1
    fi
fi

