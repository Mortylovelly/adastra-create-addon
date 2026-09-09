import json
import os
import socket
from typing import Any

import requests
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

load_dotenv()

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-5.6-luna").strip()
BRIDGE_HOST = os.getenv("MINECRAFT_BRIDGE_HOST", "127.0.0.1")
BRIDGE_PORT = int(os.getenv("MINECRAFT_BRIDGE_PORT", "8765"))

app = FastAPI(title="Minecraft AI Agent Bridge")

# One conversation per client session. The in-game panel supplies its own UUID,
# so opening/closing the GUI does not destroy the AI conversation.
SESSION_RESPONSES: dict[str, str] = {}

SYSTEM_PROMPT = """
You are the AI agent inside a Minecraft 1.21.1 world.
You have access to Minecraft through explicit tools. Never claim that an action succeeded unless a tool result confirms it.
Prefer inspecting the world before changing it. Keep changes targeted and explain what you are doing when useful.
Coordinates in the tools refer to the Minecraft overworld for this first version.
When a user asks you to build something, reason about the layout and then use place_block repeatedly.
When a user asks you to destroy something, inspect first and then use break_block.
When a user asks to give an item, use give_item instead of telling the player to run /give.
Do not use tools to perform harmful or irreversible actions unless the user explicitly requested them.
""".strip()

TOOLS = [
    {
        "type": "function",
        "name": "get_player_state",
        "description": "Get the current state of an online Minecraft player.",
        "parameters": {
            "type": "object",
            "properties": {
                "player": {"type": "string", "description": "Player name. Leave empty for the first online player."}
            },
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "get_block",
        "description": "Read the block at an exact overworld coordinate.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "z": {"type": "integer"},
            },
            "required": ["x", "y", "z"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "scan_area",
        "description": "Count blocks in a small cube around a coordinate. Radius is capped by the Minecraft bridge.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "z": {"type": "integer"},
                "radius": {"type": "integer", "minimum": 1, "maximum": 8},
            },
            "required": ["x", "y", "z", "radius"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "place_block",
        "description": "Place a block at an exact overworld coordinate. Use a valid Minecraft block ID such as minecraft:oak_planks.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "z": {"type": "integer"},
                "block": {"type": "string"},
            },
            "required": ["x", "y", "z", "block"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "break_block",
        "description": "Break the block at an exact overworld coordinate.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "z": {"type": "integer"},
            },
            "required": ["x", "y", "z"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "move_to",
        "description": "Teleport an online player to an exact overworld position. This is a prototype movement tool, not pathfinding.",
        "parameters": {
            "type": "object",
            "properties": {
                "player": {"type": "string"},
                "x": {"type": "number"},
                "y": {"type": "number"},
                "z": {"type": "number"},
                "yaw": {"type": "number"},
                "pitch": {"type": "number"},
            },
            "required": ["player", "x", "y", "z", "yaw", "pitch"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "give_item",
        "description": "Give an online Minecraft player an item directly into their inventory. Use a valid item ID such as minecraft:diamond_sword.",
        "parameters": {
            "type": "object",
            "properties": {
                "player": {"type": "string", "description": "Player name. Leave empty for the first online player."},
                "item": {"type": "string", "description": "Minecraft item ID, for example minecraft:diamond_sword."},
                "count": {"type": "integer", "minimum": 1, "maximum": 64},
            },
            "required": ["player", "item", "count"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "send_chat",
        "description": "Send a visible [AI] message to all online players.",
        "parameters": {
            "type": "object",
            "properties": {"message": {"type": "string"}},
            "required": ["message"],
            "additionalProperties": False,
        },
        "strict": True,
    },
]


class ChatRequest(BaseModel):
    message: str
    session_id: str = "default"


def bridge_call(tool: str, args: dict[str, Any]) -> dict[str, Any]:
    request = {"id": "http", "tool": tool, "args": args}
    try:
        with socket.create_connection((BRIDGE_HOST, BRIDGE_PORT), timeout=10) as sock:
            stream = sock.makefile("rwb")
            stream.write((json.dumps(request) + "\n").encode("utf-8"))
            stream.flush()
            line = stream.readline()
    except OSError as exc:
        raise HTTPException(status_code=503, detail=f"Minecraft bridge unavailable: {exc}") from exc

    if not line:
        raise HTTPException(status_code=503, detail="Minecraft bridge closed the connection")
    response = json.loads(line.decode("utf-8"))
    if not response.get("ok"):
        raise HTTPException(status_code=500, detail=response.get("error", "Minecraft tool failed"))
    return response.get("data", {})


def openai_response(payload: dict[str, Any]) -> dict[str, Any]:
    if not OPENAI_API_KEY:
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    response = requests.post(
        "https://api.openai.com/v1/responses",
        headers={
            "Authorization": f"Bearer {OPENAI_API_KEY}",
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=120,
    )
    if response.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"OpenAI API error {response.status_code}: {response.text[:1000]}")
    return response.json()


def run_agent(message: str, session_id: str) -> str:
    previous_response_id = SESSION_RESPONSES.get(session_id)
    payload: dict[str, Any] = {
        "model": OPENAI_MODEL,
        "instructions": SYSTEM_PROMPT,
        "input": message,
        "tools": TOOLS,
    }
    if previous_response_id:
        payload["previous_response_id"] = previous_response_id

    for _ in range(20):
        response = openai_response(payload)
        response_id = response.get("id")
        if response_id:
            SESSION_RESPONSES[session_id] = response_id

        function_calls = [item for item in response.get("output", []) if item.get("type") == "function_call"]

        if not function_calls:
            return response.get("output_text", "The model returned no text.")

        outputs = []
        for call in function_calls:
            try:
                arguments = json.loads(call.get("arguments", "{}"))
                result = bridge_call(call["name"], arguments)
                outputs.append({
                    "type": "function_call_output",
                    "call_id": call["call_id"],
                    "output": json.dumps(result, ensure_ascii=False),
                })
            except Exception as exc:
                outputs.append({
                    "type": "function_call_output",
                    "call_id": call.get("call_id", ""),
                    "output": json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False),
                })

        payload = {
            "model": OPENAI_MODEL,
            "instructions": SYSTEM_PROMPT,
            "previous_response_id": response.get("id"),
            "input": outputs,
            "tools": TOOLS,
        }

    raise HTTPException(status_code=500, detail="AI tool loop exceeded the safety limit")


@app.get("/health")
def health() -> dict[str, Any]:
    try:
        state = bridge_call("get_player_state", {})
        bridge = {"connected": True, "player": state.get("name")}
    except HTTPException:
        bridge = {"connected": False}
    return {"ok": True, "model": OPENAI_MODEL, "bridge": bridge}


@app.post("/chat")
def chat(request: ChatRequest) -> dict[str, str]:
    message = request.message.strip()
    session_id = request.session_id.strip() or "default"
    if not message:
        raise HTTPException(status_code=400, detail="message cannot be empty")
    return {"reply": run_agent(message, session_id)}


@app.get("/")
def index() -> FileResponse:
    return FileResponse(os.path.join(os.path.dirname(__file__), "web", "index.html"))
