# Minecraft AI Agent

This repository is now a Minecraft 1.21.1 Fabric AI-agent project. The old Forge 1.20.1 Ad Astra/Create addon has been removed.

The mod adds an in-game AI panel opened with the ` key by default. You can type natural-language requests such as:

- Выдай мне алмазный меч
- Построй небольшой дом рядом со мной
- Осмотри место вокруг меня
- Сломай этот блок

The AI receives explicit Minecraft tools and can call them itself. It does not need to type `/give` or `/setblock` into the normal Minecraft chat.

## Current tools

- `get_player_state` — coordinates, health, food and dimension
- `get_inventory` — current inventory contents
- `get_block` — inspect a block
- `scan_area` — inspect a small area
- `place_block` — place one block
- `fill_area` — fill a rectangular area, up to 4096 blocks per call
- `break_block` — break one block
- `give_item` — insert an item directly into the player's inventory
- `move_to` — teleport, only for explicit teleport requests
- `send_chat` — announce in Minecraft chat, only for explicit requests

For a building task the model can inspect the area, plan the structure, use `fill_area` for large sections, use `place_block` for details and then verify the result.

## Architecture

`Minecraft in-game panel -> local Python backend -> OpenAI Responses API -> tool calls -> Minecraft`

The Minecraft mod hosts a loopback TCP tool bridge on `127.0.0.1:8765`. The Python backend runs on `127.0.0.1:8787` and maintains a conversation session so the AI keeps context between messages and between openings of the panel during the same game session.

OpenAI's Responses API supports custom function tools, allowing the model to request Minecraft actions and receive the results before continuing the task.

## Minecraft

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

GitHub Actions builds the mod automatically on pushes to `main`.

## Windows setup

1. Install Python 3.11+ and make sure `py` works in Command Prompt.
2. Open the repository's `backend` directory and run `start.bat`.
3. The first run creates `.venv` and `.env`.
4. Put your OpenAI API key into `backend/.env` as `OPENAI_API_KEY=...`.
5. Run `start.bat` again and leave that window running.
6. Start Minecraft with the built mod installed.
7. Press the ` key to open the AI panel and type a request.

The backend defaults to `gpt-5.6-luna`; set `OPENAI_MODEL` in `.env` to use another model.

Never commit `.env` and never place the OpenAI API key inside the Minecraft jar.

## Important limitation

This is the first real agent layer, not yet a human-like autonomous player. Direct world tools are already present, but walking/pathfinding, actual mining, containers, crafting, combat, entity control, screenshots and more advanced permissions are the next layer.
