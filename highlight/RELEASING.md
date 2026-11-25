# Releasing highlight.java to Maven Central

Releases are published through the [Central Portal](https://central.sonatype.com) of Sonatype, from GitHub Actions or from your own machine. The `release` profile of `pom.xml` builds the sources and javadoc jars that Central requires, signs all artifacts with GPG and uploads them.

A version that is published on Maven Central can never be changed or removed. The upload is therefore not published automatically: it waits in the portal until you press *Publish*.

## One-time setup

### 1. An account and the `org.scrivo` namespace

1. Sign in at <https://central.sonatype.com>.
2. Under *Namespaces*, add `org.scrivo`. The portal gives a verification key.
3. Prove that you own the domain `scrivo.org` (the reverse of the namespace): add a DNS TXT record to `scrivo.org` with the verification key as its value, then press *Verify* in the portal. The record can be removed once the namespace is verified.

If you don't own `scrivo.org`, the groupId has to change to a namespace you can verify, such as `nl.scrivo` (for `scrivo.nl`) or `io.github.scrivo` (verified with a temporary public repository in the `scrivo` GitHub organization). Change `groupId` in `pom.xml` and in `../demo/pom.xml`, and the dependency in `../README.md`.

### 2. A user token

In the portal, choose *View Account* > *Generate User Token*, and add it to `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username><!-- token username --></username>
            <password><!-- token password --></password>
        </server>
    </servers>
</settings>
```

### 3. A GPG key

Central requires every artifact to be signed, with a public key that is available on a key server:

```bash
gpg --full-generate-key                      # RSA 4096 or ed25519, with your name and email
gpg --list-secret-keys --keyid-format long   # shows the key id
gpg --keyserver keys.openpgp.org --send-keys <key id>
gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
```

keys.openpgp.org sends a confirmation email before it publishes the key with its email address.

### 4. GitHub secrets (for releasing from GitHub Actions)

The workflow `../.github/workflows/release.yml` uploads a release when a tag `v<version>` is pushed. Add these secrets to the repository under *Settings* > *Secrets and variables* > *Actions*:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | the token username |
| `MAVEN_CENTRAL_PASSWORD` | the token password |
| `MAVEN_GPG_PRIVATE_KEY` | the output of `gpg --armor --export-secret-keys <key id>` |
| `MAVEN_GPG_PASSPHRASE` | the passphrase of the key |

Consider a separate signing key for GitHub (a subkey, or a key of its own that is also on the key servers), so that it can be revoked without affecting your own key.

## Releasing a version from GitHub

1. Make sure the version in `pom.xml` (and the dependency in `../README.md`) is the version to release, that the build is clean (`mvn clean verify`), and push it.

2. Tag the release and push the tag:

   ```bash
   git tag -s v10.1.2 -m "highlight.java 10.1.2"
   git push origin v10.1.2
   ```

   The *Release* workflow checks that the tag matches the version in `pom.xml`, then builds, signs and uploads the release. Its progress is under *Actions* on GitHub.

3. Review the deployment under *Deployments* in the portal and press *Publish*, or *Drop* to discard the upload (see step 3 below).

If the upload has to be redone, drop the deployment, fix the problem, and move the tag: `git tag -f -s v10.1.2 ...` and `git push -f origin v10.1.2`.

## Releasing a version from your own machine

1. Make sure the version in `pom.xml` (and the dependency in `../README.md`) is the version to release, and that the build is clean:

   ```bash
   mvn clean verify
   ```

2. Build, sign and upload:

   ```bash
   mvn -Prelease clean deploy
   ```

   GPG asks for the passphrase of the key. The upload is validated by the portal; validation errors (like a missing signature) are reported by the build.

3. Review the deployment under *Deployments* in the portal: the artifacts are the jar, the sources and javadoc jars, the `test-snippets` jar with the sample snippets used by the demo, and the pom. Press *Publish*, or *Drop* to discard the upload.

   Publishing usually takes a few minutes; the artifacts then show up on <https://central.sonatype.com/artifact/org.scrivo/highlight>, and it can take a bit longer before they're available to all builds.

4. Tag the release (don't push the tag before the release is uploaded, or the workflow uploads it a second time):

   ```bash
   git tag -s v10.1.2 -m "highlight.java 10.1.2"
   git push origin v10.1.2
   ```

To check the release artifacts without signing or uploading anything:

```bash
mvn -Prelease -Dgpg.skip clean verify
```
