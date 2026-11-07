"""Mémoire partagée Jarvis <-> Hermès (hook « gateway:startup » installé par Jarvis).

Un fil de fond, tant que la passerelle tourne :
  - envoie à Jarvis la mémoire d'Hermès (memories/MEMORY.md et USER.md) dès qu'elle change ;
  - envoie à Jarvis ce qu'Hermès sait faire en plus (skills ajoutés, serveurs MCP, plugins, automatismes) ;
  - récupère la mémoire de Jarvis et la recopie dans un bloc géré de SOUL.md, chargé par Hermès à chaque session
    (y compris les tâches planifiées et les sous-agents).
Aucune erreur ne remonte : si Jarvis est fermé, on réessaie au tour suivant.
"""
import hashlib
import json
import os
import re
import threading
import time
import urllib.request
from pathlib import Path

DEBUT = "<!-- JARVIS-MEMOIRE-DEBUT -->"
FIN = "<!-- JARVIS-MEMOIRE-FIN -->"
ANCIENNE_IDENTITE = "You are Hermes Agent, built by Nous Research."\nIDENTITE = "You are JARVIS, the single personal assistant. Hermes is only the technical agent runtime, never a separate assistant."
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
    base = base.replace(ANCIENNE_IDENTITE, IDENTITE)
    if not base:
        base = IDENTITE
    nouveau = base + ("\n\n" + DEBUT + "\n" + bloc.strip() + "\n" + FIN + "\n" if bloc.strip() else "\n")
    if nouveau == ancien:
        return False
    tmp = soul.with_name(soul.name + ".jarvis")
    tmp.write_text(nouveau, encoding="utf-8")
    os.replace(tmp, soul)
    return True


def _frontmatter(texte: str) -> dict:
    """name / description d'un SKILL.md (frontmatter YAML simple, sans dépendance)."""
    m = re.match(r"^---\s*\n(.*?)\n---", texte, flags=re.S)
    out = {}
    if m:
        for ligne in m.group(1).splitlines():
            if ":" in ligne and not ligne.startswith((" ", "\t")):
                k, v = ligne.split(":", 1)
                out[k.strip()] = v.strip().strip("'\"")
    return out


def capacites(home: Path) -> dict:
    """Ce qu'Hermès sait faire EN PLUS de Jarvis : skills ajoutés/créés (hors skills fournis d'origine),
    serveurs MCP et plugins configurés, automatismes (cron)."""
    skills = []
    racine = home / "skills"
    fournis = set()
    try:
        for ligne in (racine / ".bundled_manifest").read_text(encoding="utf-8").splitlines():
            if ":" in ligne:
                fournis.add(ligne.split(":", 1)[0].strip())
    except OSError:
        pass
    if racine.is_dir():
        for f in sorted(racine.rglob("SKILL.md")):
            if any(p.startswith(".") for p in f.relative_to(racine).parts[:-1]):
                continue
            nom = f.parent.name
            if nom in fournis:
                continue
            fm = _frontmatter(_lire(f))
            skills.append({"nom": fm.get("name") or nom, "description": (fm.get("description") or "")[:300]})
    mcp = []
    cfg = _lire(home / "config.yaml")
    bloc = re.search(r"^mcp_servers:\s*\n((?:[ \t]+.*\n?)*)", cfg, flags=re.M)
    if bloc:
        mcp = [m.group(1) for m in re.finditer(r"^  ([A-Za-z0-9_.-]+):\s*$", bloc.group(1), flags=re.M) if m.group(1) != "jarvis"]
    plugins = sorted(p.name for p in (home / "plugins").iterdir() if p.is_dir() and not p.name.startswith(".")) if (home / "plugins").is_dir() else []
    taches = []
    try:
        brut = json.loads(_lire(home / "cron" / "jobs.json") or "[]")
        jobs = brut.get("jobs", []) if isinstance(brut, dict) else brut
        for j in jobs if isinstance(jobs, list) else []:
            if isinstance(j, dict):
                taches.append({"nom": str(j.get("name") or j.get("id") or ""), "quand": str(j.get("schedule_display") or j.get("schedule") or ""),
                               "actif": bool(j.get("enabled", True))})
    except Exception:
        pass
    return {"skills": skills[:80], "mcp": mcp[:20], "plugins": plugins[:30], "automatismes": taches[:40]}


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
    cap = json.dumps(capacites(home), ensure_ascii=False, sort_keys=True)
    if cap != etat.get("capacites"):
        code, _ = _appel("POST", url + "/memoire/hermes-capacites", jeton, cap)
        if code == 200:
            etat["capacites"] = cap
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
