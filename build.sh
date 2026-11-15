#!/usr/bin/env bash
# Offline build helper: compiles against the xinbot / MovementSync jars that ship
# with this workspace and packages the plugin into build/backtothebase-1.0.0.jar.
#
# Usage:
#   ./build.sh            compile + package
#   ./build.sh --test     also compile and run the dependency-free self test
#
# Environment overrides:
#   XINBOT_JAR, MOVEMENTSYNC_JAR, JOML_JAR, JAVA_HOME
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

XINBOT_JAR="${XINBOT_JAR:-$ROOT/../src/xinbot-2.4.3-RELEASE.jar}"
MOVEMENTSYNC_JAR="${MOVEMENTSYNC_JAR:-$ROOT/../src/plugin/MovementSync-1.12.0-RELEASE.jar}"
JOML_JAR="${JOML_JAR:-$HOME/.m2/repository/org/joml/joml/1.10.5/joml-1.10.5.jar}"

for jar in "$XINBOT_JAR" "$MOVEMENTSYNC_JAR"; do
    if [[ ! -f "$jar" ]]; then
        echo "error: missing dependency jar: $jar" >&2
        echo "Set XINBOT_JAR / MOVEMENTSYNC_JAR to the jars in your xinbot install." >&2
        exit 1
    fi
done

CP="$XINBOT_JAR:$MOVEMENTSYNC_JAR"
if [[ -f "$JOML_JAR" ]]; then
    CP="$CP:$JOML_JAR"
fi

echo "==> clean"
rm -rf build
mkdir -p build/classes

echo "==> compile"
find src/main/java -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all -cp "$CP" -d build/classes @build/sources.txt

echo "==> resources"
cp -R src/main/resources/. build/classes/

echo "==> package"
jar cf build/backtothebase-1.0.1.jar -C build/classes .
echo "built: $ROOT/build/backtothebase-1.0.1.jar"

if [[ "${1:-}" == "--test" ]]; then
    echo "==> self test"
    mkdir -p build/test-classes
    find src/test/java -name '*.java' > build/test-sources.txt
    javac --release 17 -encoding UTF-8 -cp "build/classes:$CP" \
        -d build/test-classes @build/test-sources.txt
    java -cp "build/test-classes:build/classes:$CP" xin.bbtt.backtothebase.SelfTest
    java -cp "build/test-classes:build/classes:$CP" xin.bbtt.backtothebase.CommandSelfTest
    java -cp "build/test-classes:build/classes:$CP" xin.bbtt.backtothebase.PluginLoadSelfTest
fi
