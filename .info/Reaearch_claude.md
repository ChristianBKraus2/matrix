# Research

## Skills

https://claude.ai/share/f4de6beb-afb7-4dd2-a518-c8c3e18c4c00

## How skills work

Claude Code decides through **model-invoked activation**, not hardcoded logic:

- At session start, Claude Code scans skill folders (`.claude/skills/` in the project, `~/.claude/skills/` personal, plus plugin-bundled skills) and loads just the **name + description** from each `SKILL.md`'s frontmatter — not the full content.
- When you send a prompt, Claude reads your request, compares it to all available skill descriptions, and activates the ones that match. That's the whole trigger mechanism — it's semantic matching against the description, not keyword rules. [Claude Academy](https://academy.claude.com/courses/claude-code-101/skills)
- If a skill matches, Claude loads its full `SKILL.md` body (and any bundled scripts/resources) into context at that point — this is "progressive disclosure," where Claude loads only the information it needs to complete the task, preventing context window overload. [Claude](https://support.claude.com/en/articles/12512176-what-are-skills)

A few things that affect this in a project specifically:

- **Description quality matters most.** A vague description ("helps with PRs") triggers unreliably; a specific one ("Use when reviewing PRs or checking code changes") triggers well.
- **Naming conflicts**: if a project skill shares a name with a personal or bundled skill, enterprise beats personal, and any of them overrides a bundled skill of the same name. [LearnWithHasan](https://learnwithhasan.com/claude-code-guide/skills/)
- **You can override auto-invocation**: `disable-model-invocation: true` in frontmatter means Claude can never trigger it itself — you must call it with `/skill-name`. `user-invocable: false` does the reverse — only Claude can trigger it, no slash command.
- You can always force one manually with `/skill-name` regardless of whether Claude "would have" matched it.

## Minimal Skill

Here's a minimal example — a project skill that teaches Claude how to write commit messages for a specific repo:

```markdown
---
name: commit-style
description: Formats git commit messages for this repo. Use when the user asks to write, suggest, or review a commit message.
---

# Commit Message Style

When writing a commit message for this repo, follow these rules:

1. Use the format: `type(scope): short summary` (e.g. `fix(auth): handle expired tokens`)
2. Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`
3. Summary line under 72 characters, lowercase, no trailing period
4. If the change is non-trivial, add a blank line then 1-3 bullet points explaining *why*, not *what*
5. Reference the ticket number at the end if one is mentioned in conversation (e.g. `Refs: PROJ-123`)

## Example

```
fix(api): retry failed requests with backoff

- Prevents cascading failures during brief outages
- Matches retry policy used in the payments service

Refs: PROJ-456
```
```

To use it, save that as:

```
.claude/skills/commit-style/SKILL.md
```

Then Claude Code will auto-load its description at session start, and match it whenever you ask something like "write a commit message for this" — no `/commit-style` needed, though you could still invoke it manually.

The two fields that matter most are `name` and `description` in the frontmatter — the description is literally what Claude compares your request against, so being specific about *when* to use it (not just what it does) is what makes triggering reliable.
## GitHub Issues 

Those results were about bugs in Claude Code itself, not what I needed. Let me search more specifically.

Yes — a few ways, depending on what "access" means:

- **Via the `gh` CLI**: If you have GitHub's CLI (`gh`) installed and authenticated, Claude Code can run `gh issue list`, `gh issue view`, etc. directly in its bash tool, whether you're in the terminal or the VSCode extension. This is the most common approach and needs no extra setup beyond `gh auth login`.
- **Via the GitHub MCP server**: You can connect Claude Code to GitHub's official MCP server, which gives it structured tools for reading/creating issues, PRs, etc. This works the same in the VSCode extension as in the terminal.
- **Via GitHub Actions**: Claude Code GitHub Actions lets Claude analyze code, create pull requests, and fix bugs when you mention @claude in an issue or PR — but that's a separate, repo-side automation, not something driven from inside VSCode. [claude](https://code.claude.com/docs/en/github-actions.md)

For local dev in VSCode, the `gh` CLI route is simplest — just ask Claude Code something like "show me open issues in this repo" and it'll shell out to `gh` if it's installed.
