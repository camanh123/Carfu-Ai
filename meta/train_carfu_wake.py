#!/usr/bin/env python3
"""Train a compact OpenWakeWord-compatible DNN for the CARFU wake phrase.

Replicates Dicio's OwwModel frontend (melspectrogram + speech embedding) and
trains a [1,16,96] -> [1,1] classifier on espeak-ng samples of CARFU / CARFU ơi
plus negative phrases. Output is a TFLite file loadable by OwwModel.
"""
from __future__ import annotations

import os
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np
import tensorflow as tf

ROOT = Path(__file__).resolve().parents[1]
OWW_DIR = Path("/tmp/oww")
OUT_TFLITE = ROOT / "app/src/main/assets/openwakeword/carfu.tflite"

MEL_INPUT_COUNT = 512 + 160 * 4  # 1152 samples @ 16 kHz
MEL_OUTPUT_COUNT = (MEL_INPUT_COUNT - 512) // 160 + 1  # 5
EMB_INPUT_COUNT = 76
WAKE_INPUT_COUNT = 16
SAMPLE_RATE = 16000

POSITIVE_PHRASES = [
    ("en", "carfu"),
    ("en", "car fu"),
    ("en", "car foo"),
    ("en", "hey carfu"),
    ("en", "carfu oi"),
    ("en", "hello carfu"),
    ("en-us", "carfu"),
    ("en-gb", "carfu"),
    ("vi", "carfu"),
    ("vi", "cà phu"),
    ("vi", "cà phu ơi"),
    ("vi", "carfu ơi"),
    ("vi", "xin chào carfu"),
    ("vi", "ơ carfu"),
]

NEGATIVE_PHRASES = [
    ("en", "hey dicio"),
    ("en", "hey siri"),
    ("en", "okay google"),
    ("en", "hello there"),
    ("en", "open maps"),
    ("en", "what time is it"),
    ("en", "play music"),
    ("en", "next song"),
    ("en", "youtube"),
    ("en", "navigation"),
    ("vi", "mở nhạc"),
    ("vi", "tăng âm lượng"),
    ("vi", "mấy giờ rồi"),
    ("vi", "chỉ đường"),
    ("vi", "xin chào"),
    ("vi", "dừng nhạc"),
    ("vi", "bài tiếp"),
]


def synthesize(voice: str, text: str, speed: int, pitch: int) -> np.ndarray:
    with tempfile.TemporaryDirectory() as td:
        raw = Path(td) / "raw.wav"
        out = Path(td) / "16k.wav"
        subprocess.run(
            [
                "espeak-ng",
                "-v",
                voice,
                "-s",
                str(speed),
                "-p",
                str(pitch),
                "-w",
                str(raw),
                text,
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        subprocess.run(
            [
                "ffmpeg",
                "-y",
                "-i",
                str(raw),
                "-ar",
                str(SAMPLE_RATE),
                "-ac",
                "1",
                "-sample_fmt",
                "s16",
                str(out),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        with wave.open(str(out), "rb") as wf:
            frames = wf.readframes(wf.getnframes())
            audio = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
    # Pad so the OWW frontend can fill 76 mel frames + 16 embeddings.
    pad = int(1.4 * SAMPLE_RATE)
    return np.concatenate([np.zeros(pad, dtype=np.float32), audio, np.zeros(pad, dtype=np.float32)])


class OwwFrontend:
    def __init__(self, mel_path: Path, emb_path: Path) -> None:
        self.mel = tf.lite.Interpreter(model_path=str(mel_path))
        self.mel.resize_tensor_input(0, [1, MEL_INPUT_COUNT])
        self.mel.allocate_tensors()
        self.emb = tf.lite.Interpreter(model_path=str(emb_path))
        self.emb.allocate_tensors()
        self.mel_in = self.mel.get_input_details()[0]["index"]
        self.mel_out = self.mel.get_output_details()[0]["index"]
        self.emb_in = self.emb.get_input_details()[0]["index"]
        self.emb_out = self.emb.get_output_details()[0]["index"]

    def embeddings(self, audio: np.ndarray) -> np.ndarray:
        """Return all 96-dim embeddings produced while sliding over audio."""
        acc_mel: list[np.ndarray] = []
        embs: list[np.ndarray] = []
        offset = 0
        while offset + MEL_INPUT_COUNT <= len(audio):
            frame = audio[offset : offset + MEL_INPUT_COUNT]
            self.mel.set_tensor(self.mel_in, frame.reshape(1, MEL_INPUT_COUNT))
            self.mel.invoke()
            mel_out = self.mel.get_tensor(self.mel_out)[0, 0]  # [5, 32]
            scaled = (mel_out / 10.0) + 2.0
            acc_mel.extend(scaled)
            acc_mel = acc_mel[-EMB_INPUT_COUNT:]
            if len(acc_mel) == EMB_INPUT_COUNT:
                stacked = np.stack(acc_mel, axis=0).reshape(1, EMB_INPUT_COUNT, 32, 1)
                self.emb.set_tensor(self.emb_in, stacked.astype(np.float32))
                self.emb.invoke()
                embs.append(self.emb.get_tensor(self.emb_out).reshape(96))
            offset += MEL_INPUT_COUNT
        if not embs:
            return np.zeros((0, 96), dtype=np.float32)
        return np.stack(embs, axis=0)


def windows_from_embeddings(embs: np.ndarray) -> list[np.ndarray]:
    if len(embs) < WAKE_INPUT_COUNT:
        return []
    # Take several windows near the end, where the spoken phrase sits after padding.
    starts = {len(embs) - WAKE_INPUT_COUNT}
    if len(embs) > WAKE_INPUT_COUNT + 2:
        starts.add(len(embs) - WAKE_INPUT_COUNT - 1)
        starts.add(max(0, len(embs) - WAKE_INPUT_COUNT - 2))
    return [embs[s : s + WAKE_INPUT_COUNT] for s in sorted(starts)]


def collect_windows(frontend: OwwFrontend, phrases, speeds, pitches) -> np.ndarray:
    collected = []
    for voice, text in phrases:
        for speed in speeds:
            for pitch in pitches:
                try:
                    audio = synthesize(voice, text, speed, pitch)
                except subprocess.CalledProcessError:
                    continue
                embs = frontend.embeddings(audio)
                collected.extend(windows_from_embeddings(embs))
    if not collected:
        raise RuntimeError(f"No windows collected for {phrases[:3]}...")
    return np.stack(collected, axis=0).astype(np.float32)


def noise_windows(n: int, rng: np.random.Generator) -> np.ndarray:
    return rng.normal(0.0, 0.35, size=(n, WAKE_INPUT_COUNT, 96)).astype(np.float32)


class CarfuNet(tf.Module):
    """Small DNN matching OpenWakeWord's (1,16,96) -> (1,1) classifier I/O."""

    def __init__(self) -> None:
        super().__init__()
        self.w1 = tf.Variable(tf.random.normal([1536, 32], stddev=0.05), name="w1")
        self.b1 = tf.Variable(tf.zeros([32]), name="b1")
        self.w2 = tf.Variable(tf.random.normal([32, 32], stddev=0.05), name="w2")
        self.b2 = tf.Variable(tf.zeros([32]), name="b2")
        self.w3 = tf.Variable(tf.random.normal([32, 1], stddev=0.05), name="w3")
        self.b3 = tf.Variable(tf.zeros([1]), name="b3")

    @tf.function(input_signature=[tf.TensorSpec([None, WAKE_INPUT_COUNT, 96], tf.float32)])
    def score(self, x: tf.Tensor) -> tf.Tensor:
        h = tf.reshape(x, [-1, 1536])
        h = tf.nn.relu(tf.matmul(h, self.w1) + self.b1)
        h = tf.nn.relu(tf.matmul(h, self.w2) + self.b2)
        return tf.nn.sigmoid(tf.matmul(h, self.w3) + self.b3)

    @tf.function(input_signature=[tf.TensorSpec([1, WAKE_INPUT_COUNT, 96], tf.float32)])
    def infer(self, x: tf.Tensor) -> tf.Tensor:
        return self.score(x)


def train_net(x: np.ndarray, y: np.ndarray, epochs: int = 40) -> CarfuNet:
    net = CarfuNet()
    opt = tf.keras.optimizers.Adam(1e-3)
    ds = tf.data.Dataset.from_tensor_slices((x, y)).shuffle(len(x)).batch(32)
    for epoch in range(epochs):
        loss_avg = []
        acc_avg = []
        for xb, yb in ds:
            with tf.GradientTape() as tape:
                pred = net.score(xb)
                loss = tf.reduce_mean(
                    tf.keras.losses.binary_crossentropy(yb, pred)
                )
            opt.apply_gradients(
                zip(tape.gradient(loss, net.trainable_variables), net.trainable_variables)
            )
            loss_avg.append(float(loss))
            acc_avg.append(float(tf.reduce_mean(tf.cast((pred > 0.5) == (yb > 0.5), tf.float32))))
        if (epoch + 1) % 5 == 0 or epoch == 0:
            print(f"epoch {epoch + 1:02d} loss={np.mean(loss_avg):.4f} acc={np.mean(acc_avg):.3f}")
    return net


def export_tflite(net: CarfuNet, path: Path) -> None:
    concrete = net.infer.get_concrete_function()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete], net)
    tflite_model = converter.convert()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(tflite_model)
    print(f"Wrote {path} ({path.stat().st_size} bytes)")


def main() -> None:
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    frontend = OwwFrontend(
        OWW_DIR / "melspectrogram.tflite",
        OWW_DIR / "embedding_model.tflite",
    )
    print("Collecting positive windows...")
    pos = collect_windows(
        frontend, POSITIVE_PHRASES, speeds=(120, 140, 165), pitches=(40, 50, 65)
    )
    print("Collecting negative windows...")
    neg_speech = collect_windows(
        frontend, NEGATIVE_PHRASES, speeds=(130, 155), pitches=(45, 60)
    )
    rng = np.random.default_rng(7)
    neg_noise = noise_windows(max(len(pos) // 2, 32), rng)
    neg = np.concatenate([neg_speech, neg_noise], axis=0)

    x = np.concatenate([pos, neg], axis=0)
    y = np.concatenate(
        [np.ones((len(pos), 1), np.float32), np.zeros((len(neg), 1), np.float32)],
        axis=0,
    )
    idx = rng.permutation(len(x))
    x, y = x[idx], y[idx]
    print(f"Train set: {len(pos)} positive, {len(neg)} negative")

    net = train_net(x, y)
    export_tflite(net, OUT_TFLITE)

    interp = tf.lite.Interpreter(model_path=str(OUT_TFLITE))
    interp.allocate_tensors()
    print("tflite in", interp.get_input_details()[0]["shape"])
    print("tflite out", interp.get_output_details()[0]["shape"])


if __name__ == "__main__":
    main()
