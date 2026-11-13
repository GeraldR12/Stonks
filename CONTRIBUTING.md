# Contributing to Stonks

## Before you start
Open an issue first for anything bigger than a small fix, so we can agree on the approach before you spend time on it.

## Building
You need Java 21 and Maven.

```bash
mvn package
```

`dev-command` is downloaded from GitHub Packages, which needs a GitHub token even for public packages. Add this to `~/.m2/settings.xml`, with a personal access token that has the `read:packages` scope:

```xml
<settings>
  <servers>
    <server>
      <id>dev-command-repository</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_TOKEN</password>
    </server>
  </servers>
</settings>
```

## Rules for changes
- Trades are recorded in the transaction journal (`MarketTransactionJournal`, used by `BuyTransactionService` and `SellTransactionService`) before any money moves. New code that moves money should follow the same pattern instead of calling the economy directly.
- A buy followed by a sell must always lose gold. If your change touches prices or fees, add a test that proves it.
- Add or update tests with your change. `mvn test` must pass.
- Update `CHANGELOG.md`, and the player or admin guide when commands, config or behaviour change.

## Releasing
1. Set the version in `pom.xml` and `src/main/resources/plugin.yml`.
2. Add the version to `CHANGELOG.md`.
3. Merge to `main`, then tag and push: `git tag v2.10.2 && git push origin v2.10.2`.

The release workflow builds the jar, checks that the tag matches `pom.xml`, and publishes the GitHub Release with the jar and its SHA-256.
