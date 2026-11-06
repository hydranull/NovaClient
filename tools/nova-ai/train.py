"""Trainiert die kleine Nova-KI (Absicht + Sprache) und schreibt brain.bin + knowledge.json fuer den Client.

Aufruf (aus diesem Ordner):  python train.py
Netz: Merkmale (Woerter, Wortpaare, Buchstaben-3/4-Gramme, gehasht auf 4096) -> 96 versteckte Neuronen (ReLU)
-> Absicht (17) und Sprache (de/tr/en). Gleiche Vorverarbeitung wie NovaBrain.java - beide gleich halten!
"""
import json
import math
import os
import random
import re
import struct

import numpy as np

from knowledge import MODULES

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "src", "client", "resources", "assets", "nova_client", "assistant")

DIM = 4096
HIDDEN = 96
LANGS = ["de", "tr", "en"]
INTENTS = ["what", "how", "which", "on", "off", "key", "active", "list", "death", "coords", "help", "greet",
           "thanks", "who", "panic", "theme", "unknown"]

# ---------------------------------------------------------------------- Vorverarbeitung (wie Java)

FOLD = {"ä": "a", "ö": "o", "ü": "u", "ß": "ss", "ı": "i", "ş": "s", "ç": "c", "ğ": "g", "â": "a", "î": "i",
        "û": "u", "é": "e", "è": "e", "à": "a", "ê": "e"}


def normalize(text):
    """Kleinschreiben, tuerkische/deutsche Sonderzeichen als Merkmal merken und falten, nur a-z0-9."""
    t = text.lower()
    extra = []
    if any(c in t for c in "ışğç"):
        extra.append("xtrch")
    if any(c in t for c in "äß"):
        extra.append("xdech")
    out = []
    for c in t:
        c = FOLD.get(c, c)
        out.append(c if ("a" <= c <= "z" or "0" <= c <= "9") else " ")
    words = "".join(out).split()
    return words + extra


def fnv(s):
    h = 0x811C9DC5
    for b in s.encode("ascii"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def features(words):
    feats = {}

    def add(key):
        i = fnv(key) % DIM
        feats[i] = feats.get(i, 0.0) + 1.0

    for i, w in enumerate(words):
        add("w:" + w)
        if i + 1 < len(words):
            add("b:" + w + "_" + words[i + 1])
        if w.startswith("x") and w in ("xmod", "xcat", "xtrch", "xdech", "xcol"):
            continue
        g = "<" + w + ">"
        for n in (3, 4):
            for k in range(len(g) - n + 1):
                add("c:" + g[k:k + n])
    norm = math.sqrt(sum(v * v for v in feats.values())) or 1.0
    idx = np.array(sorted(feats), dtype=np.int32)
    val = np.array([feats[i] / norm for i in idx], dtype=np.float32)
    return idx, val


# ---------------------------------------------------------------------- Trainingssaetze

T = {
    "what": {
        "de": ["was macht {m}", "was macht das modul {m}", "was ist {m}", "wofur ist {m}", "wofür ist {m}",
               "wozu ist {m} gut", "was bringt {m}", "erklar mir {m}", "erkläre {m}", "was kann {m}", "was tut {m}",
               "{m} was ist das", "was genau macht {m}", "was bedeutet {m}", "info zu {m}", "kannst du mir {m} erklären",
               "was ist das {m} modul", "wofür brauche ich {m}", "was macht eigentlich {m}", "{m} was macht das",
               "was ist mit {m} gemeint", "erzähl mir was über {m}", "was macht der {m}", "was macht die {m}",
               "{m}", "{m}?", "{m} modul", "das modul {m}", "{m} info"],
        "en": ["what does {m} do", "what is {m}", "whats {m}", "what's {m}", "explain {m}", "what is the {m} module",
               "tell me about {m}", "what does the module {m} do", "what is {m} for", "what's the point of {m}",
               "describe {m}", "{m} what does it do", "info about {m}", "what can {m} do", "can you explain {m}",
               "what does {m} even do", "what is {m} used for", "explain the {m} module", "{m}", "{m}?",
               "{m} module", "the {m} module", "{m} info"],
        "tr": ["{m} ne işe yarar", "{m} ne yapar", "{m} nedir", "{m} modülü ne yapar", "{m} ne", "{m} ne demek",
               "{m} hakkında bilgi", "{m} modülü nedir", "{m} ne için", "{m} açıkla", "bana {m} anlat",
               "{m} neye yarıyor", "{m} ne işe yarıyor", "{m} modülü ne işe yarar", "{m} ne ise yarar",
               "{m} ne yapıyor", "{m} nedir ne yapar", "{m} hakkında bilgi ver", "{m}", "{m}?", "{m} modülü",
               "{m} bilgi"],
    },
    "how": {
        "de": ["wie benutze ich {m}", "wie funktioniert {m}", "wie stelle ich {m} ein", "wie nutze ich {m}",
               "einstellungen von {m}", "wie aktiviere ich {m}", "wie mache ich {m} an", "wo finde ich {m}",
               "wie bediene ich {m}", "wie richte ich {m} ein", "wie geht {m}", "wie verwende ich {m}",
               "{m} einstellungen", "wie kann ich {m} benutzen", "wo ist {m}", "wie schalte ich {m} ein"],
        "en": ["how do i use {m}", "how does {m} work", "how to use {m}", "how do i set up {m}", "{m} settings",
               "where is {m}", "how do i enable {m}", "how to configure {m}", "how can i use {m}",
               "how do i turn on {m}", "where do i find {m}", "how does the {m} module work", "settings for {m}"],
        "tr": ["{m} nasıl kullanılır", "{m} nasıl çalışır", "{m} ayarları", "{m} nasıl açılır", "{m} nerede",
               "{m} nasıl ayarlanır", "{m} nasıl kullanırım", "{m} nasıl açarım", "{m} modülü nasıl kullanılır",
               "{m} ayarlari nerede", "{m} nasil kullanilir", "{m} nasıl aktif edilir"],
    },
    "which": {
        "de": ["welches modul hilft beim {t}", "welches modul ist für {t}", "gibt es ein modul für {t}",
               "womit kann ich {t}", "was hilft gegen {t}", "ich suche ein modul für {t}", "welches modul {t}",
               "was nehme ich für {t}", "welches modul brauche ich für {t}", "hast du was für {t}",
               "gibt es was gegen {t}", "welches modul zeigt {t}", "ich will {t} welches modul"],
        "en": ["which module helps with {t}", "is there a module for {t}", "what module for {t}", "how can i {t}",
               "i need something for {t}", "which module should i use for {t}", "best module for {t}",
               "any module for {t}", "which module shows {t}", "i want to {t} which module"],
        "tr": ["{t} için hangi modül", "{t} için modül var mı", "hangi modül {t}", "{t} için ne kullanmalıyım",
               "{t} yapmak için hangi modül", "{t} icin hangi modul", "{t} için bir modül lazım",
               "hangi modül {t} gösterir", "{t} için en iyi modül"],
    },
    "on": {
        "de": ["mach {m} an", "schalte {m} ein", "aktiviere {m}", "{m} an", "{m} einschalten", "starte {m}",
               "{m} aktivieren", "bitte {m} anmachen", "{m} anmachen", "mach mal {m} an", "schalt {m} an"],
        "en": ["turn on {m}", "enable {m}", "activate {m}", "{m} on", "start {m}", "switch on {m}", "turn {m} on",
               "please enable {m}"],
        "tr": ["{m} aç", "{m} etkinleştir", "{m} aktif et", "{m} modülünü aç", "{m} çalıştır", "{m} ac",
               "{m} aktif", "{m}i aç", "{m} açar mısın"],
    },
    "off": {
        "de": ["mach {m} aus", "schalte {m} aus", "deaktiviere {m}", "{m} aus", "{m} ausschalten", "stoppe {m}",
               "{m} deaktivieren", "mach mal {m} aus", "schalt {m} ab", "{m} ausmachen"],
        "en": ["turn off {m}", "disable {m}", "deactivate {m}", "{m} off", "stop {m}", "switch off {m}",
               "turn {m} off"],
        "tr": ["{m} kapat", "{m} devre dışı bırak", "{m} durdur", "{m}i kapat", "{m} kapa", "{m} modülünü kapat",
               "{m} kapatır mısın"],
    },
    "key": {
        "de": ["welche taste hat {m}", "auf welcher taste ist {m}", "taste von {m}", "wie ist {m} belegt",
               "welche taste für {m}", "{m} taste", "mit welcher taste geht {m}"],
        "en": ["what key is {m}", "which key is {m} bound to", "keybind of {m}", "{m} keybind",
               "what is the key for {m}", "{m} key", "what key toggles {m}"],
        "tr": ["{m} tuşu ne", "{m} hangi tuşta", "{m} kısayolu ne", "{m} tuşu", "{m} hangi tuş"],
    },
    "active": {
        "de": ["was ist an", "welche module sind an", "welche module sind aktiv", "was läuft gerade",
               "zeig aktive module", "aktive module", "was ist alles an"],
        "en": ["what is on", "which modules are enabled", "active modules", "whats running",
               "show enabled modules", "what modules are on"],
        "tr": ["hangi modüller açık", "açık modüller", "aktif modüller neler", "ne açık", "acik moduller"],
    },
    "list": {
        "de": ["zeig alle module", "welche module gibt es", "liste der module", "welche {c} module gibt es",
               "zeig mir die {c} module", "was gibt es in {c}", "alle module", "was für module hast du"],
        "en": ["list all modules", "what modules are there", "show modules", "show me the {c} modules",
               "which {c} modules exist", "all modules", "what modules do you have"],
        "tr": ["tüm modülleri göster", "hangi modüller var", "modül listesi", "{c} modülleri neler",
               "{c} kategorisinde ne var", "bütün modüller", "modülleri listele"],
    },
    "death": {
        "de": ["wo bin ich gestorben", "wo ist mein letzter tod", "wo war mein tod", "todespunkt",
               "wo sind meine items", "wo bin ich zuletzt gestorben", "letzter tod"],
        "en": ["where did i die", "last death", "death location", "where are my items", "where did i die last"],
        "tr": ["nerede öldüm", "son ölüm yerim", "eşyalarım nerede", "öldüğüm yer", "en son nerede öldüm"],
    },
    "coords": {
        "de": ["wo bin ich", "meine koordinaten", "koordinaten", "meine position", "wo bin ich gerade"],
        "en": ["where am i", "my coordinates", "coords", "my position", "what are my coords"],
        "tr": ["neredeyim", "koordinatlarım", "konumum", "koordinatlarım ne", "şu an neredeyim"],
    },
    "help": {
        "de": ["hilfe", "was kannst du", "welche befehle gibt es", "wie funktionierst du", "was kann ich dich fragen",
               "zeig befehle", "was kannst du alles"],
        "en": ["help", "what can you do", "commands", "how do you work", "what can i ask", "show commands"],
        "tr": ["yardım", "neler yapabilirsin", "komutlar", "ne sorabilirim", "nasıl çalışıyorsun", "ne yapabilirsin"],
    },
    "greet": {
        "de": ["hallo", "hi", "hey", "servus", "moin", "guten tag", "na du", "hallo nova", "guten morgen"],
        "en": ["hello", "hi", "hey", "good morning", "yo", "sup", "hello there", "hi nova"],
        "tr": ["merhaba", "selam", "selamlar", "sa", "günaydın", "merhaba nova", "selam nova"],
    },
    "thanks": {
        "de": ["danke", "danke dir", "vielen dank", "dankeschön", "super danke", "danke schön", "top danke"],
        "en": ["thanks", "thank you", "thx", "ty", "cheers", "thanks a lot"],
        "tr": ["teşekkürler", "teşekkür ederim", "sağ ol", "eyvallah", "çok sağ ol", "tesekkurler", "sagol"],
    },
    "who": {
        "de": ["wer bist du", "was bist du", "bist du eine ki", "wie heißt du", "bist du ein bot"],
        "en": ["who are you", "what are you", "are you an ai", "whats your name", "are you a bot"],
        "tr": ["sen kimsin", "nesin sen", "yapay zeka mısın", "adın ne", "bot musun"],
    },
    "panic": {
        "de": ["alles aus", "panik", "mach alles aus", "alle module aus", "notaus", "schalte alles aus"],
        "en": ["turn everything off", "panic", "disable all modules", "all off", "turn off all modules"],
        "tr": ["hepsini kapat", "tüm modülleri kapat", "panik", "her şeyi kapat", "hepsini kapa"],
    },
    "theme": {
        "de": ["ändere die farbe zu {col}", "farbe {col}", "design {col}", "akzentfarbe {col}",
               "mach das menü {col}", "menüfarbe {col}", "stell die farbe auf {col}"],
        "en": ["change color to {col}", "set accent {col}", "theme {col}", "make the menu {col}",
               "accent color {col}", "change the theme to {col}"],
        "tr": ["rengi {col} yap", "tema {col}", "menü rengini {col} yap", "renk {col}", "temayı {col} yap"],
    },
    "unknown": {
        "de": ["wie ist das wetter", "ich habe hunger", "erzähl einen witz", "was ist zwei plus zwei",
               "baue mir ein haus", "wie alt bist du", "magst du pizza", "spiel musik", "ich bin müde",
               "wer hat minecraft erfunden", "was ist die hauptstadt von frankreich", "kauf mir diamanten",
               "lol", "ok", "ja", "nein", "keine ahnung", "egal"],
        "en": ["tell me a joke", "whats the weather", "im bored", "how old are you", "do you like pizza",
               "what is the meaning of life", "build me a house", "give me diamonds", "who made minecraft",
               "lol", "ok", "yes", "no", "idk", "whatever"],
        "tr": ["hava nasıl", "canım sıkılıyor", "bana fıkra anlat", "kaç yaşındasın", "pizza sever misin",
               "hayatın anlamı ne", "bana ev yap", "bana elmas ver", "minecrafti kim yaptı", "tamam", "evet",
               "hayır", "bilmiyorum", "neyse"],
    },
}

PREFIX = {"de": ["", "", "", "hey nova ", "nova ", "bitte ", "kannst du mir sagen ", "sag mal ", "ey "],
          "en": ["", "", "", "hey nova ", "nova ", "please ", "can you tell me ", "yo ", "so "],
          "tr": ["", "", "", "nova ", "hey nova ", "lütfen ", "bana söyler misin ", "abi ", "kanka "]}
SUFFIX = {"de": ["", "", "", "?", "??", " bitte", " pls", "!", " danke"],
          "en": ["", "", "", "?", "??", " please", " pls", "!"],
          "tr": ["", "", "", "?", "??", " lütfen", " ya", "!", " abi"]}
COLORS = ["blue", "indigo", "violet", "teal", "green", "amber", "rose", "mono", "blau", "grün", "lila", "türkis",
          "gelb", "rosa", "grau", "mavi", "yeşil", "mor", "turkuaz", "sarı", "pembe", "gri", "rot", "red", "kırmızı"]
CATEGORIES = ["combat", "kampf", "savaş", "misc", "sonstiges", "çeşitli", "donut", "basefinding", "base finding",
              "basen", "üs bulma", "render", "visuals", "visuell", "görsel", "nova"]


def module_words():
    out = []
    for cls, d in MODULES.items():
        out += d["aliases"]
    return out


def topics():
    out = []
    for d in MODULES.values():
        words = d["kw"].split()
        for _ in range(3):
            k = random.randint(1, 2)
            out.append(" ".join(random.sample(words, min(k, len(words)))))
    return out


def typo(text, rate):
    chars = list(text)
    i = 0
    while i < len(chars):
        if chars[i].isalpha() and random.random() < rate:
            r = random.random()
            if r < 0.33 and i + 1 < len(chars):
                chars[i], chars[i + 1] = chars[i + 1], chars[i]
            elif r < 0.66:
                del chars[i]
                continue
            else:
                chars.insert(i, chars[i])
                i += 1
        i += 1
    return "".join(chars)


def generate(n_per, seed=1):
    random.seed(seed)
    mods = module_words()
    tops = topics()
    data = []
    for intent, by_lang in T.items():
        for lang, templates in by_lang.items():
            for _ in range(n_per):
                t = random.choice(templates)
                text = t
                if "{m}" in text:
                    # Im Client wird ein erkanntes Modul durch "xmod" ersetzt; manchmal bleibt es unerkannt
                    text = text.replace("{m}", "xmod" if random.random() < 0.8 else random.choice(mods))
                if "{c}" in text:
                    text = text.replace("{c}", "xcat" if random.random() < 0.8 else random.choice(CATEGORIES))
                if "{t}" in text:
                    text = text.replace("{t}", random.choice(tops))
                if "{col}" in text:
                    text = text.replace("{col}", "xcol" if random.random() < 0.7 else random.choice(COLORS))
                text = random.choice(PREFIX[lang]) + text + random.choice(SUFFIX[lang])
                if random.random() < 0.35:
                    parts = text.split()
                    text = " ".join(p if p in ("xmod", "xcat", "xcol") else typo(p, 0.08) for p in parts)
                if random.random() < 0.3:
                    text = text.upper() if random.random() < 0.2 else text.capitalize()
                data.append((text, INTENTS.index(intent), LANGS.index(lang)))
    random.shuffle(data)
    return data


# ---------------------------------------------------------------------- Netz

def batch_forward(params, idx_list, val_list):
    w1, b1, w2, b2, w3, b3 = params
    n = len(idx_list)
    lens = np.array([len(i) for i in idx_list])
    idx = np.concatenate(idx_list)
    val = np.concatenate(val_list)
    offs = np.concatenate([[0], np.cumsum(lens)[:-1]])
    contrib = w1[idx] * val[:, None]
    z1 = np.add.reduceat(contrib, offs, axis=0) + b1
    h = np.maximum(z1, 0.0)
    return idx, val, lens, z1, h, h @ w2 + b2, h @ w3 + b3


def softmax(z):
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def train(data, epochs=18, lr=0.01, batch=128, seed=3):
    rng = np.random.default_rng(seed)
    feats = [features(normalize(t)) for t, _, _ in data]
    yi = np.array([d[1] for d in data])
    yl = np.array([d[2] for d in data])
    params = [rng.normal(0, 0.05, (DIM, HIDDEN)).astype(np.float32), np.zeros(HIDDEN, np.float32),
              rng.normal(0, 0.1, (HIDDEN, len(INTENTS))).astype(np.float32), np.zeros(len(INTENTS), np.float32),
              rng.normal(0, 0.1, (HIDDEN, len(LANGS))).astype(np.float32), np.zeros(len(LANGS), np.float32)]
    m = [np.zeros_like(p) for p in params]
    v = [np.zeros_like(p) for p in params]
    step = 0
    n = len(data)
    for ep in range(epochs):
        order = rng.permutation(n)
        loss_sum, acc_i, acc_l = 0.0, 0, 0
        for s in range(0, n, batch):
            b = order[s:s + batch]
            idx_list = [feats[k][0] for k in b]
            val_list = [feats[k][1] for k in b]
            idx, val, lens, z1, h, li, ll = batch_forward(params, idx_list, val_list)
            pi, pl = softmax(li), softmax(ll)
            bs = len(b)
            loss_sum += -np.log(pi[np.arange(bs), yi[b]] + 1e-9).sum() - 0.5 * np.log(pl[np.arange(bs), yl[b]] + 1e-9).sum()
            acc_i += (pi.argmax(1) == yi[b]).sum()
            acc_l += (pl.argmax(1) == yl[b]).sum()
            gi = pi.copy()
            gi[np.arange(bs), yi[b]] -= 1
            gi /= bs
            gl = pl.copy()
            gl[np.arange(bs), yl[b]] -= 1
            gl *= 0.5 / bs
            g = [None] * 6
            g[2] = h.T @ gi
            g[3] = gi.sum(0)
            g[4] = h.T @ gl
            g[5] = gl.sum(0)
            dh = gi @ params[2].T + gl @ params[4].T
            dz = dh * (z1 > 0)
            g[1] = dz.sum(0)
            rows = np.repeat(np.arange(bs), lens)
            g1 = np.zeros_like(params[0])
            np.add.at(g1, idx, val[:, None] * dz[rows])
            g[0] = g1 + 1e-5 * params[0]
            step += 1
            for k in range(6):
                m[k] = 0.9 * m[k] + 0.1 * g[k]
                v[k] = 0.999 * v[k] + 0.001 * g[k] * g[k]
                mh = m[k] / (1 - 0.9 ** step)
                vh = v[k] / (1 - 0.999 ** step)
                params[k] -= (lr * mh / (np.sqrt(vh) + 1e-8)).astype(np.float32)
        print(f"Epoche {ep + 1:2d}: Verlust {loss_sum / n:.4f}  Absicht {acc_i / n:.3f}  Sprache {acc_l / n:.3f}")
    return params


def predict(params, text):
    idx, val = features(normalize(text))
    _, _, _, _, _, li, ll = batch_forward(params, [idx], [val])
    pi, pl = softmax(li)[0], softmax(ll)[0]
    return INTENTS[int(pi.argmax())], float(pi.max()), LANGS[int(pl.argmax())]


def export(params, path):
    w1, b1, w2, b2, w3, b3 = params
    with open(path, "wb") as f:
        f.write(struct.pack(">Iiiii", 0x4E414931, DIM, HIDDEN, len(INTENTS), len(LANGS)))
        for name in INTENTS + LANGS:
            b = name.encode("ascii")
            f.write(struct.pack(">H", len(b)) + b)
        f.write(w1.astype(">f2").tobytes())
        for arr in (b1, w2, b2, w3, b3):
            f.write(arr.astype(">f4").tobytes())


def main():
    data = generate(400)
    print(len(data), "Trainingssaetze")
    params = train(data)
    tests = ["was macht anti vanish", "xmod ne işe yarar", "what does xmod do", "wie benutze ich xmod",
             "welches modul hilft beim basen finden", "mach xmod an", "hangi modüller açık", "danke", "selam",
             "wo bin ich gestorben", "erzähl einen witz", "base bulmak için hangi modül", "turn off xmod",
             "xmod nasıl kullanılır", "was ist xmod?", "welche taste hat xmod"]
    for t in tests:
        print(f"  {t!r:45} -> {predict(params, t)}")
    os.makedirs(OUT, exist_ok=True)
    export(params, os.path.join(OUT, "brain.bin"))
    # Schluessel = Anzeigename aus Mods.java (Klassennamen werden im Release von ProGuard umbenannt)
    mods_src = open(os.path.join(ROOT, "src", "client", "java", "nova", "nova_client", "client", "Mods.java"),
                    encoding="utf-8").read()
    names = dict(re.findall(r'new (?:[\w.]*\.)?(\w+)\("([^"]+)"', mods_src))
    knowledge = {}
    for cls, d in MODULES.items():
        if cls not in names:
            print("WARNUNG: nicht in Mods.java:", cls)
            continue
        knowledge[names[cls]] = dict(aliases=d["aliases"], kw=d["kw"], en=d["en"], de=d["de"], tr=d["tr"])
    missing = set(names) - set(MODULES)
    if missing:
        print("WARNUNG: ohne Wissen:", sorted(missing))
    with open(os.path.join(OUT, "knowledge.json"), "w", encoding="utf-8") as f:
        json.dump(knowledge, f, ensure_ascii=False, indent=1)
    print("geschrieben:", OUT, os.path.getsize(os.path.join(OUT, "brain.bin")) // 1024, "KB")


if __name__ == "__main__":
    main()
