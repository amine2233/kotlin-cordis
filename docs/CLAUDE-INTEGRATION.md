# Using kotlin-cordis with Claude and skills.sh

The repo ships one skill, `skills/kotlin-cordis/`, that teaches an AI agent the library's API, rules and
pitfalls, with examples (`references/examples.md`: simple / medium / hard) and recipes for Android, Ktor and CLI.

```
.claude-plugin/plugin.json        Claude Code plugin manifest
.claude-plugin/marketplace.json   makes this repo installable as a marketplace
skills/kotlin-cordis/SKILL.md     the skill (frontmatter: name + description drive auto-loading)
skills/kotlin-cordis/references/  examples.md, android.md, ktor.md, cli.md
.claude/skills/kotlin-cordis      symlink to the above, so the skill is active when working in this repo
```

## Claude Code — as a plugin

```
/plugin marketplace add amine2233/kotlin-cordis
/plugin install kotlin-cordis@kotlin-cordis
```

The skill then loads automatically when a task involves kotlin-cordis (`Context`, `Plugin`, `ServiceKey`,
`LoadPlan`, Android/Ktor/CLI wiring), or explicitly with `/kotlin-cordis`. Update with
`/plugin marketplace update kotlin-cordis`.

## Claude Code — as a plain skill (no plugin)

Project-level (shared with the team via git):
```bash
mkdir -p .claude/skills
cp -R path/to/kotlin-cordis/skills/kotlin-cordis .claude/skills/
```
User-level (all projects): copy it to `~/.claude/skills/kotlin-cordis`.

## skills.sh

[skills.sh](https://skills.sh) installs skills from any GitHub repo that contains a `SKILL.md`:

```bash
npx skills add amine2233/kotlin-cordis
```

The CLI discovers `skills/kotlin-cordis/SKILL.md` and installs it for the agents you select (Claude Code,
Cursor, Codex, …). Pass `-g` for a global install. Keep the whole `skills/kotlin-cordis/` folder together:
`SKILL.md` links to its `references/` files by relative path.

## Claude.ai / API

Zip the `skills/kotlin-cordis` folder (the folder itself must be the zip root's single directory) and upload it
under Settings → Capabilities → Skills.

## Telling Claude about the library in your app's repo

Add to your app's `CLAUDE.md`:

```markdown
## kotlin-cordis
This project uses kotlin-cordis (`io.github.amine2233:cordis`). Use the `kotlin-cordis` skill for plugin,
service and load-plan code. Register side effects only through `ctx`; dispose the root on shutdown.
```

## Maintaining the skill

- Keep API names in `SKILL.md` and `references/` in sync with the code; the examples in `references/examples.md`
  were compiled against the library — re-run them in a scratch test when the API changes.
- Bump `version` in `.claude-plugin/plugin.json` when the skill content changes so installed copies update.
