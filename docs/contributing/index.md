---
title: Contribute to Wanaku Semantic Router
description: Build, test, and release Wanaku Semantic Router, with the CI workflows, the test suites, and the parts of the Java SDK that the router uses.
---

# Contribute to Wanaku Semantic Router

These pages describe how Wanaku Semantic Router (WSR) is built, tested, and released.

This repository implements [Barn #182](https://github.com/wanaku-ai/wanaku-barn/issues/182) and the native runtime checks for [Barn #180](https://github.com/wanaku-ai/wanaku-barn/issues/180). [Wanaku #2101](https://github.com/wanaku-ai/wanaku/issues/2101) tracks the complete workstream.

## Pages in this section

- [Build Wanaku Semantic Router](building.md): build and package WSR from source, and format the code.
- [Release guide](release-guide.md): prepare a release and publish the runtime ZIP and container images.
- [CI automation](ci.md): the GitHub Actions workflows, the publishing setup, manual releases, and native architecture builds.
- [Write documentation](documentation.md): the page conventions and the checks that CI runs on `docs/`.
- [Test Wanaku Semantic Router](testing.md): the test suites that need more than a Maven build.
- [Java SDK reuse](sdk-reuse.md): the parts of the Wanaku Capabilities Java SDK that WSR uses.

## Next steps

- [Run Wanaku Semantic Router](../operate/index.md): start a runtime from a published catalog.
