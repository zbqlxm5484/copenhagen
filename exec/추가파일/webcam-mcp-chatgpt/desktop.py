"""Local desktop client. All captures go through the MCP endpoint."""
import asyncio
import base64
from datetime import datetime
import os
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import ttk

import cv2
import numpy as np
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

ROOT = Path(__file__).resolve().parent


async def request_photo(index):
    async with streamable_http_client('http://127.0.0.1:8765/mcp') as (reader, writer, _):
        async with ClientSession(reader, writer) as session:
            await session.initialize()
            result = await session.call_tool('capture_photo', {'camera_index': index})
            if result.isError:
                raise RuntimeError('\n'.join(x.text for x in result.content if x.type == 'text'))
            block = next(x for x in result.content if x.type == 'image')
            jpeg = base64.b64decode(block.data)
            frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
            if frame is None:
                raise RuntimeError('사진 디코딩에 실패했습니다.')
            folder = ROOT / 'photos'
            folder.mkdir(exist_ok=True)
            path = folder / (datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.jpg')
            path.write_bytes(jpeg)
            h, w = frame.shape[:2]
            scale = min(860 / w, 460 / h, 1)
            preview = cv2.resize(frame, (round(w * scale), round(h * scale)))
            ok, png = cv2.imencode('.png', preview)
            if not ok:
                raise RuntimeError('사진은 저장됐지만 미리보기 생성에 실패했습니다.')
            return path, base64.b64encode(png.tobytes()).decode('ascii'), w, h


def main():
    window = tk.Tk()
    window.title('노트북 웹캠 MCP · 로컬 촬영')
    window.geometry('940x680')
    window.minsize(700, 560)
    style = ttk.Style()
    style.configure('TButton', font=('맑은 고딕', 11), padding=8)
    frame = ttk.Frame(window, padding=20)
    frame.pack(fill='both', expand=True)
    ttk.Label(frame, text='노트북 웹캠 촬영', font=('맑은 고딕', 22, 'bold')).pack(anchor='w')
    ttk.Label(frame, text='촬영 버튼을 누르면 사진 한 장을 찍습니다. 사진은 이 컴퓨터에만 저장됩니다.', font=('맑은 고딕', 10)).pack(anchor='w', pady=(8, 12))
    controls = ttk.Frame(frame)
    controls.pack(fill='x')
    ttk.Label(controls, text='카메라 번호').pack(side='left')
    index = tk.StringVar(value='0')
    selector = ttk.Combobox(controls, textvariable=index, values=list(range(6)), width=4, state='readonly')
    selector.pack(side='left', padx=8)
    events = queue.Queue()
    status = tk.StringVar(value='준비 완료 · 카메라 0부터 사용하세요. 실시간 미리보기는 없습니다.')
    def worker(selected):
        try:
            events.put(('ok', asyncio.run(request_photo(selected))))
        except BaseException as exc:
            def details(error):
                nested = getattr(error, 'exceptions', None)
                return '\n'.join(details(e) for e in nested) if nested else str(error)
            events.put(('error', details(exc)))
    def capture():
        button.configure(state='disabled')
        selector.configure(state='disabled')
        status.set('촬영 중… 카메라를 바라봐 주세요. 최대 약 15초 걸릴 수 있습니다.')
        threading.Thread(target=worker, args=(int(index.get()),), daemon=True).start()
    button = ttk.Button(controls, text='사진 한 장 촬영', command=capture)
    button.pack(side='left', padx=8)
    def open_folder():
        folder = ROOT / 'photos'
        folder.mkdir(exist_ok=True)
        os.startfile(folder)
    ttk.Button(controls, text='저장 폴더 열기', command=open_folder).pack(side='left')
    preview = ttk.Label(frame, text='촬영한 사진이 여기에 표시됩니다.', anchor='center')
    preview.pack(fill='both', expand=True, pady=12)
    ttk.Label(frame, textvariable=status, wraplength=880, font=('맑은 고딕', 10)).pack(fill='x')
    ttk.Label(frame, text='MCP: 127.0.0.1:8765 · 외부 배포/터널/AI 분석 없음', foreground='#666666').pack(anchor='w', pady=(10, 0))
    def poll():
        try:
            kind, result = events.get_nowait()
        except queue.Empty:
            pass
        else:
            button.configure(state='normal')
            selector.configure(state='readonly')
            if kind == 'ok':
                path, data, w, h = result
                photo = tk.PhotoImage(data=data)
                preview.configure(image=photo, text='')
                preview.image = photo
                status.set(f'촬영 완료 · {w}×{h} · 저장: {path.name}')
            else:
                status.set('촬영 실패 (표시 중인 사진은 이전 결과일 수 있습니다): ' + result[:650])
        window.after(100, poll)
    poll()
    window.mainloop()


if __name__ == '__main__':
    main()
