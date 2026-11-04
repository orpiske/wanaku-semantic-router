---
title: Release guide
description: "Release the Wanaku Semantic Router: the checks before a release, the release branch, the version settings, and the release workflow."
---

# Release Guide

## Pre-Release Checklist

Before triggering a release, verify the following:

- [ ] All tests passing on the release branch
- [ ] Release version matches the root `pom.xml` version without `-SNAPSHOT`
- [ ] No unintended SNAPSHOT dependencies
- [ ] Documentation updated for new features
- [ ] Breaking changes and migration steps documented in the release notes

## Triggering a Release

Configure the credentials described in [CI automation](ci.md#publishing-setup). Container publishing is skipped unless both `QUAY_USERNAME` and `QUAY_PASSWORD` are set.

Releases are cut from release branches following the `X.Y.x` naming convention (e.g., `0.1.x`, `0.2.x`).

### Create the release branch (first release of a minor version only)

```shell
git checkout main
git checkout -b 0.3.x
git push origin 0.3.x
```

### Set the versions

```shell
export RELEASE_BRANCH=0.3.x
export CURRENT_DEVELOPMENT_VERSION=0.3.0
export NEXT_DEVELOPMENT_VERSION=0.3.1
```

### Trigger the release

```shell
gh workflow run release -r ${RELEASE_BRANCH} -f releaseBranch=${RELEASE_BRANCH} -f currentDevelopmentVersion=${CURRENT_DEVELOPMENT_VERSION} -f nextDevelopmentVersion=${NEXT_DEVELOPMENT_VERSION}
```

> [!NOTE]
> The `-r` flag tells GitHub to run the workflow from the release branch. The `releaseBranch` input tells the
> workflow which branch to check out and push version bump commits to.

## Post-Release

After the release workflow completes successfully:

The `release` workflow publishes the signed runtime ZIP containing the application JAR and its `lib/` directory. Keep both together when extracting the distribution.

1. Publish container images and multi-architecture manifests:

   ```shell
   gh workflow run release-artifacts -f currentDevelopmentVersion=${CURRENT_DEVELOPMENT_VERSION}
   ```

2. Verify release artifacts on the [GitHub Releases page](https://github.com/wanaku-ai/wanaku-semantic-router/releases)
3. Update the [Wanaku documentation site](https://wanaku.ai/docs/) if needed

## Next steps

- [Build the Wanaku Semantic Router](building.md): build and package from source.
- [CI automation](ci.md): publishing prerequisites and release artifacts.
