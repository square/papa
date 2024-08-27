# Releasing


## Set up GitHub CLI

Install GitHub CLI

```bash
brew install gh
```

## Releasing

* Create a local release branch from `main` and update `VERSION_NAME` in `gradle.properties` (removing `-SNAPSHOT`) and the README:
```bash
git checkout main && \
git pull && \
git checkout -b release_{NEW_VERSION} && \
sed -i '' 's/VERSION_NAME={PREVIOUS_NEW_VERSION}-SNAPSHOT/VERSION_NAME={NEW_VERSION}/' gradle.properties
sed -i '' 's/com.squareup.papa:papa:{PREVIOUS_VERSION}/com.squareup.papa:papa:{NEW_VERSION}/' README.md
```


* Update the changelog
```bash
mate CHANGELOG.md
```

* Release

```bash
git commit -am "Prepare {NEW_VERSION} release" && \
./gradlew clean && \
./gradlew build && \
./gradlew connectedCheck && \
git tag v{NEW_VERSION} && \
git push origin v{NEW_VERSION} && \
gh workflow run publish-release.yml --ref v{NEW_VERSION}
```

* Wait for the GitHub workflow to finish running then finish the release:

```bash
git checkout main && \
git pull && \
git merge --no-ff --no-edit release_{NEW_VERSION} && \
sed -i '' 's/VERSION_NAME={NEW_VERSION}/VERSION_NAME={NEXT_VERSION}-SNAPSHOT/' gradle.properties && \
git commit -am "Prepare for next development iteration" && \
git push && \
gh release create v{NEW_VERSION} --title v{NEW_VERSION} --notes 'See [Change Log](https://github.com/square/papa/blob/main/CHANGELOG.md)'
```

* Wait for the release to be available [on Maven Central](https://repo1.maven.org/maven2/com/squareup/papa/papa/).
* Tell your friends, update all of your apps, and tweet the new release. As a nice extra touch, mention external contributions.