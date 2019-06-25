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

if [ -d "./bin" ]; then rm -Rf ./bin; fi
docker build -f src/main/docker/Dockerfile-centos -t artemis-native-builder .
docker run --rm -v $PWD/target/lib:/work/target/lib artemis-native-builder "$@"
chown -Rv $USER:$GID ./bin
ls -liat ./target/lib

# Note: You may need to authorize docker to map folder at your folder structure
#docker build -f src/main/docker/Dockerfile-centos -t artemis-native-builder . && docker run -it --rm -v $PWD/target/lib:/work/target/lib artemis-native-builder bash


## 64 bits build
#docker image rm artemis-native-builder -f
#docker build -f ./src/main/docker/Dockerfile-ubuntu -t artemis-native-builder .

# Note: You may need to authorize docker to map folder at your folder structure
#docker run -it --rm -v $PWD/target/lib:/work/target/lib artemis-native-builder bash ./compile-native.sh

## if you need to debug compilation
#docker run -it --rm -v $PWD/target/lib:/work/target/lib artemis-native-builder bash

#docker image rm artemis-native-builder-32 -f
#docker build -f ./src/main/docker/Dockerfile-ubuntu-32 -t artemis-native-builder-32 .

# Note: You may need to authorize docker to map folder at your folder structure
#docker run -it --rm -v $PWD/target/lib:/work/target/lib artemis-native-builder-32 bash ./compile-native.sh

## if you need to debug compilation
#docker run -it --rm -v $PWD/target/lib:/work/target/lib artemis-native-builder-32 bash
