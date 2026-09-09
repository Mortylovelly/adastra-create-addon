# Minecraft AI Agent

Fabric 1.21.1 AI-agent mod with a real in-game chat panel. The old Forge 1.20.1 Ad Astra/Create addon has been removed.

Press the backtick (`) key by default to open the panel. Type natural-language requests; the AI can call explicit Minecraft tools directly instead of typing Minecraft commands into chat.

Examples:
- Выдай мне алмазный меч
- Построй небольшой дом рядом со мной
- Осмотри место вокруг меня
- Сломай этот блок

Current tools: get_player_state, get_inventory, get_block, scan_area, place_block, fill_area, break_block, give_item, move_to, send_chat.

The model can chain tools and receives each tool result before continuing. For example, a house task can inspect the player position, plan the structure, fill large rectangular sections, place details, verify results and then answer in the same panel. An item request uses give_item directly.

Architecture: Minecraft in-game panel -> local Python backend -> OpenAI Responses API -> tool calls -> Minecraft.

The mod bridge listens on 127.0.0.1:8765. The Python backend listens on 127.0.0.1:8787. Conversation state is kept per panel session.

Minecraft 1.21.1
Fabric Loader 0.19.3
Fabric API 0.116.15+1.21.1
Java 21
Fabric Loom 1.9.2
Gradle 8.12

Windows setup:
1. Install Python 3.11+.
2. Run backend/start.bat.
3. Put OPENAI_API_KEY=... into backend/.env when prompted.
4. Run backend/start.bat again and leave it running.
5. Install the built minecraft-ai-agent jar in Minecraft 1.21.1 Fabric.
6. Open the in-game panel with the ` key.

The default backend model is gpt-5.6-luna and can be changed with OPENAI_MODEL in backend/.env. Never commit backend/.env or put the API key in the jar.

Current limitation: this is a direct tool agent, not yet a full human-like player. Walking/pathfinding, realistic mining, containers, crafting, combat, entity control, screenshots and granular permission controls are later extensions.
