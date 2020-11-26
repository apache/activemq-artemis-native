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

# This will compile both 32 and 64 bits version for AMD64 and only 64 bits version for AARCH64 
ARCH=`uname -p`

if test "x$ARCH" = "x86_64"; then
    # x86_32
    cmake -DCMAKE_VERBOSE_MAKEFILE=On -DCMAKE_USER_C_FLAGS="-m32" -DARTEMIS_CROSS_COMPILE=On -DARTEMIS_CROSS_COMPILE_ROOT_PATH=/usr/lib .
    make
    rm -rf CMakeCache.txt cmake_install.cmake
fi

# 64 bit (AMD64 and AARCH64)
cmake -DCMAKE_VERBOSE_MAKEFILE=On -DCMAKE_USER_C_FLAGS="" -DARTEMIS_CROSS_COMPILE=Off -DARTEMIS_CROSS_COMPILE_ROOT_PATH=/usr/lib .
make
