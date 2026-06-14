#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# Maven wrapper shell script
# If Maven is installed locally, use it. Otherwise download via the wrapper JAR.

if [ -x "$JAVA_HOME/bin/java" ]; then
  javaExecutable="$JAVA_HOME/bin/java"
elif command -v java > /dev/null 2>&1; then
  javaExecutable="java"
else
  echo "ERROR: JAVA_HOME is not set and no 'java' command could be found."
  exit 1
fi

# Detect if Maven is installed
if command -v mvn > /dev/null 2>&1; then
  exec mvn "$@"
fi

# Fall back to maven wrapper JAR
MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(pwd)}"
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"

if [ -f "$MAVEN_WRAPPER_JAR" ]; then
  exec "$javaExecutable" \
    -classpath "$MAVEN_WRAPPER_JAR" \
    "-Dmaven.multiModuleProjectDirectory=$MAVEN_PROJECTBASEDIR" \
    org.apache.maven.wrapper.MavenWrapperMain "$@"
fi

echo "Maven not found and maven-wrapper.jar not found."
echo "Please install Maven from https://maven.apache.org/ or run: brew install maven"
exit 1
