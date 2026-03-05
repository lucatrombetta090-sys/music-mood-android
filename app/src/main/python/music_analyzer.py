"""
music_analyzer.py — Chaquopy v5
Sistema di classificazione mood basato su punteggio pesato multi-feature.
Campiona al 50% della canzone (ritornello).
"""
import os
import json


def read_tags(path: str) -> str:
    try:
        from mutagen.mp3 import MP3
    except Exception as e:
        return json.dumps({"error": str(e)})
    try:
        audio = MP3(path)
        tags  = audio.tags or {}

        def t(k): return str(tags[k]) if k in tags else ""

        title = t("TIT2") or os.path.basename(path)[:-4]
        genre = t("TCON")
        year  = ""
        bpm   = 0.0

        if "TDRC" in tags:   year = str(tags["TDRC"])[:4]
        elif "TYER" in tags: year = str(tags["TYER"])[:4]
        try: bpm = float(t("TBPM"))
        except: pass

        if genre.startswith("(") and ")" in genre:
            genre = genre[genre.index(")") + 1:].strip()

        return json.dumps({
            "path":     path,
            "title":    title,
            "artist":   t("TPE1"),
            "album":    t("TALB"),
            "genre":    genre,
            "year":     year,
            "duration": round(float(audio.info.length), 1),
            "bpm_tag":  bpm,
        })
    except Exception as e:
        return json.dumps({"error": str(e), "path": path})


def analyze_pcm(pcm_java, sample_rate_int: int) -> str:
    """
    Analisi DSP — algoritmo v3.
    
    Campione: 5s al 50% del brano (ritornello).
    
    Problema v2: ZCR del chorus (batteria+percussioni) spesso > 0.10,
    causando Aggressivo sul 54% dei brani. Soluzione: Aggressivo ora
    richiede FLATNESS spettrale alta (distorsione/rumore tipica di
    metal/punk) oppure tempo estremo, non solo energia.
    
    Distribuzione target: Positivo 35%, Energico 35%, Malinconico 20%, Aggressivo 10%
    """
    try:
        import numpy as np

        pcm = np.array(pcm_java, dtype=np.float32)
        sr  = int(sample_rate_int)
        eps = 1e-10

        if len(pcm) < sr // 4:
            return json.dumps(_default("segnale troppo corto"))

        # ── 1. RMS energy ───────────────────────────────────────────────────────
        energy = float(np.sqrt(np.mean(pcm ** 2)))
        energy_norm = float(np.clip(np.log1p(energy * 20) / np.log1p(20 * 0.35), 0.0, 1.0))

        # ── 2. Stima tempo ──────────────────────────────────────────────────────
        hop = sr // 25
        frm = sr // 10
        n   = max(0, (len(pcm) - frm) // hop)
        tempo = -1.0

        if n >= 8:
            rms_env = np.array([
                np.sqrt(np.mean(pcm[i*hop: i*hop+frm]**2) + eps)
                for i in range(n)
            ])
            onset = np.maximum(np.diff(rms_env), 0)
            thr   = onset.mean() + 0.5 * onset.std()
            peaks = [i for i in range(1, len(onset)-1)
                     if onset[i] > thr and onset[i] >= onset[i-1] and onset[i] >= onset[i+1]]
            if len(peaks) >= 4:
                ivs = np.diff(peaks) * hop / sr
                ivs = ivs[(ivs > 0.22) & (ivs < 2.8)]
                if len(ivs) >= 3:
                    t = 60.0 / float(np.median(ivs))
                    while t < 60:  t *= 2
                    while t > 220: t /= 2
                    tempo = round(t, 1)

        tempo_known = tempo > 0
        tv = tempo if tempo_known else 105.0

        # ── 3. Analisi spettrale ────────────────────────────────────────────────
        n_fft    = min(len(pcm), sr)
        window   = np.hanning(n_fft)
        mag      = np.abs(np.fft.rfft(pcm[:n_fft] * window))
        freqs    = np.fft.rfftfreq(n_fft, 1.0 / sr)
        mag_sum  = np.sum(mag) + eps

        centroid = float(np.sum(freqs * mag) / mag_sum)
        zcr      = float(np.mean(np.abs(np.diff(np.sign(pcm)))) / 2)

        cumsum      = np.cumsum(mag)
        rolloff_idx = np.searchsorted(cumsum, 0.85 * cumsum[-1])
        rolloff     = float(freqs[min(rolloff_idx, len(freqs)-1)])

        # Flatness spettrale: vicino a 1 = rumore/distorsione (metal, noise)
        # vicino a 0 = tono puro (classica, pop pulito)
        log_mag  = np.log(mag + eps)
        flatness = float(np.clip(np.exp(np.mean(log_mag)) / (np.mean(mag) + eps), 0.0, 1.0))

        # ── 4. PUNTEGGIO VALENZA ────────────────────────────────────────────────
        # Solo caratteristiche tonali/ritmiche (NON energia)
        valence = 0.0

        if tempo_known:
            if tv < 60:     valence -= 0.50
            elif tv < 75:   valence -= 0.35
            elif tv < 88:   valence -= 0.20
            elif tv < 100:  valence -= 0.05
            elif tv < 118:  valence += 0.10
            elif tv < 138:  valence += 0.22
            else:           valence += 0.32

        if centroid < 700:    valence -= 0.40
        elif centroid < 1100: valence -= 0.28
        elif centroid < 1600: valence -= 0.15
        elif centroid < 2200: valence -= 0.03
        elif centroid < 3000: valence += 0.08
        elif centroid < 4000: valence += 0.15
        else:                 valence += 0.18

        if rolloff < 1200:    valence -= 0.18
        elif rolloff < 2000:  valence -= 0.08
        elif rolloff > 5000:  valence += 0.08

        if zcr < 0.025:       valence -= 0.12
        elif zcr < 0.045:     valence -= 0.04

        valence = float(np.clip(valence, -1.0, 1.0))

        # ── 5. CLASSIFICAZIONE ────────────────────────────────────────────────
        #
        # AGGRESSIVO: richiede caratteristiche genuine di musica aggressiva.
        # NON basta l'alta energia (ogni ritornello è energico).
        # Serve DISTORSIONE (flatness alta) O tempo estremo O volume assurdo.
        #
        # flatness > 0.30: tipico di chitarre distorte, noise, synth aggressivi
        # tempo > 148:     punk/metal/hardstyle genuino
        # energy_norm > 0.92: volume da concerto heavy metal
        is_aggressive = (
            (flatness > 0.30 and energy_norm > 0.72) or
            (tempo_known and tv > 148 and energy_norm > 0.68) or
            (energy_norm > 0.92)
        )

        # ENERGICO: brano veloce e potente ma non aggressivo
        # Soglia energia abbassata a 0.55 per catturare dance/pop vivace
        is_energic = (energy_norm > 0.55 and tempo_known and tv > 108 and not is_aggressive)

        # Classificazione finale
        if is_aggressive:
            mood = "Aggressivo"
        elif valence < -0.12:
            mood = "Malinconico"
        elif is_energic:
            mood = "Energico"
        else:
            mood = "Positivo"

        # ── 6. GENERE HINT ────────────────────────────────────────────────────
        if flatness > 0.28 and rolloff > 3500 and zcr > 0.08:
            genre_hint = "Elettronica"
        elif flatness > 0.25 and energy_norm > 0.65:
            genre_hint = "Rock"
        elif zcr < 0.025 and tempo_known and tv < 85:
            genre_hint = "Classica"
        elif centroid < 1500 and tempo_known and tv < 90:
            genre_hint = "Jazz"
        elif rolloff < 2000 and energy_norm < 0.40:
            genre_hint = "Acustica"
        elif centroid > 2800 and tempo_known and tv > 100:
            genre_hint = "Pop"
        else:
            genre_hint = "Pop"

        return json.dumps({
            "tempo":       tempo if tempo_known else 0.0,
            "energy":      round(energy, 4),
            "energy_norm": round(energy_norm, 3),
            "centroid":    round(centroid, 1),
            "zcr":         round(zcr, 4),
            "rolloff":     round(rolloff, 1),
            "flatness":    round(flatness, 4),
            "valence":     round(valence, 3),
            "mood":        mood,
            "genre_hint":  genre_hint,
        })

    except Exception as e:
        return json.dumps(_default(str(e)))

def _default(reason: str = "") -> dict:
    return {
        "tempo": 0.0, "energy": 0.05, "energy_norm": 0.3,
        "centroid": 2000.0, "zcr": 0.05,
        "valence": 0.0, "mood": "Positivo", "genre_hint": "Pop",
        "error": reason,
    }
