"""Short-lived camera process: stdout contains only a JPEG; stderr diagnostics."""
import argparse
import sys
import time

import cv2


def capture(index: int, width: int, height: int) -> bytes:
    backend = cv2.CAP_DSHOW if sys.platform == "win32" else cv2.CAP_ANY
    camera = cv2.VideoCapture(index, backend)
    try:
        if not camera.isOpened():
            raise RuntimeError("Camera could not open. Check Windows camera permissions, privacy shutter, and other camera apps.")
        camera.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        camera.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        deadline = time.monotonic() + 1.2
        frame = None
        while time.monotonic() < deadline or frame is None:
            ok, candidate = camera.read()
            if not ok:
                raise RuntimeError("Camera opened but did not return a frame.")
            frame = candidate
        h, w = frame.shape[:2]
        scale = min(1.0, width / w, height / h)
        if scale < 1:
            frame = cv2.resize(frame, (round(w * scale), round(h * scale)))
        ok, encoded = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if not ok:
            raise RuntimeError("JPEG encoding failed.")
        return encoded.tobytes()
    finally:
        camera.release()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", type=int, required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    args = parser.parse_args()
    try:
        sys.stdout.buffer.write(capture(args.index, args.width, args.height))
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
