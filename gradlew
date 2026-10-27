#!/bin/sh
#
# Gradle start up script for POSIX shells.
# Minimal launcher: locates a JVM and runs the Gradle wrapper, which downloads
# the distribution named in gradle/wrapper/gradle-wrapper.properties on first use.
#

set -e

APP_HOME=$( cd -P "$( dirname "$0" )" > /dev/null && pwd -P )
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
    if ! command -v java > /dev/null 2>&1 ; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found." >&2
        echo "Set JAVA_HOME to a JDK 17 installation, or put java on your PATH." >&2
        exit 1
    fi
fi

if [ ! -f "$CLASSPATH" ] ; then
    echo "ERROR: gradle/wrapper/gradle-wrapper.jar is missing." >&2
    exit 1
fi

exec "$JAVACMD" \
    -Xmx64m -Xms64m \
    -Dorg.gradle.appname=gradlew \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
