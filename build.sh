#!/bin/sh
# Builds out/tsahr-java.jar (runnable, no dependencies) with only a JDK (>= 11). Optionally runs the parity test.
#   ./build.sh          build
#   ./build.sh test     build and run the engine parity test against the frozen RTSA 0.2.2 reference
set -e
cd "$(dirname "$0")"
rm -rf out/classes && mkdir -p out/classes
compile() { # $1 = output dir, rest = sources/classpath args; --release 11 when the JDK supports it
  out="$1"; shift
  javac --release 11 -encoding UTF-8 -d "$out" "$@" 2>/dev/null || javac -source 11 -target 11 -encoding UTF-8 -d "$out" "$@"
}
compile out/classes $(find src/main -name '*.java')
cp -r src/main/resources/* out/classes/
printf 'Main-Class: org.tsahr.cli.Main\n' > out/manifest.txt
jar cfm out/tsahr-java.jar out/manifest.txt -C out/classes .
echo "Built out/tsahr-java.jar"
if [ "$1" = "test" ]; then
  mkdir -p out/test-classes
  SEP=:; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac   # Java's classpath separator on Windows (Git Bash)
  compile out/test-classes -cp out/classes src/test/java/org/tsahr/EngineParityTest.java src/test/java/org/tsahr/RegressionTest.java
  java -cp "out/classes${SEP}out/test-classes" org.tsahr.EngineParityTest
  java -Djava.awt.headless=true -cp "out/classes${SEP}out/test-classes" org.tsahr.RegressionTest
fi
