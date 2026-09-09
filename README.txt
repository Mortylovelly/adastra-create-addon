# Minecraft AI Agent

Minecraft AI Agent is a Fabric 1.21.1 mod plus a local Python bridge that lets an AI model inspect and control a Minecraft world through explicit tools.

## Current architecture

Minecraft mod -> local TCP bridge (`127.0.0.1:8765`) -> Python backend -> OpenAI Responses API -> browser chat (`127.0.0.1:8787`)

The Minecraft side currently exposes:

- `get_player_state`
- `get_block`
- `scan_area`
- `place_block`
- `break_block`
- `move_to` (prototype teleport tool)
- `send_chat`

The browser UI is intentionally simple for the first version. The tool system is the important base; pathfinding, inventory, containers, crafting, entities, screenshots and persistent memory will be added on top of it.

## Minecraft project

Minecraft 1.21.1
Fabric Loader 0.19.3
Fabric API 0.116.15+1.21.1
Java 21
Fabric Loom 1.9.2
Gradle 8.12

Build locally with:

```text
./gradlew build --no-daemon
```

In Minecraft, `/aiagent status` reports whether the local bridge has a client connected.

## Python backend

From `backend/`:

```text
python -m venv .venv
.venv\\Scripts\\activate
pip install -r requirements.txt
copy .env.example .env
```

Put your OpenAI API key in `.env`. The backend defaults to `gpt-5.6-luna`, but the model can be changed with `OPENAI_MODEL`.

Start the web server:

```text
python -m uvicorn main:app --host 127.0.0.1 --port 8787
```

Then open `http://127.0.0.1:8787/`.

Never put the OpenAI API key into the Minecraft mod jar or commit `.env`.

## Security note

The bridge listens only on the local loopback interface. Tool access is explicit and can be restricted further as the agent grows. Irreversible or dangerous Minecraft operations should not be exposed without an explicit permission layer.
