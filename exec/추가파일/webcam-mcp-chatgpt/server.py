"""Private, local webcam MCP over loopback Streamable HTTP."""
import asyncio
import base64
from datetime import datetime, timezone
from pathlib import Path
import sys
import subprocess
from typing import Annotated

from mcp.server.fastmcp import FastMCP
from mcp.types import ImageContent, TextContent, ToolAnnotations
from pydantic import Field

ROOT = Path(__file__).resolve().parent
capture_lock = asyncio.Lock()


async def terminate_worker(process):
    # Windows venv python.exe can be a launcher with its own Python child.
    if sys.platform == "win32":
        await asyncio.to_thread(subprocess.run,
                                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=5, check=False)
    if process.returncode is None:
        try:
            process.kill()
        except ProcessLookupError:
            pass
    await process.wait()


mcp = FastMCP(
    "Laptop Webcam PoC",
    instructions=("This server controls the camera on the owner's laptop. "
                  "Only call capture_photo when the user explicitly requests a photo. "
                  "The photo is returned to this conversation for viewing or analysis. "
                  "Do not take photos in the background or retry a successful capture."),
    host="127.0.0.1", port=8765,
    stateless_http=True, json_response=True,
)


@mcp.tool(annotations=ToolAnnotations(readOnlyHint=True, openWorldHint=False))
def camera_status() -> dict:
    """Check server readiness without activating the camera. Does not test hardware."""
    return {"server": "Laptop Webcam PoC", "ready": True,
            "camera_busy": capture_lock.locked(), "camera_tested": False,
            "storage": "Photos are returned in memory; server does not save photos.",
            "transport": "Local Streamable HTTP: http://127.0.0.1:8765/mcp"}


@mcp.tool(annotations=ToolAnnotations(
    readOnlyHint=False, destructiveHint=False, idempotentHint=False, openWorldHint=False,
))
async def capture_photo(
    camera_index: Annotated[int, Field(ge=0, le=5, description="Camera index; use 0 for the default laptop webcam.")] = 0,
    width: Annotated[int, Field(ge=320, le=1920)] = 1280,
    height: Annotated[int, Field(ge=240, le=1080)] = 720,
) -> list[TextContent | ImageContent]:
    """Take ONE webcam photo now and return a JPEG image to ChatGPT.

    Use only when the user explicitly asks to take a photo with their laptop webcam.
    Activates the camera briefly, then releases it. No audio, recording, or disk storage.
    The requested resolution is an upper bound; hardware may return a smaller image.
    """
    if capture_lock.locked():
        raise ValueError("Camera is busy. Wait for the current capture to finish.")
    async with capture_lock:
        process = await asyncio.create_subprocess_exec(
            sys.executable, str(ROOT / "camera_worker.py"),
            "--index", str(camera_index), "--width", str(width), "--height", str(height),
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        try:
            try:
                jpeg, stderr = await asyncio.wait_for(process.communicate(), timeout=15)
            except asyncio.TimeoutError:
                raise ValueError("Camera timed out after 15 seconds. Check permissions and other camera apps.") from None
        finally:
            if process.returncode is None:
                await terminate_worker(process)
        if process.returncode != 0:
            details = stderr.decode("utf-8", errors="replace")[-1500:]
            raise ValueError(f"Capture failed: {details}")
        if not jpeg.startswith(b"\xff\xd8") or not jpeg.endswith(b"\xff\xd9"):
            raise ValueError("Camera returned an invalid JPEG.")
        captured_at = datetime.now(timezone.utc).isoformat()
        return [TextContent(type="text", text=f"One webcam photo captured at {captured_at}. Camera released. No photo saved by server."),
                ImageContent(type="image", data=base64.b64encode(jpeg).decode("ascii"), mimeType="image/jpeg")]


if __name__ == "__main__":
    mcp.run(transport="streamable-http")
