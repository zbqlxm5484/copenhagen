"""Test real MCP discovery and optionally capture one photo locally."""
import argparse
import asyncio
import base64
import json
from pathlib import Path

import cv2
import numpy as np
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

ROOT = Path(__file__).resolve().parent


async def check(reader, writer, capture: bool):
    async with ClientSession(reader, writer) as session:
        await session.initialize()
        tools = await session.list_tools()
        names = {tool.name for tool in tools.tools}
        assert names == {"camera_status", "capture_photo"}, names
        photo_tool = next(tool for tool in tools.tools if tool.name == "capture_photo")
        assert photo_tool.annotations.readOnlyHint is False
        status = await session.call_tool("camera_status", {})
        assert not status.isError
        invalid = await session.call_tool("capture_photo", {"camera_index": -1})
        assert invalid.isError, "Invalid index must be rejected before camera access"
        report = {"discovery": "passed", "status": "passed", "invalid_input": "rejected"}
        if capture:
            result = await session.call_tool("capture_photo", {})
            if result.isError:
                raise RuntimeError(str(result.content))
            photo = next(block for block in result.content if block.type == "image")
            data = base64.b64decode(photo.data)
            decoded = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
            assert decoded is not None
            output = ROOT / "artifacts" / "poc-capture.jpg"
            output.parent.mkdir(exist_ok=True)
            output.write_bytes(data)
            report.update(capture="passed", width=decoded.shape[1], height=decoded.shape[0],
                          bytes=len(data), saved_locally=str(output))
        print(json.dumps(report, ensure_ascii=False))


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture", action="store_true", help="Actually activate webcam once; save locally.")
    args = parser.parse_args()
    async with streamable_http_client("http://127.0.0.1:8765/mcp") as (reader, writer, _):
        await check(reader, writer, args.capture)


if __name__ == "__main__":
    asyncio.run(main())
