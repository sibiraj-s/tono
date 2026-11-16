# Changesets

Each change that should show up in the release notes gets a changeset:

```
npx changeset
```

Pick the bump (patch = fix, minor = feature, major = big change) and write a line or two for the
changelog. Commit the generated `.changeset/*.md` file with your change.

On push to `master`, the Release workflow opens (or updates) a **release: vX.Y.Z** pull request
that bumps the version in `package.json` and adds the entries to `CHANGELOG.md`. Merging it tags
`vX.Y.Z`, creates the GitHub release with those notes, builds the signed APK and attaches it.
