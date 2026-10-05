# Contributing

## Commit messages and pull request titles

Use [Conventional Commits](https://www.conventionalcommits.org/) for commit messages and PR titles:

```text
feat(ui): polish cluster summary
fix: handle expired credentials
chore(ci): pin workflow actions
```

Use one of `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, or `revert`; a scope is optional. Write a concise imperative subject (for example, `polish`, not `polished`), keep the full header to 72 characters or fewer, and use lowercase subject wording. Mark breaking changes with `!` and a `BREAKING CHANGE: description` footer, for example:

```text
feat(api)!: remove the legacy endpoint

BREAKING CHANGE: clients must use the v2 endpoint
```

GitHub Actions checks PR titles and every commit in pull requests, and checks new commits pushed to `main`. The existing repository history includes an initial nonconforming commit; it is not rewritten or included in PR checks. A pull request should not add nonconforming commits.
