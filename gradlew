#!/bin/sh
# Gradle start-up script for POSIX (Linux, macOS)
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
GRADLE_HOME=$(dirname "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD="maximum"
warn () { echo "$*"; } >&2
die () { echo; echo "$*"; echo; exit 1; } >&2
OS_TYPE="$(uname)"
case $OS_TYPE in
  CYGWIN* | MINGW* | MSYS* | Windows_NT)
    CLASSPATH="$GRADLE_HOME/gradle/wrapper/gradle-wrapper.jar"
    ;;
  *)
    CLASSPATH=$GRADLE_HOME/gradle/wrapper/gradle-wrapper.jar
    ;;
esac
JAVACMD="java"
exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
