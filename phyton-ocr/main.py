from __future__ import annotations

import re
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

import cv2
import easyocr
import numpy as np
from fastapi import FastAPI, File, UploadFile, Query
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse


# ----------------------------
# App & CORS
# ----------------------------
app = FastAPI(title="OCR Service (Robust Fast)", version="3.1")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ----------------------------
# OCR Reader (global cache)
# ----------------------------
# Uzbek Latin ID card => 'en' ko'p holatda yetadi.
# 'ru' ba'zan font/artefactlarda yordam beradi.
reader = easyocr.Reader(['en', 'ru'], gpu=False)


@app.get("/")
def health():
    return {"status": "ok"}


# ----------------------------
# JSON-safe conversions
# ----------------------------
def _json_safe(x: Any) -> Any:
    """Convert numpy types to JSON-serializable python types (int/float/list)."""
    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, (np.floating,)):
        return float(x)
    if isinstance(x, np.ndarray):
        return x.tolist()
    if isinstance(x, (list, tuple)):
        return [_json_safe(i) for i in x]
    if isinstance(x, dict):
        return {k: _json_safe(v) for k, v in x.items()}
    return x


# ----------------------------
# Text normalize
# ----------------------------
def _normalize_text(t: str) -> str:
    if not t:
        return ""
    t = t.replace("`", "'").replace("’", "'").replace("‘", "'")
    t = t.replace("“", '"').replace("”", '"')
    t = re.sub(r"\s+", " ", t).strip()
    return t


# ----------------------------
# OCR keyword scoring
# ----------------------------
KEYWORDS = [
    # === ID Karta ===
    "O'ZBEKISTON", "OZBEKISTON", "RESPUBLIKASI", "SHAXS", "GUVOHNOMASI",
    "SURNAME", "GIVEN", "DATE OF BIRTH", "CARD NUMBER", "CITIZENSHIP",
    "JINSI", "SEX", "PATRONYMIC", "FAMILIYASI", "ISM", "OTASINING",
    "IDENTITY CARD", "KARTA RAQAMI",
    # === Passport ===
    "PASSPORT", "PASPORT", "COUNTRY CODE", "UZB", "PLACE OF BIRTH",
    "DATE OF ISSUE", "DATE OF EXPIRY", "AUTHORITY", "FUQAROLIGI",
    "TUG'ILGAN", "BERILGAN", "AMAL QILISH",
    # === Haydovchilik guvohnomasi ===
    "HAYDOVCHILIK", "DRIVING", "LICENCE", "LICENSE",
    "VODITELSKOE", "UDOSTOVERENIE",
    # === MRZ ===
    "P<UZB",
]


def _score_text(text: str, avg_conf: float) -> float:
    t = (text or "").upper()

    kw = sum(1 for k in KEYWORDS if k in t)
    dates = len(re.findall(r"\b\d{2}[./\-]\d{2}[./\-]\d{4}\b", t))
    cardno = 1 if re.search(r"\b[A-Z]{2}\s?\d{7}\b", t) else 0
    caps_words = len(re.findall(r"\b[A-Z'`]{5,}\b", t))
    # MRZ: P<UZB borligi kuchli signal
    mrz_bonus = 5.0 if re.search(r"P<UZB[A-Z]+", t) else 0.0
    # PINFL (14 raqam) — haydovchilik uchun kuchli signal
    pinfl_bonus = 3.0 if re.search(r"\b\d{14}\b", t) else 0.0

    # weightlar: keyword+date+cardno muhim
    return (kw * 1.6) + (dates * 2.2) + (cardno * 3.2) + (caps_words * 0.10) + (avg_conf * 6.0) + mrz_bonus + pinfl_bonus


# ----------------------------
# OCR read (crash-proof)
# ----------------------------
def _ocr_read(img: np.ndarray) -> Tuple[str, float, List[Tuple[Any, str, float]]]:
    """
    Safe EasyOCR wrapper:
    - format farqi bo'lsa ham unpack qilmay yiqilmaydi
    - JSON-safe items qaytaradi
    """
    items_raw = reader.readtext(
        img,
        detail=1,
        paragraph=False,      # tezroq
        batch_size=8,
        text_threshold=0.50,  # past sifatli + plyonkali suratlar uchun 0.65->0.50
        low_text=0.30,
        link_threshold=0.4,
        mag_ratio=1.5,        # kattalashtirish: ko'proq matn
        contrast_ths=0.05,    # kontrastni kuchaytirish
        adjust_contrast=0.7,
    )

    texts: List[str] = []
    confs: List[float] = []
    cleaned_items: List[Tuple[Any, str, float]] = []

    for it in items_raw:
        # EasyOCR odatda: (bbox, text, conf)
        if not isinstance(it, (list, tuple)) or len(it) < 2:
            continue

        bbox = it[0]
        txt = it[1] if len(it) >= 2 else ""
        conf = it[2] if len(it) >= 3 else 0.0

        txt = (txt or "").strip()
        if not txt:
            continue

        txt2 = _normalize_text(txt)
        conf_f = float(conf) if conf is not None else 0.0

        texts.append(txt2)
        confs.append(conf_f)
        cleaned_items.append((_json_safe(bbox), txt2, float(conf_f)))

    joined = _normalize_text(" ".join(texts))
    avg_conf = float(sum(confs) / len(confs)) if confs else 0.0
    return joined, avg_conf, cleaned_items


# ----------------------------
# Geometry helpers
# ----------------------------
def _order_points(pts: np.ndarray) -> np.ndarray:
    rect = np.zeros((4, 2), dtype="float32")
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]  # tl
    rect[2] = pts[np.argmax(s)]  # br
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]  # tr
    rect[3] = pts[np.argmax(diff)]  # bl
    return rect


def _four_point_transform(image: np.ndarray, pts: np.ndarray) -> np.ndarray:
    rect = _order_points(pts)
    (tl, tr, br, bl) = rect

    widthA = np.linalg.norm(br - bl)
    widthB = np.linalg.norm(tr - tl)
    maxW = max(int(widthA), int(widthB), 10)

    heightA = np.linalg.norm(tr - br)
    heightB = np.linalg.norm(tl - bl)
    maxH = max(int(heightA), int(heightB), 10)

    dst = np.array(
        [[0, 0], [maxW - 1, 0], [maxW - 1, maxH - 1], [0, maxH - 1]],
        dtype="float32"
    )
    M = cv2.getPerspectiveTransform(rect, dst)
    warped = cv2.warpPerspective(image, M, (maxW, maxH))
    return warped


def _ensure_landscape(img_bgr: np.ndarray) -> np.ndarray:
    if img_bgr.shape[0] > img_bgr.shape[1]:
        return cv2.rotate(img_bgr, cv2.ROTATE_90_CLOCKWISE)
    return img_bgr


def _resize_for_text(img: np.ndarray, min_w: int = 1100) -> np.ndarray:
    h, w = img.shape[:2]
    if w >= min_w:
        return img
    scale = float(min_w) / float(w)
    return cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)


# ----------------------------
# Card detection (3 strategies)
# ----------------------------
@dataclass
class CandidateQuad:
    pts: np.ndarray  # (4,2)
    score: float
    method: str


def _score_quad(img_shape, quad_pts, area_bonus=1.0) -> float:
    h, w = img_shape[:2]
    img_area = float(h * w)

    area = float(cv2.contourArea(quad_pts.reshape(-1, 1, 2).astype(np.float32)))
    if area <= 0:
        return -1e9

    rect = cv2.minAreaRect(quad_pts.astype(np.float32))
    rw, rh = rect[1]
    if rw <= 1 or rh <= 1:
        return -1e9

    ar = max(rw, rh) / min(rw, rh)
    target_ar = 1.585  # ID-1 approx
    ar_penalty = abs(ar - target_ar)

    area_ratio = area / img_area

    size_bonus = 0.0
    if area_ratio > 0.02:
        size_bonus += 1.0
    if area_ratio > 0.05:
        size_bonus += 1.0
    if area_ratio > 0.20:
        size_bonus -= 0.5

    return (area_ratio * 10.0 * area_bonus) + size_bonus - (ar_penalty * 2.5)


def _detect_by_saturation(img_bgr: np.ndarray) -> Optional[CandidateQuad]:
    hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)
    s = hsv[:, :, 1]
    v = hsv[:, :, 2]

    mask_s = cv2.inRange(s, 22, 255)
    mask_v = cv2.inRange(v, 35, 255)
    mask = cv2.bitwise_and(mask_s, mask_v)

    mask = cv2.medianBlur(mask, 7)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8), iterations=2)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((9, 9), np.uint8), iterations=1)

    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None

    best = None
    for c in sorted(cnts, key=cv2.contourArea, reverse=True)[:10]:
        area = cv2.contourArea(c)
        if area < (img_bgr.shape[0] * img_bgr.shape[1]) * 0.01:
            continue
        rect = cv2.minAreaRect(c)
        box = cv2.boxPoints(rect).astype(np.float32)
        score = _score_quad(img_bgr.shape, box, area_bonus=1.1)
        if best is None or score > best.score:
            best = CandidateQuad(pts=box, score=score, method="saturation")
    return best


def _detect_by_edges_quad(img_bgr: np.ndarray) -> Optional[CandidateQuad]:
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)

    edges = cv2.Canny(gray, 50, 150)
    edges = cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=2)
    edges = cv2.erode(edges, np.ones((3, 3), np.uint8), iterations=1)

    cnts, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None

    best = None
    for c in sorted(cnts, key=cv2.contourArea, reverse=True)[:20]:
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(approx) == 4:
            quad = approx.reshape(4, 2).astype(np.float32)
            score = _score_quad(img_bgr.shape, quad, area_bonus=1.0)
            if best is None or score > best.score:
                best = CandidateQuad(pts=quad, score=score, method="edges_quad")
    return best


def _detect_by_minarearect(img_bgr: np.ndarray) -> Optional[CandidateQuad]:
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    thr = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                cv2.THRESH_BINARY_INV, 31, 7)
    thr = cv2.morphologyEx(thr, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8), iterations=2)

    cnts, _ = cv2.findContours(thr, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None

    best = None
    for c in sorted(cnts, key=cv2.contourArea, reverse=True)[:15]:
        area = cv2.contourArea(c)
        if area < (img_bgr.shape[0] * img_bgr.shape[1]) * 0.015:
            continue
        rect = cv2.minAreaRect(c)
        box = cv2.boxPoints(rect).astype(np.float32)
        score = _score_quad(img_bgr.shape, box, area_bonus=0.9)
        if best is None or score > best.score:
            best = CandidateQuad(pts=box, score=score, method="minarearect")
    return best


def _detect_best_card(img_bgr: np.ndarray) -> Optional[CandidateQuad]:
    cands = []
    for fn in (_detect_by_saturation, _detect_by_edges_quad, _detect_by_minarearect):
        cand = fn(img_bgr)
        if cand is not None:
            cands.append(cand)

    if not cands:
        return None
    return max(cands, key=lambda c: c.score)


# ----------------------------
# Preprocess variants (fast & effective)
# ----------------------------
def _clahe_gray(img_bgr: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    return clahe.apply(gray)


def _unsharp(gray: np.ndarray) -> np.ndarray:
    blur = cv2.GaussianBlur(gray, (0, 0), 1.2)
    sharp = cv2.addWeighted(gray, 1.7, blur, -0.7, 0)
    return sharp


def _build_ocr_images_fast(img_bgr: np.ndarray) -> List[Tuple[str, np.ndarray]]:
    out = [("bgr", img_bgr)]
    g = _clahe_gray(img_bgr)
    out.append(("clahe", g))
    out.append(("clahe_unsharp", _unsharp(g)))
    return out


def _try_rotations_fast(img: np.ndarray) -> List[Tuple[str, np.ndarray]]:
    # ensure_landscape() dan keyin ko'p holatda rot0 kifoya,
    # fallback uchun rot180 qoldiramiz.
    return [
        ("rot0", img),
        ("rot180", cv2.rotate(img, cv2.ROTATE_180)),
    ]


# ----------------------------
# Time budget helper
# ----------------------------
def _now_ms() -> int:
    return int(time.time() * 1000)


def _elapsed_ms(start_ms: int) -> int:
    return _now_ms() - start_ms


def _pick_best_with_budget(
    warped: np.ndarray,
    start_ms: int,
    budget_ms: int,
) -> Dict[str, Any]:
    best_pack = {"score": -1e9, "text": "", "avgConf": 0.0, "variant": "none", "items": []}

    def out_of_time() -> bool:
        return _elapsed_ms(start_ms) > budget_ms

    for rname, rimg in _try_rotations_fast(warped):
        if out_of_time():
            break

        for pname, pimg in _build_ocr_images_fast(rimg):
            if out_of_time():
                break

            text, conf, items = _ocr_read(pimg)
            score = _score_text(text, conf)

            if score > best_pack["score"]:
                best_pack = {
                    "variant": f"{rname}/{pname}",
                    "text": text,
                    "score": score,
                    "avgConf": conf,
                    "items": items,
                }

            # Early-exit: yetarli “signal” bo'lsa qaytaramiz
            # (keyword+date+card topilgan holatlar odatda shu yerda)
            if score >= 12.0:
                return best_pack

    return best_pack


# ----------------------------
# Main endpoint
# ----------------------------
@app.post("/ocr/")
@app.post("/ocr")
async def ocr(
    file: UploadFile = File(...),
    debug: int = Query(0, description="1 -> return meta+items"),
    max_time_ms: int = Query(20000, description="Time budget in ms (default 20000)"),
):
    start_ms = _now_ms()

    # 1) Read + decode
    contents = await file.read()
    np_img = np.frombuffer(contents, np.uint8)
    img = cv2.imdecode(np_img, cv2.IMREAD_COLOR)

    if img is None:
        return JSONResponse(status_code=400, content={"text": ""})

    # 2) Baseline OCR (fast)
    base_text, base_conf, base_items = _ocr_read(img)
    base_score = _score_text(base_text, base_conf)

    best_pack: Dict[str, Any] = {
        "variant": "baseline",
        "text": base_text,
        "score": base_score,
        "avgConf": base_conf,
        "items": base_items,
    }

    # Early exit: baseline juda yaxshi bo'lsa
    if best_pack["score"] >= 12.0 or _elapsed_ms(start_ms) > max_time_ms:
        if debug == 0:
            return JSONResponse(content={"text": best_pack["text"]})

        return JSONResponse(content=_json_safe({
            "text": best_pack["text"],
            "meta": {
                "elapsedMs": _elapsed_ms(start_ms),
                "selectedVariant": best_pack["variant"],
                "score": best_pack["score"],
                "avgConf": best_pack["avgConf"],
                "baselineScore": base_score,
                "baselineConf": base_conf,
                "croppedUsed": False,
            },
            "items": best_pack["items"],
        }))

    # 3) Detect card quad (optional)
    cand = _detect_best_card(img)

    cropped_used = False
    crop_meta = None

    if cand is not None and cand.score > 0.2 and _elapsed_ms(start_ms) < max_time_ms:
        cropped_used = True
        crop_meta = {"method": cand.method, "score": float(cand.score), "quad": _json_safe(cand.pts)}

        warped = _four_point_transform(img, cand.pts)
        warped = _ensure_landscape(warped)
        warped = _resize_for_text(warped, min_w=1100)

        # remaining budget
        remaining = max(500, max_time_ms - _elapsed_ms(start_ms))

        pack2 = _pick_best_with_budget(
            warped=warped,
            start_ms=start_ms,
            budget_ms=max_time_ms,  # overall budget
        )

        # small bias: crop pipeline faqat haqiqatan yaxshi bo'lsa yutsin
        pack2["score"] = float(pack2["score"]) + 0.25

        if pack2["score"] > best_pack["score"]:
            best_pack = {
                "variant": f"crop/{cand.method}/{pack2['variant']}",
                "text": pack2["text"],
                "score": pack2["score"],
                "avgConf": pack2["avgConf"],
                "items": pack2["items"],
            }

    # 4) Response
    if debug == 0:
        return JSONResponse(content={"text": best_pack["text"]})

    return JSONResponse(content=_json_safe({
        "text": best_pack["text"],
        "meta": {
            "elapsedMs": _elapsed_ms(start_ms),
            "selectedVariant": best_pack["variant"],
            "score": best_pack["score"],
            "avgConf": best_pack["avgConf"],
            "baselineScore": base_score,
            "baselineConf": base_conf,
            "croppedUsed": cropped_used,
            "crop": crop_meta,
            "maxTimeMs": max_time_ms,
        },
        "items": best_pack["items"],
    }))