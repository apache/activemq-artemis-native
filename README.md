# Introduction


This is a simple tutorial about building and packaging the libartemis-native library. The libartemis-native is a thin
layer library that interface with Linux' lib AIO library as part of the journaling feature of the broker when operating
with AIO journal.

The lib AIO is a Linux-specific dependency, therefore having a relatively modern Linux operating system is assumed for
the purpose of this documentation.

There are two ways to build the native libraries:

- Using a container image created during the build phase
- Bare Metal

## Docker and Podman

You can use either Docker or Podman to compile the native bits in a container created during the build phase.

You can do this using the -Pdocker profile with maven:

```bash
$ mvn install -Pdocker
```

Or you can use the -Ppodman profile with maven:

```bash
$ mvn install -Ppodman
```

Alternatively, you can run the related scripts directly to execute the container builds, though note you must first separately make the java compiler generate the .h header by running:

```bash
$ mvn generate-sources
```
Then you can use the script and the correct image and script should be called.

```bash
$ ./scripts/compile-using-docker.sh
```

or

```bash
$ ./scripts/compile-using-podman.sh
```


## Bare Metal Dependencies

In order to build the package, make sure you install these packages:

- The GNU compiler library container both the C and C++ compiler
- The GNU C library
- The respective libaio package for your Linux distribution
- JDK (full JDK)


For example, on Fedora Linux, compilation of the library requires the following specific packages:

- glibc-devel
- libaio-devel
- gcc
- gcc-g++
- java-1.8.0-openjdk-devel

### Cross compilation

Using a 64-bit Linux OS, it is possible to cross-compile the 32-bit version of the library. For this, the 32-bits
version of the GNU C Library and lib AIO should be installed.

Once again using Fedora Linux as an example, it would mean that the following packages need to be installed:

- glibc-devel.i686
- libaio-devel.i686


### Scripts on Bare Metal

You can use the ./scripts/compile-native.sh script. This script is using cross compilation towards 64 bits and 32 bits from a Linux environment.

Note you must first have the java compiler generate the .h header manually by running:

```bash
$ mvn generate-sources
```

Then call the script to compile the native libs:

```bash
$ ./scripts/compile-native.sh
```

Alternatively you can just use the bare-metal profile for maven which combines both of those steps in one operation:

```bash
$ mvn install -Pbare-metal
```

## Lib AIO Information

The Lib AIO is the Linux' Kernel Asynchronous I/O Support Library. It is part of the kernel project. The library makes
system calls on the kernel layer.

This is the project information:

Git Repository:  git://git.kernel.org/pub/scm/libs/libaio/libaio.git
Mailing List:    linux-aio@kvack.org

## Manual steps to build (via Docker)

From the project base directory, run:

```docker build -f src/main/docker/Dockerfile-centos -t artemis-native-builder . && docker run -v $PWD/target/lib:/work/target/lib artemis-native-builder && sudo chown -Rv $USER:$GID target/lib```


## Steps to build it manually

1. Make sure you have JAVA_HOME defined, and pointing to the root of your JDK:

Example:

```export JAVA_HOME=/usr/share/jdk11```

2. Run mvn generate-sources to genrate the .h header file needed:
 $>  mvn generate-sources

3. Call compile-native.sh. Bootstrap will call all the initial scripts you need
 $>  ./compile-native.sh

if you are missing any dependencies, autoconf would tell you what you're missing.


### Compiled File

The generated jar will include the ./lib/

### Advanced Compilation Methods and Developer-specific Documentation

Passing additional options to the compiler:
```cmake -DCMAKE_USER_C_FLAGS="-fomit-frame-pointer" -DCMAKE_VERBOSE_MAKEFILE=On .```

Compiling with debug options:
```cmake -DCMAKE_BUILD_TYPE=Debug -DCMAKE_VERBOSE_MAKEFILE=On .```

Cross-compilation:
```cmake -DCMAKE_VERBOSE_MAKEFILE=On -DCMAKE_USER_C_FLAGS="-m32" -DARTEMIS_CROSS_COMPILE=On -DARTEMIS_CROSS_COMPILE_ROOT_PATH=/usr/lib .```

Cross-compilation with debugging symbols:
```cmake -DCMAKE_VERBOSE_MAKEFILE=On -DCMAKE_USER_C_FLAGS="-m32" -DARTEMIS_CROSS_COMPILE=On -DARTEMIS_CROSS_COMPILE_ROOT_PATH=/usr/lib .```


## Lib AIO Documentation

The User Manual, chapter 38 (Libaio Native Libraries) will provide more details about our native libraries on libaio.
