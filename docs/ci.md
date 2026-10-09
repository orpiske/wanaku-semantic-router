# CI automation

WSR carries the same seven GitHub Actions workflows as [Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability/tree/main/.github/workflows).

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| Build Main | Push to `main`, maintenance branches (`0.1.x`), `v*` tags, or manual dispatch | Verify Java 21 builds on Linux AMD64 and ARM64, upload test reports and packaged runtimes, smoke-test and publish containers and manifests. |
| Multiplatform PR Builds | PRs targeting `main` or maintenance branches | Verify Linux AMD64, Linux ARM64, and macOS; Windows is experimental. Upload test reports; container checks run on Linux. |
| Dependency Review | PRs | Review dependency changes for known vulnerabilities. |
| Markdown Lint | PRs changing Markdown or lint configuration | Check documentation with markdownlint-cli2. |
| early-access | Manual dispatch | Build and smoke-test both Linux architectures, publish containers and manifests, and publish a signed early-access runtime ZIP through JReleaser. |
| release | Manual dispatch | Prepare the Maven release tag and next development version on the selected branch, verify the release, and publish a signed runtime ZIP through JReleaser. |
| release-artifacts | Manual dispatch | Build the Maven release tag and publish containers and manifests for both Linux architectures. |

All builds install the SDK revision recorded in `.github/sdk-revision`. Maven verification runs both unit and packaged runtime integration tests. WSR retains its formatting, manifest assembly, and container smoke checks.

Dependabot checks Maven dependencies weekly, excluding Apache Camel dependencies as in CIC.

## Publishing setup

Configure these repository secrets before running publishing workflows:

- `QUAY_USERNAME` and `QUAY_PASSWORD`: credentials for `quay.io/wanaku/wanaku-semantic-router`.
- `GPG_PUBLIC_KEY`, `GPG_PRIVATE_KEY`, and `MAVEN_GPG_PASSPHRASE`: JReleaser artifact signing keys and passphrase.

GitHub release jobs use the automatically supplied `GITHUB_TOKEN` with `contents: write`. Publishing is restricted to `wanaku-ai/wanaku-semantic-router`. Enable the dependency graph for Dependency Review.

## Manual releases

For early access, dispatch `early-access` from the development branch with `currentDevelopmentVersion` matching the POM version, including `-SNAPSHOT` (for example, `0.1.0-SNAPSHOT`).

For a release, dispatch `release` with a release version without `-SNAPSHOT`, the next development version without `-SNAPSHOT`, and an existing release branch. For example: `0.1.0`, `0.1.1`, and `0.1.x`. The workflow creates `wanaku-semantic-router-0.1.0` and updates the branch to `0.1.1-SNAPSHOT`. WSR currently uses Camel and SDK snapshots, which release preparation explicitly permits.

After the release completes, dispatch `release-artifacts` with the same release version (`0.1.0`) to build the tagged containers. GitHub-token pushes do not automatically trigger another workflow.

The release ZIP contains the application JAR and its `lib/` directory. Extract both into the same directory and run `java -jar wanaku-semantic-router-VERSION.jar` with the desired command.
