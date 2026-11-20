#!/usr/bin/env bash
# Builds JInterface (OtpErlang.jar) from the OTP sources that match the local
# Erlang install and installs it in the local Maven repository as
#   org.erlang.otp:jinterface:<jinterface version>
#
# Why: the Windows Erlang installer ships JInterface docs but not the jar, and
# the jars on Maven Central are from OTP 17 (2014). The jar must match the OTP
# release the back-end runs, so we build it from source.
#
# Usage:  tools/install-jinterface.sh            (needs erl, git, javac, jar, mvn)
#         MVN=/path/to/mvn tools/install-jinterface.sh
set -euo pipefail

MVN="${MVN:-mvn}"
OTP_VSN=$(erl -noshell -eval '{ok,V}=file:read_file(filename:join([code:root_dir(),"releases",erlang:system_info(otp_release),"OTP_VERSION"])),io:format("~s",[string:trim(V)]),halt().')
echo "Local OTP: $OTP_VSN"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

git clone -q --depth 1 --branch "OTP-$OTP_VSN" --filter=blob:none --sparse \
    https://github.com/erlang/otp.git "$WORK/otp"
git -C "$WORK/otp" sparse-checkout set lib/jinterface

JI_VSN=$(sed -n 's/^JINTERFACE_VSN *= *//p' "$WORK/otp/lib/jinterface/vsn.mk")
echo "JInterface: $JI_VSN"

# Java tools on Windows (Git Bash) need native paths, not /tmp/...
native() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

mkdir -p "$WORK/classes"
(cd "$WORK/otp/lib/jinterface/java_src" && find . -name '*.java' > "$WORK/sources.txt" \
    && javac -nowarn --release 17 -d "$(native "$WORK/classes")" @"$(native "$WORK/sources.txt")")
jar --create --file "$(native "$WORK/OtpErlang.jar")" -C "$(native "$WORK/classes")" .

"$MVN" -q install:install-file -Dfile="$(native "$WORK/OtpErlang.jar")" \
    -DgroupId=org.erlang.otp -DartifactId=jinterface -Dversion="$JI_VSN" -Dpackaging=jar

echo "Installed org.erlang.otp:jinterface:$JI_VSN in the local Maven repository."
