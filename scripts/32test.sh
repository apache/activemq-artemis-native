#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# This will generate a 32bit image for testing and start the shell

docker build -f src/main/docker/Dockerfile-ubuntu-32 -t artemis-native-builder-32 .
# This is mapping your maven repository inside the image to avoid you downloading the internet again
docker run -it --rm -v $PWD/target/lib:/work/target/lib -v $HOME/.m2/repository/:/root/.m2/repository artemis-native-builder-32 ./mvnw test

# you could use it this way
#docker run -it --rm -v $PWD/target/lib:/work/target/lib -v $HOME/.m2/repository/:/root/.m2/repository artemis-native-builder-64 bash
