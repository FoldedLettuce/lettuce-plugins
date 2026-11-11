#!/usr/bin/env bash
# Builds LettuceRestart-<version>.jar with only a JDK (21+).
# stubs/ holds minimal copies of the few Paper/Velocity API classes the plugin uses,
# only for compiling. They are not packaged into the jar.
set -euo pipefail
cd "$(dirname "$0")"
VERSION=$(grep '^version:' res/plugin.yml | awk '{print $2}')
rm -rf build && mkdir -p build/stubs build/classes build/test build/pkg
javac --release 21 -d build/stubs $(find stubs -name '*.java')
javac --release 21 -cp build/stubs -d build/classes $(find src -name '*.java')
javac --release 21 -cp build/classes -d build/test $(find test -name '*.java')
java -cp build/classes:build/test xyz.foldedlettuce.lettucerestart.SchedulerTest
cp -r build/classes/xyz build/pkg/
cp res/plugin.yml res/velocity-plugin.json res/lettucerestart-config.yml build/pkg/
jar cfm "LettuceRestart-$VERSION.jar" res/MANIFEST.MF -C build/pkg .
echo "Built LettuceRestart-$VERSION.jar"
