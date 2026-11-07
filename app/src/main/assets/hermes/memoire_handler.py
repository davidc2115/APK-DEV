"""Mémoire partagée Jarvis <-> Hermès (hook « gateway:startup » installé par Jarvis).

Un fil de fond, tant que la passerelle tourne :
  - envoie à Jarvis la mémoire d'Hermès (memories/MEMORY.md et USER.md) dès qu'elle change ;
  - récupère la mémoire de Jarvis et la recopie dans un bloc géré de SOUL.md, chargé par Hermès à chaque session
    (y compris les tâches planifiées et les sous-agents).
Aucune erreur ne remonte : si Jarvis est fermé, on réessaie au tour suivant.
"""
import hashlib
import os
import re
import threading
import time
import urllib.request
from pathlib import Path

DEBUT = "<!-- JARVIS-MEMOIRE-DEBUT -->"
FIN = "<!-- JARVIS-MEMOIRE-FIN -->"
IDENTITE = "You are Hermes Agent, built by Nous Research."
_lance = False


def _home() -> Path:
    h = os.environ.get("HERMES_HOME")
    return Path(h) if h else Path.home() / ".hermes"


def _pont(home: Path):
    url, jeton = "", ""
    try:
        for ligne in (home / ".env").read_text(encoding="utf-8").splitlines():
            if ligne.startswith("JARVIS_PONT_URL="):
                url = ligne.split("=", 1)[1].strip().strip("'\"")
            elif ligne.startswith("JARVIS_PONT_JETON="):
                jeton = ligne.split("=", 1)[1].strip().strip("'\"")
    except OSError:
        pass
    if not jeton or not url:
        try:
            cfg = (home / "config.yaml").read_text(encoding="utf-8")
            m = re.search(r"jarvis:\s*\n\s*url:\s*\"?(http://127\.0\.0\.1:\d+)", cfg)
            t = re.search(r"Authorization:\s*\"?Bearer ([A-Za-z0-9]+)", cfg)
            url = url or (m.group(1) if m else "")
            jeton = jeton or (t.group(1) if t else "")
        except OSError:
            pass
    return url.rstrip("/"), jeton


def _appel(methode: str, url: str, jeton: str, corps: str = None):
    data = corps.encode("utf-8") if corps is not None else None
    req = urllib.request.Request(url, data=data, method=methode, headers={
        "Authorization": "Bearer " + jeton, "Content-Type": "text/plain; charset=utf-8"})
    with urllib.request.urlopen(req, timeout=8) as r:
        return r.status, r.read().decode("utf-8", "replace")


def _lire(p: Path) -> str:
    try:
        return p.read_text(encoding="utf-8")
    except OSError:
        return ""


def ecrire_soul(soul: Path, bloc: str) -> bool:
    """Remplace le bloc géré de SOUL.md (créé si besoin) ; renvoie True si le fichier a changé."""
    ancien = _lire(soul)
    base = re.sub(re.escape(DEBUT) + r".*?" + re.escape(FIN), "", ancien, flags=re.S).rstrip()
    if not base:
        base = IDENTITE
    nouveau = base + ("\n\n" + DEBUT + "\n" + bloc.strip() + "\n" + FIN + "\n" if bloc.strip() else "\n")
    if nouveau == ancien:
        return False
    tmp = soul.with_name(soul.name + ".jarvis")
    tmp.write_text(nouveau, encoding="utf-8")
    os.replace(tmp, soul)
    return True


def synchroniser(home: Path, etat: dict) -> None:
    url, jeton = _pont(home)
    if not url or not jeton:
        return
    mem = home / "memories"
    texte = "### MEMORY.md\n" + _lire(mem / "MEMORY.md").strip() + "\n\n### USER.md\n" + _lire(mem / "USER.md").strip() + "\n"
    empreinte = hashlib.sha256(texte.encode("utf-8")).hexdigest()
    if empreinte != etat.get("envoi"):
        code, _ = _appel("POST", url + "/memoire/hermes", jeton, texte)
        if code == 200:
            etat["envoi"] = empreinte
    code, bloc = _appel("GET", url + "/memoire/jarvis", jeton)
    if code == 200 and bloc != etat.get("jarvis"):
        ecrire_soul(home / "SOUL.md", bloc)
        etat["jarvis"] = bloc


def _boucle() -> None:
    etat: dict = {}
    while True:
        try:
            synchroniser(_home(), etat)
        except Exception:
            pass
        time.sleep(30)


def handle(event_type, context):
    global _lance
    if _lance:
        return
    _lance = True
    threading.Thread(target=_boucle, name="jarvis-memoire", daemon=True).start()
