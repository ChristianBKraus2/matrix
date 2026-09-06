# Research

## Skills

https://claude.ai/share/f4de6beb-afb7-4dd2-a518-c8c3e18c4c00

## GitHub Issues 

Those results were about bugs in Claude Code itself, not what I needed. Let me search more specifically.

Yes — a few ways, depending on what "access" means:

- **Via the `gh` CLI**: If you have GitHub's CLI (`gh`) installed and authenticated, Claude Code can run `gh issue list`, `gh issue view`, etc. directly in its bash tool, whether you're in the terminal or the VSCode extension. This is the most common approach and needs no extra setup beyond `gh auth login`.
- **Via the GitHub MCP server**: You can connect Claude Code to GitHub's official MCP server, which gives it structured tools for reading/creating issues, PRs, etc. This works the same in the VSCode extension as in the terminal.
- **Via GitHub Actions**: Claude Code GitHub Actions lets Claude analyze code, create pull requests, and fix bugs when you mention @claude in an issue or PR — but that's a separate, repo-side automation, not something driven from inside VSCode. [claude](https://code.claude.com/docs/en/github-actions.md)

For local dev in VSCode, the `gh` CLI route is simplest — just ask Claude Code something like "show me open issues in this repo" and it'll shell out to `gh` if it's installed.
