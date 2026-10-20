# Scripts

Run these commands from the repository root.

| Folder | Script | Purpose |
| --- | --- | --- |
| app | `start.sh` | Start the local development server using bootRun and .env |
| app | `stop.sh` | Stop the local development server |
| llm | `llm-start.sh` | Start the local Ollama runtime/model |
| llm | `llm-stop.sh` | Unload models and stop Ollama, or unload one named model |
| llm | `llm-status.sh` | Inspect the local LLM runtime |
| deploy | `install-macos-schedule.sh` | Install/re-enable daily 08:30 macOS deployment |
| deploy | `daily-update.sh` | Pull master, clean-build/test and restart the managed server |
| deploy | `run-deployed.sh` | Internal LaunchAgent entry point for the packaged server |
| deploy | `stop-macos-deployment.sh` | Stop and disable the macOS deployment and server |

Examples:

```bash
bash scripts/app/start.sh
bash scripts/llm/llm-status.sh
bash scripts/deploy/install-macos-schedule.sh
bash scripts/deploy/daily-update.sh
bash scripts/deploy/stop-macos-deployment.sh
```

Use app scripts for local development or deploy scripts for managed deployment.
See [macOS setup](../deploy/macos/README.md) and [Linux setup](../deploy/README.md).
