# A check list of things to be done before a release.

* Ensure you have an empty just checked out folder out of git. Optionally you could apply git clean:
```bash
git clean -xdf
```
* Compile the native binaries by using the docker image.

You will of course need docker installed and running for this:
```bash
MyComputer activemq-artemis-native myuser$ ./scripts/compile-using-docker.sh
```

* Build the release locally: mvn clean install -Prelease

## Key to Sign the Release

If you don't have a key to sign the release artifacts you can generate one using this command:

```
gpg --gen-key
```

Ensure that your key is listed at https://dist.apache.org/repos/dist/release/activemq/KEYS.
If not, generate the key information, e.g.:

```
gpg --list-sigs username@apache.org > /tmp/key; gpg --armor --export username@apache.org >> /tmp/key
```

Then send the key information in `/tmp/key` to `private@activemq.apache.org` so it can be added.

Add your key id (available via `gpg --fingerprint <key>`) to the `OpenPGP Public Key Primary Fingerprint` field at 
https://id.apache.org/. Note: this is just the key id, not the whole fingerprint.

## Checking out a new empty git repository

Before starting make sure you clone a brand new git as follows as the release plugin will use the upstream for pushing the tags:

```sh
git clone git://github.com/apache/activemq-artemis-native.git
cd activemq-artemis-artemis
git remote add upstream https://gitbox.apache.org/repos/asf/activemq-artemis-native.git
```

If your git `user.email` and/or `user.name` are not set globally then you'll need to set these on the newly clone 
repository as they will be used during the release process to make commits to the upstream repository, e.g.:

```
git config user.email "username@apache.org"
git config user.name "FirstName LastName"
```

This should be the same `user.email` and `user.name` you use on your main repository.

## Running the release

You will have to use this following maven command to perform the release:

```sh
mvn clean release:prepare -DautoVersionSubmodules=true -Prelease -Pdocker
```

You could optionally set `pushChanges=false` so the version commit and tag won't be pushed upstream (you would have to do it yourself):

```sh
mvn clean release:prepare -DautoVersionSubmodules=true -DpushChanges=false -Prelease -Pdocker
```

When prompted make sure the next is a major release. Example:

```
[INFO] Checking dependencies and plugins for snapshots ...
What is the release version for "ActiveMQ Artemis Parent"? (org.apache.activemq:artemis-pom) 1.4.0: :
What is SCM release tag or label for "ActiveMQ Artemis Parent"? (org.apache.activemq:artemis-pom) artemis-pom-1.4.0: : 1.4.0
What is the new development version for "ActiveMQ Artemis Parent"? (org.apache.activemq:artemis-pom) 1.4.1-SNAPSHOT: : 1.5.0-SNAPSHOT
```

Otherwise snapshots will be created at 1.4.1 and forgotten. (Unless we ever release 1.4.1 on that example).

For more information look at the prepare plugin:

- https://maven.apache.org/maven-release/maven-release-plugin/prepare-mojo.html#pushChanges

If you set `pushChanges=false` then you will have to push the changes manually.  The first command is to push the commits
which are for changing the `&lt;version>` in the pom.xml files, and the second push is for the tag, e.g.:

```sh
git push upstream
git push upstream <version>
```

## Uploading to nexus

To upload it to nexus, perform this command:

```sh
mvn release:perform -Prelease -Pdocker
```

Note: this can take quite awhile depending on the speed for your Internet connection.


### Resuming release upload

If something happened during the release upload to nexus, you may need to eventually redo the upload.

There is a release.properties file that is generated at the root of the project during the release. In case you want to upload a previously tagged release, add this file as follows:

- release.properties
```
scm.url=scm:git:https://github.com/apache/activemq-artemis.git
scm.tag=1.4.0
```


## Apache Guide

For more information consult the apache guide at this address:

* http://www.apache.org/dev/publishing-maven-artifacts.html
