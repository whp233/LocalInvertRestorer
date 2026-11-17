#!/usr/bin/env python3
"""
invrestore_ref.py — desktop reference implementation of the LocalInvertRestorer
detection / restoration pipeline.

This file is the *specification* of the algorithm that the Android app
(app/src/main/java/com/invrestore/core/*.kt) implements.  Both share the same
constants and the same order of operations, so a detection that works here is
expected to work on device.

Why it works
------------
A local colour inversion (a.k.a. "negate", "invert") is an *information
preserving* transform:  c' = 255 - c.  It is a bijection, so the original
pixels are still in the file.  The only thing it destroys is the visual
continuity across the edit boundary, which is exactly the signal we score:
for a candidate rectangle we measure the colour step between the inner band
and the outer band of each of its four sides, once with the region as-is and
once with the region negated.  A genuine inversion makes that step collapse.

Pipeline
--------
1. downscale so the long edge is <= SEARCH_MAX  (search happens here)
2. build summed-area tables -> O(1) band means
3. find strong horizontal / vertical seams (candidate rectangle borders)
4. enumerate rectangles from seam pairs, score all 8 inversion masks
5. non-maximum suppression, classify, restore with tone matching + feathering

Usage
-----
    python3 invrestore_ref.py INPUT.jpg --out-dir out/
    python3 invrestore_ref.py --selftest
"""
from __future__ import annotations

import argparse
import json
import os
from dataclasses import dataclass, asdict

import numpy as np

# ---------------------------------------------------------------- constants
SEARCH_MAX = 640      # long edge used for the search pass
BAND = 3              # band width (px, search scale) for seam-step measurement
SEAM_TOP = 8          # keep the N strongest vertical / horizontal seams
SEAM_MIN_SEP = 6      # px, minimum distance between two seams of one axis
MIN_SIDE_RATIO = 0.05  # a candidate must be at least 5% of the short edge
MAX_AREA_RATIO = 0.55  # ... and at most 55% of the image area
NMS_IOU = 0.30
INVERSION_RATIO = 0.55  # cost_after / cost_before must drop below this
INVERSION_GAIN = 0.45   # ... and the relative gain must exceed this
REPLACE_SEAM = 0.10     # raw seam step that marks "something was pasted here"
FEATHER = 0             # px, 边界羽化；纯反色逆运算精确，默认关闭


# ---------------------------------------------------------------- data types
@dataclass
class Rect:
    l: int
    t: int
    r: int   # exclusive
    b: int   # exclusive

    @property
    def w(self) -> int:
        return self.r - self.l

    @property
    def h(self) -> int:
        return self.b - self.t

    def area(self) -> int:
        return self.w * self.h

    def iou(self, o: "Rect") -> float:
        ix = max(0, min(self.r, o.r) - max(self.l, o.l))
        iy = max(0, min(self.b, o.b) - max(self.t, o.t))
        inter = ix * iy
        union = self.w * self.h + o.w * o.h - inter
        return inter / union if union else 0.0


@dataclass
class Candidate:
    rect: Rect
    mask: int          # bit0=R, bit1=G, bit2=B  (which channels to negate)
    cost_before: float
    cost_after: float
    gain: float
    kind: str          # "INVERSION" | "REPLACEMENT"
    confidence: float


# ---------------------------------------------------------------- helpers
def load_rgb(path: str) -> np.ndarray:
    from PIL import Image
    im = Image.open(path).convert("RGB")
    return np.asarray(im, dtype=np.float32)


def downscale(img: np.ndarray, long_edge: int = SEARCH_MAX):
    """Box-filter downscale. Returns (small_img, scale) with scale = small/full."""
    from PIL import Image
    h, w = img.shape[:2]
    s = min(1.0, long_edge / float(max(h, w)))
    if s >= 1.0:
        return img.copy(), 1.0
    nw, nh = max(1, int(round(w * s))), max(1, int(round(h * s)))
    small = Image.fromarray(img.astype(np.uint8)).resize((nw, nh), Image.BOX)
    return np.asarray(small, dtype=np.float32), nw / float(w)


def integral(img: np.ndarray) -> np.ndarray:
    """Summed-area table per channel, shape (h+1, w+1, 3)."""
    h, w = img.shape[:2]
    sat = np.zeros((h + 1, w + 1, 3), dtype=np.float64)
    sat[1:, 1:] = img.astype(np.float64).cumsum(0).cumsum(1)
    return sat


def rect_mean(sat: np.ndarray, l: int, t: int, r: int, b: int) -> np.ndarray:
    """Mean colour of the half-open rectangle, O(1)."""
    l, t = max(0, l), max(0, t)
    r, b = min(sat.shape[1] - 1, r), min(sat.shape[0] - 1, b)
    if r <= l or b <= t:
        return np.zeros(3, dtype=np.float64)
    area = (r - l) * (b - t)
    s = sat[b, r] - sat[t, r] - sat[b, l] + sat[t, l]
    return s / area


def seam_step(sat: np.ndarray, rc: Rect, mask: int, band: int = BAND,
              per_side: bool = False):
    """
    Colour step across the four borders of `rc`, assuming the inside pixels are
    negated on the channels selected by `mask`.  Negation is affine, so it can
    be applied to the band means instead of to every pixel.
    Returns the mean absolute per-channel difference, normalised to 0..1
    (or the list of per-side values when `per_side` is set).
    """
    H, W = sat.shape[0] - 1, sat.shape[1] - 1
    sides = []

    def cmp(inner, outer):
        m_in = rect_mean(sat, *inner)
        if mask:
            m_in = np.array([255.0 - m_in[c] if (mask >> c) & 1 else m_in[c]
                             for c in range(3)])
        m_out = rect_mean(sat, *outer)
        return float(np.abs(m_in - m_out).mean() / 255.0)

    # top / bottom
    if rc.t - band >= 0:
        sides.append(cmp((rc.l, rc.t, rc.r, min(rc.t + band, rc.b)),
                         (rc.l, rc.t - band, rc.r, rc.t)))
    if rc.b + band <= H:
        sides.append(cmp((rc.l, max(rc.b - band, rc.t), rc.r, rc.b),
                         (rc.l, rc.b, rc.r, min(rc.b + band, H))))
    # left / right
    if rc.l - band >= 0:
        sides.append(cmp((rc.l, rc.t, min(rc.l + band, rc.r), rc.b),
                         (rc.l - band, rc.t, rc.l, rc.b)))
    if rc.r + band <= W:
        sides.append(cmp((max(rc.r - band, rc.l), rc.t, rc.r, rc.b),
                         (rc.r, rc.t, min(rc.r + band, W), rc.b)))
    if not sides:
        return [] if per_side else 0.0
    return sides if per_side else float(np.mean(sides))


def find_seams(profile: np.ndarray, top_n: int = SEAM_TOP,
               min_sep: int = SEAM_MIN_SEP) -> list[int]:
    """Local maxima of a 1-D edge profile, strongest first, spaced >= min_sep."""
    n = profile.size
    if n < 3:
        return []
    peaks = []
    for i in range(1, n - 1):
        if profile[i] >= profile[i - 1] and profile[i] > profile[i + 1]:
            peaks.append(i)
    if not peaks:
        return []
    med = float(np.median(profile))
    thr = max(0.012, med * 2.2)
    peaks = [p for p in peaks if profile[p] >= thr]
    peaks.sort(key=lambda p: profile[p], reverse=True)
    picked: list[int] = []
    for p in peaks:
        if all(abs(p - q) >= min_sep for q in picked):
            picked.append(p)
        if len(picked) >= top_n:
            break
    return sorted(picked)


def _windowed_mean(a: np.ndarray, axis: int, win: int) -> np.ndarray:
    """Moving average of `a` along `axis` with window `win` (edge-padded)."""
    pad = [(0, 0)] * a.ndim
    pad[axis] = (0, 0)
    n = a.shape[axis]
    win = max(1, min(win, n))
    c = np.cumsum(a, axis=axis)
    c = np.concatenate([np.zeros_like(np.take(c, [0], axis=axis)), c], axis=axis)
    full = (np.take(c, np.arange(win, n + 1), axis=axis)
            - np.take(c, np.arange(0, n - win + 1), axis=axis)) / win
    # pad the head with the first value, tail with the last
    head = np.repeat(np.take(full, [0], axis=axis), win // 2, axis=axis)
    tail = np.repeat(np.take(full, [-1], axis=axis), n - win - win // 2, axis=axis)
    out = np.concatenate([head, full, tail], axis=axis)
    return out[:n]


def edge_profiles(gray: np.ndarray, win: int = 48):
    """
    Local seam strength, so that a rectangle side that is only ~1/10 of the
    image height still reads as a strong seam.

    Returns (col_profile, row_profile) where col_profile[x] is the strongest
    windowed mean |dI/dx| found anywhere along column x, and vice versa.
    """
    dx = np.abs(np.diff(gray, axis=1)) / 255.0          # (h, w-1)
    dy = np.abs(np.diff(gray, axis=0)) / 255.0          # (h-1, w)
    wc = _windowed_mean(dx, 0, win).max(axis=0)          # per column
    wr = _windowed_mean(dy, 1, win).max(axis=1)          # per row
    return wc, wr


def _runs(mask_1d: np.ndarray, min_len: int, gap: int = 3):
    """
    Maximal runs of True in a 1-D boolean array, tolerating small gaps.

    真实照片里接缝常被穿过它的内容（文字、发丝、纹理）打断，逐像素要求连续
    会把一条完整的边切成碎片，所以允许 gap 个像素的空缺后再接上。
    """
    idx = np.flatnonzero(mask_1d)
    if idx.size == 0:
        return []
    out = []
    start = prev = int(idx[0])
    for k in idx[1:]:
        k = int(k)
        if k - prev - 1 <= gap:
            prev = k
            continue
        if prev - start + 1 >= min_len:
            out.append((start, prev + 1))
        start = prev = k
    if prev - start + 1 >= min_len:
        out.append((start, prev + 1))
    return out


def inversion_seam_maps(img: np.ndarray, k: int = 3, tau: float = 40.0,
                        min_step: float = 8.0):
    """
    逐像素判定「这里像不像一条反色边界」。

    通用边缘图会把所有强边都算进来，一条弱边（比如反色块的左边恰好压在
    与自身反相后很接近的颜色上）就直接掉到阈值以下，整块区域随之漏检。
    这里换成假设驱动的度量：取边界两侧各 k 个像素的均值，若内侧取反后与
    外侧接近（残差小）、同时两侧本来确实有落差（不是平坦区），才认为像反色接缝。

    返回 (maskV, maskH)：maskV[y, x] 表示竖边界落在 x-1|x 之间。
    """
    h, w = img.shape[:2]
    out = []
    for axis in (1, 0):
        n = w if axis == 1 else h
        cs = np.cumsum(np.pad(img, ((0, 0), (1, 0), (0, 0)) if axis == 1
                              else ((1, 0), (0, 0), (0, 0))), axis=axis)

        def band(a, b, cs=cs, axis=axis):
            return (cs[:, b] - cs[:, a]) / max(1, b - a) if axis == 1 else \
                   (cs[b] - cs[a]) / max(1, b - a)

        inv = np.full((h, w), 255.0, dtype=np.float32)
        raw = np.zeros((h, w), dtype=np.float32)
        for i in range(1, n):
            lo = band(max(0, i - k), i)
            hi = band(i, min(n, i + k))
            res = np.abs(255.0 - lo - hi).mean(axis=-1)
            dif = np.abs(lo - hi).mean(axis=-1)
            if axis == 1:
                inv[:, i] = res
                raw[:, i] = dif
            else:
                inv[i, :] = res
                raw[i, :] = dif
        out.append((inv < tau) & (raw > min_step))
    return out[0], out[1]


def _pair_runs(vrun, hrun, maskH, h, w, min_side, max_area, tol, max_rects):
    """把高度重叠的两条垂直游程配对，再验证上下两条水平边确实存在。"""
    out: list[Rect] = []
    for i in range(len(vrun)):
        x1, a1, b1 = vrun[i]
        for j in range(i + 1, len(vrun)):
            x2, a2, b2 = vrun[j]
            if x2 - x1 < min_side:
                continue
            # 两条垂直边必须在高度上大幅重叠（间隙容差会让游程略微超出角点，
            # 所以用重叠比例而不是绝对端点差来判断）
            ov = min(b1, b2) - max(a1, a2)
            if ov < 0.8 * min(b1 - a1, b2 - a2):
                continue
            t, b = max(a1, a2), min(b1, b2)
            if b - t < min_side or (x2 - x1) * (b - t) > max_area:
                continue
            best_t, cov_t = t, 0.0
            for tt in range(max(0, t - tol), min(h, t + tol + 1)):
                c = float(maskH[tt, x1:x2].mean())
                if c > cov_t:
                    best_t, cov_t = tt, c
            best_b, cov_b = b, 0.0
            for bb in range(max(0, b - tol), min(h, b + tol + 1)):
                c = float(maskH[bb, x1:x2].mean())
                if c > cov_b:
                    best_b, cov_b = bb, c
            if cov_t >= 0.5 and cov_b >= 0.5 and best_b - best_t >= min_side:
                out.append(Rect(x1, best_t, x2, best_b))
            if len(out) >= max_rects:
                return out
    return out


def rect_candidates(gray: np.ndarray, maskV: np.ndarray | None = None,
                    maskH: np.ndarray | None = None, max_runs: int = 220,
                    max_rects: int = 400) -> list[Rect]:
    """
    Propose rectangles whose four sides are long, straight, high-contrast edges.

    两路候选来源：
      1. 通用边缘图 —— 处理高对比边界，也覆盖「贴图覆盖」这类不可逆情况；
      2. 反色缝隙图 —— 由反色假设直接推出，弱边也能抓到。
    两路各跑一遍游程配对，最后按 IOU 去重。
    """
    h, w = gray.shape
    thr = 0.055
    ex = np.abs(np.diff(gray, axis=1)) / 255.0
    ey = np.abs(np.diff(gray, axis=0)) / 255.0
    ex = np.pad(ex, ((0, 0), (0, 1)), mode="edge")
    ey = np.pad(ey, ((0, 1), (0, 0)), mode="edge")

    min_run = max(10, int(0.030 * min(h, w)))
    gap = max(2, int(0.006 * min(h, w)))
    # 反色缝隙图本来就会被穿过边界的内容打断，需要更宽的间隙容差才能连成一条
    inv_gap = max(3, int(0.012 * min(h, w)))

    sources = [(ex > thr, ey > thr, gap)]
    if maskV is not None and maskH is not None:
        sources.append((maskV, maskH, inv_gap))
    min_side = max(10, int(MIN_SIDE_RATIO * min(h, w)))
    max_area = MAX_AREA_RATIO * w * h
    tol = max(2, int(0.008 * min(h, w)))

    out: list[Rect] = []
    for bx, by, gp in sources:
        vrun = [(x, s, e) for x in range(w) for s, e in _runs(bx[:, x], min_run, gp)]
        hrun = [(y, s, e) for y in range(h) for s, e in _runs(by[y, :], min_run, gp)]
        if len(vrun) < 2:
            continue
        # 先按长度截断（太短的边不可能是矩形的边），再按 x 排序 ——
        # _pair_runs 默认 x1 < x2，顺序错了会把绝大多数配对直接跳过
        vrun.sort(key=lambda r: r[2] - r[1], reverse=True)
        sel = sorted(vrun[:max_runs], key=lambda r: r[0])
        out.extend(_pair_runs(sel, hrun, by, h, w,
                              min_side, max_area, tol, max_rects - len(out)))
        if len(out) >= max_rects:
            break

    # 去重：同一个框可能被两路都提出来
    kept: list[Rect] = []
    for r in sorted(out, key=lambda z: z.area(), reverse=True):
        if all(r.iou(k) < 0.6 for k in kept):
            kept.append(r)
    return kept


def refine(sat: np.ndarray, rc: Rect, mask: int, rad: int = 2) -> Rect:
    """Nudge each edge by up to `rad` px to minimise the seam step."""
    H, W = sat.shape[0] - 1, sat.shape[1] - 1
    best, bc = rc, seam_step(sat, rc, mask)
    for dl in range(-rad, rad + 1):
        for dt in range(-rad, rad + 1):
            for dr in range(-rad, rad + 1):
                for db in range(-rad, rad + 1):
                    cand = Rect(rc.l + dl, rc.t + dt, rc.r + dr, rc.b + db)
                    if cand.l < 0 or cand.t < 0 or cand.r > W or cand.b > H:
                        continue
                    if cand.w < 8 or cand.h < 8:
                        continue
                    c = seam_step(sat, cand, mask)
                    if c < bc:
                        best, bc = cand, c
    return best


# ---------------------------------------------------------------- detector
def detect(img: np.ndarray) -> list[Candidate]:
    small, scale = downscale(img)
    h, w = small.shape[:2]
    sat = integral(small)
    gray = small.mean(axis=2)
    mask_v, mask_h = inversion_seam_maps(small)
    rects = rect_candidates(gray, mask_v, mask_h)

    raw: list[Candidate] = []
    for rc in rects:
        sides0 = seam_step(sat, rc, 0, per_side=True)
        if len(sides0) < 3:
            continue
        c0 = float(np.mean(sides0))
        # every side has to carry a real step, otherwise it is just one edge
        if min(sides0) < 0.035 or c0 < 0.10:
            continue

        best_mask, cbest = 0, c0
        for mask in range(1, 8):
            c = seam_step(sat, rc, mask)
            if c < cbest:
                best_mask, cbest = mask, c

        # 先把边界对齐到亚像素级，再据此判定类别，避免量化误差把真反色判成覆盖
        r = refine(sat, rc, best_mask)
        c0 = seam_step(sat, r, 0)
        c1 = seam_step(sat, r, best_mask) if best_mask else c0
        ratio = c1 / max(c0, 1e-6)
        gain = (c0 - c1) / max(c0, 1e-6)

        if best_mask and ratio <= INVERSION_RATIO and gain >= INVERSION_GAIN:
            kind, conf = "INVERSION", min(1.0, 0.65 * gain + 0.35 * (1.0 - ratio))
        elif c0 >= REPLACE_SEAM and len(sides0) == 4:
            kind, conf, best_mask = "REPLACEMENT", min(1.0, c0 / 0.32), 0
        else:
            continue
        raw.append(Candidate(r, best_mask, c0, c1, gain, kind, conf))

    # non-maximum suppression: keep the tightest / strongest non-overlapping box
    raw.sort(key=lambda c: (c.kind == "INVERSION", c.confidence, c.cost_before),
             reverse=True)
    kept: list[Candidate] = []
    for c in raw:
        if all(c.rect.iou(k.rect) < NMS_IOU for k in kept):
            kept.append(c)
        if len(kept) >= 5:
            break

    out: list[Candidate] = []
    for c in kept:
        r = c.rect
        out.append(Candidate(
            Rect(*[int(round(v / scale)) for v in (r.l, r.t, r.r, r.b)]),
            c.mask, c.cost_before, c.cost_after, c.gain, c.kind, c.confidence))
    return out


# ---------------------------------------------------------------- restorer
def snap_sides(img: np.ndarray, rc: Rect, mask: int, rad: int = 6) -> Rect:
    """
    用真实像素逐边对齐（±rad），抵消缩略图搜索带来的量化误差。

    判据是比值 score = |内侧取反 - 外侧| / (|内侧 - 外侧| + 1)，不是绝对残差：
    正确边界处“取反后接得上”且“本来就有落差”，比值极小；一旦整段偏移都落在
    边界外侧的平滑区，绝对残差处处相近，argmin 会随机挑一个，把边界带偏。
    """
    H, W = img.shape[:2]
    bits = np.array([1, 2, 4])

    def pair_cost(inner, outer):
        inv = np.where((mask & bits) > 0, 255.0 - inner, inner)
        return np.abs(inv - outer).mean(), np.abs(inner - outer).mean()

    def pick(costs):
        best, best_score = 0, np.inf
        for o, (res, dif) in costs.items():
            sc = res / (dif + 1.0)
            if sc < best_score:
                best_score, best = sc, o
        return best

    l, t, r, b = rc.l, rc.t, rc.r, rc.b

    l += pick({o: np.mean([pair_cost(img[y, rc.l + o], img[y, rc.l + o - 1])
                           for y in range(rc.t, rc.b)], axis=0)
               for o in range(-rad, rad + 1) if 0 < rc.l + o < W})
    r += pick({o: np.mean([pair_cost(img[y, rc.r - 1 + o], img[y, rc.r - 1 + o + 1])
                           for y in range(rc.t, rc.b)], axis=0)
               for o in range(-rad, rad + 1) if 0 <= rc.r - 1 + o < W - 1})
    t += pick({o: np.mean([pair_cost(img[rc.t + o, x], img[rc.t + o - 1, x])
                           for x in range(l, r)], axis=0)
               for o in range(-rad, rad + 1) if 0 < rc.t + o < H})
    b += pick({o: np.mean([pair_cost(img[rc.b - 1 + o, x], img[rc.b - 1 + o + 1, x])
                           for x in range(l, r)], axis=0)
               for o in range(-rad, rad + 1) if 0 <= rc.b - 1 + o < H - 1})
    return Rect(l, t, r, b)


def restore(img: np.ndarray, rc: Rect, mask: int, match_tone: bool = True,
            feather: int = FEATHER) -> np.ndarray:
    """Negate `rc` (channels in `mask`), optionally match the surrounding tone."""
    out = img.copy()
    rc = snap_sides(img, rc, mask)

    patch = out[rc.t:rc.b, rc.l:rc.r].copy()
    if mask & 1:
        patch[:, :, 0] = 255.0 - patch[:, :, 0]
    if mask & 2:
        patch[:, :, 1] = 255.0 - patch[:, :, 1]
    if mask & 4:
        patch[:, :, 2] = 255.0 - patch[:, :, 2]

    if match_tone:
        k = max(2, feather)
        ring, inner = [], []
        if rc.t - k >= 0:
            ring.append(img[rc.t - k:rc.t, rc.l:rc.r].reshape(-1, 3))
            inner.append(patch[:k, :].reshape(-1, 3))
        if rc.b + k <= img.shape[0]:
            ring.append(img[rc.b:rc.b + k, rc.l:rc.r].reshape(-1, 3))
            inner.append(patch[-k:, :].reshape(-1, 3))
        if rc.l - k >= 0:
            ring.append(img[rc.t:rc.b, rc.l - k:rc.l].reshape(-1, 3))
            inner.append(patch[:, :k].reshape(-1, 3))
        if rc.r + k <= img.shape[1]:
            ring.append(img[rc.t:rc.b, rc.r:rc.r + k].reshape(-1, 3))
            inner.append(patch[:, -k:].reshape(-1, 3))
        if ring:
            mr = np.concatenate(ring).mean(0)
            mi = np.concatenate(inner).mean(0)
            for ch in range(3):
                if abs(mi[ch]) < 1e-3:
                    continue
                scale_c = float(np.clip(mr[ch] / mi[ch], 0.75, 1.35))
                patch[:, :, ch] = np.clip(patch[:, :, ch] * scale_c, 0, 255)

    # feathered composite so the seam does not become a hard line
    out[rc.t:rc.b, rc.l:rc.r] = patch
    if feather > 0:
        h, w = patch.shape[:2]
        wy = np.ones(h)
        wx = np.ones(w)
        ramp = np.linspace(0.0, 1.0, feather + 1)[:-1]
        wy[:feather] *= ramp[:min(h, feather)]
        wy[-feather:] *= ramp[::-1][:min(h, feather)]
        wx[:feather] *= ramp[:min(w, feather)]
        wx[-feather:] *= ramp[::-1][:min(w, feather)]
        alpha = np.clip(np.outer(wy, wx), 0.0, 1.0)[:, :, None]
        base = out[rc.t:rc.b, rc.l:rc.r]
        out[rc.t:rc.b, rc.l:rc.r] = base * alpha + img[rc.t:rc.b, rc.l:rc.r] * (1 - alpha)
    return np.clip(out, 0, 255)


# ---------------------------------------------------------------- cli
def main() -> None:
    ap = argparse.ArgumentParser(description="detect + undo local colour inversion")
    ap.add_argument("input")
    ap.add_argument("--out-dir", default="out")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    from PIL import Image
    os.makedirs(args.out_dir, exist_ok=True)
    img = load_rgb(args.input)
    cands = detect(img)
    print(json.dumps([asdict(c) for c in cands], indent=2, ensure_ascii=False))

    for i, c in enumerate(cands):
        if c.kind != "INVERSION":
            continue
        res = restore(img, c.rect, c.mask)
        Image.fromarray(res.astype(np.uint8)).save(
            os.path.join(args.out_dir, f"restored_{i}.png"))


if __name__ == "__main__":
    main()
