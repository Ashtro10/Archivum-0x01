# Makes the camera clips for HandsUiTest (Chromium fake capture device, Y4M I420, 640x480, 30 frames per image).
# Images used: MediaPipe's own sample photos, not stored in this repository:
#   https://storage.googleapis.com/mediapipe-tasks/gesture_recognizer/pointing_up.jpg  -> point.y4m
#   https://storage.googleapis.com/mediapipe-tasks/gesture_recognizer/victory.jpg      -> victory.y4m
#   a plain grey 640x480 image                                                       -> none.y4m
# The photo is scaled to cover the frame (a hand close to a laptop webcam); fitted inside it with borders, the victory
# hand is ~110 px wide and the real model does not find it, a real limit for hands far from the camera.
# Usage: python3 y4m.py out.y4m image [image ...]
import sys, numpy as np
from PIL import Image
W, H = 640, 480
out = open(sys.argv[1], "wb"); out.write(f"YUV4MPEG2 W{W} H{H} F30:1 Ip A1:1 C420jpeg\n".encode())
for p in sys.argv[2:]:
    im = Image.open(p).convert("RGB"); k = max(W / im.width, H / im.height)  # cover: fill the frame like a webcam close up
    im = im.resize((round(im.width * k), round(im.height * k)), Image.LANCZOS); bg = im.crop(((im.width - W) // 2, (im.height - H) // 2, (im.width - W) // 2 + W, (im.height - H) // 2 + H))
    a = np.asarray(bg).astype(np.float32); r, g, b = a[..., 0], a[..., 1], a[..., 2]
    y = 0.299 * r + 0.587 * g + 0.114 * b; u = -0.169 * r - 0.331 * g + 0.5 * b + 128; v = 0.5 * r - 0.419 * g - 0.081 * b + 128
    sub = lambda c: c.reshape(H // 2, 2, W // 2, 2).mean(axis=(1, 3))
    frame = b"FRAME\n" + np.clip(y, 0, 255).astype(np.uint8).tobytes() + np.clip(sub(u), 0, 255).astype(np.uint8).tobytes() + np.clip(sub(v), 0, 255).astype(np.uint8).tobytes()
    for _ in range(30): out.write(frame)
out.close()
